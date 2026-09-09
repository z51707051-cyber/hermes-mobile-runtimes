package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.observer.PhoneStateCaptureStatus
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateObserver
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSnapshot
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSource
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableException
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiLimits
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiProbe
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiProbeSource
import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import java.time.Instant

internal fun interface WaitSleeper {
    @Throws(InterruptedException::class)
    fun sleep(millis: Long)
}

internal interface WaitCancellationSource {
    fun begin(requestId: String): Boolean

    fun isCancelled(requestId: String): Boolean

    fun finish(requestId: String)
}

/** Process-local cancellation seam; it can only reduce authority by stopping an active wait. */
internal object RuntimeWaitCancellationSource : WaitCancellationSource {
    private val active = mutableSetOf<String>()
    private val cancelled = mutableSetOf<String>()

    @Synchronized
    override fun begin(requestId: String): Boolean {
        if (!active.add(requestId)) return false
        cancelled.remove(requestId)
        return true
    }

    @Synchronized
    fun cancel(requestId: String): Boolean =
        if (requestId in active) cancelled.add(requestId) else false

    @Synchronized
    override fun isCancelled(requestId: String): Boolean = requestId in cancelled

    @Synchronized
    override fun finish(requestId: String) {
        cancelled.remove(requestId)
        active.remove(requestId)
    }
}

internal data class WaitCondition(
    val kind: String,
    val expected: String?,
)

/** Bounded, cancellable wait whose conditions are evaluated only over coherent observations. */
internal class WaitProvider(
    private val phoneStateSource: PhoneStateSource,
    private val semanticUiSource: SemanticUiProbeSource,
    private val cancellationSource: WaitCancellationSource = RuntimeWaitCancellationSource,
    private val epochClock: ProviderEpochClock = ProviderEpochClock { System.currentTimeMillis() },
    private val elapsedClock: ProviderElapsedClock =
        ProviderElapsedClock { System.nanoTime() / 1_000_000 },
    private val sleeper: WaitSleeper = WaitSleeper { Thread.sleep(it) },
) : CapabilityProvider {
    override val descriptor = CapabilityDescriptor(TOOL, PROVIDER_ID)

    override fun execute(action: AuthorizedAction): ByteArray {
        val startedAt = elapsedClock.nowMillis()
        if (!cancellationSource.begin(action.requestId)) {
            return ProtocolCodec.encode(duplicate(action, durationSince(startedAt)))
        }
        return try {
            executeRegistered(action, startedAt)
        } catch (exc: InterruptedException) {
            Thread.currentThread().interrupt()
            ProtocolCodec.encode(cancelled(action, null, null, durationSince(startedAt)))
        } finally {
            cancellationSource.finish(action.requestId)
        }
    }

    private fun executeRegistered(
        action: AuthorizedAction,
        startedAt: Long,
    ): ByteArray {
        val requestedTimeout = (action.parameters.getValue("timeout_ms") as Number).toLong()
        val condition = condition(action.parameters)
        val authorizationRemaining = action.stopEpochMillis() - epochClock.nowMillis()
        val budget = minOf(requestedTimeout, authorizationRemaining.coerceAtLeast(0))
        val stopAt = startedAt + budget
        if (cancelled(action)) {
            return ProtocolCodec.encode(cancelled(action, null, null, durationSince(startedAt)))
        }
        if (budget == 0L) {
            return ProtocolCodec.encode(timedOut(action, condition, null, null, durationSince(startedAt)))
        }
        return if (condition == null) {
            waitForTimer(action, startedAt, stopAt, requestedTimeout == budget)
        } else {
            waitForCondition(action, condition, startedAt, stopAt)
        }
    }

    private fun waitForTimer(
        action: AuthorizedAction,
        startedAt: Long,
        stopAt: Long,
        fullBudgetAvailable: Boolean,
    ): ByteArray {
        val before = currentStateOrNull()
        val interruption = sleepUntil(action, stopAt)
        val after = currentStateOrNull()
        return ProtocolCodec.encode(
            when (interruption) {
                WaitInterruption.CANCELLED ->
                    cancelled(action, before, after, durationSince(startedAt))
                WaitInterruption.DEADLINE ->
                    if (fullBudgetAvailable) {
                        succeededTimer(action, before, after, durationSince(startedAt))
                    } else {
                        timedOut(action, null, before, after, durationSince(startedAt))
                    }
                WaitInterruption.CONTINUE -> error("timer wait returned before its bound")
            },
        )
    }

    private fun waitForCondition(
        action: AuthorizedAction,
        condition: WaitCondition,
        startedAt: Long,
        stopAt: Long,
    ): ByteArray {
        var before: SemanticUiProbe? = null
        var latest: SemanticUiProbe? = null
        val redactions = linkedSetOf<String>()
        try {
            val initial = semanticUiSource.probe(PROBE_LIMITS)
            before = initial
            latest = initial
            redactions += initial.redactions
            if (conditionPassed(condition, initial, initial)) {
                return ProtocolCodec.encode(
                    succeededCondition(
                        action,
                        condition,
                        initial.state,
                        initial.state,
                        redactions,
                        durationSince(startedAt),
                    ),
                )
            }
            while (true) {
                when (sleepOnePoll(action, stopAt)) {
                    WaitInterruption.CANCELLED ->
                        return ProtocolCodec.encode(
                            cancelled(
                                action,
                                initial.state,
                                latest?.state,
                                durationSince(startedAt),
                                redactions,
                            ),
                        )
                    WaitInterruption.DEADLINE ->
                        return ProtocolCodec.encode(
                            timedOut(
                                action,
                                condition,
                                initial.state,
                                latest?.state,
                                durationSince(startedAt),
                                redactions,
                            ),
                        )
                    WaitInterruption.CONTINUE -> Unit
                }
                val current = semanticUiSource.probe(PROBE_LIMITS)
                latest = current
                redactions += current.redactions
                if (conditionPassed(condition, initial, current)) {
                    return ProtocolCodec.encode(
                        succeededCondition(
                            action,
                            condition,
                            initial.state,
                            current.state,
                            redactions,
                            durationSince(startedAt),
                        ),
                    )
                }
            }
        } catch (exc: PhoneStateUnavailableException) {
            return ProtocolCodec.encode(
                unavailable(
                    action,
                    exc,
                    before?.state,
                    latest?.state,
                    durationSince(startedAt),
                    redactions,
                ),
            )
        } catch (exc: InterruptedException) {
            Thread.currentThread().interrupt()
            return ProtocolCodec.encode(
                cancelled(
                    action,
                    before?.state,
                    latest?.state,
                    durationSince(startedAt),
                    redactions,
                ),
            )
        } catch (exc: Exception) {
            return ProtocolCodec.encode(
                observationFailure(
                    action,
                    before?.state,
                    latest?.state,
                    durationSince(startedAt),
                    redactions,
                ),
            )
        }
        @Suppress("UNREACHABLE_CODE")
        error("conditional wait loop terminated unexpectedly")
    }

    private fun sleepUntil(
        action: AuthorizedAction,
        stopAt: Long,
    ): WaitInterruption {
        while (true) {
            when (val status = sleepOnePoll(action, stopAt)) {
                WaitInterruption.CONTINUE -> Unit
                else -> return status
            }
        }
    }

    @Throws(InterruptedException::class)
    private fun sleepOnePoll(
        action: AuthorizedAction,
        stopAt: Long,
    ): WaitInterruption {
        if (cancelled(action)) return WaitInterruption.CANCELLED
        val monotonicRemaining = stopAt - elapsedClock.nowMillis()
        val authorizationRemaining = action.stopEpochMillis() - epochClock.nowMillis()
        val remaining = minOf(monotonicRemaining, authorizationRemaining)
        if (remaining <= 0) return WaitInterruption.DEADLINE
        sleeper.sleep(minOf(POLL_MILLIS, remaining))
        return if (cancelled(action)) WaitInterruption.CANCELLED else WaitInterruption.CONTINUE
    }

    private fun cancelled(action: AuthorizedAction): Boolean =
        Thread.currentThread().isInterrupted || cancellationSource.isCancelled(action.requestId)

    private fun AuthorizedAction.stopEpochMillis(): Long =
        minOf(deadline.toEpochMilli(), authorizationExpiresAt.toEpochMilli())

    private fun condition(parameters: Map<String, Any?>): WaitCondition? {
        @Suppress("UNCHECKED_CAST")
        val value = parameters["condition"] as? Map<String, Any?> ?: return null
        return WaitCondition(value.getValue("kind") as String, value["expected"] as? String)
    }

    private fun conditionPassed(
        condition: WaitCondition,
        before: SemanticUiProbe,
        current: SemanticUiProbe,
    ): Boolean {
        if (current.state.captureStatus != PhoneStateCaptureStatus.COMPLETE) return false
        return when (condition.kind) {
            "STATE_CHANGED" ->
                before.state.captureStatus == PhoneStateCaptureStatus.COMPLETE &&
                    current.state.screenFingerprint.basis == before.state.screenFingerprint.basis &&
                    current.state.screenFingerprint != before.state.screenFingerprint
            "FOREGROUND_APP_IS" -> current.state.packageName == condition.expected
            "TEXT_PRESENT" ->
                !condition.expected.isNullOrEmpty() &&
                    current.visibleText.any { condition.expected in it }
            else -> false
        }
    }

    private fun currentStateOrNull(): PhoneStateSnapshot? =
        try {
            phoneStateSource.current(PhoneStateObserver.DEFAULT_MAXIMUM_AGE_MILLIS)
        } catch (exc: Exception) {
            null
        }

    private fun succeededTimer(
        action: AuthorizedAction,
        before: PhoneStateSnapshot?,
        after: PhoneStateSnapshot?,
        durationMillis: Long,
    ): Map<String, Any?> =
        result(
            action = action,
            executionStatus = "SUCCEEDED",
            before = before,
            after = after,
            durationMillis = durationMillis,
            error = null,
            recoverable = false,
            verificationStatus = "NOT_APPLICABLE",
            verificationExplanation = "bounded timer wait completed",
        )

    private fun succeededCondition(
        action: AuthorizedAction,
        condition: WaitCondition,
        before: PhoneStateSnapshot,
        after: PhoneStateSnapshot,
        redactions: Set<String>,
        durationMillis: Long,
    ): Map<String, Any?> =
        result(
            action = action,
            executionStatus = "SUCCEEDED",
            before = before,
            after = after,
            durationMillis = durationMillis,
            error = null,
            recoverable = false,
            verificationStatus = "PASSED",
            verificationExplanation =
                when (condition.kind) {
                    "STATE_CHANGED" -> "comparable semantic screen fingerprint changed"
                    "FOREGROUND_APP_IS" -> "foreground package reached the requested value"
                    else -> "requested text is present in the protected observation"
                },
            redactions = redactions,
        )

    private fun timedOut(
        action: AuthorizedAction,
        condition: WaitCondition?,
        before: PhoneStateSnapshot?,
        after: PhoneStateSnapshot?,
        durationMillis: Long,
        redactions: Set<String> = emptySet(),
    ): Map<String, Any?> =
        result(
            action = action,
            executionStatus = "TIMED_OUT",
            before = before,
            after = after,
            durationMillis = durationMillis,
            error =
                protocolError(
                    code = "ACTION_TIMEOUT",
                    message = "bounded wait reached its deadline",
                    retryDisposition = if (condition == null) "NEVER" else "REOBSERVE",
                    reason = if (condition == null) "ACTION_DEADLINE" else "CONDITION_NOT_OBSERVED",
                ),
            recoverable = condition != null,
            verificationStatus = if (condition == null) "INCONCLUSIVE" else "FAILED",
            verificationExplanation =
                if (condition == null) {
                    "action deadline elapsed before the timer duration"
                } else {
                    "wait condition was not observed before the deadline"
                },
            redactions = redactions,
        )

    private fun cancelled(
        action: AuthorizedAction,
        before: PhoneStateSnapshot?,
        after: PhoneStateSnapshot?,
        durationMillis: Long,
        redactions: Set<String> = emptySet(),
    ): Map<String, Any?> =
        result(
            action = action,
            executionStatus = "CANCELLED",
            before = before,
            after = after,
            durationMillis = durationMillis,
            error =
                protocolError(
                    code = "ACTION_REJECTED",
                    message = "bounded wait was cancelled",
                    retryDisposition = "NEVER",
                    reason = "CANCELLED",
                ),
            recoverable = false,
            verificationStatus = "INCONCLUSIVE",
            verificationExplanation = "wait stopped before completion",
            redactions = redactions,
        )

    private fun unavailable(
        action: AuthorizedAction,
        exception: PhoneStateUnavailableException,
        before: PhoneStateSnapshot?,
        after: PhoneStateSnapshot?,
        durationMillis: Long,
        redactions: Set<String>,
    ): Map<String, Any?> =
        result(
            action = action,
            executionStatus = "FAILED",
            before = before,
            after = after,
            durationMillis = durationMillis,
            error =
                protocolError(
                    code = "CAPABILITY_UNAVAILABLE",
                    message = "semantic wait observation is unavailable",
                    retryDisposition = "REOBSERVE",
                    reason = exception.reason.name,
                ),
            recoverable = true,
            verificationStatus = "INCONCLUSIVE",
            verificationExplanation = "no coherent semantic observation is available",
            redactions = redactions,
        )

    private fun observationFailure(
        action: AuthorizedAction,
        before: PhoneStateSnapshot?,
        after: PhoneStateSnapshot?,
        durationMillis: Long,
        redactions: Set<String>,
    ): Map<String, Any?> =
        result(
            action = action,
            executionStatus = "FAILED",
            before = before,
            after = after,
            durationMillis = durationMillis,
            error =
                protocolError(
                    code = "CAPABILITY_UNAVAILABLE",
                    message = "semantic wait observation failed",
                    retryDisposition = "REOBSERVE",
                    reason = "UI_CAPTURE_FAILED",
                ),
            recoverable = true,
            verificationStatus = "INCONCLUSIVE",
            verificationExplanation = "semantic condition evidence could not be captured",
            redactions = redactions,
        )

    private fun duplicate(
        action: AuthorizedAction,
        durationMillis: Long,
    ): Map<String, Any?> =
        result(
            action = action,
            executionStatus = "FAILED",
            before = null,
            after = null,
            durationMillis = durationMillis,
            error =
                protocolError(
                    code = "ACTION_REJECTED",
                    message = "a wait with this request id is already active",
                    retryDisposition = "NEVER",
                    reason = "DUPLICATE_ACTIVE_WAIT",
                ),
            recoverable = false,
            verificationStatus = "INCONCLUSIVE",
            verificationExplanation = "duplicate active wait was not started",
        )

    private fun result(
        action: AuthorizedAction,
        executionStatus: String,
        before: PhoneStateSnapshot?,
        after: PhoneStateSnapshot?,
        durationMillis: Long,
        error: Map<String, Any?>?,
        recoverable: Boolean,
        verificationStatus: String,
        verificationExplanation: String,
        redactions: Set<String> = emptySet(),
    ): Map<String, Any?> {
        val observedStateIds =
            listOfNotNull(before?.stateId, after?.stateId).distinct().take(MAX_OBSERVED_STATES)
        return RESULT_BINDING_FIELDS.associateWith { action.message.getValue(it) } +
            mapOf(
                "message_type" to "tool.execution_result",
                "execution_status" to executionStatus,
                "before_state" to before?.protocolValue(action.deviceId),
                "after_state" to after?.protocolValue(action.deviceId),
                "duration" to durationMillis,
                "error" to error,
                "recoverable" to recoverable,
                "timestamp" to Instant.ofEpochMilli(epochClock.nowMillis()).toString(),
                "parameter_digest" to CanonicalJson.sha256(action.parameters),
                "permission_decision_id" to action.policyDecisionId,
                "verification" to
                    mapOf(
                        "status" to verificationStatus,
                        "observed_state_ids" to observedStateIds,
                        "evaluator" to PROVIDER_ID,
                        "explanation" to verificationExplanation,
                    ),
                "artifacts" to emptyList<Any>(),
                "redactions" to redactions.sorted(),
            )
    }

    private fun protocolError(
        code: String,
        message: String,
        retryDisposition: String,
        reason: String,
    ): Map<String, Any?> =
        mapOf(
            "code" to code,
            "category" to "EXECUTION",
            "owner" to "CAPABILITY",
            "message" to message,
            "retry_disposition" to retryDisposition,
            "details" to mapOf("reason" to reason),
        )

    private fun durationSince(startedAt: Long): Long =
        (elapsedClock.nowMillis() - startedAt).coerceIn(0, MAXIMUM_DURATION_MILLIS)

    private enum class WaitInterruption {
        CONTINUE,
        CANCELLED,
        DEADLINE,
    }

    private companion object {
        const val TOOL = "phone.wait"
        const val PROVIDER_ID = "android.runtime.wait.v1"
        const val POLL_MILLIS = 100L
        const val MAXIMUM_DURATION_MILLIS = 86_400_000L
        const val MAX_OBSERVED_STATES = 8
        val PROBE_LIMITS = SemanticUiLimits(maxNodes = 500, maxTextChars = 20_000)
        val RESULT_BINDING_FIELDS =
            setOf(
                "protocol_version",
                "request_id",
                "task_id",
                "span_id",
                "device_id",
                "tool",
                "parameters",
                "attempt",
                "idempotency_key",
            )
    }
}

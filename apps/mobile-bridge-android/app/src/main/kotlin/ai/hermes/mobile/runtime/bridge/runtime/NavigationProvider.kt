package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.observer.NavigationActionSource
import ai.hermes.mobile.runtime.bridge.observer.NavigationExecution
import ai.hermes.mobile.runtime.bridge.observer.NavigationFailureException
import ai.hermes.mobile.runtime.bridge.observer.NavigationFailureReason
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableException
import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import java.time.Instant

/** One closed provider instance per navigation tool, sharing one Android source. */
internal class NavigationProvider(
    tool: String,
    private val source: NavigationActionSource,
    private val epochClock: ProviderEpochClock = ProviderEpochClock { System.currentTimeMillis() },
    private val elapsedClock: ProviderElapsedClock = ProviderElapsedClock { System.nanoTime() / 1_000_000 },
) : CapabilityProvider {
    override val descriptor = CapabilityDescriptor(tool, "android.navigation.${tool.substringAfter('.')}.v1")

    init {
        require(NavigationCommandParser.supports(tool)) { "unsupported navigation provider" }
    }

    override fun execute(action: AuthorizedAction): ByteArray {
        val startedAt = elapsedClock.nowMillis()
        return try {
            val execution =
                source.execute(
                    NavigationCommandParser.parse(action),
                    NavigationCommandParser.verification(action),
                )
            ProtocolCodec.encode(success(action, execution, durationSince(startedAt)))
        } catch (exc: NavigationFailureException) {
            ProtocolCodec.encode(
                failure(
                    action,
                    exc.reason,
                    durationSince(startedAt),
                    exc.beforeState?.protocolValue(action.deviceId),
                ),
            )
        } catch (exc: PhoneStateUnavailableException) {
            ProtocolCodec.encode(
                unavailable(action, exc, durationSince(startedAt)),
            )
        } catch (exc: Exception) {
            ProtocolCodec.encode(
                failure(
                    action,
                    NavigationFailureReason.INTERNAL_ERROR,
                    durationSince(startedAt),
                    null,
                ),
            )
        }
    }

    private fun success(
        action: AuthorizedAction,
        execution: NavigationExecution,
        durationMillis: Long,
    ): Map<String, Any?> =
        baseResult(action, durationMillis) +
            mapOf(
                "execution_status" to "SUCCEEDED",
                "before_state" to execution.beforeState.protocolValue(action.deviceId),
                "after_state" to execution.afterState.protocolValue(action.deviceId),
                "error" to null,
                "recoverable" to false,
                "verification" to
                    mapOf(
                        "status" to execution.verificationStatus,
                        "observed_state_ids" to listOf(execution.afterState.stateId),
                        "evaluator" to VERIFIER_ID,
                        "explanation" to execution.verificationExplanation,
                        "evidence" to execution.afterState.artifacts.map { it.protocolValue() },
                    ),
                "artifacts" to execution.afterState.artifacts.map { it.protocolValue() },
                "redactions" to execution.redactions,
            )

    private fun failure(
        action: AuthorizedAction,
        reason: NavigationFailureReason,
        durationMillis: Long,
        beforeState: Map<String, Any?>?,
    ): Map<String, Any?> =
        baseResult(action, durationMillis) +
            mapOf(
                "execution_status" to if (reason.outcomeUnknown) "UNKNOWN_OUTCOME" else "FAILED",
                "before_state" to beforeState,
                "after_state" to null,
                "error" to
                    mapOf(
                        "code" to reason.errorCode,
                        "category" to if (reason.outcomeUnknown) "VERIFICATION" else "EXECUTION",
                        "owner" to if (reason.outcomeUnknown) "VERIFIER" else "CAPABILITY",
                        "message" to "navigation action did not produce a verified result",
                        "retry_disposition" to reason.retryDisposition,
                        "details" to mapOf("reason" to reason.name),
                    ),
                "recoverable" to reason.recoverable,
                "verification" to
                    mapOf(
                        "status" to "INCONCLUSIVE",
                        "observed_state_ids" to emptyList<String>(),
                        "evaluator" to VERIFIER_ID,
                        "explanation" to "no verified post-action observation",
                    ),
                "artifacts" to emptyList<Any>(),
                "redactions" to emptyList<String>(),
            )

    private fun unavailable(
        action: AuthorizedAction,
        exception: PhoneStateUnavailableException,
        durationMillis: Long,
    ): Map<String, Any?> =
        baseResult(action, durationMillis) +
            mapOf(
                "execution_status" to "FAILED",
                "before_state" to null,
                "after_state" to null,
                "error" to
                    mapOf(
                        "code" to "CAPABILITY_UNAVAILABLE",
                        "category" to "OBSERVATION",
                        "owner" to "OBSERVER",
                        "message" to "fresh pre-action state is unavailable",
                        "retry_disposition" to "REOBSERVE",
                        "details" to mapOf("reason" to exception.reason.name),
                    ),
                "recoverable" to true,
                "verification" to
                    mapOf(
                        "status" to "INCONCLUSIVE",
                        "observed_state_ids" to emptyList<String>(),
                        "evaluator" to VERIFIER_ID,
                        "explanation" to "action was not executed",
                    ),
                "artifacts" to emptyList<Any>(),
                "redactions" to emptyList<String>(),
            )

    private fun baseResult(
        action: AuthorizedAction,
        durationMillis: Long,
    ): Map<String, Any?> =
        RESULT_BINDING_FIELDS.associateWith { field -> action.message.getValue(field) } +
            mapOf(
                "message_type" to "tool.execution_result",
                "duration" to durationMillis,
                "timestamp" to Instant.ofEpochMilli(epochClock.nowMillis()).toString(),
                "parameter_digest" to CanonicalJson.sha256(action.parameters),
                "permission_decision_id" to action.policyDecisionId,
            )

    private fun durationSince(startedAt: Long): Long =
        (elapsedClock.nowMillis() - startedAt).coerceIn(0, MAXIMUM_DURATION_MILLIS)

    private companion object {
        const val VERIFIER_ID = "android.semantic.verifier.v1"
        const val MAXIMUM_DURATION_MILLIS = 86_400_000L
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

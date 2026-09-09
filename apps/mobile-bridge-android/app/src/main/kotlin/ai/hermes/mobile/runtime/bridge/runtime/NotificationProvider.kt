package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.observer.NotificationCapture
import ai.hermes.mobile.runtime.bridge.observer.NotificationCaptureException
import ai.hermes.mobile.runtime.bridge.observer.NotificationCaptureSource
import ai.hermes.mobile.runtime.bridge.observer.NotificationFailureReason
import ai.hermes.mobile.runtime.bridge.observer.NotificationQuery
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateObserver
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSnapshot
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSource
import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import java.time.Instant

internal class NotificationProvider(
    private val source: NotificationCaptureSource,
    private val phoneStateSource: PhoneStateSource,
    private val epochClock: ProviderEpochClock = ProviderEpochClock { System.currentTimeMillis() },
    private val elapsedClock: ProviderElapsedClock = ProviderElapsedClock { System.nanoTime() / 1_000_000 },
) : CapabilityProvider {
    override val descriptor = CapabilityDescriptor(TOOL, PROVIDER_ID)

    override fun execute(action: AuthorizedAction): ByteArray {
        val startedAt = elapsedClock.nowMillis()
        val before = currentPhoneState()
        return try {
            val capture = source.capture(query(action.parameters))
            ProtocolCodec.encode(success(action, capture, before, durationSince(startedAt)))
        } catch (exc: NotificationCaptureException) {
            ProtocolCodec.encode(failure(action, exc.reason, before, durationSince(startedAt)))
        } catch (exc: Exception) {
            ProtocolCodec.encode(
                failure(action, NotificationFailureReason.CAPTURE_FAILED, before, durationSince(startedAt)),
            )
        }
    }

    private fun query(parameters: Map<String, Any?>): NotificationQuery =
        NotificationQuery(
            cursor = parameters["cursor"] as? String,
            limit = (parameters["limit"] as? Number)?.toInt() ?: DEFAULT_LIMIT,
            sourcePackages =
                (parameters["source_packages"] as? List<*>)
                    ?.map { it as String }
                    ?.toSet()
                    ?: emptySet(),
        )

    private fun success(
        action: AuthorizedAction,
        capture: NotificationCapture,
        before: PhoneStateSnapshot?,
        durationMillis: Long,
    ): Map<String, Any?> =
        baseResult(action, durationMillis) +
            mapOf(
                "execution_status" to "SUCCEEDED",
                "before_state" to before?.protocolValue(action.deviceId),
                "after_state" to before?.protocolValue(action.deviceId),
                "error" to null,
                "recoverable" to false,
                "verification" to
                    mapOf(
                        "status" to "NOT_APPLICABLE",
                        "observed_state_ids" to before?.let { listOf(it.stateId) }.orEmpty(),
                        "evaluator" to PROVIDER_ID,
                        "explanation" to "read-only protected notification capture",
                    ),
                "artifacts" to listOf(capture.artifact.protocolValue()),
                "redactions" to capture.redactions,
            )

    private fun failure(
        action: AuthorizedAction,
        reason: NotificationFailureReason,
        before: PhoneStateSnapshot?,
        durationMillis: Long,
    ): Map<String, Any?> =
        baseResult(action, durationMillis) +
            mapOf(
                "execution_status" to "FAILED",
                "before_state" to before?.protocolValue(action.deviceId),
                "after_state" to null,
                "error" to
                    mapOf(
                        "code" to if (reason == NotificationFailureReason.CAPTURE_FAILED) "CAPABILITY_UNAVAILABLE" else "ACTION_REJECTED",
                        "category" to "OBSERVATION",
                        "owner" to "OBSERVER",
                        "message" to "notification observation is unavailable",
                        "retry_disposition" to reason.retryDisposition,
                        "details" to mapOf("reason" to reason.name),
                    ),
                "recoverable" to reason.recoverable,
                "verification" to
                    mapOf(
                        "status" to "INCONCLUSIVE",
                        "observed_state_ids" to emptyList<String>(),
                        "evaluator" to PROVIDER_ID,
                        "explanation" to "no notification artifact was published",
                    ),
                "artifacts" to emptyList<Any>(),
                "redactions" to emptyList<String>(),
            )

    private fun currentPhoneState(): PhoneStateSnapshot? =
        try {
            phoneStateSource.current(PhoneStateObserver.DEFAULT_MAXIMUM_AGE_MILLIS)
        } catch (exc: Exception) {
            null
        }

    private fun baseResult(action: AuthorizedAction, durationMillis: Long): Map<String, Any?> =
        RESULT_BINDING_FIELDS.associateWith { action.message.getValue(it) } +
            mapOf(
                "message_type" to "tool.execution_result",
                "duration" to durationMillis,
                "timestamp" to Instant.ofEpochMilli(epochClock.nowMillis()).toString(),
                "parameter_digest" to CanonicalJson.sha256(action.parameters),
                "permission_decision_id" to action.policyDecisionId,
            )

    private fun durationSince(startedAt: Long): Long =
        (elapsedClock.nowMillis() - startedAt).coerceIn(0, 86_400_000L)

    private companion object {
        const val TOOL = "phone.notifications"
        const val PROVIDER_ID = "android.notification.capture.v1"
        const val DEFAULT_LIMIT = 20
        val RESULT_BINDING_FIELDS =
            setOf(
                "protocol_version", "request_id", "task_id", "span_id", "device_id",
                "tool", "parameters", "attempt", "idempotency_key",
            )
    }
}

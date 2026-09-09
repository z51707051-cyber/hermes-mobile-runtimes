package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableException
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiCapture
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiCaptureSource
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiLimits
import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import java.time.Instant

/** Produces a protected normalized UI-tree artifact for the active window only. */
internal class ReadScreenProvider(
    private val source: SemanticUiCaptureSource,
    private val epochClock: ProviderEpochClock = ProviderEpochClock { System.currentTimeMillis() },
    private val elapsedClock: ProviderElapsedClock = ProviderElapsedClock { System.nanoTime() / 1_000_000 },
) : CapabilityProvider {
    override val descriptor = CapabilityDescriptor(TOOL, PROVIDER_ID)

    override fun execute(action: AuthorizedAction): ByteArray {
        val startedAt = elapsedClock.nowMillis()
        return try {
            val limits = limits(action.parameters)
            val capture = source.capture(limits)
            ProtocolCodec.encode(success(action, capture, durationSince(startedAt)))
        } catch (exc: PhoneStateUnavailableException) {
            ProtocolCodec.encode(unavailable(action, exc, durationSince(startedAt)))
        } catch (exc: Exception) {
            ProtocolCodec.encode(failed(action, durationSince(startedAt)))
        }
    }

    private fun limits(parameters: Map<String, Any?>): SemanticUiLimits =
        SemanticUiLimits(
            maxNodes = (parameters["max_nodes"] as? Number)?.toInt() ?: DEFAULT_MAX_NODES,
            maxTextChars =
                (parameters["max_text_chars"] as? Number)?.toInt()
                    ?: DEFAULT_MAX_TEXT_CHARS,
        )

    private fun success(
        action: AuthorizedAction,
        capture: SemanticUiCapture,
        durationMillis: Long,
    ): Map<String, Any?> {
        val state = capture.state.protocolValue(action.deviceId)
        return baseResult(action, durationMillis) +
            mapOf(
                "execution_status" to "SUCCEEDED",
                "before_state" to state,
                "after_state" to state,
                "error" to null,
                "recoverable" to false,
                "verification" to
                    mapOf(
                        "status" to "NOT_APPLICABLE",
                        "observed_state_ids" to listOf(capture.state.stateId),
                        "evaluator" to PROVIDER_ID,
                        "explanation" to "read-only semantic UI capture",
                    ),
                "artifacts" to listOf(capture.artifact.protocolValue()),
                "redactions" to capture.redactions,
            )
    }

    private fun unavailable(
        action: AuthorizedAction,
        exception: PhoneStateUnavailableException,
        durationMillis: Long,
    ): Map<String, Any?> =
        failureResult(
            action,
            durationMillis,
            reason = exception.reason.name,
            retryDisposition = "REOBSERVE",
        )

    private fun failed(
        action: AuthorizedAction,
        durationMillis: Long,
    ): Map<String, Any?> =
        failureResult(
            action,
            durationMillis,
            reason = "UI_CAPTURE_FAILED",
            retryDisposition = "RETRY_SAME_ACTION",
        )

    private fun failureResult(
        action: AuthorizedAction,
        durationMillis: Long,
        reason: String,
        retryDisposition: String,
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
                        "message" to "semantic UI observation is unavailable",
                        "retry_disposition" to retryDisposition,
                        "details" to mapOf("reason" to reason),
                    ),
                "recoverable" to true,
                "verification" to
                    mapOf(
                        "status" to "INCONCLUSIVE",
                        "observed_state_ids" to emptyList<String>(),
                        "evaluator" to PROVIDER_ID,
                        "explanation" to "no coherent semantic UI capture",
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
        const val TOOL = "phone.read_screen"
        const val PROVIDER_ID = "android.accessibility.read_screen.v1"
        const val DEFAULT_MAX_NODES = 200
        const val DEFAULT_MAX_TEXT_CHARS = 10_000
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

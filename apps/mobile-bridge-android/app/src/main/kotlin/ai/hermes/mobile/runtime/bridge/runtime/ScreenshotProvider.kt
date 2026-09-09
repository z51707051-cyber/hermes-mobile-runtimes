package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableException
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotCapture
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotCaptureException
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotCaptureSource
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotCrop
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotFailureReason
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotFormat
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotSpec
import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import java.time.Instant

/** Produces a protected screenshot artifact without exposing image bytes. */
internal class ScreenshotProvider(
    private val source: ScreenshotCaptureSource,
    private val epochClock: ProviderEpochClock = ProviderEpochClock { System.currentTimeMillis() },
    private val elapsedClock: ProviderElapsedClock = ProviderElapsedClock { System.nanoTime() / 1_000_000 },
) : CapabilityProvider {
    override val descriptor = CapabilityDescriptor(TOOL, PROVIDER_ID)

    override fun execute(action: AuthorizedAction): ByteArray {
        val startedAt = elapsedClock.nowMillis()
        return try {
            val spec = spec(action.parameters)
            if (spec.displayId != DEFAULT_DISPLAY_ID) {
                throw ScreenshotCaptureException(ScreenshotFailureReason.INVALID_DISPLAY)
            }
            val capture = source.capture(spec)
            ProtocolCodec.encode(success(action, capture, durationSince(startedAt)))
        } catch (exc: ScreenshotCaptureException) {
            ProtocolCodec.encode(failure(action, exc.reason, durationSince(startedAt)))
        } catch (exc: PhoneStateUnavailableException) {
            ProtocolCodec.encode(
                failure(
                    action,
                    ScreenshotFailureReason.WINDOW_CHANGED,
                    durationSince(startedAt),
                    detail = exc.reason.name,
                ),
            )
        } catch (exc: Exception) {
            ProtocolCodec.encode(
                failure(action, ScreenshotFailureReason.INTERNAL_ERROR, durationSince(startedAt)),
            )
        }
    }

    private fun spec(parameters: Map<String, Any?>): ScreenshotSpec {
        val crop =
            (parameters["crop"] as? Map<*, *>)?.let { value ->
                ScreenshotCrop(
                    xPx = (value["x_px"] as Number).toInt(),
                    yPx = (value["y_px"] as Number).toInt(),
                    widthPx = (value["width_px"] as Number).toInt(),
                    heightPx = (value["height_px"] as Number).toInt(),
                )
            }
        return ScreenshotSpec(
            displayId = (parameters["display_id"] as? Number)?.toInt() ?: 0,
            format =
                when (parameters["format"] as? String ?: "PNG") {
                    "WEBP" -> ScreenshotFormat.WEBP
                    else -> ScreenshotFormat.PNG
                },
            crop = crop,
        )
    }

    private fun success(
        action: AuthorizedAction,
        capture: ScreenshotCapture,
        durationMillis: Long,
    ): Map<String, Any?> =
        baseResult(action, durationMillis) +
            mapOf(
                "execution_status" to "SUCCEEDED",
                "before_state" to capture.beforeState.protocolValue(action.deviceId),
                "after_state" to capture.state.protocolValue(action.deviceId),
                "error" to null,
                "recoverable" to false,
                "verification" to
                    mapOf(
                        "status" to "NOT_APPLICABLE",
                        "observed_state_ids" to listOf(capture.state.stateId),
                        "evaluator" to PROVIDER_ID,
                        "explanation" to "read-only protected screenshot capture",
                    ),
                "artifacts" to listOf(capture.artifact.protocolValue()),
                "redactions" to emptyList<String>(),
            )

    private fun failure(
        action: AuthorizedAction,
        reason: ScreenshotFailureReason,
        durationMillis: Long,
        detail: String = reason.name,
    ): Map<String, Any?> =
        baseResult(action, durationMillis) +
            mapOf(
                "execution_status" to "FAILED",
                "before_state" to null,
                "after_state" to null,
                "error" to
                    mapOf(
                        "code" to reason.errorCode,
                        "category" to "OBSERVATION",
                        "owner" to "CAPABILITY",
                        "message" to "protected screenshot capture is unavailable",
                        "retry_disposition" to reason.retryDisposition,
                        "details" to mapOf("reason" to detail),
                    ),
                "recoverable" to reason.recoverable,
                "verification" to
                    mapOf(
                        "status" to "INCONCLUSIVE",
                        "observed_state_ids" to emptyList<String>(),
                        "evaluator" to PROVIDER_ID,
                        "explanation" to "no coherent protected screenshot capture",
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
        const val TOOL = "phone.screenshot"
        const val PROVIDER_ID = "android.accessibility.screenshot.v1"
        const val DEFAULT_DISPLAY_ID = 0
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

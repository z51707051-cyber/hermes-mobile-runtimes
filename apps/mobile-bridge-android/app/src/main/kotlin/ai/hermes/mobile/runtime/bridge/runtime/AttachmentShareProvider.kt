package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.observer.AttachmentShareException
import ai.hermes.mobile.runtime.bridge.observer.AttachmentShareFailureReason
import ai.hermes.mobile.runtime.bridge.observer.AttachmentShareLaunch
import ai.hermes.mobile.runtime.bridge.observer.AttachmentShareSource
import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import java.time.Instant

/** Opens one exact-package share flow without claiming that the later send succeeded. */
internal class AttachmentShareProvider(
    private val source: AttachmentShareSource,
    private val epochClock: ProviderEpochClock = ProviderEpochClock { System.currentTimeMillis() },
    private val elapsedClock: ProviderElapsedClock = ProviderElapsedClock { System.nanoTime() / 1_000_000 },
) : CapabilityProvider {
    override val descriptor = CapabilityDescriptor(TOOL, PROVIDER_ID)

    override fun execute(action: AuthorizedAction): ByteArray {
        val startedAt = elapsedClock.nowMillis()
        return try {
            val launch = source.share(action.parameters.getValue("package") as String)
            ProtocolCodec.encode(success(action, launch, durationSince(startedAt)))
        } catch (exc: AttachmentShareException) {
            ProtocolCodec.encode(failure(action, exc.reason, durationSince(startedAt)))
        } catch (_: Exception) {
            ProtocolCodec.encode(
                failure(
                    action,
                    AttachmentShareFailureReason.LAUNCH_REJECTED,
                    durationSince(startedAt),
                ),
            )
        }
    }

    private fun success(
        action: AuthorizedAction,
        launch: AttachmentShareLaunch,
        durationMillis: Long,
    ): Map<String, Any?> =
        baseResult(action, durationMillis) +
            mapOf(
                "execution_status" to "SUCCEEDED",
                "before_state" to null,
                "after_state" to null,
                "error" to null,
                "recoverable" to false,
                "verification" to
                    mapOf(
                        "status" to "NOT_APPLICABLE",
                        "observed_state_ids" to emptyList<String>(),
                        "evaluator" to PROVIDER_ID,
                        "explanation" to
                            "opened ${launch.packageName} with the user-selected attachment; " +
                                "recipient selection and delivery remain separate verified steps",
                    ),
                "artifacts" to emptyList<Any>(),
                "redactions" to listOf("ATTACHMENT_URI_WITHHELD", "ATTACHMENT_METADATA_MINIMIZED"),
            )

    private fun failure(
        action: AuthorizedAction,
        reason: AttachmentShareFailureReason,
        durationMillis: Long,
    ): Map<String, Any?> =
        baseResult(action, durationMillis) +
            mapOf(
                "execution_status" to "FAILED",
                "before_state" to null,
                "after_state" to null,
                "error" to
                    mapOf(
                        "code" to reason.errorCode,
                        "category" to "EXECUTION",
                        "owner" to "CAPABILITY",
                        "message" to "the selected attachment share flow could not be opened",
                        "retry_disposition" to reason.retryDisposition,
                        "details" to mapOf("reason" to reason.name),
                    ),
                "recoverable" to reason.recoverable,
                "verification" to
                    mapOf(
                        "status" to "INCONCLUSIVE",
                        "observed_state_ids" to emptyList<String>(),
                        "evaluator" to PROVIDER_ID,
                        "explanation" to "no attachment share flow was launched",
                    ),
                "artifacts" to emptyList<Any>(),
                "redactions" to listOf("ATTACHMENT_URI_WITHHELD"),
            )

    private fun baseResult(
        action: AuthorizedAction,
        durationMillis: Long,
    ): Map<String, Any?> =
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
        const val TOOL = "phone.share_attachment"
        const val PROVIDER_ID = "android.attachment.share.v1"
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

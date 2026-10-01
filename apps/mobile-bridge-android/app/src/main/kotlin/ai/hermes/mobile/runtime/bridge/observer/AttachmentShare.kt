package ai.hermes.mobile.runtime.bridge.observer

internal enum class AttachmentShareFailureReason(
    val errorCode: String,
    val retryDisposition: String,
    val recoverable: Boolean,
) {
    SERVICE_DISCONNECTED("CAPABILITY_UNAVAILABLE", "REPLAN", true),
    TARGET_UNAVAILABLE("CAPABILITY_UNAVAILABLE", "REPLAN", true),
    LAUNCH_REJECTED("ACTION_REJECTED", "NEVER", false),
    ALREADY_USED("ACTION_REJECTED", "NEVER", false),
}

internal class AttachmentShareException(
    val reason: AttachmentShareFailureReason,
) : IllegalStateException(reason.name)

internal data class AttachmentShareLaunch(
    val packageName: String,
)

/** One task-scoped, one-shot share capability backed by a user-selected URI. */
internal interface AttachmentShareSource {
    fun availability(): AttachmentShareFailureReason?

    fun share(packageName: String): AttachmentShareLaunch
}

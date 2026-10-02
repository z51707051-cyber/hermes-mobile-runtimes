package ai.hermes.mobile.runtime.bridge.attachment

import ai.hermes.mobile.runtime.bridge.accessibility.AttachmentShareGateway
import ai.hermes.mobile.runtime.bridge.observer.AttachmentShareException
import ai.hermes.mobile.runtime.bridge.observer.AttachmentShareFailureReason
import ai.hermes.mobile.runtime.bridge.observer.AttachmentShareLaunch
import ai.hermes.mobile.runtime.bridge.observer.AttachmentShareSource

/** Binds one picker selection to one successful share-flow launch in one task. */
internal class AndroidAttachmentShareSource(
    private val attachment: SelectedAttachment,
) : AttachmentShareSource {
    private var used = false

    @Synchronized
    override fun availability(): AttachmentShareFailureReason? =
        if (used) {
            AttachmentShareFailureReason.ALREADY_USED
        } else {
            AttachmentShareGateway.availability()
        }

    @Synchronized
    override fun share(packageName: String): AttachmentShareLaunch {
        if (used) throw AttachmentShareException(AttachmentShareFailureReason.ALREADY_USED)
        val launch = AttachmentShareGateway.launch(attachment, packageName)
        used = true
        return launch
    }
}

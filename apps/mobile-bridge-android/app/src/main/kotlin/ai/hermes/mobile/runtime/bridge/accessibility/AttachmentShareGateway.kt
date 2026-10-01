package ai.hermes.mobile.runtime.bridge.accessibility

import ai.hermes.mobile.runtime.bridge.attachment.SelectedAttachment
import ai.hermes.mobile.runtime.bridge.observer.AttachmentShareException
import ai.hermes.mobile.runtime.bridge.observer.AttachmentShareFailureReason
import ai.hermes.mobile.runtime.bridge.observer.AttachmentShareLaunch
import java.lang.ref.WeakReference

/** Weak process-local path into the system-bound Accessibility service. */
internal object AttachmentShareGateway {
    private var service = WeakReference<CurrentAppAccessibilityService>(null)

    @Synchronized
    fun connect(value: CurrentAppAccessibilityService) {
        service = WeakReference(value)
    }

    @Synchronized
    fun disconnect(value: CurrentAppAccessibilityService) {
        if (service.get() === value) service.clear()
    }

    @Synchronized
    fun availability(): AttachmentShareFailureReason? =
        if (service.get() == null) {
            AttachmentShareFailureReason.SERVICE_DISCONNECTED
        } else {
            null
        }

    fun launch(
        attachment: SelectedAttachment,
        packageName: String,
    ): AttachmentShareLaunch =
        active().launchAttachmentShare(attachment, packageName)

    private fun active(): CurrentAppAccessibilityService =
        synchronized(this) { service.get() }
            ?: throw AttachmentShareException(
                AttachmentShareFailureReason.SERVICE_DISCONNECTED,
            )
}

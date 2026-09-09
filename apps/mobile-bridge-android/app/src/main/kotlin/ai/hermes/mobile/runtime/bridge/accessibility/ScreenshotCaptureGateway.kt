package ai.hermes.mobile.runtime.bridge.accessibility

import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableException
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableReason
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotCapture
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotCaptureSource
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotSpec
import java.lang.ref.WeakReference

/** Holds only the live system-bound service and exposes no screenshot bytes. */
internal object ScreenshotCaptureGateway : ScreenshotCaptureSource {
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
    override fun availability(): PhoneStateUnavailableReason? =
        if (service.get() == null) PhoneStateUnavailableReason.SERVICE_DISCONNECTED else null

    override fun capture(spec: ScreenshotSpec): ScreenshotCapture {
        val active =
            synchronized(this) { service.get() }
                ?: throw PhoneStateUnavailableException(
                    PhoneStateUnavailableReason.SERVICE_DISCONNECTED,
                )
        return active.captureScreenshot(spec)
    }
}

package ai.hermes.mobile.runtime.bridge.accessibility

import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableException
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableReason
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiCapture
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiCaptureSource
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiLimits
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiProbe
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiProbeSource
import java.lang.ref.WeakReference

/** Holds only the live system-bound service; it never retains a UI node or tree plaintext. */
internal object SemanticUiCaptureGateway : SemanticUiCaptureSource, SemanticUiProbeSource {
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

    override fun capture(limits: SemanticUiLimits): SemanticUiCapture {
        val active =
            synchronized(this) { service.get() }
                ?: throw PhoneStateUnavailableException(
                    PhoneStateUnavailableReason.SERVICE_DISCONNECTED,
                )
        return active.captureSemanticUi(limits)
    }

    override fun probe(limits: SemanticUiLimits): SemanticUiProbe {
        val active =
            synchronized(this) { service.get() }
                ?: throw PhoneStateUnavailableException(
                    PhoneStateUnavailableReason.SERVICE_DISCONNECTED,
                )
        return active.probeSemanticUi(limits)
    }
}

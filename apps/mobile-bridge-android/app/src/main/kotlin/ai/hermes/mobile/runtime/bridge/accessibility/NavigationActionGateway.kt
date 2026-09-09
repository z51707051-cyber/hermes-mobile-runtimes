package ai.hermes.mobile.runtime.bridge.accessibility

import ai.hermes.mobile.runtime.bridge.observer.NavigationActionSource
import ai.hermes.mobile.runtime.bridge.observer.NavigationCommand
import ai.hermes.mobile.runtime.bridge.observer.NavigationExecution
import ai.hermes.mobile.runtime.bridge.observer.NavigationVerificationRequest
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableException
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableReason
import java.lang.ref.WeakReference

/** Weak process-local capability handle; no Android node or Context is retained. */
internal object NavigationActionGateway : NavigationActionSource {
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

    override fun requiredRisk(command: NavigationCommand): String = active().requiredRisk(command)

    override fun execute(
        command: NavigationCommand,
        verification: NavigationVerificationRequest?,
    ): NavigationExecution = active().executeNavigation(command, verification)

    private fun active(): CurrentAppAccessibilityService =
        synchronized(this) { service.get() }
            ?: throw PhoneStateUnavailableException(
                PhoneStateUnavailableReason.SERVICE_DISCONNECTED,
            )
}

package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.observer.CurrentAppStateStore
import ai.hermes.mobile.runtime.bridge.observer.CurrentAppTracker

/** Process-local composition root. It exposes no network, Binder, shell, or raw command endpoint. */
internal object BridgeRuntime {
    private val currentAppProvider = CurrentAppProvider(CurrentAppStateStore)
    private val capabilities = CapabilityRegistry(listOf(currentAppProvider))

    fun router(
        authorizationPep: AndroidPolicyEnforcementPoint = DenyAllPolicyEnforcementPoint,
    ): AndroidToolRouter =
        AndroidToolRouter(
            capabilities = capabilities,
            policyEnforcementPoint =
                CurrentAppPolicyEnforcementPoint(
                    authorizationDelegate = authorizationPep,
                    source = CurrentAppStateStore,
                ),
        )

    fun availableCapabilities(): List<String> =
        if (
            CurrentAppStateStore.availability(CurrentAppTracker.DEFAULT_MAXIMUM_AGE_MILLIS) == null
        ) {
            listOf(currentAppProvider.descriptor.tool)
        } else {
            emptyList()
        }
}

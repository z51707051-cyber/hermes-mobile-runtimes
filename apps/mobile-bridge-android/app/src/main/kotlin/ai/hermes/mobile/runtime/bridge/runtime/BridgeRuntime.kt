package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.accessibility.SemanticUiCaptureGateway
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateStore
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateObserver

/** Process-local composition root. It exposes no network, Binder, shell, or raw command endpoint. */
internal object BridgeRuntime {
    private val currentAppProvider = CurrentAppProvider(PhoneStateStore)
    private val readScreenProvider = ReadScreenProvider(SemanticUiCaptureGateway)
    private val capabilities = CapabilityRegistry(listOf(currentAppProvider, readScreenProvider))

    fun router(
        authorizationPep: AndroidPolicyEnforcementPoint = DenyAllPolicyEnforcementPoint,
    ): AndroidToolRouter =
        AndroidToolRouter(
            capabilities = capabilities,
            policyEnforcementPoint =
                CurrentAppPolicyEnforcementPoint(
                    authorizationDelegate = authorizationPep,
                    source = PhoneStateStore,
                    semanticUiSource = SemanticUiCaptureGateway,
                ),
        )

    fun availableCapabilities(): List<String> =
        if (
            PhoneStateStore.availability(PhoneStateObserver.DEFAULT_MAXIMUM_AGE_MILLIS) == null
        ) {
            buildList {
                add(currentAppProvider.descriptor.tool)
                if (SemanticUiCaptureGateway.availability() == null) {
                    add(readScreenProvider.descriptor.tool)
                }
            }
        } else {
            emptyList()
        }
}

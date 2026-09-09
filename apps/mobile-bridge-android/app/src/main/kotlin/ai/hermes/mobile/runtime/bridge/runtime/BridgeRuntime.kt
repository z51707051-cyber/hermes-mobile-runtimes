package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.accessibility.SemanticUiCaptureGateway
import ai.hermes.mobile.runtime.bridge.accessibility.ScreenshotCaptureGateway
import ai.hermes.mobile.runtime.bridge.accessibility.NavigationActionGateway
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateStore
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateObserver

/** Process-local composition root. It exposes no network, Binder, shell, or raw command endpoint. */
internal object BridgeRuntime {
    private val currentAppProvider = CurrentAppProvider(PhoneStateStore)
    private val readScreenProvider = ReadScreenProvider(SemanticUiCaptureGateway)
    private val screenshotProvider = ScreenshotProvider(ScreenshotCaptureGateway)
    private val navigationProviders =
        listOf(
            "phone.tap",
            "phone.long_press",
            "phone.type",
            "phone.swipe",
            "phone.back",
            "phone.home",
            "phone.open_app",
        ).map { tool -> NavigationProvider(tool, NavigationActionGateway) }
    private val capabilities =
        CapabilityRegistry(
            listOf(currentAppProvider, readScreenProvider, screenshotProvider) + navigationProviders,
        )

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
                    screenshotSource = ScreenshotCaptureGateway,
                    navigationSource = NavigationActionGateway,
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
                if (ScreenshotCaptureGateway.availability() == null) {
                    add(screenshotProvider.descriptor.tool)
                }
                if (NavigationActionGateway.availability() == null) {
                    addAll(navigationProviders.map { it.descriptor.tool })
                }
            }
        } else {
            emptyList()
        }
}

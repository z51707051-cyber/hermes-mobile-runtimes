package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSource
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateObserver
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableReason
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiCaptureSource
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotCaptureSource

/** Checks live capability state after authorization and immediately before dispatch. */
internal class CurrentAppPolicyEnforcementPoint(
    private val authorizationDelegate: AndroidPolicyEnforcementPoint,
    private val source: PhoneStateSource,
    private val semanticUiSource: SemanticUiCaptureSource? = null,
    private val screenshotSource: ScreenshotCaptureSource? = null,
    private val maximumAgeMillis: Long = PhoneStateObserver.DEFAULT_MAXIMUM_AGE_MILLIS,
) : AndroidPolicyEnforcementPoint {
    override fun evaluate(action: AuthorizedAction): PepDecision {
        val authorizationDecision = authorizationDelegate.evaluate(action)
        if (authorizationDecision.disposition != PepDisposition.ALLOW) {
            return authorizationDecision
        }
        return when (action.tool) {
            CURRENT_APP_TOOL ->
                if (source.availability(maximumAgeMillis) == null) {
                    PepDecision.allow()
                } else {
                    PepDecision.deny("CAPABILITY_UNAVAILABLE")
                }
            READ_SCREEN_TOOL ->
                if (
                    readCaptureIdentityAvailable() &&
                    semanticUiSource != null &&
                    semanticUiSource.availability() == null
                ) {
                    PepDecision.allow()
                } else {
                    PepDecision.deny("CAPABILITY_UNAVAILABLE")
                }
            SCREENSHOT_TOOL ->
                if (
                    readCaptureIdentityAvailable() &&
                    screenshotSource != null &&
                    screenshotSource.availability() == null
                ) {
                    PepDecision.allow()
                } else {
                    PepDecision.deny("CAPABILITY_UNAVAILABLE")
                }
            else -> authorizationDecision
        }
    }

    private fun readCaptureIdentityAvailable(): Boolean =
        source.availability(maximumAgeMillis).let { unavailable ->
            unavailable == null || unavailable ==
                PhoneStateUnavailableReason.STALE_WINDOW_STATE
        }

    private companion object {
        const val CURRENT_APP_TOOL = "phone.current_app"
        const val READ_SCREEN_TOOL = "phone.read_screen"
        const val SCREENSHOT_TOOL = "phone.screenshot"
    }
}

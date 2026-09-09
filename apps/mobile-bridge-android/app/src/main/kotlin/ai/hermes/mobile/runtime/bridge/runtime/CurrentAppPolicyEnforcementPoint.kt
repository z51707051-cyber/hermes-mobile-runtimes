package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.observer.DeviceStateCaptureSource
import ai.hermes.mobile.runtime.bridge.observer.NavigationActionSource
import ai.hermes.mobile.runtime.bridge.observer.NavigationFailureException
import ai.hermes.mobile.runtime.bridge.observer.NotificationCaptureSource
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateCaptureStatus
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateObserver
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSource
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableReason
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiCaptureSource
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotCaptureSource

/** Checks live capability state after authorization and immediately before dispatch. */
internal class CurrentAppPolicyEnforcementPoint(
    private val authorizationDelegate: AndroidPolicyEnforcementPoint,
    private val source: PhoneStateSource,
    private val semanticUiSource: SemanticUiCaptureSource? = null,
    private val screenshotSource: ScreenshotCaptureSource? = null,
    private val navigationSource: NavigationActionSource? = null,
    private val notificationSource: NotificationCaptureSource? = null,
    private val deviceStateSource: DeviceStateCaptureSource? = null,
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
            NOTIFICATIONS_TOOL ->
                if (notificationSource?.let { it.availability() == null } == true) {
                    PepDecision.allow()
                } else {
                    PepDecision.deny("CAPABILITY_UNAVAILABLE")
                }
            DEVICE_STATE_TOOL ->
                if (deviceStateSource?.let { it.availability() == null } == true) {
                    PepDecision.allow()
                } else {
                    PepDecision.deny("CAPABILITY_UNAVAILABLE")
                }
            in NAVIGATION_TOOLS -> navigationDecision(action)
            else -> authorizationDecision
        }
    }

    private fun navigationDecision(action: AuthorizedAction): PepDecision {
        val activeSource = navigationSource ?: return PepDecision.deny("CAPABILITY_UNAVAILABLE")
        if (activeSource.availability() != null) {
            return PepDecision.deny("CAPABILITY_UNAVAILABLE")
        }
        val command =
            try {
                NavigationCommandParser.parse(action)
            } catch (exc: Exception) {
                return PepDecision.deny("ACTION_MISMATCH")
            }
        val maximumAge = command.precondition?.maximumAgeMillis ?: maximumAgeMillis
        val current =
            try {
                source.current(maximumAge)
            } catch (exc: Exception) {
                return PepDecision.deny("CAPABILITY_UNAVAILABLE")
            }
        if (current.captureStatus != PhoneStateCaptureStatus.COMPLETE) {
            return PepDecision.deny("ACTION_MISMATCH")
        }
        command.precondition?.let { precondition ->
            if (
                current.stateId != precondition.stateId ||
                precondition.foregroundPackage?.let { it != current.packageName } == true
            ) {
                return PepDecision.deny("ACTION_MISMATCH")
            }
        }
        if (!effectiveTargetMatches(action)) {
            return PepDecision.deny("ACTION_MISMATCH")
        }
        val requiredRisk =
            try {
                activeSource.requiredRisk(command)
            } catch (exc: NavigationFailureException) {
                return PepDecision.deny("ACTION_MISMATCH")
            } catch (exc: Exception) {
                return PepDecision.deny("CAPABILITY_UNAVAILABLE")
            }
        if (riskValue(requiredRisk) >= 4) {
            // V0.1 has no device-authenticated L4/L5 execution surface.
            return PepDecision.deny("PERMISSION_DENIED")
        }
        return if (riskValue(action.effectiveRisk) < riskValue(requiredRisk)) {
            PepDecision.deny("RISK_UPGRADE_REQUIRED")
        } else {
            PepDecision.allow()
        }
    }

    private fun effectiveTargetMatches(action: AuthorizedAction): Boolean {
        val effective = action.message["effective_target"]
        return when (action.tool) {
            "phone.tap", "phone.long_press" -> effective == action.parameters["target"]
            "phone.type" -> effective == action.parameters["target"]
            else -> effective == null
        }
    }

    private fun riskValue(risk: String): Int = risk.removePrefix("L").toIntOrNull() ?: 6

    private fun readCaptureIdentityAvailable(): Boolean =
        source.availability(maximumAgeMillis).let { unavailable ->
            unavailable == null || unavailable ==
                PhoneStateUnavailableReason.STALE_WINDOW_STATE
        }

    private companion object {
        const val CURRENT_APP_TOOL = "phone.current_app"
        const val READ_SCREEN_TOOL = "phone.read_screen"
        const val SCREENSHOT_TOOL = "phone.screenshot"
        const val NOTIFICATIONS_TOOL = "phone.notifications"
        const val DEVICE_STATE_TOOL = "phone.device_state"
        val NAVIGATION_TOOLS =
            setOf(
                "phone.tap",
                "phone.long_press",
                "phone.type",
                "phone.swipe",
                "phone.back",
                "phone.home",
                "phone.open_app",
            )
    }
}

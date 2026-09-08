package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSource
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateObserver

/** Checks live capability state after authorization and immediately before dispatch. */
internal class CurrentAppPolicyEnforcementPoint(
    private val authorizationDelegate: AndroidPolicyEnforcementPoint,
    private val source: PhoneStateSource,
    private val maximumAgeMillis: Long = PhoneStateObserver.DEFAULT_MAXIMUM_AGE_MILLIS,
) : AndroidPolicyEnforcementPoint {
    override fun evaluate(action: AuthorizedAction): PepDecision {
        val authorizationDecision = authorizationDelegate.evaluate(action)
        if (authorizationDecision.disposition != PepDisposition.ALLOW) {
            return authorizationDecision
        }
        if (action.tool != CURRENT_APP_TOOL) return authorizationDecision
        return if (source.availability(maximumAgeMillis) == null) {
            PepDecision.allow()
        } else {
            PepDecision.deny("CAPABILITY_UNAVAILABLE")
        }
    }

    private companion object {
        const val CURRENT_APP_TOOL = "phone.current_app"
    }
}


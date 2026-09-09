package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.observer.NavigationActionSource
import ai.hermes.mobile.runtime.bridge.observer.NavigationCommand
import ai.hermes.mobile.runtime.bridge.observer.NavigationExecution
import ai.hermes.mobile.runtime.bridge.observer.NavigationFailureException
import ai.hermes.mobile.runtime.bridge.observer.NavigationFailureReason
import ai.hermes.mobile.runtime.bridge.observer.NavigationVerificationRequest
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateCaptureStatus
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSnapshot
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSource
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableReason
import ai.hermes.mobile.runtime.bridge.observer.ScreenFingerprint
import ai.hermes.mobile.runtime.bridge.observer.ScreenFingerprintBasis
import ai.hermes.mobile.runtime.bridge.observer.ScreenTransition
import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.protocol.FixtureFiles
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NavigationProviderTest {
    @Test
    fun semanticTapReturnsSeparateExecutionAndVerification() {
        val source = FakeNavigationSource(requiredRisk = "L1")
        val router = router(source)

        val result = ProtocolCodec.decode(router.routeAuthorized(tapAction("L1")))

        assertEquals("SUCCEEDED", result["execution_status"])
        assertEquals("state-tree", (result["before_state"] as Map<*, *>)["state_id"])
        assertEquals("state-after", (result["after_state"] as Map<*, *>)["state_id"])
        assertEquals("PASSED", (result["verification"] as Map<*, *>)["status"])
        assertEquals(1, source.riskCalls)
        assertEquals(1, source.executeCalls)
    }

    @Test
    fun devicePepRequiresRiskUpgradeBeforeProvider() {
        val source = FakeNavigationSource(requiredRisk = "L3")
        val rejected =
            assertThrows(AndroidRouteRejectedException::class.java) {
                router(source).routeAuthorized(tapAction("L1"))
            }

        assertEquals("RISK_UPGRADE_REQUIRED", rejected.code)
        assertEquals(1, source.riskCalls)
        assertEquals(0, source.executeCalls)
    }

    @Test
    fun destructiveTargetStaysBlockedWithoutDeviceAuthentication() {
        val source = FakeNavigationSource(requiredRisk = "L4")
        val rejected =
            assertThrows(AndroidRouteRejectedException::class.java) {
                router(source).routeAuthorized(tapAction("L4"))
            }

        assertEquals("PERMISSION_DENIED", rejected.code)
        assertEquals(0, source.executeCalls)
    }

    @Test
    fun exactStateAndEffectiveTargetAreEnforcedAtDevicePep() {
        val source = FakeNavigationSource(requiredRisk = "L1")
        val changedState = tapAction("L1", preconditionState = "state-other")
        val stateFailure =
            assertThrows(AndroidRouteRejectedException::class.java) {
                router(source).routeAuthorized(changedState)
            }
        assertEquals("ACTION_MISMATCH", stateFailure.code)

        val action = ProtocolCodec.decode(tapAction("L1")).toMutableMap()
        action["effective_target"] =
            mapOf("state_id" to "state-tree", "node_id" to "node-2")
        action["action_digest"] = CanonicalJson.actionDigest(action)
        val targetFailure =
            assertThrows(AndroidRouteRejectedException::class.java) {
                router(source).routeAuthorized(ProtocolCodec.encode(action))
            }
        assertEquals("ACTION_MISMATCH", targetFailure.code)
        assertEquals(0, source.executeCalls)
    }

    @Test
    fun postActionObservationFailureIsUnknownOutcomeNotBlindRetrySuccess() {
        val before = state("state-tree")
        val source =
            FakeNavigationSource(
                requiredRisk = "L1",
                failure =
                    NavigationFailureException(
                        NavigationFailureReason.POST_ACTION_OBSERVATION_FAILED,
                        before,
                    ),
            )
        val provider = provider(source)
        val router =
            AndroidToolRouter(
                CapabilityRegistry(listOf(provider)),
                AndroidPolicyEnforcementPoint { PepDecision.allow() },
            )

        val result = ProtocolCodec.decode(router.routeAuthorized(tapAction("L1")))

        assertEquals("UNKNOWN_OUTCOME", result["execution_status"])
        assertEquals(true, result["recoverable"])
        assertEquals("state-tree", (result["before_state"] as Map<*, *>)["state_id"])
        assertEquals("VERIFICATION_FAILED", (result["error"] as Map<*, *>)["code"])
        assertEquals("INCONCLUSIVE", (result["verification"] as Map<*, *>)["status"])
    }

    private class FakeNavigationSource(
        private val requiredRisk: String,
        private val failure: NavigationFailureException? = null,
    ) : NavigationActionSource {
        var riskCalls = 0
        var executeCalls = 0

        override fun availability(): PhoneStateUnavailableReason? = null

        override fun requiredRisk(command: NavigationCommand): String {
            riskCalls += 1
            return requiredRisk
        }

        override fun execute(
            command: NavigationCommand,
            verification: NavigationVerificationRequest?,
        ): NavigationExecution {
            executeCalls += 1
            failure?.let { throw it }
            return NavigationExecution(
                beforeState = state("state-tree"),
                afterState = state("state-after", previous = "state-tree"),
                verificationStatus = "PASSED",
                verificationExplanation = "comparable screen fingerprint changed",
                redactions = emptyList(),
            )
        }
    }

    private class FakePhoneStateSource : PhoneStateSource {
        override fun availability(maximumAgeMillis: Long): PhoneStateUnavailableReason? = null

        override fun current(maximumAgeMillis: Long): PhoneStateSnapshot = state("state-tree")
    }

    private companion object {
        fun router(source: NavigationActionSource): AndroidToolRouter =
            AndroidToolRouter(
                CapabilityRegistry(listOf(provider(source))),
                CurrentAppPolicyEnforcementPoint(
                    authorizationDelegate = AndroidPolicyEnforcementPoint { PepDecision.allow() },
                    source = FakePhoneStateSource(),
                    navigationSource = source,
                ),
            )

        fun provider(source: NavigationActionSource): NavigationProvider =
            NavigationProvider(
                tool = "phone.tap",
                source = source,
                epochClock = ProviderEpochClock { 1_788_150_001_000L },
                elapsedClock = ProviderElapsedClock { 100L },
            )

        fun tapAction(
            risk: String,
            preconditionState: String = "state-tree",
        ): ByteArray {
            val target = mapOf("state_id" to preconditionState, "node_id" to "node-1")
            val action =
                ProtocolCodec.decode(
                    FixtureFiles.bytes("valid/authorized-action.json"),
                ).toMutableMap()
            action["tool"] = "phone.tap"
            action["parameters"] = mapOf("target" to target)
            action["state_precondition"] =
                mapOf(
                    "state_id" to preconditionState,
                    "maximum_age_ms" to 1_000,
                    "foreground_package" to "com.example.music",
                )
            action["verification"] = mapOf("condition" to "STATE_CHANGED")
            action["effective_target"] = target
            action["effective_risk"] = risk
            action["action_digest"] = CanonicalJson.actionDigest(action)
            return ProtocolCodec.encode(action)
        }

        fun state(
            id: String,
            previous: String? = "state-window",
        ): PhoneStateSnapshot =
            PhoneStateSnapshot(
                stateId = id,
                previousStateId = previous,
                packageName = "com.example.music",
                activityName = "com.example.music.PlayerActivity",
                screenFingerprint =
                    ScreenFingerprint(
                        basis = ScreenFingerprintBasis.UI_HIERARCHY,
                        digest =
                            "sha256:" +
                                (if (id == "state-after") "b".repeat(64) else "a".repeat(64)),
                    ),
                captureStatus = PhoneStateCaptureStatus.COMPLETE,
                captureErrors = emptyList(),
                transition = if (id == "state-after") ScreenTransition.CHANGED else ScreenTransition.UNKNOWN,
                capturedAtEpochMillis = 1_788_150_000_000L,
                freshnessMillis = 25,
            )
    }
}

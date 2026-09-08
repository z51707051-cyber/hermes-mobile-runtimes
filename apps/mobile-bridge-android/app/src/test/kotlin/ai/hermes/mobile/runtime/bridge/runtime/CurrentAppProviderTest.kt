package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSnapshot
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSource
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableException
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableReason
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateCaptureStatus
import ai.hermes.mobile.runtime.bridge.observer.ScreenFingerprint
import ai.hermes.mobile.runtime.bridge.observer.ScreenFingerprintBasis
import ai.hermes.mobile.runtime.bridge.observer.ScreenTransition
import ai.hermes.mobile.runtime.bridge.protocol.FixtureFiles
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CurrentAppProviderTest {
    @Test
    fun protectedRouteReturnsBoundCurrentAppState() {
        val source = FakePhoneStateSource(observation = observation())
        val provider = provider(source)
        val router =
            AndroidToolRouter(
                CapabilityRegistry(listOf(provider)),
                CurrentAppPolicyEnforcementPoint(
                    authorizationDelegate = AndroidPolicyEnforcementPoint { PepDecision.allow() },
                    source = source,
                ),
            )

        val result =
            ProtocolCodec.decode(
                router.routeAuthorized(FixtureFiles.bytes("valid/authorized-action.json")),
            )

        assertEquals("SUCCEEDED", result["execution_status"])
        assertEquals("decision-0001", result["permission_decision_id"])
        assertEquals(1, source.currentCalls)
        val before = result["before_state"] as Map<*, *>
        val after = result["after_state"] as Map<*, *>
        assertEquals("com.example.music", before["foreground_package"])
        assertEquals("com.example.music.PlayerActivity", before["foreground_activity"])
        assertEquals("state-previous", before["previous_state_id"])
        assertEquals("CHANGED", before["transition"])
        assertEquals("COMPLETE", before["capture_status"])
        assertEquals(emptyList<String>(), before["capture_errors"])
        assertEquals(
            "WINDOW_IDENTITY",
            (before["screen_fingerprint"] as Map<*, *>)["basis"],
        )
        assertEquals(before, after)
        assertEquals("state-current", after["state_id"])
        assertEquals(false, result.containsKey("extensions"))
        assertNull(result["error"])
    }

    @Test
    fun capabilityRevocationAtPepNeverInvokesProvider() {
        val source =
            FakePhoneStateSource(
                observation = observation(),
                unavailableReason = PhoneStateUnavailableReason.SERVICE_DISCONNECTED,
            )
        val router =
            AndroidToolRouter(
                CapabilityRegistry(listOf(provider(source))),
                CurrentAppPolicyEnforcementPoint(
                    authorizationDelegate = AndroidPolicyEnforcementPoint { PepDecision.allow() },
                    source = source,
                ),
            )

        val rejected =
            assertThrows(AndroidRouteRejectedException::class.java) {
                router.routeAuthorized(FixtureFiles.bytes("valid/authorized-action.json"))
            }

        assertEquals("CAPABILITY_UNAVAILABLE", rejected.code)
        assertEquals(0, source.currentCalls)
    }

    @Test
    fun disconnectRaceProducesSchemaValidTypedFailure() {
        val source =
            FakePhoneStateSource(
                observation = observation(),
                failCurrent = PhoneStateUnavailableReason.SERVICE_DISCONNECTED,
            )
        val router =
            AndroidToolRouter(
                CapabilityRegistry(listOf(provider(source))),
                CurrentAppPolicyEnforcementPoint(
                    authorizationDelegate = AndroidPolicyEnforcementPoint { PepDecision.allow() },
                    source = source,
                ),
            )

        val result =
            ProtocolCodec.decode(
                router.routeAuthorized(FixtureFiles.bytes("valid/authorized-action.json")),
            )

        assertEquals("FAILED", result["execution_status"])
        assertEquals(true, result["recoverable"])
        assertEquals("CAPABILITY_UNAVAILABLE", (result["error"] as Map<*, *>)["code"])
        assertEquals(1, source.currentCalls)
    }

    @Test
    fun authorizationDenialRunsBeforeCapabilityCheck() {
        val source = FakePhoneStateSource(observation = observation())
        val router =
            AndroidToolRouter(
                CapabilityRegistry(listOf(provider(source))),
                CurrentAppPolicyEnforcementPoint(
                    authorizationDelegate =
                        AndroidPolicyEnforcementPoint {
                            PepDecision.deny("AUTHORIZATION_INVALID")
                        },
                    source = source,
                ),
            )

        val rejected =
            assertThrows(AndroidRouteRejectedException::class.java) {
                router.routeAuthorized(FixtureFiles.bytes("valid/authorized-action.json"))
            }

        assertEquals("AUTHORIZATION_INVALID", rejected.code)
        assertEquals(0, source.availabilityCalls)
        assertEquals(0, source.currentCalls)
    }

    private class FakePhoneStateSource(
        private val observation: PhoneStateSnapshot,
        private val unavailableReason: PhoneStateUnavailableReason? = null,
        private val failCurrent: PhoneStateUnavailableReason? = null,
    ) : PhoneStateSource {
        var availabilityCalls = 0
        var currentCalls = 0

        override fun availability(maximumAgeMillis: Long): PhoneStateUnavailableReason? {
            availabilityCalls += 1
            return unavailableReason
        }

        override fun current(maximumAgeMillis: Long): PhoneStateSnapshot {
            currentCalls += 1
            failCurrent?.let { throw PhoneStateUnavailableException(it) }
            return observation
        }
    }

    private companion object {
        fun provider(source: PhoneStateSource): CurrentAppProvider =
            CurrentAppProvider(
                source = source,
                epochClock = ProviderEpochClock { 1_788_150_001_000L },
                elapsedClock = ProviderElapsedClock { 100L },
            )

        fun observation(): PhoneStateSnapshot =
            PhoneStateSnapshot(
                stateId = "state-current",
                previousStateId = "state-previous",
                packageName = "com.example.music",
                activityName = "com.example.music.PlayerActivity",
                screenFingerprint =
                    ScreenFingerprint(
                        basis = ScreenFingerprintBasis.WINDOW_IDENTITY,
                        digest = "sha256:" + "a".repeat(64),
                    ),
                captureStatus = PhoneStateCaptureStatus.COMPLETE,
                captureErrors = emptyList(),
                transition = ScreenTransition.CHANGED,
                capturedAtEpochMillis = 1_788_150_000_000L,
                freshnessMillis = 25,
            )
    }
}

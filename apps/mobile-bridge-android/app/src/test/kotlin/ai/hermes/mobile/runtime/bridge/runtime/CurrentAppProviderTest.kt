package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.observer.CurrentAppObservation
import ai.hermes.mobile.runtime.bridge.observer.CurrentAppSource
import ai.hermes.mobile.runtime.bridge.observer.CurrentAppUnavailableException
import ai.hermes.mobile.runtime.bridge.observer.CurrentAppUnavailableReason
import ai.hermes.mobile.runtime.bridge.protocol.FixtureFiles
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CurrentAppProviderTest {
    @Test
    fun protectedRouteReturnsBoundCurrentAppState() {
        val source = FakeCurrentAppSource(observation = observation())
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
        assertEquals(before, after)
        assertEquals("state-current", after["state_id"])
        assertEquals(false, result.containsKey("extensions"))
        assertNull(result["error"])
    }

    @Test
    fun capabilityRevocationAtPepNeverInvokesProvider() {
        val source =
            FakeCurrentAppSource(
                observation = observation(),
                unavailableReason = CurrentAppUnavailableReason.SERVICE_DISCONNECTED,
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
            FakeCurrentAppSource(
                observation = observation(),
                failCurrent = CurrentAppUnavailableReason.SERVICE_DISCONNECTED,
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
        val source = FakeCurrentAppSource(observation = observation())
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

    private class FakeCurrentAppSource(
        private val observation: CurrentAppObservation,
        private val unavailableReason: CurrentAppUnavailableReason? = null,
        private val failCurrent: CurrentAppUnavailableReason? = null,
    ) : CurrentAppSource {
        var availabilityCalls = 0
        var currentCalls = 0

        override fun availability(maximumAgeMillis: Long): CurrentAppUnavailableReason? {
            availabilityCalls += 1
            return unavailableReason
        }

        override fun current(maximumAgeMillis: Long): CurrentAppObservation {
            currentCalls += 1
            failCurrent?.let { throw CurrentAppUnavailableException(it) }
            return observation
        }
    }

    private companion object {
        fun provider(source: CurrentAppSource): CurrentAppProvider =
            CurrentAppProvider(
                source = source,
                epochClock = ProviderEpochClock { 1_788_150_001_000L },
                elapsedClock = ProviderElapsedClock { 100L },
            )

        fun observation(): CurrentAppObservation =
            CurrentAppObservation(
                stateId = "state-current",
                packageName = "com.example.music",
                activityName = "com.example.music.PlayerActivity",
                capturedAtEpochMillis = 1_788_150_000_000L,
                freshnessMillis = 25,
            )
    }
}

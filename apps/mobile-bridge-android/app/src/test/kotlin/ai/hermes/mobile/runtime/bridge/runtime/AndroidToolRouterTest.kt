package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.protocol.FixtureFiles
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidToolRouterTest {
    @Test
    fun capabilityCatalogMatchesProtocolAndRejectsInvalidProviders() {
        assertEquals(
            ProtocolCodec.canonicalToolNames(),
            CapabilityRegistry.definitions.map { it.tool }.toSet(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CapabilityRegistry(
                listOf(
                    FakeProvider("phone.raw_shell", validResult()),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CapabilityRegistry(
                listOf(
                    FakeProvider("phone.current_app", validResult()),
                    FakeProvider("phone.current_app", validResult()),
                ),
            )
        }
    }

    @Test
    fun plannerRequestCannotReachPepOrProvider() {
        val provider = FakeProvider("phone.current_app", validResult())
        var pepCalls = 0
        val router =
            AndroidToolRouter(CapabilityRegistry(listOf(provider))) {
                pepCalls += 1
                PepDecision.allow()
            }

        val rejected =
            assertThrows(AndroidRouteRejectedException::class.java) {
                router.routeAuthorized(FixtureFiles.bytes("valid/tool-execution-request.json"))
            }

        assertEquals("ACTION_REJECTED", rejected.code)
        assertEquals(0, pepCalls)
        assertEquals(0, provider.calls)
    }

    @Test
    fun pepDenialOccursBeforeProviderResolution() {
        val router = AndroidToolRouter(CapabilityRegistry()) { PepDecision.deny("DEVICE_LOCKED") }

        val rejected =
            assertThrows(AndroidRouteRejectedException::class.java) {
                router.routeAuthorized(FixtureFiles.bytes("valid/authorized-action.json"))
            }

        assertEquals("DEVICE_LOCKED", rejected.code)
    }

    @Test
    fun unavailableCapabilityFailsClosedAfterPepAllows() {
        val router = AndroidToolRouter(CapabilityRegistry()) { PepDecision.allow() }

        val rejected =
            assertThrows(AndroidRouteRejectedException::class.java) {
                router.routeAuthorized(FixtureFiles.bytes("valid/authorized-action.json"))
            }

        assertEquals("CAPABILITY_UNAVAILABLE", rejected.code)
    }

    @Test
    fun defaultOrFailingPepNeverInvokesProvider() {
        val provider = FakeProvider("phone.current_app", validResult())
        val registry = CapabilityRegistry(listOf(provider))

        val denied =
            assertThrows(AndroidRouteRejectedException::class.java) {
                AndroidToolRouter(registry)
                    .routeAuthorized(FixtureFiles.bytes("valid/authorized-action.json"))
            }
        assertEquals("CAPABILITY_UNAVAILABLE", denied.code)

        val failed =
            assertThrows(AndroidRouteRejectedException::class.java) {
                AndroidToolRouter(registry) { error("PEP unavailable") }
                    .routeAuthorized(FixtureFiles.bytes("valid/authorized-action.json"))
            }
        assertEquals("AUTHORIZATION_INVALID", failed.code)
        assertEquals(0, provider.calls)
    }

    @Test
    fun authorizedActionDispatchesExactlyOnceAndResultIsBound() {
        val provider = FakeProvider("phone.current_app", validResult())
        var pepCalls = 0
        val router =
            AndroidToolRouter(CapabilityRegistry(listOf(provider))) { action ->
                pepCalls += 1
                assertEquals("decision-0001", action.policyDecisionId)
                PepDecision.allow()
            }

        val result =
            ProtocolCodec.decode(
                router.routeAuthorized(FixtureFiles.bytes("valid/authorized-action.json")),
            )

        assertEquals("SUCCEEDED", result["execution_status"])
        assertEquals(1, pepCalls)
        assertEquals(1, provider.calls)
    }

    @Test
    fun providerCannotChangeRequestOrDecisionBinding() {
        val changed =
            ProtocolCodec.decode(validResult()) +
                ("permission_decision_id" to "decision-other")
        val provider = FakeProvider("phone.current_app", ProtocolCodec.encode(changed))
        val router = AndroidToolRouter(CapabilityRegistry(listOf(provider))) { PepDecision.allow() }

        val rejected =
            assertThrows(AndroidRouteRejectedException::class.java) {
                router.routeAuthorized(FixtureFiles.bytes("valid/authorized-action.json"))
            }

        assertEquals("ACTION_MISMATCH", rejected.code)
        assertEquals(1, provider.calls)
    }

    @Test
    fun capabilityCannotUndercutItsBaselineRisk() {
        val action = ProtocolCodec.decode(FixtureFiles.bytes("valid/authorized-action.json")).toMutableMap()
        action["tool"] = "phone.open_app"
        action["parameters"] = mapOf("package" to "com.example.music")
        action["effective_risk"] = "L0"
        action["action_digest"] = CanonicalJson.actionDigest(action)
        val provider = FakeProvider("phone.open_app", validResult())
        var pepCalls = 0
        val router =
            AndroidToolRouter(CapabilityRegistry(listOf(provider))) {
                pepCalls += 1
                PepDecision.allow()
            }

        val rejected =
            assertThrows(AndroidRouteRejectedException::class.java) {
                router.routeAuthorized(ProtocolCodec.encode(action))
            }

        assertEquals("ACTION_MISMATCH", rejected.code)
        assertEquals(0, pepCalls)
        assertEquals(0, provider.calls)
    }

    private class FakeProvider(
        tool: String,
        private val result: ByteArray,
    ) : CapabilityProvider {
        override val descriptor = CapabilityDescriptor(tool, "fake.provider")
        var calls = 0

        override fun execute(action: AuthorizedAction): ByteArray {
            calls += 1
            return result
        }
    }

    private companion object {
        fun validResult(): ByteArray {
            val action = ProtocolCodec.decode(FixtureFiles.bytes("valid/authorized-action.json"))
            val result =
                ProtocolCodec.decode(FixtureFiles.bytes("valid/tool-execution-result.json")) +
                    mapOf(
                        "permission_decision_id" to action["policy_decision_id"],
                        "parameter_digest" to CanonicalJson.sha256(action["parameters"]),
                    )
            return ProtocolCodec.encode(result)
        }
    }
}

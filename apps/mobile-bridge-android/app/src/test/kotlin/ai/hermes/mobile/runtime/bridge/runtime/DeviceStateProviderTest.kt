package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.artifact.ArtifactReference
import ai.hermes.mobile.runtime.bridge.observer.DeviceStateCapture
import ai.hermes.mobile.runtime.bridge.observer.DeviceStateCaptureSource
import ai.hermes.mobile.runtime.bridge.observer.DeviceStateQuery
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSnapshot
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSource
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableException
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableReason
import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.protocol.FixtureFiles
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceStateProviderTest {
    @Test
    fun protectedRouteReturnsOnlyAllowlistedD2ArtifactReference() {
        val source = FakeDeviceStateSource()
        val phoneState = UnavailablePhoneStateSource()
        val router =
            AndroidToolRouter(
                CapabilityRegistry(
                    listOf(
                        DeviceStateProvider(
                            source,
                            phoneState,
                            ProviderEpochClock { 1_788_150_001_000L },
                            ProviderElapsedClock { 100L },
                        ),
                    ),
                ),
                CurrentAppPolicyEnforcementPoint(
                    AndroidPolicyEnforcementPoint { PepDecision.allow() },
                    phoneState,
                    deviceStateSource = source,
                ),
            )

        val result = ProtocolCodec.decode(router.routeAuthorized(action()))

        assertEquals("SUCCEEDED", result["execution_status"])
        assertNull(result["before_state"])
        assertEquals(DeviceStateQuery(setOf("BATTERY", "WIFI")), source.query)
        val artifact = (result["artifacts"] as List<*>).single() as Map<*, *>
        assertEquals("device-state-artifact", artifact["artifact_id"])
        assertEquals("D2", artifact["sensitivity"])
        assertEquals(emptyList<String>(), result["redactions"])
    }

    private class FakeDeviceStateSource : DeviceStateCaptureSource {
        var query: DeviceStateQuery? = null

        override fun availability(): PhoneStateUnavailableReason? = null

        override fun capture(query: DeviceStateQuery): DeviceStateCapture {
            this.query = query
            return DeviceStateCapture(
                ArtifactReference(
                    "device-state-artifact",
                    "application/vnd.hermes.device-state+json",
                    64,
                    "sha256:" + "d".repeat(64),
                    "D2",
                    "REDACTED",
                    "EPHEMERAL",
                    1_788_150_300_000L,
                ),
                emptyList(),
            )
        }
    }

    private companion object {
        fun action(): ByteArray {
            val action =
                ProtocolCodec.decode(
                    FixtureFiles.bytes("valid/authorized-action.json"),
                ).toMutableMap()
            action["tool"] = "phone.device_state"
            action["parameters"] = mapOf("fields" to listOf("BATTERY", "WIFI"))
            action["action_digest"] = CanonicalJson.actionDigest(action)
            return ProtocolCodec.encode(action)
        }
    }

    private class UnavailablePhoneStateSource : PhoneStateSource {
        override fun availability(maximumAgeMillis: Long): PhoneStateUnavailableReason =
            PhoneStateUnavailableReason.SERVICE_DISCONNECTED

        override fun current(maximumAgeMillis: Long): PhoneStateSnapshot =
            throw PhoneStateUnavailableException(PhoneStateUnavailableReason.SERVICE_DISCONNECTED)
    }
}

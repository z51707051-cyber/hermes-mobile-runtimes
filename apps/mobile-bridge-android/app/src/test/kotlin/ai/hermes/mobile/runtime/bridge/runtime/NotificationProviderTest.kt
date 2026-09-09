package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.artifact.ArtifactReference
import ai.hermes.mobile.runtime.bridge.observer.NotificationCapture
import ai.hermes.mobile.runtime.bridge.observer.NotificationCaptureSource
import ai.hermes.mobile.runtime.bridge.observer.NotificationQuery
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSnapshot
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSource
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableException
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableReason
import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.protocol.FixtureFiles
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NotificationProviderTest {
    @Test
    fun protectedRouteReturnsOnlyD3ArtifactReferenceWithoutPhoneStateDependency() {
        val source = FakeNotificationSource()
        val router = router(source)

        val result = ProtocolCodec.decode(router.routeAuthorized(action()))

        assertEquals("SUCCEEDED", result["execution_status"])
        assertNull(result["before_state"])
        assertEquals(NotificationQuery(null, 20, setOf("com.example.chat")), source.query)
        val artifact = (result["artifacts"] as List<*>).single() as Map<*, *>
        assertEquals("notification-artifact", artifact["artifact_id"])
        assertEquals("D3", artifact["sensitivity"])
        assertEquals(listOf("NOTIFICATION_FIELDS_MINIMIZED"), result["redactions"])
        assertEquals(false, result.toString().contains("message body"))
    }

    @Test
    fun notificationAccessRevocationIsRejectedBeforeCapture() {
        val source = FakeNotificationSource(PhoneStateUnavailableReason.SERVICE_DISCONNECTED)
        val router = router(source)

        val rejected =
            assertThrows(AndroidRouteRejectedException::class.java) {
                router.routeAuthorized(action())
            }

        assertEquals("CAPABILITY_UNAVAILABLE", rejected.code)
        assertEquals(0, source.calls)
    }

    private class FakeNotificationSource(
        private val unavailable: PhoneStateUnavailableReason? = null,
    ) : NotificationCaptureSource {
        var calls = 0
        var query: NotificationQuery? = null

        override fun availability(): PhoneStateUnavailableReason? = unavailable

        override fun capture(query: NotificationQuery): NotificationCapture {
            calls += 1
            this.query = query
            return NotificationCapture(artifact(), listOf("NOTIFICATION_FIELDS_MINIMIZED"))
        }
    }

    private companion object {
        fun router(source: NotificationCaptureSource): AndroidToolRouter {
            val phoneState = UnavailablePhoneStateSource()
            return AndroidToolRouter(
                CapabilityRegistry(
                    listOf(
                        NotificationProvider(
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
                    notificationSource = source,
                ),
            )
        }

        fun action(): ByteArray {
            val action =
                ProtocolCodec.decode(
                    FixtureFiles.bytes("valid/authorized-action.json"),
                ).toMutableMap()
            action["tool"] = "phone.notifications"
            action["parameters"] =
                mapOf("limit" to 20, "source_packages" to listOf("com.example.chat"))
            action["action_digest"] = CanonicalJson.actionDigest(action)
            return ProtocolCodec.encode(action)
        }

        fun artifact(): ArtifactReference =
            ArtifactReference(
                "notification-artifact",
                "application/vnd.hermes.notifications+json",
                128,
                "sha256:" + "c".repeat(64),
                "D3",
                "REDACTED",
                "EPHEMERAL",
                1_788_150_300_000L,
            )
    }

    private class UnavailablePhoneStateSource : PhoneStateSource {
        override fun availability(maximumAgeMillis: Long): PhoneStateUnavailableReason =
            PhoneStateUnavailableReason.SERVICE_DISCONNECTED

        override fun current(maximumAgeMillis: Long): PhoneStateSnapshot =
            throw PhoneStateUnavailableException(PhoneStateUnavailableReason.SERVICE_DISCONNECTED)
    }
}

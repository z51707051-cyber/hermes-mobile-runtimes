package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.observer.AttachmentShareException
import ai.hermes.mobile.runtime.bridge.observer.AttachmentShareFailureReason
import ai.hermes.mobile.runtime.bridge.observer.AttachmentShareLaunch
import ai.hermes.mobile.runtime.bridge.observer.AttachmentShareSource
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSnapshot
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSource
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableException
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableReason
import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.protocol.FixtureFiles
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AttachmentShareProviderTest {
    @Test
    fun `route opens only the exact package and does not claim delivery`() {
        val source = FakeAttachmentShareSource()
        val result = ProtocolCodec.decode(router(source).routeAuthorized(action()))

        assertEquals("SUCCEEDED", result["execution_status"])
        assertEquals(listOf("com.tencent.mm"), source.packages)
        assertEquals(emptyList<Any>(), result["artifacts"])
        assertEquals(
            listOf("ATTACHMENT_URI_WITHHELD", "ATTACHMENT_METADATA_MINIMIZED"),
            result["redactions"],
        )
        val verification = result["verification"] as Map<*, *>
        assertEquals("NOT_APPLICABLE", verification["status"])
        assertFalse(verification["explanation"].toString().contains("delivered"))
    }

    @Test
    fun `missing picker grant fails before provider dispatch`() {
        val source = FakeAttachmentShareSource(AttachmentShareFailureReason.ALREADY_USED)

        val rejected =
            assertThrows(AndroidRouteRejectedException::class.java) {
                router(source).routeAuthorized(action())
            }

        assertEquals("CAPABILITY_UNAVAILABLE", rejected.code)
        assertEquals(emptyList<String>(), source.packages)
    }

    private class FakeAttachmentShareSource(
        private val unavailable: AttachmentShareFailureReason? = null,
    ) : AttachmentShareSource {
        val packages = mutableListOf<String>()

        override fun availability(): AttachmentShareFailureReason? = unavailable

        override fun share(packageName: String): AttachmentShareLaunch {
            unavailable?.let { throw AttachmentShareException(it) }
            packages += packageName
            return AttachmentShareLaunch(packageName)
        }
    }

    private companion object {
        fun router(source: AttachmentShareSource): AndroidToolRouter {
            val phoneState = UnavailablePhoneStateSource()
            return AndroidToolRouter(
                CapabilityRegistry(
                    listOf(
                        AttachmentShareProvider(
                            source,
                            ProviderEpochClock { 1_788_150_001_000L },
                            ProviderElapsedClock { 100L },
                        ),
                    ),
                ),
                CurrentAppPolicyEnforcementPoint(
                    AndroidPolicyEnforcementPoint { PepDecision.allow() },
                    phoneState,
                    attachmentShareSource = source,
                ),
            )
        }

        fun action(): ByteArray {
            val action =
                ProtocolCodec.decode(
                    FixtureFiles.bytes("valid/authorized-action.json"),
                ).toMutableMap()
            action["tool"] = "phone.share_attachment"
            action["parameters"] = mapOf("package" to "com.tencent.mm")
            action["effective_risk"] = "L3"
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

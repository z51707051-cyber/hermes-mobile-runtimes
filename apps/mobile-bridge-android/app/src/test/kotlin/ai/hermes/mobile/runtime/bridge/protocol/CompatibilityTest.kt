package ai.hermes.mobile.runtime.bridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CompatibilityTest {
    @Test
    fun identicalBundlesNegotiateTheHighestPatch() {
        val message = ProtocolCodec.decode(FixtureFiles.bytes("valid/compatibility-offer.json"))
        val local = CompatibilityOffer.fromMessage(message + ("protocol_max" to "0.1.3"))
        val remote =
            CompatibilityOffer.fromMessage(
                message +
                    mapOf(
                        "protocol_min" to "0.1.1",
                        "protocol_max" to "0.1.2",
                    ),
            )

        val selection = CompatibilityNegotiator.negotiate(local, remote)

        assertEquals("0.1.2", selection.selectedVersion.toString())
        assertEquals(listOf("closed-schema", "strict-json"), selection.acceptedFeatures)
    }

    @Test
    fun digestMismatchAndDowngradeFailClosed() {
        val message = ProtocolCodec.decode(FixtureFiles.bytes("valid/compatibility-offer.json"))
        val local = CompatibilityOffer.fromMessage(message)
        val wrongDigest =
            CompatibilityOffer.fromMessage(message + ("schema_bundle_digest" to "sha256:${"0".repeat(64)}"))
        assertThrows(ProtocolCompatibilityException::class.java) {
            CompatibilityNegotiator.negotiate(local, wrongDigest)
        }

        val old =
            CompatibilityOffer.fromMessage(
                message + mapOf("protocol_min" to "0.0.9", "protocol_max" to "0.0.9"),
            )
        assertThrows(ProtocolCompatibilityException::class.java) {
            CompatibilityNegotiator.negotiate(local, old)
        }
    }
}

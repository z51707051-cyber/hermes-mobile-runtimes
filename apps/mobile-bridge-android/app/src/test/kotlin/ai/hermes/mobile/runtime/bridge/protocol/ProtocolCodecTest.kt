package ai.hermes.mobile.runtime.bridge.protocol

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolCodecTest {
    @Test
    fun validGoldenMessagesRoundTrip() {
        Files.list(FixtureFiles.root.resolve("valid")).use { paths ->
            paths.sorted().forEach { path ->
                val message = ProtocolCodec.decode(Files.readAllBytes(path))
                assertEquals(message, ProtocolCodec.decode(ProtocolCodec.encode(message)))
            }
        }
    }

    @Test
    fun invalidGoldenMessagesFailClosed() {
        Files.list(FixtureFiles.root.resolve("invalid")).use { paths ->
            paths.sorted().forEach { path ->
                assertThrows(ProtocolValidationException::class.java) {
                    ProtocolCodec.decode(Files.readAllBytes(path))
                }
            }
        }
    }

    @Test
    fun everyToolHasPositiveAndNegativeSharedParameters() {
        val fixtures = StrictJson.decodeObject(FixtureFiles.bytes("all-tool-parameters.json"))

        fixtures.forEach { (tool, examplesValue) ->
            @Suppress("UNCHECKED_CAST")
            val examples = examplesValue as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val valid = examples["valid"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val invalid = examples["invalid"] as Map<String, Any?>

            ProtocolCodec.validateToolParameters(tool, valid)
            assertThrows(ProtocolValidationException::class.java) {
                ProtocolCodec.validateToolParameters(tool, invalid)
            }
        }
    }

    @Test
    fun authorizedActionDigestBindsNormalizedFields() {
        val action = ProtocolCodec.decode(FixtureFiles.bytes("valid/authorized-action.json"))
        val changed = action + ("device_id" to "device-0002")

        assertThrows(ProtocolValidationException::class.java) {
            ProtocolCodec.encode(changed)
        }
    }
}

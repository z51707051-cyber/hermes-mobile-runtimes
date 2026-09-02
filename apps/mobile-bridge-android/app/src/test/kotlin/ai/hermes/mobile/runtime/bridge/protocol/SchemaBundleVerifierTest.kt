package ai.hermes.mobile.runtime.bridge.protocol

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaBundleVerifierTest {
    @Test
    fun verifiesTheNormativeSharedSchemaBundle() {
        val bundle = SchemaBundleVerifier.verify { relative ->
            Files.readAllBytes(FixtureFiles.schemaRoot.resolve(relative))
        }

        assertEquals("0.1.1", bundle.protocolVersion)
        assertEquals(20, bundle.fileDigests.size)
        assertTrue(bundle.digest.startsWith("sha256:"))
    }

    @Test
    fun rejectsAChangedSchemaBeforeUse() {
        assertThrows(ProtocolJsonException::class.java) {
            SchemaBundleVerifier.verify { relative ->
                val original = Files.readAllBytes(FixtureFiles.schemaRoot.resolve(relative))
                if (relative == "tools/phone.tap.schema.json") original + '\n'.code.toByte() else original
            }
        }
    }
}

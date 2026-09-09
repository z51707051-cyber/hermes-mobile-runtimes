package ai.hermes.mobile.runtime.bridge.artifact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactStoreTest {
    @Test
    fun encryptedStoreReturnsBoundMetadataAndExpiresContent() {
        var now = 1_788_150_000_000L
        var sequence = 0
        val store =
            EncryptedInMemoryArtifactStore(
                encryptionKey = ByteArray(32) { 1 },
                digestKey = ByteArray(32) { 2 },
                clock = ArtifactClock { now },
                ids = ArtifactIdGenerator { "artifact-${++sequence}" },
                nonceSource = { ByteArray(it) { sequence.toByte() } },
            )

        val reference = store.put(request("sensitive screen text"))

        assertEquals("artifact-1", reference.artifactId)
        assertEquals("D3", reference.sensitivity)
        assertEquals("EPHEMERAL", reference.retentionClass)
        assertEquals(21, reference.sizeBytes)
        assertTrue(reference.digest.matches(Regex("sha256:[0-9a-f]{64}")))
        assertEquals(now + 1_000, reference.expiresAtEpochMillis)
        assertEquals(0, store.purgeExpired())

        now += 1_000
        assertEquals(1, store.purgeExpired())
        assertFalse(store.delete(reference.artifactId))
    }

    @Test
    fun keyedDigestChangesAcrossAuthoritiesAndNonceReuseIsRejected() {
        val first =
            EncryptedInMemoryArtifactStore(
                encryptionKey = ByteArray(32) { 1 },
                digestKey = ByteArray(32) { 2 },
                ids = ArtifactIdGenerator { "artifact-first" },
                nonceSource = { ByteArray(it) { 3 } },
            )
        val second =
            EncryptedInMemoryArtifactStore(
                encryptionKey = ByteArray(32) { 1 },
                digestKey = ByteArray(32) { 4 },
                ids = ArtifactIdGenerator { "artifact-second" },
                nonceSource = { ByteArray(it) { 5 } },
            )

        assertNotEquals(first.put(request("same")).digest, second.put(request("same")).digest)

        var sequence = 0
        val repeatedNonce =
            EncryptedInMemoryArtifactStore(
                encryptionKey = ByteArray(32) { 6 },
                digestKey = ByteArray(32) { 7 },
                ids = ArtifactIdGenerator { "artifact-${++sequence}" },
                nonceSource = { ByteArray(it) },
            )
        repeatedNonce.put(request("first"))
        assertThrows(IllegalArgumentException::class.java) {
            repeatedNonce.put(request("second"))
        }
    }

    @Test
    fun encryptionAndDigestKeysMustBePurposeSeparated() {
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedInMemoryArtifactStore(
                encryptionKey = ByteArray(32) { 9 },
                digestKey = ByteArray(32) { 9 },
            )
        }
    }

    private fun request(content: String): ArtifactWriteRequest =
        ArtifactWriteRequest(
            mediaType = "application/vnd.hermes.ui-tree+json",
            content = content.toByteArray(),
            sensitivity = "D3",
            redactionStatus = "REDACTED",
            timeToLiveMillis = 1_000,
        )
}

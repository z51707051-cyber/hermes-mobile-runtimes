package ai.hermes.mobile.runtime.bridge.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StrictJsonTest {
    @Test
    fun rejectsDuplicateKeysInvalidUtf8AndUnsafeIntegers() {
        assertThrows(ProtocolJsonException::class.java) {
            StrictJson.decode("{\"a\":1,\"a\":2}".toByteArray())
        }
        assertThrows(ProtocolJsonException::class.java) {
            StrictJson.decode(byteArrayOf(0x22, 0xff.toByte(), 0x22))
        }
        assertThrows(ProtocolJsonException::class.java) {
            StrictJson.decode("9007199254740992".toByteArray())
        }
    }

    @Test
    fun deterministicEncodingSortsObjectKeys() {
        val value = linkedMapOf<String, Any?>("z" to listOf(true, null), "a" to mapOf("number" to 7L))

        assertEquals("{\"a\":{\"number\":7},\"z\":[true,null]}", StrictJson.encode(value).decodeToString())
    }

    @Test
    fun canonicalDigestMatchesTheSharedGoldenFixture() {
        val fixture = StrictJson.decodeObject(FixtureFiles.bytes("canonical/action-digest.json"))
        val input = fixture["input"]

        assertArrayEquals((fixture["canonical"] as String).toByteArray(), CanonicalJson.encode(input))
        assertEquals(fixture["digest"], CanonicalJson.sha256(input))
    }
}

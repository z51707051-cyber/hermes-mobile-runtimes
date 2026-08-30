package ai.hermes.mobile.runtime.bridge.security.identity

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityDescriptorTest {
    @Test
    fun deviceIdIsStableAndBoundToThePublicKey() {
        val first = generatePublicKey()
        val second = generatePublicKey()

        val firstId = DeviceIdentityDescriptor.deviceId(first)

        assertEquals(firstId, DeviceIdentityDescriptor.deviceId(first))
        assertNotEquals(firstId, DeviceIdentityDescriptor.deviceId(second))
        assertTrue(firstId.matches(Regex("hmr_[A-Za-z0-9_-]{43}")))
    }

    private fun generatePublicKey() =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair().public
        }
}

package ai.hermes.mobile.runtime.bridge.security.identity

import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

internal enum class IdentitySecurityLevel {
    SOFTWARE,
    HARDWARE_BACKED,
    TRUSTED_ENVIRONMENT,
    STRONGBOX,
    UNKNOWN,
}

internal data class DeviceIdentity(
    val deviceId: String,
    val publicKeySpki: ByteArray,
    val certificateChainDer: List<ByteArray>,
    val securityLevel: IdentitySecurityLevel,
)

internal object DeviceIdentityDescriptor {
    fun deviceId(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return "hmr_$encoded"
    }
}

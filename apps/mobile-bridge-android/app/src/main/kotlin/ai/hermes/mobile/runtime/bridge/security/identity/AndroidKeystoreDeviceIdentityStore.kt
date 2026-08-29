package ai.hermes.mobile.runtime.bridge.security.identity

import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Owns the per-install device identity key.
 *
 * The private key never leaves Android Keystore. This class deliberately does
 * not own enrollment state, broker certificates, transport sockets or policy
 * authorization; those authorities remain separate.
 */
internal class AndroidKeystoreDeviceIdentityStore(
    private val packageManager: PackageManager,
    private val alias: String = DEVICE_IDENTITY_ALIAS,
) {
    fun create(attestationChallenge: ByteArray? = null): DeviceIdentity {
        val keyStore = loadKeyStore()
        check(!keyStore.containsAlias(alias)) { "device identity already exists" }

        val strongBoxAvailable =
            packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (strongBoxAvailable) {
            try {
                generateKey(attestationChallenge, strongBoxBacked = true)
                return loadRequired()
            } catch (_: StrongBoxUnavailableException) {
                // Android explicitly permits falling back when the requested
                // algorithm is not supported by a device's StrongBox.
            }
        }

        generateKey(attestationChallenge, strongBoxBacked = false)
        return loadRequired()
    }

    fun load(): DeviceIdentity? {
        val keyStore = loadKeyStore()
        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry ?: return null
        val keyFactory = KeyFactory.getInstance(entry.privateKey.algorithm, ANDROID_KEY_STORE)
        val keyInfo = keyFactory.getKeySpec(entry.privateKey, KeyInfo::class.java)
        val certificateChain =
            keyStore.getCertificateChain(alias)
                ?.map { certificate -> certificate.encoded.copyOf() }
                .orEmpty()

        return DeviceIdentity(
            deviceId = DeviceIdentityDescriptor.deviceId(entry.certificate.publicKey),
            publicKeySpki = entry.certificate.publicKey.encoded.copyOf(),
            certificateChainDer = certificateChain,
            securityLevel = keyInfo.toIdentitySecurityLevel(),
        )
    }

    fun signProof(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "proof payload must not be empty" }
        val entry =
            loadKeyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
                ?: error("device identity is unavailable")
        return Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(entry.privateKey)
            update(payload)
            sign()
        }
    }

    fun delete() {
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    private fun loadRequired(): DeviceIdentity =
        checkNotNull(load()) { "generated device identity could not be loaded" }

    private fun generateKey(
        attestationChallenge: ByteArray?,
        strongBoxBacked: Boolean,
    ) {
        val parameters =
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            ).apply {
                setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
                setDigests(KeyProperties.DIGEST_SHA256)
                setIsStrongBoxBacked(strongBoxBacked)
                attestationChallenge?.let {
                    val allowedSize =
                        MIN_ATTESTATION_CHALLENGE_BYTES..MAX_ATTESTATION_CHALLENGE_BYTES
                    require(it.size in allowedSize) {
                        "attestation challenge must be 16..128 bytes"
                    }
                    setAttestationChallenge(it.copyOf())
                }
            }.build()

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE).run {
            initialize(parameters)
            generateKeyPair()
        }
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun KeyInfo.toIdentitySecurityLevel(): IdentitySecurityLevel =
        when (securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> IdentitySecurityLevel.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ->
                IdentitySecurityLevel.TRUSTED_ENVIRONMENT
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> IdentitySecurityLevel.SOFTWARE
            else -> IdentitySecurityLevel.UNKNOWN
        }

    private companion object {
        const val DEVICE_IDENTITY_ALIAS = "hmr.device.identity.v1"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val EC_CURVE = "secp256r1"
        const val MIN_ATTESTATION_CHALLENGE_BYTES = 16
        const val MAX_ATTESTATION_CHALLENGE_BYTES = 128
    }
}

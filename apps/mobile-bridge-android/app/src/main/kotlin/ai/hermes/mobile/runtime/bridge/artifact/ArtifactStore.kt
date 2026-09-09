package ai.hermes.mobile.runtime.bridge.artifact

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class ArtifactReference(
    val artifactId: String,
    val mediaType: String,
    val sizeBytes: Int,
    val digest: String,
    val sensitivity: String,
    val redactionStatus: String,
    val retentionClass: String,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(OPAQUE_ID.matches(artifactId)) { "artifact id is invalid" }
        require(MEDIA_TYPE.matches(mediaType)) { "artifact media type is invalid" }
        require(sizeBytes in 0..MAX_REFERENCE_BYTES) { "artifact size is invalid" }
        require(DIGEST.matches(digest)) { "artifact digest is invalid" }
        require(sensitivity in SENSITIVITIES) { "artifact sensitivity is invalid" }
        require(redactionStatus in REDACTION_STATUSES) {
            "artifact redaction status is invalid"
        }
        require(retentionClass in RETENTION_CLASSES) { "artifact retention is invalid" }
        require(expiresAtEpochMillis > 0) { "artifact expiry is invalid" }
    }

    fun protocolValue(): Map<String, Any?> =
        mapOf(
            "artifact_id" to artifactId,
            "media_type" to mediaType,
            "size_bytes" to sizeBytes,
            "digest" to digest,
            "sensitivity" to sensitivity,
            "redaction_status" to redactionStatus,
            "retention_class" to retentionClass,
            "expires_at" to Instant.ofEpochMilli(expiresAtEpochMillis).toString(),
        )

    private companion object {
        const val MAX_REFERENCE_BYTES = 67_108_864
        val OPAQUE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        val MEDIA_TYPE = Regex("[A-Za-z0-9][A-Za-z0-9.+-]*/[A-Za-z0-9][A-Za-z0-9.+-]{0,126}")
        val DIGEST = Regex("sha256:[0-9a-f]{64}")
        val SENSITIVITIES = setOf("D0", "D1", "D2", "D3", "D4")
        val REDACTION_STATUSES = setOf("NONE", "REDACTED", "WITHHELD")
        val RETENTION_CLASSES = setOf("EPHEMERAL", "TASK", "AUDIT")
    }
}

internal data class ArtifactWriteRequest(
    val mediaType: String,
    val content: ByteArray,
    val sensitivity: String,
    val redactionStatus: String,
    val retentionClass: String = "EPHEMERAL",
    val timeToLiveMillis: Long,
)

internal interface ArtifactStore {
    fun put(request: ArtifactWriteRequest): ArtifactReference

    fun delete(artifactId: String): Boolean

    fun purgeExpired(): Int
}

/** One process-local protected store shared by reviewed Android capture adapters. */
internal object RuntimeArtifactStore : ArtifactStore {
    private val delegate = EncryptedInMemoryArtifactStore()

    override fun put(request: ArtifactWriteRequest): ArtifactReference = delegate.put(request)

    override fun delete(artifactId: String): Boolean = delegate.delete(artifactId)

    override fun purgeExpired(): Int = delegate.purgeExpired()
}

internal fun interface ArtifactClock {
    fun nowMillis(): Long
}

internal fun interface ArtifactIdGenerator {
    fun next(): String
}

/**
 * Process-local encrypted storage for D3 artifacts.
 *
 * The store deliberately exposes no content read method. Retrieval must later
 * be added through a separately authorized and audited broker operation.
 */
internal class EncryptedInMemoryArtifactStore(
    encryptionKey: ByteArray = randomKey(),
    digestKey: ByteArray = randomKey(),
    private val clock: ArtifactClock = ArtifactClock { System.currentTimeMillis() },
    private val ids: ArtifactIdGenerator = ArtifactIdGenerator { "artifact:${UUID.randomUUID()}" },
    private val nonceSource: (Int) -> ByteArray = ::secureRandomBytes,
) : ArtifactStore {
    private data class EncryptedArtifact(
        val reference: ArtifactReference,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
    )

    private val encryptionKey = encryptionKey.copyOf()
    private val digestKey = digestKey.copyOf()
    private val artifacts = linkedMapOf<String, EncryptedArtifact>()
    private val usedNonces = mutableSetOf<String>()

    init {
        require(this.encryptionKey.size == KEY_BYTES) { "artifact encryption key must be 256-bit" }
        require(this.digestKey.size >= KEY_BYTES) { "artifact digest key must be at least 256-bit" }
        require(!MessageDigest.isEqual(this.encryptionKey, this.digestKey)) {
            "artifact encryption and digest keys must be different"
        }
    }

    @Synchronized
    override fun put(request: ArtifactWriteRequest): ArtifactReference {
        validate(request)
        val content = request.content.copyOf()
        purgeExpiredLocked()
        val now = clock.nowMillis()
        val artifactId = ids.next()
        require(artifactId !in artifacts) { "artifact id was reused" }
        val expiresAt = Math.addExact(now, request.timeToLiveMillis)
        val reference =
            ArtifactReference(
                artifactId = artifactId,
                mediaType = request.mediaType,
                sizeBytes = content.size,
                digest = keyedDigest(content),
                sensitivity = request.sensitivity,
                redactionStatus = request.redactionStatus,
                retentionClass = request.retentionClass,
                expiresAtEpochMillis = expiresAt,
            )
        val nonce = nonceSource(NONCE_BYTES)
        require(nonce.size == NONCE_BYTES) { "artifact nonce is invalid" }
        require(usedNonces.add(nonce.toHex())) { "artifact nonce was reused" }
        val ciphertext =
            try {
                encrypt(content, nonce, associatedData(reference))
            } finally {
                content.fill(0)
            }
        artifacts[artifactId] = EncryptedArtifact(reference, nonce.copyOf(), ciphertext)
        return reference
    }

    @Synchronized
    override fun delete(artifactId: String): Boolean = artifacts.remove(artifactId) != null

    @Synchronized
    override fun purgeExpired(): Int = purgeExpiredLocked()

    private fun purgeExpiredLocked(): Int {
        val now = clock.nowMillis()
        val expired = artifacts.values.filter { it.reference.expiresAtEpochMillis <= now }
        expired.forEach { artifacts.remove(it.reference.artifactId) }
        return expired.size
    }

    private fun validate(request: ArtifactWriteRequest) {
        require(request.content.size in 1..MAX_CONTENT_BYTES) { "artifact content size is invalid" }
        require(MEDIA_TYPE.matches(request.mediaType)) { "artifact media type is invalid" }
        require(request.sensitivity in SENSITIVITIES) { "artifact sensitivity is invalid" }
        require(request.redactionStatus in REDACTION_STATUSES) {
            "artifact redaction status is invalid"
        }
        require(request.retentionClass in RETENTION_CLASSES) { "artifact retention is invalid" }
        require(request.timeToLiveMillis in 1..MAX_TTL_MILLIS) { "artifact TTL is invalid" }
    }

    private fun keyedDigest(content: ByteArray): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(digestKey, HMAC_ALGORITHM))
        return "sha256:" + mac.doFinal(content).toHex()
    }

    private fun encrypt(
        content: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(encryptionKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(associatedData)
        return cipher.doFinal(content)
    }

    private fun associatedData(reference: ArtifactReference): ByteArray =
        listOf(
            reference.artifactId,
            reference.mediaType,
            reference.sensitivity,
            reference.redactionStatus,
            reference.retentionClass,
            reference.expiresAtEpochMillis.toString(),
        ).joinToString("\u0000").toByteArray(Charsets.UTF_8)

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
        // UI trees retain their stricter 1 MiB encoder limit. This store also
        // accepts bounded compressed screenshots without putting them on wire.
        const val MAX_CONTENT_BYTES = 16_777_216
        const val MAX_TTL_MILLIS = 86_400_000L
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        val MEDIA_TYPE = Regex("[A-Za-z0-9][A-Za-z0-9.+-]*/[A-Za-z0-9][A-Za-z0-9.+-]{0,126}")
        val SENSITIVITIES = setOf("D0", "D1", "D2", "D3", "D4")
        val REDACTION_STATUSES = setOf("NONE", "REDACTED", "WITHHELD")
        val RETENTION_CLASSES = setOf("EPHEMERAL", "TASK", "AUDIT")

        fun randomKey(): ByteArray = secureRandomBytes(KEY_BYTES)
    }
}

private fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also(SecureRandom()::nextBytes)

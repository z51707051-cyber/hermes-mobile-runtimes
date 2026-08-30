package ai.hermes.mobile.runtime.bridge.protocol

import java.security.MessageDigest

internal data class VerifiedSchemaBundle(
    val protocolVersion: String,
    val digest: String,
    val fileDigests: Map<String, String>,
)

/** Integrity verification for the normative bundle before any message is accepted. */
internal object SchemaBundleVerifier {
    private val SAFE_PATH = Regex("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*\\.json")
    private val DIGEST = Regex("sha256:[0-9a-f]{64}")

    fun verify(read: (String) -> ByteArray): VerifiedSchemaBundle {
        val manifest = StrictJson.decodeObject(read("manifest.json"))
        manifest.requireClosed(
            setOf("bundle_digest", "digest_algorithm", "files", "protocol_version"),
        )
        if (manifest["digest_algorithm"] != "SHA-256") fail("unsupported digest algorithm")
        val version = manifest["protocol_version"] as? String ?: fail("missing protocol version")
        if (version != "0.1.0") fail("unexpected protocol version")
        val expectedBundle = manifest["bundle_digest"] as? String ?: fail("missing bundle digest")
        if (!DIGEST.matches(expectedBundle)) fail("invalid bundle digest")
        val files = manifest["files"] as? List<*> ?: fail("manifest files must be an array")
        if (files.isEmpty()) fail("manifest must contain schemas")

        val entries = mutableListOf<Pair<String, String>>()
        val schemaIds = mutableSetOf<String>()
        files.forEach { rawEntry ->
            val entry = rawEntry as? Map<*, *> ?: fail("invalid manifest entry")
            if (entry.keys.any { it !is String }) fail("invalid manifest entry key")
            @Suppress("UNCHECKED_CAST")
            val typed = entry as Map<String, Any?>
            typed.requireClosed(setOf("digest", "path"))
            val path = typed["path"] as? String ?: fail("manifest path must be a string")
            val digest = typed["digest"] as? String ?: fail("manifest digest must be a string")
            if (!SAFE_PATH.matches(path) || path.split('/').contains("..")) fail("unsafe schema path")
            if (!DIGEST.matches(digest)) fail("invalid schema digest")
            if (entries.any { it.first == path }) fail("duplicate schema path")
            val rawSchema = read(path)
            if (sha256(rawSchema) != digest) fail("schema digest mismatch: $path")
            val schema = StrictJson.decodeObject(rawSchema)
            val schemaId = schema["\$id"] as? String ?: fail("schema is missing \$id: $path")
            if (!schemaId.startsWith("https://hermesmobile.dev/schemas/v0.1/")) {
                fail("invalid schema \$id: $path")
            }
            if (!schemaIds.add(schemaId)) fail("duplicate schema \$id")
            entries += path to digest
        }
        if (entries != entries.sortedBy { it.first }) fail("manifest entries must be sorted")
        val material =
            entries.joinToString(separator = "") { (path, digest) -> "$path\u0000$digest\n" }
                .toByteArray(Charsets.UTF_8)
        val actualBundle = sha256(material)
        if (actualBundle != expectedBundle) fail("schema bundle digest mismatch")
        return VerifiedSchemaBundle(version, actualBundle, entries.toMap())
    }

    private fun sha256(value: ByteArray): String =
        "sha256:" +
            MessageDigest.getInstance("SHA-256")
                .digest(value)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun Map<String, Any?>.requireClosed(required: Set<String>) {
        if (keys != required) fail("object is not closed")
    }

    private fun fail(message: String): Nothing = throw ProtocolJsonException(message)
}

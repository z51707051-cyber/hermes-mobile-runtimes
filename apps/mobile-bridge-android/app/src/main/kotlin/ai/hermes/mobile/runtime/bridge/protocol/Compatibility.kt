package ai.hermes.mobile.runtime.bridge.protocol

internal class ProtocolCompatibilityException(message: String) : IllegalArgumentException(message)

internal data class ProtocolVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<ProtocolVersion> {
    override fun compareTo(other: ProtocolVersion): Int =
        compareValuesBy(this, other, ProtocolVersion::major, ProtocolVersion::minor, ProtocolVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val VERSION = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")

        fun parse(raw: String): ProtocolVersion {
            val match = VERSION.matchEntire(raw) ?: incompatible("invalid protocol version: $raw")
            val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: incompatible("version overflow") }
            return ProtocolVersion(parts[0], parts[1], parts[2])
        }
    }
}

internal data class CompatibilityOffer(
    val protocolMin: ProtocolVersion,
    val protocolMax: ProtocolVersion,
    val schemaBundleDigest: String,
    val toolSchemaDigests: Map<String, String>,
    val features: Set<String>,
    val requiredFeatures: Set<String>,
) {
    init {
        if (protocolMin > protocolMax) incompatible("protocol range minimum exceeds maximum")
        if (protocolMin.major != protocolMax.major || protocolMin.minor != protocolMax.minor) {
            incompatible("V0.1 offers may span patch versions only")
        }
        if (!features.containsAll(requiredFeatures)) {
            incompatible("required features must also be advertised")
        }
    }

    companion object {
        fun fromMessage(message: Map<String, Any?>): CompatibilityOffer {
            if (message["message_type"] != "compatibility.offer") {
                incompatible("message is not a compatibility offer")
            }
            return CompatibilityOffer(
                protocolMin = ProtocolVersion.parse(message.string("protocol_min")),
                protocolMax = ProtocolVersion.parse(message.string("protocol_max")),
                schemaBundleDigest = message.string("schema_bundle_digest"),
                toolSchemaDigests = message.stringMap("tool_schema_digests"),
                features = message.stringSet("features"),
                requiredFeatures = message.stringSet("required_features"),
            )
        }
    }
}

internal data class CompatibilitySelection(
    val selectedVersion: ProtocolVersion,
    val schemaBundleDigest: String,
    val acceptedFeatures: List<String>,
) {
    fun toMessage(): Map<String, Any?> =
        linkedMapOf(
            "message_type" to "compatibility.selection",
            "selected_version" to selectedVersion.toString(),
            "schema_bundle_digest" to schemaBundleDigest,
            "accepted_features" to acceptedFeatures,
        )
}

internal object CompatibilityNegotiator {
    fun negotiate(
        local: CompatibilityOffer,
        remote: CompatibilityOffer,
        minimumAccepted: String = "0.1.1",
    ): CompatibilitySelection {
        val floor = maxOf(local.protocolMin, remote.protocolMin, ProtocolVersion.parse(minimumAccepted))
        val ceiling = minOf(local.protocolMax, remote.protocolMax)
        if (floor > ceiling || floor.major != ceiling.major || floor.minor != ceiling.minor) {
            incompatible("peers have no acceptable protocol version")
        }
        if (local.schemaBundleDigest != remote.schemaBundleDigest) {
            incompatible("schema bundle digest mismatch")
        }
        if (local.toolSchemaDigests != remote.toolSchemaDigests) {
            incompatible("tool schema digest mismatch")
        }
        if (!remote.features.containsAll(local.requiredFeatures)) {
            incompatible("remote peer lacks a required local feature")
        }
        if (!local.features.containsAll(remote.requiredFeatures)) {
            incompatible("local peer lacks a required remote feature")
        }
        return CompatibilitySelection(
            selectedVersion = ceiling,
            schemaBundleDigest = local.schemaBundleDigest,
            acceptedFeatures = local.features.intersect(remote.features).sorted(),
        )
    }
}

private fun Map<String, Any?>.string(name: String): String =
    this[name] as? String ?: incompatible("$name must be a string")

private fun Map<String, Any?>.stringMap(name: String): Map<String, String> {
    val value = this[name] as? Map<*, *> ?: incompatible("$name must be an object")
    return value.entries.associate { (key, item) ->
        (key as? String ?: incompatible("$name keys must be strings")) to
            (item as? String ?: incompatible("$name values must be strings"))
    }
}

private fun Map<String, Any?>.stringSet(name: String): Set<String> {
    val value = this[name] as? List<*> ?: incompatible("$name must be an array")
    val strings = value.map { it as? String ?: incompatible("$name values must be strings") }
    if (strings.toSet().size != strings.size) incompatible("$name values must be unique")
    return strings.toSet()
}

private fun incompatible(message: String): Nothing = throw ProtocolCompatibilityException(message)

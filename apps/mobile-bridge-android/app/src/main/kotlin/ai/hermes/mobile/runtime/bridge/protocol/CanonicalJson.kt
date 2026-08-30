package ai.hermes.mobile.runtime.bridge.protocol

import java.security.MessageDigest

/** Canonical JSON for the integer-only V0.1 authorization digest domain. */
internal object CanonicalJson {
    fun encode(value: Any?): ByteArray = render(value, 0).toByteArray(Charsets.UTF_8)

    fun sha256(value: Any?): String =
        "sha256:" +
            MessageDigest.getInstance("SHA-256")
                .digest(encode(value))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun actionDigest(message: Map<String, Any?>): String = sha256(actionDigestMaterial(message))

    fun actionDigestMaterial(message: Map<String, Any?>): Map<String, Any?> {
        val version = message["protocol_version"] as? String ?: fail("missing protocol version")
        val parts = version.split('.')
        if (parts.size != 3) fail("invalid protocol version")
        val fields =
            listOf(
                "request_id",
                "task_id",
                "device_id",
                "tool",
                "parameters",
                "state_precondition",
                "verification",
                "idempotency_key",
                "deadline",
                "effective_target",
                "effective_risk",
            )
        if (fields.any { it !in message }) fail("authorized action is missing digest material")
        return linkedMapOf<String, Any?>("protocol_line" to "${parts[0]}.${parts[1]}").apply {
            fields.forEach { field -> put(field, message[field]) }
        }
    }

    private fun render(
        value: Any?,
        depth: Int,
    ): String {
        if (depth > StrictJson.MAX_DEPTH) fail("canonical JSON nesting exceeds the limit")
        return when (value) {
            null -> "null"
            true -> "true"
            false -> "false"
            is Byte, is Short, is Int, is Long -> {
                val number = (value as Number).toLong()
                if (number !in -StrictJson.MAX_SAFE_INTEGER..StrictJson.MAX_SAFE_INTEGER) {
                    fail("canonical JSON integer exceeds the safe range")
                }
                number.toString()
            }
            is Float, is Double, is java.math.BigDecimal ->
                fail("security digests forbid floating-point values")
            is String -> escaped(value)
            is List<*> ->
                value.joinToString(separator = ",", prefix = "[", postfix = "]") {
                    render(it, depth + 1)
                }
            is Map<*, *> -> {
                if (value.keys.any { it !is String }) fail("canonical object keys must be strings")
                value.entries
                    .sortedWith(compareBy { it.key as String })
                    .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, item) ->
                        "${escaped(key as String)}:${render(item, depth + 1)}"
                    }
            }
            else -> fail("unsupported canonical JSON type: ${value::class.java.name}")
        }
    }

    private fun escaped(value: String): String {
        val result = StringBuilder(value.length + 2).append('"')
        value.forEachIndexed { index, character ->
            when {
                Character.isHighSurrogate(character) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                        fail("canonical JSON forbids unpaired Unicode surrogates")
                    }
                    result.append(character)
                }
                Character.isLowSurrogate(character) -> {
                    if (index == 0 || !Character.isHighSurrogate(value[index - 1])) {
                        fail("canonical JSON forbids unpaired Unicode surrogates")
                    }
                    result.append(character)
                }
                character == '"' -> result.append("\\\"")
                character == '\\' -> result.append("\\\\")
                character == '\b' -> result.append("\\b")
                character == '\t' -> result.append("\\t")
                character == '\n' -> result.append("\\n")
                character == '\u000c' -> result.append("\\f")
                character == '\r' -> result.append("\\r")
                character.code < 0x20 -> result.append("\\u%04x".format(character.code))
                else -> result.append(character)
            }
        }
        return result.append('"').toString()
    }

    private fun fail(message: String): Nothing = throw ProtocolJsonException(message)
}

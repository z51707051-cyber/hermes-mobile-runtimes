package ai.hermes.mobile.runtime.bridge.protocol

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import okio.Buffer

internal class ProtocolJsonException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal object StrictJson {
    const val MAX_DOCUMENT_BYTES = 1_048_576
    const val MAX_DEPTH = 64
    const val MAX_CONTAINER_ITEMS = 4_096
    const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    fun decode(payload: ByteArray): Any? {
        if (payload.size > MAX_DOCUMENT_BYTES) fail("JSON document exceeds the protocol byte limit")
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload))
                    .toString()
            } catch (exc: Exception) {
                throw ProtocolJsonException("protocol JSON must be valid UTF-8", exc)
            }
        val reader = JsonReader.of(Buffer().writeUtf8(text)).apply { isLenient = false }
        return try {
            val value = readValue(reader, 0)
            if (reader.peek() != JsonReader.Token.END_DOCUMENT) fail("trailing JSON data")
            value
        } catch (exc: ProtocolJsonException) {
            throw exc
        } catch (exc: Exception) {
            throw ProtocolJsonException("invalid protocol JSON", exc)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun decodeObject(payload: ByteArray): Map<String, Any?> =
        decode(payload) as? Map<String, Any?>
            ?: fail("protocol message must be a JSON object")

    fun encode(value: Any?): ByteArray {
        val buffer = Buffer()
        val writer = JsonWriter.of(buffer).apply { serializeNulls = true }
        try {
            writeValue(writer, value, 0)
            writer.close()
        } catch (exc: ProtocolJsonException) {
            throw exc
        } catch (exc: Exception) {
            throw ProtocolJsonException("value cannot be encoded as protocol JSON", exc)
        }
        val encoded = buffer.readByteArray()
        if (encoded.size > MAX_DOCUMENT_BYTES) fail("JSON document exceeds the protocol byte limit")
        return encoded
    }

    private fun readValue(
        reader: JsonReader,
        depth: Int,
    ): Any? {
        if (depth > MAX_DEPTH) fail("JSON nesting exceeds the protocol limit")
        return when (reader.peek()) {
            JsonReader.Token.BEGIN_OBJECT -> {
                reader.beginObject()
                val result = linkedMapOf<String, Any?>()
                while (reader.hasNext()) {
                    if (result.size >= MAX_CONTAINER_ITEMS) fail("JSON object exceeds the item limit")
                    val name = reader.nextName()
                    if (result.containsKey(name)) fail("duplicate JSON object key: $name")
                    result[name] = readValue(reader, depth + 1)
                }
                reader.endObject()
                result
            }
            JsonReader.Token.BEGIN_ARRAY -> {
                reader.beginArray()
                val result = mutableListOf<Any?>()
                while (reader.hasNext()) {
                    if (result.size >= MAX_CONTAINER_ITEMS) fail("JSON array exceeds the item limit")
                    result += readValue(reader, depth + 1)
                }
                reader.endArray()
                result
            }
            JsonReader.Token.STRING -> reader.nextString()
            JsonReader.Token.NUMBER -> parseNumber(reader.nextString())
            JsonReader.Token.BOOLEAN -> reader.nextBoolean()
            JsonReader.Token.NULL -> reader.nextNull<Any?>()
            else -> fail("unexpected JSON token: ${reader.peek()}")
        }
    }

    private fun parseNumber(raw: String): Number {
        if (!raw.contains('.') && !raw.contains('e', ignoreCase = true)) {
            val value = raw.toLongOrNull() ?: fail("JSON integer is outside the supported range")
            if (value !in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) {
                fail("JSON integer exceeds the interoperable safe range")
            }
            return value
        }
        return try {
            BigDecimal(raw).also {
                if (!it.toDouble().isFinite()) fail("non-finite JSON numbers are forbidden")
            }
        } catch (exc: NumberFormatException) {
            throw ProtocolJsonException("invalid JSON number", exc)
        }
    }

    private fun writeValue(
        writer: JsonWriter,
        value: Any?,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) fail("JSON nesting exceeds the protocol limit")
        when (value) {
            null -> writer.nullValue()
            is Boolean -> writer.value(value)
            is String -> writer.value(value)
            is Byte, is Short, is Int, is Long -> {
                val number = (value as Number).toLong()
                if (number !in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) {
                    fail("JSON integer exceeds the interoperable safe range")
                }
                writer.value(number)
            }
            is BigDecimal -> writer.value(value)
            is Float, is Double -> {
                val number = (value as Number).toDouble()
                if (!number.isFinite()) fail("non-finite JSON numbers are forbidden")
                writer.value(number)
            }
            is List<*> -> {
                if (value.size > MAX_CONTAINER_ITEMS) fail("JSON array exceeds the item limit")
                writer.beginArray()
                value.forEach { writeValue(writer, it, depth + 1) }
                writer.endArray()
            }
            is Map<*, *> -> {
                if (value.size > MAX_CONTAINER_ITEMS) fail("JSON object exceeds the item limit")
                if (value.keys.any { it !is String }) fail("JSON object keys must be strings")
                writer.beginObject()
                value.entries.sortedBy { it.key as String }.forEach { (key, item) ->
                    writer.name(key as String)
                    writeValue(writer, item, depth + 1)
                }
                writer.endObject()
            }
            else -> fail("unsupported protocol JSON type: ${value::class.java.name}")
        }
    }

    private fun fail(message: String): Nothing = throw ProtocolJsonException(message)
}

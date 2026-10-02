package ai.hermes.mobile.runtime.bridge.attachment

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

internal data class SelectedAttachment(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
)

/**
 * Holds one picker-granted content URI in memory until the next accepted task.
 *
 * The store never accepts filesystem paths and never requests a persistable URI
 * grant. Process death therefore drops both the capability and its metadata.
 */
internal class AttachmentSelectionStore(
    private val contentResolver: ContentResolver,
) {
    private var selected: SelectedAttachment? = null

    @Synchronized
    fun select(uri: Uri): SelectedAttachment {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) {
            "attachment must use a content URI"
        }
        val metadata = queryMetadata(uri)
        contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val size = metadata.sizeBytes ?: descriptor.statSize.takeIf { it >= 0 }
            AttachmentSelectionPolicy.requireAllowedSize(size)
            return SelectedAttachment(
                uri = uri,
                displayName = AttachmentSelectionPolicy.normalizeDisplayName(metadata.displayName),
                mimeType =
                    AttachmentSelectionPolicy.normalizeMimeType(
                        contentResolver.getType(uri),
                    ),
                sizeBytes = size,
            ).also { selected = it }
        }
        throw IllegalArgumentException("selected attachment is not readable")
    }

    @Synchronized
    fun current(): SelectedAttachment? = selected

    @Synchronized
    fun takeForTask(): SelectedAttachment? = selected.also { selected = null }

    @Synchronized
    fun clear() {
        selected = null
    }

    private fun queryMetadata(uri: Uri): RawAttachmentMetadata {
        var displayName: String? = null
        var sizeBytes: Long? = null
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { index ->
                    if (!cursor.isNull(index)) displayName = cursor.getString(index)
                }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { index ->
                    if (!cursor.isNull(index)) sizeBytes = cursor.getLong(index).takeIf { it >= 0 }
                }
            }
        }
        return RawAttachmentMetadata(displayName, sizeBytes)
    }

    private data class RawAttachmentMetadata(
        val displayName: String?,
        val sizeBytes: Long?,
    )
}

internal object AttachmentSelectionPolicy {
    private const val FALLBACK_NAME = "selected-attachment"
    private const val FALLBACK_MIME_TYPE = "application/octet-stream"
    private const val MAXIMUM_NAME_CHARS = 128
    private const val MAXIMUM_ATTACHMENT_BYTES = 128L * 1024L * 1024L
    private val MIME_TYPE = Regex("[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}")
    private val WHITESPACE = Regex("\\s+")

    fun normalizeDisplayName(value: String?): String {
        val normalized =
            value
                ?.map { character ->
                    when {
                        character.isISOControl() -> ' '
                        character == '/' || character == '\\' -> '_'
                        else -> character
                    }
                }
                ?.joinToString("")
                ?.trim()
                ?.replace(WHITESPACE, " ")
                ?.takeIf { it.isNotEmpty() }
                ?: FALLBACK_NAME
        var end = normalized.length.coerceAtMost(MAXIMUM_NAME_CHARS)
        if (
            end in 1 until normalized.length &&
            Character.isHighSurrogate(normalized[end - 1]) &&
            Character.isLowSurrogate(normalized[end])
        ) {
            end -= 1
        }
        return normalized.substring(0, end)
    }

    fun normalizeMimeType(value: String?): String {
        val normalized = value?.trim()?.lowercase(Locale.ROOT)
        return normalized?.takeIf(MIME_TYPE::matches) ?: FALLBACK_MIME_TYPE
    }

    fun requireAllowedSize(sizeBytes: Long?) {
        require(sizeBytes == null || sizeBytes in 0..MAXIMUM_ATTACHMENT_BYTES) {
            "selected attachment exceeds the task limit"
        }
    }
}

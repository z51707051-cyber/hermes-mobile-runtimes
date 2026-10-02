package ai.hermes.mobile.runtime.bridge.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AttachmentSelectionPolicyTest {
    @Test
    fun `normalizes picker metadata without creating path authority`() {
        assertEquals(
            "folder_secret photo.jpg",
            AttachmentSelectionPolicy.normalizeDisplayName(" folder/secret\nphoto.jpg "),
        )
        assertEquals(
            "selected-attachment",
            AttachmentSelectionPolicy.normalizeDisplayName("\u0000\t"),
        )
        assertEquals(
            "image/jpeg",
            AttachmentSelectionPolicy.normalizeMimeType(" IMAGE/JPEG "),
        )
        assertEquals(
            "application/octet-stream",
            AttachmentSelectionPolicy.normalizeMimeType("not-a-mime"),
        )
        assertEquals(
            "a".repeat(127),
            AttachmentSelectionPolicy.normalizeDisplayName("a".repeat(127) + "😀tail"),
        )
    }

    @Test
    fun `rejects a known attachment larger than the task bound`() {
        AttachmentSelectionPolicy.requireAllowedSize(null)
        AttachmentSelectionPolicy.requireAllowedSize(128L * 1024L * 1024L)

        assertThrows(IllegalArgumentException::class.java) {
            AttachmentSelectionPolicy.requireAllowedSize(128L * 1024L * 1024L + 1L)
        }
    }
}

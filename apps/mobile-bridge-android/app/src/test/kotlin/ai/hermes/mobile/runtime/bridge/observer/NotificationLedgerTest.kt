package ai.hermes.mobile.runtime.bridge.observer

import ai.hermes.mobile.runtime.bridge.protocol.StrictJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationLedgerTest {
    @Test
    fun identicalCallbacksAreDeduplicatedAndChangesUseOpaqueCursor() {
        var now = 1_000L
        val ledger = ledger { now++ }
        val input = notification(text = "hello")

        assertTrue(ledger.post(input))
        assertFalse(ledger.post(input))
        val snapshot = document(ledger.query(NotificationQuery(null, 20, emptySet())))
        val cursor = snapshot.getValue("cursor") as String
        assertEquals(1, (snapshot.getValue("records") as List<*>).size)

        ledger.post(input.copy(text = "updated"))
        val changes = document(ledger.query(NotificationQuery(cursor, 20, emptySet())))
        val records = changes.getValue("records") as List<*>
        assertEquals("UPDATED", (records.single() as Map<*, *>)["kind"])
        assertEquals("updated", (records.single() as Map<*, *>)["text"])
    }

    @Test
    fun sourceFiltersRemovalAndInvalidCursorFailClosed() {
        val ledger = ledger { 2_000L }
        ledger.post(notification(sourcePackage = "com.example.chat"))
        ledger.post(notification(systemKey = "second", sourcePackage = "com.example.mail"))

        val filtered =
            document(
                ledger.query(NotificationQuery(null, 20, setOf("com.example.mail"))),
            )
        assertEquals(1, (filtered.getValue("records") as List<*>).size)
        assertTrue(ledger.remove("second", "com.example.mail"))

        val exception =
            assertThrows(NotificationCaptureException::class.java) {
                ledger.query(NotificationQuery("notifications:other:0", 20, emptySet()))
            }
        assertEquals(NotificationFailureReason.CURSOR_INVALID, exception.reason)
    }

    @Test
    fun boundedActiveSetAndUnicodeTextSurfaceTruncationWithoutBrokenSurrogates() {
        val ledger = ledger { 3_000L }
        ledger.replaceActive(
            (0..100).map { index ->
                notification(systemKey = "key-$index", text = "😀".repeat(5_000))
            },
        )

        val snapshot = document(ledger.query(NotificationQuery(null, 100, emptySet())))
        assertEquals(true, snapshot["truncated"])
        val records = snapshot.getValue("records") as List<*>
        assertEquals(100, records.size)
        val text = (records.first() as Map<*, *>)["text"] as String
        assertEquals(4_096, text.codePointCount(0, text.length))
        assertFalse(text.last().isHighSurrogate())
    }

    @Test
    fun cursorExpiresWhenBoundedChangeWindowAdvances() {
        val ledger = ledger { 4_000L }
        val initial = document(ledger.query(NotificationQuery(null, 1, emptySet())))
        val cursor = initial.getValue("cursor") as String
        repeat(257) { index -> ledger.post(notification(text = "update-$index")) }

        val exception =
            assertThrows(NotificationCaptureException::class.java) {
                ledger.query(NotificationQuery(cursor, 20, emptySet()))
            }
        assertEquals(NotificationFailureReason.CURSOR_EXPIRED, exception.reason)
    }

    private fun document(batch: NotificationBatch): Map<String, Any?> =
        StrictJson.decodeObject(batch.payload)

    private fun ledger(clock: () -> Long): NotificationLedger =
        NotificationLedger(ByteArray(32) { 7 }, NotificationClock(clock), "session-1")

    private fun notification(
        systemKey: String = "key-1",
        sourcePackage: String = "com.example.chat",
        text: String = "hello",
    ): NotificationInput =
        NotificationInput(
            systemKey,
            sourcePackage,
            900L,
            "Alice",
            text,
            null,
            "msg",
            false,
            true,
        )
}

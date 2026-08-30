package ai.hermes.mobile.runtime.bridge.security.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayProtectorTest {
    private val now = 2_000_000L

    @Test
    fun acceptsStrictSequenceAndPersistsBeforeASecondProtector() {
        val store = MemoryReplayStateStore()
        val first = ReplayProtector(store)

        assertTrue(first.checkAndRecord(envelope(sequence = 1), now).accepted)

        val afterRestart = ReplayProtector(store)
        assertEquals(
            ReplayStatus.REPLAYED,
            afterRestart.checkAndRecord(envelope(sequence = 1), now).status,
        )
        assertTrue(
            afterRestart.checkAndRecord(
                envelope(sequence = 2, nonceByte = 2),
                now,
            ).accepted,
        )
    }

    @Test
    fun rejectsNonceReuseAndSequenceGaps() {
        val protector = ReplayProtector(MemoryReplayStateStore())

        assertTrue(protector.checkAndRecord(envelope(sequence = 1), now).accepted)
        assertEquals(
            ReplayStatus.REPLAYED,
            protector.checkAndRecord(envelope(sequence = 2), now).status,
        )
        assertEquals(
            ReplayStatus.SEQUENCE_GAP,
            protector.checkAndRecord(envelope(sequence = 3, nonceByte = 3), now).status,
        )
    }

    @Test
    fun rejectsExpiredFutureAndOverlongAuthorizations() {
        val protector = ReplayProtector(MemoryReplayStateStore())

        assertEquals(
            ReplayStatus.EXPIRED,
            protector.checkAndRecord(
                envelope(sequence = 1, issuedAt = now - 20_000, expiresAt = now),
                now,
            ).status,
        )
        assertEquals(
            ReplayStatus.NOT_YET_VALID,
            protector.checkAndRecord(
                envelope(
                    sequence = 1,
                    issuedAt = now + 120_001,
                    expiresAt = now + 130_000,
                    sessionExpiresAt = now + 200_000,
                ),
                now,
            ).status,
        )
        assertEquals(
            ReplayStatus.INVALID,
            protector.checkAndRecord(
                envelope(sequence = 1, issuedAt = now, expiresAt = now + 30_001),
                now,
            ).status,
        )
    }

    @Test
    fun storageFailureFailsClosed() {
        val store = MemoryReplayStateStore(failSave = true)
        val protector = ReplayProtector(store)

        val decision = protector.checkAndRecord(envelope(sequence = 1), now)

        assertEquals(ReplayStatus.STATE_UNAVAILABLE, decision.status)
        assertFalse(decision.accepted)
        assertEquals(ReplaySnapshot(), store.snapshot)
    }

    @Test
    fun corruptOrUnreadableStateFailsClosed() {
        val protector = ReplayProtector(MemoryReplayStateStore(failLoad = true))

        assertEquals(
            ReplayStatus.STATE_UNAVAILABLE,
            protector.checkAndRecord(envelope(sequence = 1), now).status,
        )
    }

    @Test
    fun timestampArithmeticCannotOverflowTheLifetimeLimit() {
        val protector = ReplayProtector(MemoryReplayStateStore())

        assertEquals(
            ReplayStatus.INVALID,
            protector.checkAndRecord(
                ReplayEnvelope(
                    sessionId = "abcdefghijklmnopqrstuv",
                    sequence = 1,
                    nonce = ByteArray(16) { 4 },
                    issuedAtEpochMs = 1,
                    expiresAtEpochMs = Long.MAX_VALUE,
                    sessionExpiresAtEpochMs = Long.MAX_VALUE,
                ),
                now,
            ).status,
        )
    }

    private fun envelope(
        sequence: Long,
        nonceByte: Byte = 1,
        issuedAt: Long = now,
        expiresAt: Long = now + 30_000,
        sessionExpiresAt: Long = now + 60_000,
    ) = ReplayEnvelope(
        sessionId = "abcdefghijklmnopqrstuv",
        sequence = sequence,
        nonce = ByteArray(16) { nonceByte },
        issuedAtEpochMs = issuedAt,
        expiresAtEpochMs = expiresAt,
        sessionExpiresAtEpochMs = sessionExpiresAt,
    )

    private class MemoryReplayStateStore(
        private val failLoad: Boolean = false,
        private val failSave: Boolean = false,
    ) : ReplayStateStore {
        var snapshot = ReplaySnapshot()

        override fun load(): ReplaySnapshot {
            if (failLoad) throw ReplayStateException("test load failure")
            return snapshot
        }

        override fun save(snapshot: ReplaySnapshot) {
            if (failSave) throw ReplayStateException("test save failure")
            this.snapshot = snapshot
        }
    }
}

package ai.hermes.mobile.runtime.bridge.observer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentAppTrackerTest {
    @Test
    fun trackerRequiresConnectedFreshWindowIdentity() {
        var elapsed = 1_000L
        val epoch = 1_788_150_000_000L
        var stateSequence = 0
        val tracker =
            CurrentAppTracker(
                elapsedClock = ElapsedRealtimeClock { elapsed },
                epochClock = EpochClock { epoch },
                stateIds = StateIdGenerator { "state-${++stateSequence}" },
            )

        assertEquals(
            CurrentAppUnavailableReason.SERVICE_DISCONNECTED,
            tracker.availability(5_000),
        )
        tracker.markConnected()
        assertEquals(
            CurrentAppUnavailableReason.NO_WINDOW_STATE,
            tracker.availability(5_000),
        )
        assertFalse(tracker.recordWindow("bad package name", "Bad Activity"))

        assertTrue(tracker.recordWindow("com.example.music", "com.example.music.PlayerActivity"))
        assertNull(tracker.availability(5_000))
        elapsed += 250

        val observation = tracker.current(5_000)
        assertEquals("state-1", observation.stateId)
        assertEquals("com.example.music", observation.packageName)
        assertEquals("com.example.music.PlayerActivity", observation.activityName)
        assertEquals(250L, observation.freshnessMillis)

        elapsed += 5_000
        val stale =
            assertThrows(CurrentAppUnavailableException::class.java) {
                tracker.current(5_000)
            }
        assertEquals(CurrentAppUnavailableReason.STALE_WINDOW_STATE, stale.reason)

        tracker.markDisconnected()
        assertEquals(
            CurrentAppUnavailableReason.SERVICE_DISCONNECTED,
            tracker.availability(5_000),
        )
    }
}

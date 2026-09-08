package ai.hermes.mobile.runtime.bridge.observer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneStateObserverTest {
    @Test
    fun trackerRequiresConnectedFreshWindowIdentity() {
        var elapsed = 1_000L
        val epoch = 1_788_150_000_000L
        var stateSequence = 0
        val tracker =
            PhoneStateObserver(
                elapsedClock = ElapsedRealtimeClock { elapsed },
                epochClock = EpochClock { epoch },
                stateIds = StateIdGenerator { "state-${++stateSequence}" },
            )

        assertEquals(
            PhoneStateUnavailableReason.SERVICE_DISCONNECTED,
            tracker.availability(5_000),
        )
        assertFalse(tracker.recordWindow("com.example.music", "com.example.music.PlayerActivity"))
        tracker.markConnected()
        assertEquals(
            PhoneStateUnavailableReason.NO_WINDOW_STATE,
            tracker.availability(5_000),
        )
        assertFalse(tracker.recordWindow("bad package name", "Bad Activity"))

        assertTrue(tracker.recordWindow("com.example.music", "com.example.music.PlayerActivity"))
        assertNull(tracker.availability(5_000))
        elapsed += 250

        val observation = tracker.current(5_000)
        assertEquals("state-1", observation.stateId)
        assertNull(observation.previousStateId)
        assertEquals("com.example.music", observation.packageName)
        assertEquals("com.example.music.PlayerActivity", observation.activityName)
        assertEquals(250L, observation.freshnessMillis)
        assertEquals(ScreenTransition.UNKNOWN, observation.transition)
        assertEquals(PhoneStateCaptureStatus.COMPLETE, observation.captureStatus)
        assertTrue(observation.captureErrors.isEmpty())
        assertEquals(ScreenFingerprintBasis.WINDOW_IDENTITY, observation.screenFingerprint.basis)
        assertTrue(observation.screenFingerprint.digest.matches(Regex("sha256:[0-9a-f]{64}")))

        val firstFingerprint = observation.screenFingerprint
        assertTrue(tracker.recordWindow("com.example.music", "com.example.music.PlayerActivity"))
        val unchanged = tracker.current(5_000)
        assertEquals("state-2", unchanged.stateId)
        assertEquals("state-1", unchanged.previousStateId)
        assertEquals(ScreenTransition.NONE, unchanged.transition)
        assertEquals(firstFingerprint, unchanged.screenFingerprint)

        assertTrue(tracker.recordWindow("com.example.maps", "com.example.maps.MapActivity"))
        val changed = tracker.current(5_000)
        assertEquals("state-3", changed.stateId)
        assertEquals("state-2", changed.previousStateId)
        assertEquals(ScreenTransition.CHANGED, changed.transition)
        assertTrue(firstFingerprint != changed.screenFingerprint)

        elapsed += 5_001
        val stale =
            assertThrows(PhoneStateUnavailableException::class.java) {
                tracker.current(5_000)
            }
        assertEquals(PhoneStateUnavailableReason.STALE_WINDOW_STATE, stale.reason)

        tracker.markDisconnected()
        assertEquals(
            PhoneStateUnavailableReason.SERVICE_DISCONNECTED,
            tracker.availability(5_000),
        )
        tracker.markConnected()
        assertEquals(
            PhoneStateUnavailableReason.NO_WINDOW_STATE,
            tracker.availability(5_000),
        )
    }

    @Test
    fun missingOrInvalidActivityProducesAnExplicitPartialState() {
        val tracker =
            PhoneStateObserver(
                elapsedClock = ElapsedRealtimeClock { 100L },
                epochClock = EpochClock { 1_788_150_000_000L },
                stateIds = StateIdGenerator { "state-partial" },
            )
        tracker.markConnected()

        assertTrue(tracker.recordWindow("com.example.music", "invalid activity"))

        val observation = tracker.current(5_000)
        assertNull(observation.activityName)
        assertEquals(PhoneStateCaptureStatus.PARTIAL, observation.captureStatus)
        assertEquals(listOf("FOREGROUND_ACTIVITY_UNAVAILABLE"), observation.captureErrors)
        assertEquals(ScreenTransition.UNKNOWN, observation.transition)
    }
}

package ai.hermes.mobile.runtime.bridge.observer

import ai.hermes.mobile.runtime.bridge.artifact.ArtifactReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneStateObserverTest {
    @Test
    fun invalidatingDependentStateKeepsServiceConnectedButRequiresANewObservation() {
        var stateSequence = 0
        val tracker =
            PhoneStateObserver(
                elapsedClock = ElapsedRealtimeClock { 100L },
                epochClock = EpochClock { 1_788_150_000_000L },
                stateIds = StateIdGenerator { "state-${++stateSequence}" },
            )
        tracker.markConnected()
        assertTrue(tracker.recordWindow("com.example.app", "com.example.app.MainActivity", 1))

        tracker.invalidateCurrent()

        assertEquals(PhoneStateUnavailableReason.NO_WINDOW_STATE, tracker.availability(1_000))
        assertTrue(tracker.recordWindow("com.example.app", "com.example.app.MainActivity", 1))
        assertNull(tracker.availability(1_000))
    }

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

    @Test
    fun uiTreeCaptureMustMatchTheCurrentWindowGeneration() {
        var stateSequence = 0
        val tracker =
            PhoneStateObserver(
                elapsedClock = ElapsedRealtimeClock { 100L },
                epochClock = EpochClock { 1_788_150_000_000L },
                stateIds = StateIdGenerator { "state-${++stateSequence}" },
            )
        tracker.markConnected()
        tracker.recordWindow(
            "com.example.music",
            "com.example.music.PlayerActivity",
            windowId = 42,
        )

        val captured =
            tracker.recordUiTree(
                packageName = "com.example.music",
                windowId = 42,
                fingerprintDigest = "sha256:" + "b".repeat(64),
                captureErrors = emptyList(),
                artifact = artifact(),
            )

        assertEquals("state-2", captured.stateId)
        assertEquals("state-1", captured.previousStateId)
        assertEquals(ScreenFingerprintBasis.UI_HIERARCHY, captured.screenFingerprint.basis)
        assertEquals(ScreenTransition.UNKNOWN, captured.transition)
        assertEquals(listOf("artifact-tree"), captured.artifacts.map { it.artifactId })

        val mismatch =
            assertThrows(PhoneStateUnavailableException::class.java) {
                tracker.recordUiTree(
                    packageName = "com.example.other",
                    windowId = 42,
                    fingerprintDigest = "sha256:" + "c".repeat(64),
                    captureErrors = emptyList(),
                    artifact = artifact(),
                )
            }
        assertEquals(PhoneStateUnavailableReason.UI_WINDOW_MISMATCH, mismatch.reason)
        assertEquals("state-2", tracker.current(5_000).stateId)
    }

    @Test
    fun screenshotCaptureIsBoundToExactStateAndUsesComparableFingerprints() {
        var stateSequence = 0
        val tracker =
            PhoneStateObserver(
                elapsedClock = ElapsedRealtimeClock { 100L },
                epochClock = EpochClock { 1_788_150_000_000L },
                stateIds = StateIdGenerator { "state-${++stateSequence}" },
            )
        tracker.markConnected()
        tracker.recordWindow("com.example.music", "com.example.music.PlayerActivity", 42)

        val first =
            tracker.recordScreenshot(
                expectedStateId = "state-1",
                fingerprintDigest = "sha256:" + "c".repeat(64),
                artifact = screenshotArtifact("artifact-shot-1", "c"),
            )
        assertEquals(ScreenFingerprintBasis.SCREENSHOT, first.screenFingerprint.basis)
        assertEquals(ScreenTransition.UNKNOWN, first.transition)

        val same =
            tracker.recordScreenshot(
                expectedStateId = "state-2",
                fingerprintDigest = "sha256:" + "c".repeat(64),
                artifact = screenshotArtifact("artifact-shot-2", "c"),
            )
        assertEquals(ScreenTransition.NONE, same.transition)

        tracker.recordWindow("com.example.maps", "com.example.maps.MapActivity", 7)
        val mismatch =
            assertThrows(PhoneStateUnavailableException::class.java) {
                tracker.recordScreenshot(
                    expectedStateId = "state-3",
                    fingerprintDigest = "sha256:" + "d".repeat(64),
                    artifact = screenshotArtifact("artifact-shot-3", "d"),
                )
            }
        assertEquals(PhoneStateUnavailableReason.SCREENSHOT_WINDOW_CHANGED, mismatch.reason)
    }

    @Test
    fun liveRootCanAnchorReadCaptureWithoutMakingStaleStateMutationSafe() {
        var elapsed = 100L
        val tracker =
            PhoneStateObserver(
                elapsedClock = ElapsedRealtimeClock { elapsed },
                epochClock = EpochClock { 1_788_150_000_000L },
                stateIds = StateIdGenerator { "state-anchor" },
            )
        tracker.markConnected()
        tracker.recordWindow("com.example.music", "com.example.music.PlayerActivity", 42)
        elapsed += 5_001

        assertEquals(
            PhoneStateUnavailableReason.STALE_WINDOW_STATE,
            tracker.availability(5_000),
        )
        assertEquals(
            "state-anchor",
            tracker.visualCaptureAnchor("com.example.music", 42).stateId,
        )
        val mismatch =
            assertThrows(PhoneStateUnavailableException::class.java) {
                tracker.visualCaptureAnchor("com.example.other", 42)
            }
        assertEquals(PhoneStateUnavailableReason.UI_WINDOW_MISMATCH, mismatch.reason)
    }

    private fun artifact(): ArtifactReference =
        ArtifactReference(
            artifactId = "artifact-tree",
            mediaType = "application/vnd.hermes.ui-tree+json",
            sizeBytes = 100,
            digest = "sha256:" + "b".repeat(64),
            sensitivity = "D3",
            redactionStatus = "NONE",
            retentionClass = "EPHEMERAL",
            expiresAtEpochMillis = 1_788_150_300_000L,
        )

    private fun screenshotArtifact(
        id: String,
        digestCharacter: String,
    ): ArtifactReference =
        ArtifactReference(
            artifactId = id,
            mediaType = "image/png",
            sizeBytes = 1_024,
            digest = "sha256:" + digestCharacter.repeat(64),
            sensitivity = "D3",
            redactionStatus = "NONE",
            retentionClass = "EPHEMERAL",
            expiresAtEpochMillis = 1_788_150_300_000L,
        )
}

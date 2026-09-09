package ai.hermes.mobile.runtime.bridge.observer

import ai.hermes.mobile.runtime.bridge.artifact.ArtifactReference
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationVerifierTest {
    @Test
    fun stateChangeRequiresComparableChangedContent() {
        val changed = NavigationVerifier.evaluate(capture(ScreenTransition.CHANGED), null)
        val same = NavigationVerifier.evaluate(capture(ScreenTransition.NONE), null)
        val unknown = NavigationVerifier.evaluate(capture(ScreenTransition.UNKNOWN), null)

        assertEquals("PASSED", changed.status)
        assertEquals("FAILED", same.status)
        assertEquals("INCONCLUSIVE", unknown.status)
    }

    @Test
    fun partialObservationAndEmptyTextCannotPass() {
        val partial =
            NavigationVerifier.evaluate(
                capture(ScreenTransition.CHANGED, PhoneStateCaptureStatus.PARTIAL, listOf("Sent")),
                NavigationVerificationRequest("TEXT_PRESENT", "Sent"),
            )
        val empty =
            NavigationVerifier.evaluate(
                capture(ScreenTransition.CHANGED, visibleText = listOf("Sent")),
                NavigationVerificationRequest("TEXT_PRESENT", ""),
            )

        assertEquals("INCONCLUSIVE", partial.status)
        assertEquals("INCONCLUSIVE", empty.status)
    }

    @Test
    fun protectedTextConditionsAreEvaluatedWithoutReturningText() {
        val present =
            NavigationVerifier.evaluate(
                capture(ScreenTransition.CHANGED, visibleText = listOf("Message sent")),
                NavigationVerificationRequest("TEXT_PRESENT", "sent"),
            )
        val absent =
            NavigationVerifier.evaluate(
                capture(ScreenTransition.CHANGED, visibleText = listOf("Inbox")),
                NavigationVerificationRequest("TEXT_ABSENT", "Sending"),
            )

        assertEquals("PASSED", present.status)
        assertEquals("PASSED", absent.status)
    }

    private fun capture(
        transition: ScreenTransition,
        status: PhoneStateCaptureStatus = PhoneStateCaptureStatus.COMPLETE,
        visibleText: List<String> = emptyList(),
    ): SemanticUiCapture =
        SemanticUiCapture(
            state =
                PhoneStateSnapshot(
                    stateId = "state-after",
                    previousStateId = "state-before",
                    packageName = "com.example.app",
                    activityName = "com.example.app.MainActivity",
                    screenFingerprint =
                        ScreenFingerprint(
                            ScreenFingerprintBasis.UI_HIERARCHY,
                            "sha256:" + "a".repeat(64),
                        ),
                    captureStatus = status,
                    captureErrors = if (status == PhoneStateCaptureStatus.COMPLETE) emptyList() else listOf("TEXT_LIMIT_REACHED"),
                    transition = transition,
                    capturedAtEpochMillis = 1_788_150_000_000L,
                    freshnessMillis = 10,
                ),
            artifact = artifact(),
            redactions = emptyList(),
            visibleText = visibleText,
        )

    private fun artifact() =
        ArtifactReference(
            artifactId = "artifact-after",
            mediaType = "application/vnd.hermes.ui-tree+json",
            sizeBytes = 100,
            digest = "sha256:" + "a".repeat(64),
            sensitivity = "D3",
            redactionStatus = "NONE",
            retentionClass = "EPHEMERAL",
            expiresAtEpochMillis = 1_788_150_300_000L,
        )
}

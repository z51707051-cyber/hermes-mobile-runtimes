package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.artifact.ArtifactReference
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateCaptureStatus
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSnapshot
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSource
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableReason
import ai.hermes.mobile.runtime.bridge.observer.ScreenFingerprint
import ai.hermes.mobile.runtime.bridge.observer.ScreenFingerprintBasis
import ai.hermes.mobile.runtime.bridge.observer.ScreenTransition
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotCapture
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotCaptureException
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotCaptureSource
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotCrop
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotFailureReason
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotFormat
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotSpec
import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.protocol.FixtureFiles
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScreenshotProviderTest {
    @Test
    fun protectedRouteReturnsOnlyScreenshotArtifactReference() {
        val source = FakeScreenshotSource(capture = capture())
        val router = router(source)

        val result = ProtocolCodec.decode(router.routeAuthorized(screenshotAction()))

        assertEquals("SUCCEEDED", result["execution_status"])
        assertEquals(
            ScreenshotSpec(
                displayId = 0,
                format = ScreenshotFormat.WEBP,
                crop = ScreenshotCrop(10, 20, 300, 400),
            ),
            source.lastSpec,
        )
        val artifacts = result["artifacts"] as List<*>
        val reference = artifacts.single() as Map<*, *>
        assertEquals("artifact-screenshot", reference["artifact_id"])
        assertEquals("image/webp", reference["media_type"])
        assertEquals("D3", reference["sensitivity"])
        val beforeState = result["before_state"] as Map<*, *>
        val afterState = result["after_state"] as Map<*, *>
        assertEquals("state-window", beforeState["state_id"])
        assertEquals("state-screenshot", afterState["state_id"])
        assertEquals(
            "SCREENSHOT",
            (afterState["screen_fingerprint"] as Map<*, *>)["basis"],
        )
        assertEquals(1, source.captureCalls)
    }

    @Test
    fun devicePepRejectsUnavailableScreenshotSourceBeforeProvider() {
        val source =
            FakeScreenshotSource(
                capture = capture(),
                unavailable = PhoneStateUnavailableReason.SERVICE_DISCONNECTED,
            )
        val router = router(source)

        val rejected =
            assertThrows(AndroidRouteRejectedException::class.java) {
                router.routeAuthorized(screenshotAction())
            }

        assertEquals("CAPABILITY_UNAVAILABLE", rejected.code)
        assertEquals(0, source.captureCalls)
    }

    @Test
    fun secureWindowFailureIsTypedAndNotRecoverable() {
        val source =
            FakeScreenshotSource(
                capture = capture(),
                failure = ScreenshotFailureReason.SECURE_WINDOW,
            )
        val result = ProtocolCodec.decode(router(source).routeAuthorized(screenshotAction()))

        assertEquals("FAILED", result["execution_status"])
        assertEquals(false, result["recoverable"])
        val error = result["error"] as Map<*, *>
        assertEquals("PERMISSION_DENIED", error["code"])
        assertEquals("NEVER", error["retry_disposition"])
        assertEquals("SECURE_WINDOW", (error["details"] as Map<*, *>)["reason"])
    }

    @Test
    fun secondaryDisplayIsRejectedBeforeAndroidCapture() {
        val source = FakeScreenshotSource(capture = capture())
        val action = ProtocolCodec.decode(screenshotAction()).toMutableMap()
        action["parameters"] = mapOf("display_id" to 1, "format" to "PNG")
        action["action_digest"] = CanonicalJson.actionDigest(action)

        val result =
            ProtocolCodec.decode(
                router(source).routeAuthorized(ProtocolCodec.encode(action)),
            )

        assertEquals("FAILED", result["execution_status"])
        assertEquals(0, source.captureCalls)
        val error = result["error"] as Map<*, *>
        assertEquals("ACTION_REJECTED", error["code"])
        assertEquals("INVALID_DISPLAY", (error["details"] as Map<*, *>)["reason"])
    }

    private class FakeScreenshotSource(
        private val capture: ScreenshotCapture,
        private val unavailable: PhoneStateUnavailableReason? = null,
        private val failure: ScreenshotFailureReason? = null,
    ) : ScreenshotCaptureSource {
        var captureCalls = 0
        var lastSpec: ScreenshotSpec? = null

        override fun availability(): PhoneStateUnavailableReason? = unavailable

        override fun capture(spec: ScreenshotSpec): ScreenshotCapture {
            captureCalls += 1
            lastSpec = spec
            failure?.let { throw ScreenshotCaptureException(it) }
            return capture
        }
    }

    private class FakePhoneStateSource : PhoneStateSource {
        override fun availability(maximumAgeMillis: Long): PhoneStateUnavailableReason? = null

        override fun current(maximumAgeMillis: Long): PhoneStateSnapshot = state("state-window")
    }

    private companion object {
        fun router(source: ScreenshotCaptureSource): AndroidToolRouter =
            AndroidToolRouter(
                capabilities =
                    CapabilityRegistry(
                        listOf(
                            ScreenshotProvider(
                                source = source,
                                epochClock = ProviderEpochClock { 1_788_150_001_000L },
                                elapsedClock = ProviderElapsedClock { 100L },
                            ),
                        ),
                    ),
                policyEnforcementPoint =
                    CurrentAppPolicyEnforcementPoint(
                        authorizationDelegate =
                            AndroidPolicyEnforcementPoint { PepDecision.allow() },
                        source = FakePhoneStateSource(),
                        screenshotSource = source,
                    ),
            )

        fun screenshotAction(): ByteArray {
            val action =
                ProtocolCodec.decode(
                    FixtureFiles.bytes("valid/authorized-action.json"),
                ).toMutableMap()
            action["tool"] = "phone.screenshot"
            action["parameters"] =
                mapOf(
                    "display_id" to 0,
                    "format" to "WEBP",
                    "crop" to
                        mapOf(
                            "x_px" to 10,
                            "y_px" to 20,
                            "width_px" to 300,
                            "height_px" to 400,
                        ),
                )
            action["action_digest"] = CanonicalJson.actionDigest(action)
            return ProtocolCodec.encode(action)
        }

        fun capture(): ScreenshotCapture =
            ScreenshotCapture(
                beforeState = state("state-window"),
                state = state("state-screenshot", screenshot = true),
                artifact = artifact(),
            )

        fun state(
            id: String,
            screenshot: Boolean = false,
        ): PhoneStateSnapshot =
            PhoneStateSnapshot(
                stateId = id,
                previousStateId = if (screenshot) "state-window" else null,
                packageName = "com.example.music",
                activityName = "com.example.music.PlayerActivity",
                screenFingerprint =
                    ScreenFingerprint(
                        basis =
                            if (screenshot) {
                                ScreenFingerprintBasis.SCREENSHOT
                            } else {
                                ScreenFingerprintBasis.WINDOW_IDENTITY
                            },
                        digest =
                            "sha256:" +
                                (if (screenshot) "c".repeat(64) else "a".repeat(64)),
                    ),
                captureStatus = PhoneStateCaptureStatus.COMPLETE,
                captureErrors = emptyList(),
                transition = ScreenTransition.UNKNOWN,
                capturedAtEpochMillis = 1_788_150_000_000L,
                freshnessMillis = 25,
                artifacts = if (screenshot) listOf(artifact()) else emptyList(),
            )

        fun artifact(): ArtifactReference =
            ArtifactReference(
                artifactId = "artifact-screenshot",
                mediaType = "image/webp",
                sizeBytes = 1_024,
                digest = "sha256:" + "c".repeat(64),
                sensitivity = "D3",
                redactionStatus = "NONE",
                retentionClass = "EPHEMERAL",
                expiresAtEpochMillis = 1_788_150_300_000L,
            )
    }
}

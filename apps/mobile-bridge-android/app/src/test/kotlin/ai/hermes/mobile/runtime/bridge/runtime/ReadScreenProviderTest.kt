package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.artifact.ArtifactReference
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateCaptureStatus
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSnapshot
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSource
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableException
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableReason
import ai.hermes.mobile.runtime.bridge.observer.ScreenFingerprint
import ai.hermes.mobile.runtime.bridge.observer.ScreenFingerprintBasis
import ai.hermes.mobile.runtime.bridge.observer.ScreenTransition
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiCapture
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiCaptureSource
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiLimits
import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.protocol.FixtureFiles
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReadScreenProviderTest {
    @Test
    fun protectedRouteReturnsOnlyStateAndProtectedArtifactReference() {
        val source = FakeSemanticSource(capture = capture())
        val phoneState = FakePhoneStateSource()
        val router = router(source, phoneState)

        val result = ProtocolCodec.decode(router.routeAuthorized(readScreenAction()))

        assertEquals("SUCCEEDED", result["execution_status"])
        assertEquals(SemanticUiLimits(25, 500), source.lastLimits)
        val artifacts = result["artifacts"] as List<*>
        val artifact = artifacts.single() as Map<*, *>
        assertEquals("artifact-tree", artifact["artifact_id"])
        assertEquals("D3", artifact["sensitivity"])
        assertEquals("EPHEMERAL", artifact["retention_class"])
        val state = result["after_state"] as Map<*, *>
        assertEquals(
            "UI_HIERARCHY",
            (state["screen_fingerprint"] as Map<*, *>)["basis"],
        )
        assertEquals(listOf("PASSWORD_CONTENT_WITHHELD"), result["redactions"])
        assertEquals(1, source.captureCalls)
    }

    @Test
    fun devicePepRechecksBothWindowStateAndUiServiceBeforeProvider() {
        val source =
            FakeSemanticSource(
                capture = capture(),
                unavailable = PhoneStateUnavailableReason.SERVICE_DISCONNECTED,
            )
        val router = router(source, FakePhoneStateSource())

        val rejected =
            assertThrows(AndroidRouteRejectedException::class.java) {
                router.routeAuthorized(readScreenAction())
            }

        assertEquals("CAPABILITY_UNAVAILABLE", rejected.code)
        assertEquals(0, source.captureCalls)
    }

    @Test
    fun captureRaceReturnsSchemaValidRecoverableFailure() {
        val source =
            FakeSemanticSource(
                capture = capture(),
                failure = PhoneStateUnavailableReason.UI_WINDOW_MISMATCH,
            )
        val router = router(source, FakePhoneStateSource())

        val result = ProtocolCodec.decode(router.routeAuthorized(readScreenAction()))

        assertEquals("FAILED", result["execution_status"])
        assertEquals(true, result["recoverable"])
        val error = result["error"] as Map<*, *>
        assertEquals("CAPABILITY_UNAVAILABLE", error["code"])
        assertEquals(
            "UI_WINDOW_MISMATCH",
            (error["details"] as Map<*, *>)["reason"],
        )
    }

    @Test
    fun liveCaptureCanRenewAStaleIdentityGeneration() {
        val source = FakeSemanticSource(capture = capture())
        val router =
            router(
                source,
                FakePhoneStateSource(PhoneStateUnavailableReason.STALE_WINDOW_STATE),
            )

        val result = ProtocolCodec.decode(router.routeAuthorized(readScreenAction()))

        assertEquals("SUCCEEDED", result["execution_status"])
        assertEquals(1, source.captureCalls)
    }

    private class FakeSemanticSource(
        private val capture: SemanticUiCapture,
        private val unavailable: PhoneStateUnavailableReason? = null,
        private val failure: PhoneStateUnavailableReason? = null,
    ) : SemanticUiCaptureSource {
        var captureCalls = 0
        var lastLimits: SemanticUiLimits? = null

        override fun availability(): PhoneStateUnavailableReason? = unavailable

        override fun capture(limits: SemanticUiLimits): SemanticUiCapture {
            captureCalls += 1
            lastLimits = limits
            failure?.let { throw PhoneStateUnavailableException(it) }
            return capture
        }
    }

    private class FakePhoneStateSource(
        private val unavailable: PhoneStateUnavailableReason? = null,
    ) : PhoneStateSource {
        override fun availability(maximumAgeMillis: Long): PhoneStateUnavailableReason? = unavailable

        override fun current(maximumAgeMillis: Long): PhoneStateSnapshot = observation()
    }

    private companion object {
        fun router(
            source: SemanticUiCaptureSource,
            phoneState: PhoneStateSource,
        ): AndroidToolRouter =
            AndroidToolRouter(
                capabilities =
                    CapabilityRegistry(
                        listOf(
                            ReadScreenProvider(
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
                        source = phoneState,
                        semanticUiSource = source,
                    ),
            )

        fun readScreenAction(): ByteArray {
            val action =
                ProtocolCodec.decode(
                    FixtureFiles.bytes("valid/authorized-action.json"),
                ).toMutableMap()
            action["tool"] = "phone.read_screen"
            action["parameters"] =
                mapOf(
                    "scope" to "ACTIVE_WINDOW",
                    "max_nodes" to 25,
                    "max_text_chars" to 500,
                )
            action["action_digest"] = CanonicalJson.actionDigest(action)
            return ProtocolCodec.encode(action)
        }

        fun capture(): SemanticUiCapture =
            SemanticUiCapture(
                state = observation(),
                artifact = artifact(),
                redactions = listOf("PASSWORD_CONTENT_WITHHELD"),
            )

        fun observation(): PhoneStateSnapshot =
            PhoneStateSnapshot(
                stateId = "state-tree",
                previousStateId = "state-window",
                packageName = "com.example.music",
                activityName = "com.example.music.PlayerActivity",
                screenFingerprint =
                    ScreenFingerprint(
                        basis = ScreenFingerprintBasis.UI_HIERARCHY,
                        digest = "sha256:" + "b".repeat(64),
                    ),
                captureStatus = PhoneStateCaptureStatus.COMPLETE,
                captureErrors = emptyList(),
                transition = ScreenTransition.UNKNOWN,
                capturedAtEpochMillis = 1_788_150_000_000L,
                freshnessMillis = 25,
                artifacts = listOf(artifact()),
            )

        fun artifact(): ArtifactReference =
            ArtifactReference(
                artifactId = "artifact-tree",
                mediaType = "application/vnd.hermes.ui-tree+json",
                sizeBytes = 512,
                digest = "sha256:" + "b".repeat(64),
                sensitivity = "D3",
                redactionStatus = "REDACTED",
                retentionClass = "EPHEMERAL",
                expiresAtEpochMillis = 1_788_150_300_000L,
            )
    }
}

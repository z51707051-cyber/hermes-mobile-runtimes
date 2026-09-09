package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.observer.PhoneStateCaptureStatus
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSnapshot
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSource
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableException
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableReason
import ai.hermes.mobile.runtime.bridge.observer.ScreenFingerprint
import ai.hermes.mobile.runtime.bridge.observer.ScreenFingerprintBasis
import ai.hermes.mobile.runtime.bridge.observer.ScreenTransition
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiLimits
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiProbe
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiProbeSource
import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.protocol.FixtureFiles
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitProviderTest {
    @Test
    fun timerWaitCompletesAtRequestedBoundWithoutSemanticCapture() {
        val time = FakeTime(epochStart())
        val source = FakeProbeSource(listOf(probe("a")))
        val result = route(parameters = mapOf("timeout_ms" to 250), time = time, source = source)

        assertEquals("SUCCEEDED", result["execution_status"])
        assertEquals(250L, (result["duration"] as Number).toLong())
        assertEquals(0, source.probeCalls)
        assertEquals("NOT_APPLICABLE", verification(result)["status"])
        assertNull(result["error"])
    }

    @Test
    fun stateChangedUsesComparableSemanticFingerprints() {
        val time = FakeTime(epochStart())
        val source = FakeProbeSource(listOf(probe("a"), probe("b")))
        val result =
            route(
                parameters =
                    mapOf(
                        "timeout_ms" to 1_000,
                        "condition" to mapOf("kind" to "STATE_CHANGED"),
                    ),
                time = time,
                source = source,
            )

        assertEquals("SUCCEEDED", result["execution_status"])
        assertEquals("PASSED", verification(result)["status"])
        assertEquals(2, source.probeCalls)
        assertEquals(100L, (result["duration"] as Number).toLong())
        assertEquals(emptyList<Any>(), result["artifacts"])
    }

    @Test
    fun foregroundAndTextConditionsCanPassImmediately() {
        val foreground =
            route(
                parameters =
                    mapOf(
                        "timeout_ms" to 1_000,
                        "condition" to
                            mapOf("kind" to "FOREGROUND_APP_IS", "expected" to "com.example.music"),
                    ),
                time = FakeTime(epochStart()),
                source = FakeProbeSource(listOf(probe("a"))),
            )
        val text =
            route(
                parameters =
                    mapOf(
                        "timeout_ms" to 1_000,
                        "condition" to mapOf("kind" to "TEXT_PRESENT", "expected" to "Ready"),
                    ),
                time = FakeTime(epochStart()),
                source = FakeProbeSource(listOf(probe("a", visibleText = listOf("Player Ready")))),
            )

        assertEquals("SUCCEEDED", foreground["execution_status"])
        assertEquals("SUCCEEDED", text["execution_status"])
        assertEquals(0L, (foreground["duration"] as Number).toLong())
        assertEquals(0L, (text["duration"] as Number).toLong())
    }

    @Test
    fun partialEvidenceCannotSatisfyConditionAndTimeoutDoesNotRetryAction() {
        val time = FakeTime(epochStart())
        val partial = probe("a", status = PhoneStateCaptureStatus.PARTIAL, visibleText = listOf("Ready"))
        val source = FakeProbeSource(listOf(partial))
        val result =
            route(
                parameters =
                    mapOf(
                        "timeout_ms" to 200,
                        "condition" to mapOf("kind" to "TEXT_PRESENT", "expected" to "Ready"),
                    ),
                time = time,
                source = source,
            )

        assertEquals("TIMED_OUT", result["execution_status"])
        assertEquals(true, result["recoverable"])
        assertEquals("ACTION_TIMEOUT", error(result)["code"])
        assertEquals("FAILED", verification(result)["status"])
        assertTrue(source.probeCalls in 2..3)
    }

    @Test
    fun actionDeadlineTruncatesTimerAndReturnsTypedTimeout() {
        val time = FakeTime(epochStart())
        val result =
            route(
                parameters = mapOf("timeout_ms" to 1_000),
                time = time,
                source = FakeProbeSource(listOf(probe("a"))),
                deadlineMillis = time.epochMillis + 150,
            )

        assertEquals("TIMED_OUT", result["execution_status"])
        assertEquals(150L, (result["duration"] as Number).toLong())
        assertEquals(false, result["recoverable"])
        assertEquals("ACTION_DEADLINE", (error(result)["details"] as Map<*, *>)["reason"])
    }

    @Test
    fun cancellationStopsWithinOnePollAndNeverReportsSuccess() {
        val time = FakeTime(epochStart())
        val cancellation = FakeCancellation()
        val source = FakeProbeSource(listOf(probe("a")))
        val result =
            route(
                parameters =
                    mapOf(
                        "timeout_ms" to 5_000,
                        "condition" to mapOf("kind" to "STATE_CHANGED"),
                    ),
                time = time,
                source = source,
                cancellation = cancellation,
                afterSleep = { cancellation.cancelled = true },
            )

        assertEquals("CANCELLED", result["execution_status"])
        assertEquals(false, result["recoverable"])
        assertTrue((result["duration"] as Number).toLong() <= 100L)
        assertEquals(1, source.probeCalls)
        assertEquals(1, cancellation.finishCalls)
    }

    @Test
    fun observationRevocationRaceReturnsSchemaValidFailure() {
        val time = FakeTime(epochStart())
        val source =
            FakeProbeSource(
                probes = listOf(probe("a")),
                failure = PhoneStateUnavailableReason.SERVICE_DISCONNECTED,
            )
        val result =
            route(
                parameters =
                    mapOf(
                        "timeout_ms" to 500,
                        "condition" to mapOf("kind" to "STATE_CHANGED"),
                    ),
                time = time,
                source = source,
            )

        assertEquals("FAILED", result["execution_status"])
        assertEquals("CAPABILITY_UNAVAILABLE", error(result)["code"])
        assertEquals("INCONCLUSIVE", verification(result)["status"])
    }

    @Test
    fun pepRequiresSemanticAuthorityOnlyForConditionalWait() {
        val phoneState = FakePhoneStateSource(probe("a").state)
        val unavailable =
            FakeProbeSource(
                probes = listOf(probe("a")),
                unavailable = PhoneStateUnavailableReason.SERVICE_DISCONNECTED,
            )
        val pep =
            CurrentAppPolicyEnforcementPoint(
                authorizationDelegate = AndroidPolicyEnforcementPoint { PepDecision.allow() },
                source = phoneState,
                semanticUiProbeSource = unavailable,
            )

        assertEquals(PepDisposition.ALLOW, pep.evaluate(action(mapOf("timeout_ms" to 10))).disposition)
        assertEquals(
            "CAPABILITY_UNAVAILABLE",
            pep.evaluate(
                    action(
                        mapOf(
                            "timeout_ms" to 10,
                            "condition" to mapOf("kind" to "STATE_CHANGED"),
                        ),
                    ),
                ).errorCode,
        )
    }

    @Test
    fun duplicateActiveRequestFailsClosed() {
        val time = FakeTime(epochStart())
        val cancellation = FakeCancellation(beginResult = false)
        val result =
            route(
                parameters = mapOf("timeout_ms" to 10),
                time = time,
                source = FakeProbeSource(listOf(probe("a"))),
                cancellation = cancellation,
            )

        assertEquals("FAILED", result["execution_status"])
        assertEquals("DUPLICATE_ACTIVE_WAIT", (error(result)["details"] as Map<*, *>)["reason"])
        assertEquals(0, cancellation.finishCalls)
    }

    @Test
    fun routerStillRejectsInvalidWaitParametersBeforeProvider() {
        val invalid = ProtocolCodec.decode(FixtureFiles.bytes("valid/authorized-action.json")).toMutableMap()
        invalid["tool"] = "phone.wait"
        invalid["parameters"] = mapOf("timeout_ms" to 30_001)
        invalid["action_digest"] = CanonicalJson.actionDigest(invalid)

        assertThrows(Exception::class.java) { ProtocolCodec.encode(invalid) }
    }

    private fun route(
        parameters: Map<String, Any?>,
        time: FakeTime,
        source: FakeProbeSource,
        cancellation: WaitCancellationSource = FakeCancellation(),
        deadlineMillis: Long? = null,
        afterSleep: () -> Unit = {},
    ): Map<String, Any?> {
        val provider =
            WaitProvider(
                phoneStateSource = FakePhoneStateSource(probe("a").state),
                semanticUiSource = source,
                cancellationSource = cancellation,
                epochClock = ProviderEpochClock { time.epochMillis },
                elapsedClock = ProviderElapsedClock { time.elapsedMillis },
                sleeper =
                    WaitSleeper { millis ->
                        time.advance(millis)
                        afterSleep()
                    },
            )
        val router = AndroidToolRouter(CapabilityRegistry(listOf(provider))) { PepDecision.allow() }
        return ProtocolCodec.decode(
            router.routeAuthorized(ProtocolCodec.encode(actionMap(parameters, deadlineMillis))),
        )
    }

    private class FakeTime(
        var epochMillis: Long,
        var elapsedMillis: Long = 0,
    ) {
        fun advance(millis: Long) {
            epochMillis += millis
            elapsedMillis += millis
        }
    }

    private class FakeCancellation(
        private val beginResult: Boolean = true,
    ) : WaitCancellationSource {
        var cancelled = false
        var finishCalls = 0

        override fun begin(requestId: String): Boolean = beginResult

        override fun isCancelled(requestId: String): Boolean = cancelled

        override fun finish(requestId: String) {
            finishCalls += 1
        }
    }

    private class FakeProbeSource(
        private val probes: List<SemanticUiProbe>,
        private val unavailable: PhoneStateUnavailableReason? = null,
        private val failure: PhoneStateUnavailableReason? = null,
    ) : SemanticUiProbeSource {
        var probeCalls = 0

        override fun availability(): PhoneStateUnavailableReason? = unavailable

        override fun probe(limits: SemanticUiLimits): SemanticUiProbe {
            probeCalls += 1
            failure?.let { throw PhoneStateUnavailableException(it) }
            return probes[(probeCalls - 1).coerceAtMost(probes.lastIndex)]
        }
    }

    private class FakePhoneStateSource(
        private val state: PhoneStateSnapshot,
    ) : PhoneStateSource {
        override fun availability(maximumAgeMillis: Long): PhoneStateUnavailableReason? = null

        override fun current(maximumAgeMillis: Long): PhoneStateSnapshot = state
    }

    private companion object {
        fun epochStart(): Long = Instant.parse("2026-08-30T10:00:10Z").toEpochMilli()

        fun action(parameters: Map<String, Any?>): AuthorizedAction = AuthorizedAction(actionMap(parameters))

        fun actionMap(
            parameters: Map<String, Any?>,
            deadlineMillis: Long? = null,
        ): Map<String, Any?> {
            val action =
                ProtocolCodec.decode(FixtureFiles.bytes("valid/authorized-action.json")).toMutableMap()
            action["tool"] = "phone.wait"
            action["parameters"] = parameters
            action["effective_risk"] = "L0"
            action["effective_target"] = null
            deadlineMillis?.let {
                val deadline = Instant.ofEpochMilli(it).toString()
                action["deadline"] = deadline
                action["expires_at"] = deadline
            }
            action["action_digest"] = CanonicalJson.actionDigest(action)
            return action
        }

        fun probe(
            digestCharacter: String,
            status: PhoneStateCaptureStatus = PhoneStateCaptureStatus.COMPLETE,
            visibleText: List<String> = emptyList(),
        ): SemanticUiProbe =
            SemanticUiProbe(
                state =
                    PhoneStateSnapshot(
                        stateId = "state-$digestCharacter",
                        previousStateId = "state-window",
                        packageName = "com.example.music",
                        activityName = "com.example.music.PlayerActivity",
                        screenFingerprint =
                            ScreenFingerprint(
                                ScreenFingerprintBasis.UI_HIERARCHY,
                                "sha256:" + digestCharacter.repeat(64),
                            ),
                        captureStatus = status,
                        captureErrors =
                            if (status == PhoneStateCaptureStatus.COMPLETE) {
                                emptyList()
                            } else {
                                listOf("NODE_LIMIT_REACHED")
                            },
                        transition = ScreenTransition.NONE,
                        capturedAtEpochMillis = epochStart(),
                        freshnessMillis = 0,
                    ),
                visibleText = visibleText,
                redactions = emptyList(),
            )

        @Suppress("UNCHECKED_CAST")
        fun verification(result: Map<String, Any?>): Map<String, Any?> =
            result.getValue("verification") as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        fun error(result: Map<String, Any?>): Map<String, Any?> =
            result.getValue("error") as Map<String, Any?>
    }
}

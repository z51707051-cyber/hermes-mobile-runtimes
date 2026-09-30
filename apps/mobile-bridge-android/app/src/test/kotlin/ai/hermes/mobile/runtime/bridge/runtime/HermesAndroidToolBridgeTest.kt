package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import ai.hermes.mobile.runtime.bridge.protocol.StrictJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HermesAndroidToolBridgeTest {
    @Test
    fun agentCallIsAuthorizedRoutedAndReturnedAsStructuredResult() {
        val fixture = fixture()

        val result =
            StrictJson.decodeObject(
                fixture.bridge.execute("phone.wait", "{\"timeout_ms\":1}")
                    .toByteArray(),
            )

        assertEquals("SUCCEEDED", result["execution_status"])
        assertEquals("phone.wait", result["tool"])
        assertEquals(1, fixture.provider.calls)
        assertNotNull(fixture.provider.lastAction)
        assertEquals(
            PepDisposition.DENY,
            fixture.authorization.evaluate(requireNotNull(fixture.provider.lastAction)).disposition,
        )
    }

    @Test
    fun unknownOrMalformedToolNeverReachesProvider() {
        val fixture = fixture()

        val unknown = decode(fixture.bridge.execute("phone.raw_shell", "{}"))
        val malformed = decode(fixture.bridge.execute("phone.wait", "{\"timeout_ms\":0}"))

        assertEquals("ACTION_REJECTED", errorCode(unknown))
        assertEquals("ACTION_REJECTED", errorCode(malformed))
        assertEquals(0, fixture.provider.calls)
    }

    @Test
    fun closingTaskScopeRevokesFurtherAgentActions() {
        val fixture = fixture()
        fixture.bridge.close()

        val result = decode(fixture.bridge.execute("phone.wait", "{\"timeout_ms\":1}"))

        assertEquals("ACTION_REJECTED", errorCode(result))
        assertEquals(0, fixture.provider.calls)
    }

    private fun fixture(): Fixture {
        val clock = AgentBridgeEpochClock { 1_700_000_000_000L }
        var nonce = 0
        val authorization =
            AgentTaskAuthorizationSession(
                taskId = "task-test",
                deviceId = "device-test",
                sessionId = "session_abcdefghijklmnop",
                clock = clock,
                nonceSource =
                    AgentBridgeNonceSource {
                        ByteArray(16).also { it[it.lastIndex] = (++nonce).toByte() }
                    },
            )
        val provider = RecordingProvider()
        val router =
            AndroidToolRouter(
                CapabilityRegistry(listOf(provider)),
                authorization,
            )
        return Fixture(
            authorization,
            provider,
            HermesAndroidToolBridge(
                authorization = authorization,
                router = router,
                clock = clock,
            ),
        )
    }

    private class RecordingProvider : CapabilityProvider {
        override val descriptor = CapabilityDescriptor("phone.wait", "test.agent.bridge")
        var calls = 0
        var lastAction: AuthorizedAction? = null

        override fun execute(action: AuthorizedAction): ByteArray {
            calls += 1
            lastAction = action
            val result =
                RESULT_BINDING_FIELDS.associateWith { action.message.getValue(it) } +
                    mapOf(
                        "message_type" to "tool.execution_result",
                        "execution_status" to "SUCCEEDED",
                        "before_state" to null,
                        "after_state" to null,
                        "duration" to 0,
                        "error" to null,
                        "recoverable" to false,
                        "timestamp" to "2023-11-14T22:13:20Z",
                        "parameter_digest" to CanonicalJson.sha256(action.parameters),
                        "permission_decision_id" to action.policyDecisionId,
                        "verification" to
                            mapOf(
                                "status" to "NOT_APPLICABLE",
                                "observed_state_ids" to emptyList<String>(),
                                "evaluator" to "test.agent.bridge",
                                "explanation" to "test result",
                            ),
                        "artifacts" to emptyList<Any>(),
                        "redactions" to emptyList<String>(),
                    )
            return ProtocolCodec.encode(result)
        }
    }

    private data class Fixture(
        val authorization: AgentTaskAuthorizationSession,
        val provider: RecordingProvider,
        val bridge: HermesAndroidToolBridge,
    )

    private fun decode(value: String): Map<String, Any?> =
        StrictJson.decodeObject(value.toByteArray())

    @Suppress("UNCHECKED_CAST")
    private fun errorCode(result: Map<String, Any?>): String =
        (result.getValue("error") as Map<String, Any?>).getValue("code") as String

    private companion object {
        val RESULT_BINDING_FIELDS =
            setOf(
                "protocol_version",
                "request_id",
                "task_id",
                "span_id",
                "device_id",
                "tool",
                "parameters",
                "attempt",
                "idempotency_key",
            )
    }
}

package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.observer.CurrentAppObservation
import ai.hermes.mobile.runtime.bridge.observer.CurrentAppSource
import ai.hermes.mobile.runtime.bridge.observer.CurrentAppTracker
import ai.hermes.mobile.runtime.bridge.observer.CurrentAppUnavailableException
import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import java.time.Instant

internal fun interface ProviderEpochClock {
    fun nowMillis(): Long
}

internal fun interface ProviderElapsedClock {
    fun nowMillis(): Long
}

/** First real Android provider; it emits only the V0.1 foreground-package state. */
internal class CurrentAppProvider(
    private val source: CurrentAppSource,
    private val maximumAgeMillis: Long = CurrentAppTracker.DEFAULT_MAXIMUM_AGE_MILLIS,
    private val epochClock: ProviderEpochClock = ProviderEpochClock { System.currentTimeMillis() },
    private val elapsedClock: ProviderElapsedClock = ProviderElapsedClock { System.nanoTime() / 1_000_000 },
) : CapabilityProvider {
    override val descriptor = CapabilityDescriptor(TOOL, PROVIDER_ID)

    override fun execute(action: AuthorizedAction): ByteArray {
        val startedAt = elapsedClock.nowMillis()
        return try {
            val observation = source.current(maximumAgeMillis)
            ProtocolCodec.encode(success(action, observation, durationSince(startedAt)))
        } catch (exc: CurrentAppUnavailableException) {
            ProtocolCodec.encode(unavailable(action, exc, durationSince(startedAt)))
        }
    }

    private fun success(
        action: AuthorizedAction,
        observation: CurrentAppObservation,
        durationMillis: Long,
    ): Map<String, Any?> {
        val state =
            mapOf(
                "state_id" to observation.stateId,
                "captured_at" to timestamp(observation.capturedAtEpochMillis),
                "freshness_ms" to observation.freshnessMillis,
                "device_id" to action.deviceId,
                "foreground_package" to observation.packageName,
                "transition" to "NONE",
            )
        return baseResult(action, durationMillis) +
            mapOf(
                "execution_status" to "SUCCEEDED",
                "before_state" to state,
                "after_state" to state,
                "error" to null,
                "recoverable" to false,
                "verification" to
                    mapOf(
                        "status" to "NOT_APPLICABLE",
                        "observed_state_ids" to listOf(observation.stateId),
                        "evaluator" to PROVIDER_ID,
                        "explanation" to "read-only current app observation",
                    ),
            )
    }

    private fun unavailable(
        action: AuthorizedAction,
        exception: CurrentAppUnavailableException,
        durationMillis: Long,
    ): Map<String, Any?> =
        baseResult(action, durationMillis) +
            mapOf(
                "execution_status" to "FAILED",
                "before_state" to null,
                "after_state" to null,
                "error" to
                    mapOf(
                        "code" to "CAPABILITY_UNAVAILABLE",
                        "category" to "OBSERVATION",
                        "owner" to "OBSERVER",
                        "message" to "current app observation is unavailable",
                        "retry_disposition" to "REOBSERVE",
                        "details" to mapOf("reason" to exception.reason.name),
                    ),
                "recoverable" to true,
                "verification" to
                    mapOf(
                        "status" to "INCONCLUSIVE",
                        "observed_state_ids" to emptyList<String>(),
                        "evaluator" to PROVIDER_ID,
                        "explanation" to "no fresh current app observation",
                    ),
            )

    private fun baseResult(
        action: AuthorizedAction,
        durationMillis: Long,
    ): Map<String, Any?> =
        RESULT_BINDING_FIELDS.associateWith { field -> action.message.getValue(field) } +
            mapOf(
                "message_type" to "tool.execution_result",
                "duration" to durationMillis,
                "timestamp" to timestamp(epochClock.nowMillis()),
                "parameter_digest" to CanonicalJson.sha256(action.parameters),
                "permission_decision_id" to action.policyDecisionId,
                "artifacts" to emptyList<Any>(),
                "redactions" to emptyList<String>(),
            )

    private fun durationSince(startedAt: Long): Long =
        (elapsedClock.nowMillis() - startedAt).coerceIn(0, MAXIMUM_DURATION_MILLIS)

    private fun timestamp(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    private companion object {
        const val TOOL = "phone.current_app"
        const val PROVIDER_ID = "android.accessibility.current_app.v1"
        const val MAXIMUM_DURATION_MILLIS = 86_400_000L
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

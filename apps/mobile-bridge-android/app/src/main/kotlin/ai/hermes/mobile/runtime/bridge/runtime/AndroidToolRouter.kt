package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolValidationException

internal class AndroidRouteRejectedException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class AuthorizedAction internal constructor(
    internal val message: Map<String, Any?>,
) {
    val tool: String
        get() = message.getValue("tool") as String
    val requestId: String
        get() = message.getValue("request_id") as String
    val deviceId: String
        get() = message.getValue("device_id") as String
    val policyDecisionId: String
        get() = message.getValue("policy_decision_id") as String
    val effectiveRisk: String
        get() = message.getValue("effective_risk") as String

    @Suppress("UNCHECKED_CAST")
    val parameters: Map<String, Any?>
        get() = message.getValue("parameters") as Map<String, Any?>
}

/**
 * Sole Android capability dispatch path.
 *
 * It accepts only a codec-validated AuthorizedAction, invokes the PEP before
 * resolving a provider and validates the provider result before returning it.
 * HMR-105 registers one read-only provider through a separate composition
 * root; this class still exposes no transport listener or raw command path.
 */
internal class AndroidToolRouter(
    private val capabilities: CapabilityRegistry,
    private val policyEnforcementPoint: AndroidPolicyEnforcementPoint =
        DenyAllPolicyEnforcementPoint,
) {
    fun routeAuthorized(payload: ByteArray): ByteArray {
        val message = decode(payload)
        if (message["message_type"] != "action.authorized") {
            reject("ACTION_REJECTED", "Android Tool Router accepts only AuthorizedAction")
        }
        val action = AuthorizedAction(message)
        val definition = capabilities.definition(action.tool)
        if (riskValue(action.effectiveRisk) < riskValue(definition.minimumRisk)) {
            reject("ACTION_MISMATCH", "effective risk is below the capability baseline")
        }

        val pepDecision =
            try {
                policyEnforcementPoint.evaluate(action)
            } catch (exc: Exception) {
                throw AndroidRouteRejectedException(
                    "AUTHORIZATION_INVALID",
                    "Android PEP evaluation failed",
                    exc,
                )
            }
        if (pepDecision.disposition != PepDisposition.ALLOW) {
            reject(pepDecision.errorCode ?: "PERMISSION_DENIED", "Android PEP denied action")
        }

        val provider = capabilities.requireProvider(action.tool)
        val resultPayload =
            try {
                provider.execute(action)
            } catch (exc: AndroidRouteRejectedException) {
                throw exc
            } catch (exc: Exception) {
                throw AndroidRouteRejectedException(
                    "ACTION_REJECTED",
                    "capability provider failed",
                    exc,
                )
            }
        val result = decode(resultPayload)
        if (result["message_type"] != "tool.execution_result") {
            reject("ACTION_REJECTED", "capability returned wrong message type")
        }
        validateResultBinding(action, result)
        return ProtocolCodec.encode(result)
    }

    private fun decode(payload: ByteArray): Map<String, Any?> =
        try {
            ProtocolCodec.decode(payload)
        } catch (exc: ProtocolValidationException) {
            throw AndroidRouteRejectedException(
                "PROTOCOL_INCOMPATIBLE",
                "invalid protocol message",
                exc,
            )
        }

    private fun validateResultBinding(
        action: AuthorizedAction,
        result: Map<String, Any?>,
    ) {
        RESULT_BINDING_FIELDS.forEach { field ->
            if (action.message[field] != result[field]) {
                reject("ACTION_MISMATCH", "execution result changed request field: $field")
            }
        }
        if (result["parameter_digest"] != CanonicalJson.sha256(action.parameters)) {
            reject("ACTION_MISMATCH", "parameter digest mismatch")
        }
        if (result["permission_decision_id"] != action.policyDecisionId) {
            reject("ACTION_MISMATCH", "permission decision mismatch")
        }
    }

    private fun riskValue(risk: String): Int =
        risk.removePrefix("L").toIntOrNull()
            ?: reject("ACTION_MISMATCH", "invalid effective risk")

    private fun reject(
        code: String,
        message: String,
    ): Nothing = throw AndroidRouteRejectedException(code, message)

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

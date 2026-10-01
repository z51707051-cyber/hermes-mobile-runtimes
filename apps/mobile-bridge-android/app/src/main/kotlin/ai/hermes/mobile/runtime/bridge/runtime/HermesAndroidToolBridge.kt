package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.attachment.AndroidAttachmentShareSource
import ai.hermes.mobile.runtime.bridge.attachment.SelectedAttachment
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateObserver
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSource
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateStore
import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import ai.hermes.mobile.runtime.bridge.protocol.StrictJson
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal fun interface AgentBridgeEpochClock {
    fun nowMillis(): Long
}

internal fun interface AgentBridgeNonceSource {
    fun next(): ByteArray
}

/**
 * One revocable authorization scope created by the Android UI for one user task.
 *
 * The Agent never receives the signing key or an AuthorizedAction. It can only
 * request one of the closed canonical tools through [HermesAndroidToolBridge].
 */
internal class AgentTaskAuthorizationSession(
    val taskId: String = "task-${UUID.randomUUID()}",
    val deviceId: String = "device-${UUID.randomUUID()}",
    val sessionId: String = randomOpaqueId("session"),
    private val keyPair: KeyPair = generateSigningKey(),
    private val clock: AgentBridgeEpochClock = AgentBridgeEpochClock { System.currentTimeMillis() },
    private val nonceSource: AgentBridgeNonceSource =
        AgentBridgeNonceSource { ByteArray(16).also(java.security.SecureRandom()::nextBytes) },
    private val lifetimeMillis: Long = DEFAULT_SESSION_LIFETIME_MILLIS,
) : AndroidPolicyEnforcementPoint {
    private val startedAtMillis = clock.nowMillis()
    private val sessionExpiresAtMillis = Math.addExact(startedAtMillis, lifetimeMillis)
    private val nextSequence = AtomicLong()
    private var acceptedSequence = 0L
    private val acceptedNonces = mutableSetOf<String>()
    private var active = true

    init {
        require(taskId.matches(OPAQUE_ID)) { "invalid task id" }
        require(deviceId.matches(OPAQUE_ID)) { "invalid device id" }
        require(sessionId.matches(SESSION_ID)) { "invalid session id" }
        require(lifetimeMillis in 1..MAXIMUM_SESSION_LIFETIME_MILLIS) {
            "invalid task authorization lifetime"
        }
    }

    @Synchronized
    fun authorize(request: Map<String, Any?>): Map<String, Any?> {
        check(active) { "task authorization is revoked" }
        val nowMillis = clock.nowMillis()
        check(nowMillis in startedAtMillis until sessionExpiresAtMillis) {
            "task authorization is expired"
        }
        val sequence = nextSequence.incrementAndGet()
        val issuedAt = Instant.ofEpochMilli(nowMillis)
        val expiresAt =
            Instant.ofEpochMilli(
                minOf(
                    Math.addExact(nowMillis, ACTION_AUTHORIZATION_LIFETIME_MILLIS),
                    sessionExpiresAtMillis,
                ),
            )
        val action =
            LinkedHashMap(request).apply {
                put("message_type", "action.authorized")
                put("effective_target", effectiveTarget(request))
                put("effective_risk", authorizedRisk(request.getValue("tool") as String))
                put("policy_decision_id", "decision-${UUID.randomUUID()}")
                put("authorization_algorithm", "ES256")
                put("authorization_key_id", AUTHORIZATION_KEY_ID)
                put("broker_id", BROKER_ID)
                put("session_id", sessionId)
                put("sequence", sequence)
                put("nonce", base64Url(nonceSource.next()))
                put("issued_at", issuedAt.toString())
                put("expires_at", expiresAt.toString())
            }
        action["action_digest"] = CanonicalJson.actionDigest(action)
        action["execution_authorization"] = sign(action)
        return action
    }

    @Synchronized
    override fun evaluate(action: AuthorizedAction): PepDecision {
        if (!active) return PepDecision.deny("AUTHORIZATION_INVALID")
        val nowMillis = clock.nowMillis()
        if (nowMillis >= sessionExpiresAtMillis || nowMillis >= action.authorizationExpiresAt.toEpochMilli()) {
            return PepDecision.deny("AUTHORIZATION_EXPIRED")
        }
        if (
            action.message["task_id"] != taskId ||
            action.deviceId != deviceId ||
            action.message["session_id"] != sessionId ||
            action.message["authorization_key_id"] != AUTHORIZATION_KEY_ID ||
            action.message["broker_id"] != BROKER_ID
        ) {
            return PepDecision.deny("AUTHORIZATION_INVALID")
        }
        if (riskValue(action.effectiveRisk) >= 4) {
            return PepDecision.deny("PERMISSION_DENIED")
        }
        if (!verify(action.message)) {
            return PepDecision.deny("AUTHORIZATION_INVALID")
        }
        val sequence = (action.message["sequence"] as? Number)?.toLong()
            ?: return PepDecision.deny("AUTHORIZATION_INVALID")
        val nonce = action.message["nonce"] as? String
            ?: return PepDecision.deny("AUTHORIZATION_INVALID")
        if (sequence != acceptedSequence + 1 || nonce in acceptedNonces) {
            return PepDecision.deny("REPLAY_DETECTED")
        }
        if (acceptedNonces.size >= MAXIMUM_ACTIONS_PER_SESSION) {
            return PepDecision.deny("AUTHORIZATION_INVALID")
        }
        acceptedSequence = sequence
        acceptedNonces += nonce
        return PepDecision.allow()
    }

    @Synchronized
    fun revoke() {
        active = false
    }

    private fun sign(action: Map<String, Any?>): String =
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(keyPair.private)
            update(signatureMaterial(action))
            base64Url(sign())
        }

    private fun verify(action: Map<String, Any?>): Boolean =
        try {
            val encoded = action["execution_authorization"] as? String ?: return false
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(keyPair.public)
                update(signatureMaterial(action))
                verify(Base64.getUrlDecoder().decode(encoded))
            }
        } catch (_: Exception) {
            false
        }

    private fun signatureMaterial(action: Map<String, Any?>): ByteArray =
        CanonicalJson.encode(action.filterKeys { it != "execution_authorization" })

    private fun authorizedRisk(tool: String): String =
        if (tool in READ_ONLY_TOOLS) "L0" else "L3"

    private fun effectiveTarget(request: Map<String, Any?>): Any? {
        @Suppress("UNCHECKED_CAST")
        val parameters = request["parameters"] as Map<String, Any?>
        return when (request["tool"]) {
            "phone.tap", "phone.long_press", "phone.type" -> parameters["target"]
            else -> null
        }
    }

    private fun riskValue(risk: String): Int = risk.removePrefix("L").toIntOrNull() ?: 6

    private companion object {
        const val ACTION_AUTHORIZATION_LIFETIME_MILLIS = 10_000L
        const val DEFAULT_SESSION_LIFETIME_MILLIS = 30 * 60 * 1_000L
        const val MAXIMUM_SESSION_LIFETIME_MILLIS = 24 * 60 * 60 * 1_000L
        const val MAXIMUM_ACTIONS_PER_SESSION = 1_024
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val AUTHORIZATION_KEY_ID = "android-in-process-task-key-v1"
        const val BROKER_ID = "android-in-process-task-broker-v1"
        val OPAQUE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        val SESSION_ID = Regex("[A-Za-z0-9_-]{22,128}")
        val READ_ONLY_TOOLS =
            setOf(
                "phone.read_screen",
                "phone.screenshot",
                "phone.wait",
                "phone.notifications",
                "phone.current_app",
                "phone.device_state",
            )

        fun generateSigningKey(): KeyPair =
            KeyPairGenerator.getInstance("EC").run {
                initialize(ECGenParameterSpec("secp256r1"))
                generateKeyPair()
            }

        fun randomOpaqueId(prefix: String): String =
            "$prefix-${UUID.randomUUID().toString().replace("-", "")}".take(128)

        fun base64Url(value: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }
}

/**
 * Narrow object passed from Android to the embedded Hermes Python runtime.
 * Its only callable surface is one canonical tool plus one JSON parameter object.
 */
class HermesAndroidToolBridge internal constructor(
    private val authorization: AgentTaskAuthorizationSession,
    private val router: AndroidToolRouter,
    private val phoneStateSource: PhoneStateSource = PhoneStateStore,
    private val clock: AgentBridgeEpochClock = AgentBridgeEpochClock { System.currentTimeMillis() },
) : AutoCloseable {
    @Synchronized
    fun execute(
        canonicalTool: String,
        parametersJson: String,
    ): String {
        return try {
            val parameters = StrictJson.decodeObject(parametersJson.toByteArray(Charsets.UTF_8))
            ProtocolCodec.validateToolParameters(canonicalTool, parameters)
            val request = request(canonicalTool, parameters)
            val action = authorization.authorize(request)
            router.routeAuthorized(ProtocolCodec.encode(action)).toString(Charsets.UTF_8)
        } catch (exc: AndroidRouteRejectedException) {
            rejection(canonicalTool, exc.code)
        } catch (_: Exception) {
            rejection(canonicalTool, "ACTION_REJECTED")
        }
    }

    override fun close() {
        authorization.revoke()
    }

    private fun request(
        canonicalTool: String,
        parameters: Map<String, Any?>,
    ): Map<String, Any?> {
        val now = Instant.ofEpochMilli(clock.nowMillis())
        val requestId = "request-${UUID.randomUUID()}"
        return linkedMapOf(
            "message_type" to "tool.execution_request",
            "protocol_version" to "0.1.1",
            "request_id" to requestId,
            "task_id" to authorization.taskId,
            "span_id" to "span-${UUID.randomUUID()}",
            "device_id" to authorization.deviceId,
            "tool" to canonicalTool,
            "parameters" to parameters,
            "state_precondition" to statePrecondition(canonicalTool, parameters),
            "verification" to null,
            "idempotency_key" to "idempotency-${UUID.randomUUID()}",
            "attempt" to 1,
            "requested_at" to now.toString(),
            "deadline" to now.plusSeconds(15).toString(),
        )
    }

    private fun statePrecondition(
        tool: String,
        parameters: Map<String, Any?>,
    ): Map<String, Any?>? {
        if (tool !in STATE_BOUND_TOOLS) return null
        val stateId =
            when (tool) {
                "phone.tap", "phone.long_press", "phone.type" ->
                    (parameters["target"] as? Map<*, *>)?.get("state_id") as? String
                "phone.swipe" ->
                    (parameters["start"] as? Map<*, *>)?.get("state_id") as? String
                else -> null
            } ?: throw IllegalArgumentException("state-bound tool requires an explicit target")
        val current =
            try {
                phoneStateSource.current(PhoneStateObserver.DEFAULT_MAXIMUM_AGE_MILLIS)
            } catch (_: Exception) {
                null
            }
        return buildMap {
            put("state_id", stateId)
            put("maximum_age_ms", PhoneStateObserver.DEFAULT_MAXIMUM_AGE_MILLIS)
            if (current?.stateId == stateId) put("foreground_package", current.packageName)
        }
    }

    private fun rejection(
        tool: String,
        code: String,
    ): String =
        StrictJson.encode(
            mapOf(
                "execution_status" to if (code == "PERMISSION_DENIED") "DENIED" else "FAILED",
                "tool" to tool,
                "error" to
                    mapOf(
                        "code" to code,
                        "message" to
                            if (code == "PERMISSION_DENIED") {
                                "Android policy requires direct user confirmation"
                            } else {
                                "Android phone action was rejected"
                            },
                    ),
            ),
        ).toString(Charsets.UTF_8)

    companion object {
        /** Called only by the Android UI when the user starts a concrete task. */
        @JvmStatic
        internal fun createForUserTask(
            selectedAttachment: SelectedAttachment? = null,
        ): HermesAndroidToolBridge {
            val authorization = AgentTaskAuthorizationSession()
            val attachmentShareSource = selectedAttachment?.let(::AndroidAttachmentShareSource)
            return HermesAndroidToolBridge(
                authorization = authorization,
                router = BridgeRuntime.router(authorization, attachmentShareSource),
            )
        }

        private val STATE_BOUND_TOOLS =
            setOf("phone.tap", "phone.long_press", "phone.type", "phone.swipe")
    }
}

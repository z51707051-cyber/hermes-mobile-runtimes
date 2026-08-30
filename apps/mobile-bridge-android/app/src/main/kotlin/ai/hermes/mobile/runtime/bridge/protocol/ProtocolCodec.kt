package ai.hermes.mobile.runtime.bridge.protocol

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

internal class ProtocolValidationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Closed V0.1 wire codec. It deliberately has no capability registration or
 * dispatch dependency; validation must complete before a later PEP can act.
 */
internal object ProtocolCodec {
    private val canonicalTools =
        setOf(
            "phone.read_screen",
            "phone.screenshot",
            "phone.tap",
            "phone.long_press",
            "phone.type",
            "phone.swipe",
            "phone.back",
            "phone.home",
            "phone.open_app",
            "phone.wait",
            "phone.notifications",
            "phone.current_app",
            "phone.device_state",
        )
    private val stateBoundTools = setOf("phone.tap", "phone.long_press", "phone.type", "phone.swipe")
    private val opaqueId = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    private val digest = Regex("sha256:[0-9a-f]{64}")
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

    fun decode(payload: ByteArray): Map<String, Any?> {
        val message =
            try {
                StrictJson.decodeObject(payload)
            } catch (exc: ProtocolJsonException) {
                throw ProtocolValidationException(exc.message ?: "invalid protocol JSON", exc)
            }
        validate(message)
        return message
    }

    fun encode(message: Map<String, Any?>): ByteArray {
        validate(message)
        return StrictJson.encode(message)
    }

    fun validate(message: Map<String, Any?>) {
        when (message.string("message_type")) {
            "compatibility.offer" -> validateOffer(message)
            "compatibility.selection" -> validateSelection(message)
            "tool.execution_request" -> validateRequest(message, authorized = false)
            "action.authorized" -> validateRequest(message, authorized = true)
            "tool.execution_result" -> validateResult(message)
            "mobile.event" -> validateEvent(message)
            else -> invalid("unknown protocol message_type")
        }
    }

    fun validateToolParameters(
        tool: String,
        parameters: Map<String, Any?>,
    ) {
        if (tool !in canonicalTools) invalid("unknown canonical tool: $tool")
        when (tool) {
            "phone.back", "phone.home", "phone.current_app" -> parameters.closed()
            "phone.read_screen" -> {
                parameters.closed(optional = setOf("scope", "max_nodes", "max_text_chars"))
                parameters.optionalString("scope")?.let { if (it != "ACTIVE_WINDOW") invalid("invalid scope") }
                parameters.optionalLong("max_nodes")?.requireRange(1, 500, "max_nodes")
                parameters.optionalLong("max_text_chars")?.requireRange(1, 20_000, "max_text_chars")
            }
            "phone.screenshot" -> {
                parameters.closed(optional = setOf("display_id", "format", "crop"))
                parameters.optionalLong("display_id")?.requireRange(0, 7, "display_id")
                parameters.optionalString("format")?.requireEnum(setOf("PNG", "WEBP"), "format")
                parameters.optionalObject("crop")?.let { crop ->
                    crop.closed(required = setOf("x_px", "y_px", "width_px", "height_px"))
                    crop.long("x_px").requireRange(0, 32_767, "x_px")
                    crop.long("y_px").requireRange(0, 32_767, "y_px")
                    crop.long("width_px").requireRange(1, 32_768, "width_px")
                    crop.long("height_px").requireRange(1, 32_768, "height_px")
                }
            }
            "phone.tap" -> {
                parameters.closed(required = setOf("target"))
                validateTarget(parameters.objectValue("target"), allowCoordinates = true)
            }
            "phone.long_press" -> {
                parameters.closed(required = setOf("target", "duration_ms"))
                validateTarget(parameters.objectValue("target"), allowCoordinates = true)
                parameters.long("duration_ms").requireRange(300, 5_000, "duration_ms")
            }
            "phone.type" -> {
                parameters.closed(required = setOf("text", "mode"), optional = setOf("target"))
                parameters.string("text").requireLength(1, 10_000, "text")
                parameters.string("mode").requireEnum(setOf("APPEND", "REPLACE"), "mode")
                parameters.optionalObject("target")?.let { validateTarget(it, allowCoordinates = false) }
            }
            "phone.swipe" -> {
                parameters.closed(required = setOf("start", "end", "duration_ms"))
                validatePoint(parameters.objectValue("start"))
                validatePoint(parameters.objectValue("end"))
                parameters.long("duration_ms").requireRange(100, 5_000, "duration_ms")
            }
            "phone.open_app" -> {
                parameters.closed(required = setOf("package"))
                validatePackage(parameters.string("package"))
            }
            "phone.wait" -> {
                parameters.closed(required = setOf("timeout_ms"), optional = setOf("condition"))
                parameters.long("timeout_ms").requireRange(1, 30_000, "timeout_ms")
                parameters.optionalObject("condition")?.let { condition ->
                    condition.closed(required = setOf("kind"), optional = setOf("expected"))
                    val kind = condition.string("kind")
                    kind.requireEnum(setOf("STATE_CHANGED", "FOREGROUND_APP_IS", "TEXT_PRESENT"), "kind")
                    if (kind != "STATE_CHANGED") condition.string("expected").requireLength(0, 4_096, "expected")
                    condition.optionalString("expected")?.requireLength(0, 4_096, "expected")
                }
            }
            "phone.notifications" -> {
                parameters.closed(optional = setOf("cursor", "limit", "source_packages"))
                parameters.optionalString("cursor")?.let(::validateOpaqueId)
                parameters.optionalLong("limit")?.requireRange(1, 100, "limit")
                parameters.optionalList("source_packages")?.let { packages ->
                    if (packages.size > 20) invalid("too many source_packages")
                    val strings = packages.map { it as? String ?: invalid("source_packages must contain strings") }
                    if (strings.toSet().size != strings.size) invalid("source_packages must be unique")
                    strings.forEach(::validatePackage)
                }
            }
            "phone.device_state" -> {
                parameters.closed(optional = setOf("fields"))
                parameters.optionalList("fields")?.let { fields ->
                    if (fields.size !in 1..7) invalid("fields has invalid size")
                    val allowed = setOf("BATTERY", "CHARGING", "WIFI", "BLUETOOTH", "NETWORK", "SCREEN", "LOCALE")
                    val strings = fields.map { it as? String ?: invalid("fields must contain strings") }
                    if (strings.toSet().size != strings.size || strings.any { it !in allowed }) invalid("invalid fields")
                }
            }
        }
    }

    private fun validateOffer(message: Map<String, Any?>) {
        message.closed(
            required =
                setOf(
                    "message_type", "protocol_min", "protocol_max", "schema_bundle_digest",
                    "tool_schema_digests", "features", "required_features",
                ),
            optional = setOf("extensions"),
        )
        val offer = CompatibilityOffer.fromMessage(message)
        if (!digest.matches(offer.schemaBundleDigest)) invalid("invalid schema bundle digest")
        if (offer.toolSchemaDigests.keys != canonicalTools) invalid("offer must bind all canonical tool schemas")
        if (offer.toolSchemaDigests.values.any { !digest.matches(it) }) invalid("invalid tool schema digest")
        validateFeatures(message.list("features"))
        validateFeatures(message.list("required_features"))
        validateExtensions(message)
    }

    private fun validateSelection(message: Map<String, Any?>) {
        message.closed(
            required = setOf("message_type", "selected_version", "schema_bundle_digest", "accepted_features"),
            optional = setOf("extensions"),
        )
        ProtocolVersion.parse(message.string("selected_version"))
        if (!digest.matches(message.string("schema_bundle_digest"))) invalid("invalid schema bundle digest")
        validateFeatures(message.list("accepted_features"))
        validateExtensions(message)
    }

    private fun validateRequest(
        message: Map<String, Any?>,
        authorized: Boolean,
    ) {
        val base =
            setOf(
                "message_type", "protocol_version", "request_id", "task_id", "span_id", "device_id",
                "tool", "parameters", "state_precondition", "verification", "idempotency_key", "attempt",
                "requested_at", "deadline",
            )
        val authorization =
            setOf(
                "action_digest", "effective_target", "effective_risk", "policy_decision_id",
                "execution_authorization", "authorization_algorithm", "authorization_key_id", "broker_id",
                "session_id", "sequence", "nonce", "issued_at", "expires_at",
            )
        message.closed(required = if (authorized) base + authorization else base, optional = setOf("extensions"))
        if (message.string("protocol_version") != "0.1.0") invalid("unsupported protocol version")
        listOf("request_id", "task_id", "span_id", "device_id", "idempotency_key").forEach {
            validateOpaqueId(message.string(it))
        }
        val tool = message.string("tool")
        val parameters = message.objectValue("parameters")
        validateToolParameters(tool, parameters)
        val precondition = message.optionalObject("state_precondition")
        precondition?.let(::validateStatePrecondition)
        if (tool in stateBoundTools && precondition == null) invalid("state-bound action requires state_precondition")
        validateVerificationRequest(message.optionalObject("verification"))
        message.long("attempt").requireRange(1, 32, "attempt")
        val requestedAt = parseTimestamp(message.string("requested_at"))
        val deadline = parseTimestamp(message.string("deadline"))
        if (!requestedAt.isBefore(deadline)) invalid("request deadline must follow requested_at")
        if (tool == "phone.swipe") {
            val stateId = precondition?.string("state_id") ?: invalid("swipe requires state precondition")
            if (parameters.objectValue("start").string("state_id") != stateId ||
                parameters.objectValue("end").string("state_id") != stateId
            ) {
                invalid("swipe points must bind to the precondition state")
            }
        }
        if (authorized) validateAuthorization(message, deadline)
        validateExtensions(message)
    }

    private fun validateAuthorization(
        message: Map<String, Any?>,
        deadline: OffsetDateTime,
    ) {
        if (!digest.matches(message.string("action_digest"))) invalid("invalid action digest")
        if (message.string("action_digest") != CanonicalJson.actionDigest(message)) {
            invalid("action_digest does not bind the normalized action")
        }
        message.optionalObject("effective_target")?.let { validateTarget(it, allowCoordinates = true) }
        message.string("effective_risk").requireEnum(setOf("L0", "L1", "L2", "L3", "L4", "L5"), "effective_risk")
        listOf("policy_decision_id", "authorization_key_id", "broker_id", "session_id").forEach {
            validateOpaqueId(message.string(it))
        }
        if (!Regex("[A-Za-z0-9_-]{16,8192}").matches(message.string("execution_authorization"))) {
            invalid("invalid execution authorization")
        }
        if (message.string("authorization_algorithm") != "ES256") invalid("unsupported authorization algorithm")
        message.long("sequence").requireRange(1, StrictJson.MAX_SAFE_INTEGER, "sequence")
        if (!Regex("[A-Za-z0-9_-]{22,86}").matches(message.string("nonce"))) invalid("invalid nonce")
        val issued = parseTimestamp(message.string("issued_at"))
        val expires = parseTimestamp(message.string("expires_at"))
        if (!issued.isBefore(expires) || expires.isAfter(deadline) ||
            java.time.Duration.between(issued, expires).seconds > 30
        ) {
            invalid("authorization expiry is outside request bounds")
        }
    }

    private fun validateResult(message: Map<String, Any?>) {
        message.closed(
            required =
                setOf(
                    "message_type", "protocol_version", "request_id", "task_id", "span_id", "device_id", "tool",
                    "parameters", "execution_status", "before_state", "after_state", "duration", "error", "recoverable",
                    "timestamp", "attempt", "idempotency_key", "parameter_digest", "permission_decision_id",
                    "verification", "artifacts", "redactions",
                ),
            optional = setOf("extensions"),
        )
        if (message.string("protocol_version") != "0.1.0") invalid("unsupported protocol version")
        listOf("request_id", "task_id", "span_id", "device_id", "idempotency_key").forEach {
            validateOpaqueId(message.string(it))
        }
        val tool = message.string("tool")
        validateToolParameters(tool, message.objectValue("parameters"))
        val statuses = setOf("NOT_STARTED", "AWAITING_CONFIRMATION", "SUCCEEDED", "FAILED", "DENIED", "CANCELLED", "TIMED_OUT", "UNKNOWN_OUTCOME")
        val status = message.string("execution_status").also { it.requireEnum(statuses, "execution_status") }
        message.optionalObject("before_state")?.let(::validatePhoneStateRef)
        message.optionalObject("after_state")?.let(::validatePhoneStateRef)
        message.long("duration").requireRange(0, 86_400_000, "duration")
        val recoverable = message["recoverable"] as? Boolean ?: invalid("recoverable must be boolean")
        parseTimestamp(message.string("timestamp"))
        message.long("attempt").requireRange(1, 32, "attempt")
        if (!digest.matches(message.string("parameter_digest"))) invalid("invalid parameter digest")
        message.optionalString("permission_decision_id")?.requireLength(0, 128, "permission_decision_id")
        val error = message.optionalObject("error")
        if (status == "SUCCEEDED" && error != null) invalid("successful execution cannot contain an error")
        val retryDisposition = error?.let(::validateProtocolError)
        val expectedRecoverable = retryDisposition in setOf("REOBSERVE", "RETRY_SAME_ACTION", "REPLAN")
        if (recoverable != expectedRecoverable) invalid("recoverable must derive from retry_disposition")
        validateVerificationResult(message.objectValue("verification"))
        validateArtifacts(message.list("artifacts"))
        validateStringArray(message.list("redactions"), 32, 128, "redactions")
        validateExtensions(message)
    }

    private fun validateEvent(message: Map<String, Any?>) {
        message.closed(
            required = setOf("message_type", "protocol_version", "id", "type", "source", "device_id", "timestamp", "cursor", "payload", "sensitivity", "deduplication_key", "redactions"),
            optional = setOf("extensions"),
        )
        if (message.string("protocol_version") != "0.1.0") invalid("unsupported protocol version")
        listOf("id", "device_id", "cursor", "deduplication_key").forEach { validateOpaqueId(message.string(it)) }
        if (!Regex("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+").matches(message.string("type"))) invalid("invalid event type")
        message.string("source").requireLength(0, 128, "source")
        parseTimestamp(message.string("timestamp"))
        if (message.objectValue("payload").size > 64) invalid("event payload is too large")
        message.string("sensitivity").requireEnum(setOf("D0", "D1", "D2", "D3", "D4"), "sensitivity")
        validateStringArray(message.list("redactions"), 32, 128, "redactions")
        validateExtensions(message)
    }

    private fun validateTarget(
        target: Map<String, Any?>,
        allowCoordinates: Boolean,
    ) {
        if ("node_id" in target) {
            target.closed(required = setOf("state_id", "node_id"))
            validateOpaqueId(target.string("state_id"))
            validateOpaqueId(target.string("node_id"))
        } else {
            if (!allowCoordinates) invalid("coordinate target is forbidden")
            validatePoint(target)
        }
    }

    private fun validatePoint(point: Map<String, Any?>) {
        point.closed(required = setOf("state_id", "x_px", "y_px"))
        validateOpaqueId(point.string("state_id"))
        point.long("x_px").requireRange(0, 32_767, "x_px")
        point.long("y_px").requireRange(0, 32_767, "y_px")
    }

    private fun validateStatePrecondition(value: Map<String, Any?>) {
        value.closed(required = setOf("state_id", "maximum_age_ms"), optional = setOf("foreground_package"))
        validateOpaqueId(value.string("state_id"))
        value.long("maximum_age_ms").requireRange(1, 5_000, "maximum_age_ms")
        value.optionalString("foreground_package")?.let(::validatePackage)
    }

    private fun validatePhoneStateRef(value: Map<String, Any?>) {
        value.closed(
            required =
                setOf(
                    "state_id", "captured_at", "freshness_ms", "device_id",
                    "foreground_package", "transition",
                ),
            optional = setOf("artifacts"),
        )
        validateOpaqueId(value.string("state_id"))
        parseTimestamp(value.string("captured_at"))
        value.long("freshness_ms").requireRange(0, 60_000, "freshness_ms")
        validateOpaqueId(value.string("device_id"))
        value.optionalString("foreground_package")?.let {
            if (it.length > 255) invalid("foreground_package is too long")
        }
        value.string("transition").requireEnum(setOf("NONE", "CHANGED", "UNKNOWN"), "transition")
        value.optionalList("artifacts")?.let(::validateArtifacts)
    }

    private fun validateProtocolError(value: Map<String, Any?>): String {
        value.closed(
            required = setOf("code", "category", "owner", "message", "retry_disposition"),
            optional = setOf("details", "cause_request_id"),
        )
        val codes =
            setOf(
                "TRANSPORT_UNAVAILABLE", "AUTHENTICATION_FAILED", "PROTOCOL_INCOMPATIBLE",
                "PERMISSION_DENIED", "CONFIRMATION_REQUIRED", "CAPABILITY_UNAVAILABLE", "APP_NOT_FOUND",
                "NODE_NOT_FOUND", "ACTION_REJECTED", "ACTION_TIMEOUT", "STATE_UNCHANGED",
                "UNEXPECTED_TRANSITION", "APP_CRASHED", "NETWORK_UNAVAILABLE", "VERIFICATION_FAILED",
                "AUTHORIZATION_INVALID", "AUTHORIZATION_EXPIRED", "REPLAY_DETECTED", "DEVICE_REVOKED",
                "DEVICE_IDENTITY_UNAVAILABLE", "REPLAY_STATE_UNAVAILABLE", "TLS_POLICY_VIOLATION",
            )
        value.string("code").requireEnum(codes, "error code")
        value.string("category").requireEnum(
            setOf("VALIDATION", "AUTHENTICATION", "POLICY", "TRANSPORT", "EXECUTION", "OBSERVATION", "VERIFICATION"),
            "error category",
        )
        value.string("owner").requireEnum(
            setOf("HERMES_ADAPTER", "RUNTIME", "POLICY", "TRANSPORT", "ANDROID_PEP", "CAPABILITY", "OBSERVER", "VERIFIER"),
            "error owner",
        )
        value.string("message").requireLength(0, 512, "error message")
        value.optionalObject("details")?.let { if (it.size > 16) invalid("too many error details") }
        value.optionalString("cause_request_id")?.let(::validateOpaqueId)
        return value.string("retry_disposition").also {
            it.requireEnum(setOf("NEVER", "REOBSERVE", "RETRY_SAME_ACTION", "REPLAN", "ASK_USER"), "retry_disposition")
        }
    }

    private fun validateVerificationResult(value: Map<String, Any?>) {
        value.closed(
            required = setOf("status", "observed_state_ids", "evaluator", "explanation"),
            optional = setOf("evidence"),
        )
        value.string("status").requireEnum(
            setOf("NOT_APPLICABLE", "PENDING", "PASSED", "FAILED", "INCONCLUSIVE"),
            "verification status",
        )
        val stateIds = value.list("observed_state_ids")
        if (stateIds.size > 8) invalid("too many observed state ids")
        val strings = stateIds.map { it as? String ?: invalid("observed state ids must be strings") }
        if (strings.toSet().size != strings.size) invalid("observed state ids must be unique")
        strings.forEach(::validateOpaqueId)
        value.string("evaluator").requireLength(0, 128, "evaluator")
        value.string("explanation").requireLength(0, 1_024, "explanation")
        value.optionalList("evidence")?.let(::validateArtifacts)
    }

    private fun validateArtifacts(values: List<Any?>) {
        if (values.size > 16) invalid("too many artifacts")
        values.forEach { raw ->
            val artifact = raw as? Map<*, *> ?: invalid("artifact must be an object")
            if (artifact.keys.any { it !is String }) invalid("artifact keys must be strings")
            @Suppress("UNCHECKED_CAST")
            validateArtifact(artifact as Map<String, Any?>)
        }
    }

    private fun validateArtifact(value: Map<String, Any?>) {
        value.closed(
            required =
                setOf(
                    "artifact_id", "media_type", "size_bytes", "digest", "sensitivity",
                    "redaction_status", "retention_class", "expires_at",
                ),
        )
        validateOpaqueId(value.string("artifact_id"))
        value.string("media_type").requireLength(0, 127, "media_type")
        value.long("size_bytes").requireRange(0, 67_108_864, "size_bytes")
        if (!digest.matches(value.string("digest"))) invalid("invalid artifact digest")
        value.string("sensitivity").requireEnum(setOf("D0", "D1", "D2", "D3", "D4"), "sensitivity")
        value.string("redaction_status").requireEnum(setOf("NONE", "REDACTED", "WITHHELD"), "redaction_status")
        value.string("retention_class").requireEnum(setOf("EPHEMERAL", "TASK", "AUDIT"), "retention_class")
        parseTimestamp(value.string("expires_at"))
    }

    private fun validateVerificationRequest(value: Map<String, Any?>?) {
        value ?: return
        value.closed(required = setOf("condition"), optional = setOf("expected"))
        val condition = value.string("condition")
        condition.requireEnum(setOf("STATE_CHANGED", "FOREGROUND_APP_IS", "TEXT_PRESENT", "TEXT_ABSENT"), "condition")
        if (condition != "STATE_CHANGED") value.string("expected").requireLength(0, 4_096, "expected")
    }

    private fun validateFeatures(values: List<Any?>) {
        if (values.size > 64) invalid("too many features")
        val strings = values.map { it as? String ?: invalid("features must contain strings") }
        if (strings.toSet().size != strings.size || strings.any { !Regex("[a-z][a-z0-9_.-]{0,63}").matches(it) }) {
            invalid("invalid feature set")
        }
    }

    private fun validateExtensions(message: Map<String, Any?>) {
        val extensions = message.optionalObject("extensions") ?: return
        if (extensions.size > 32 || extensions.keys.any { !Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9_-]*)+").matches(it) }) {
            invalid("invalid extensions")
        }
    }

    private fun validateStringArray(
        values: List<Any?>,
        maximumItems: Int,
        maximumLength: Int,
        name: String,
    ) {
        if (values.size > maximumItems) invalid("too many $name")
        val strings = values.map { it as? String ?: invalid("$name must contain strings") }
        if (strings.toSet().size != strings.size || strings.any { it.length > maximumLength }) invalid("invalid $name")
    }

    private fun validateOpaqueId(value: String) {
        if (!opaqueId.matches(value)) invalid("invalid opaque id")
    }

    private fun validatePackage(value: String) {
        if (value.length > 255 || !packageName.matches(value)) invalid("invalid Android package name")
    }

    private fun parseTimestamp(value: String): OffsetDateTime =
        try {
            OffsetDateTime.parse(value).also {
                if (it.offset.totalSeconds != 0) invalid("protocol timestamps must use UTC")
            }
        } catch (exc: DateTimeParseException) {
            throw ProtocolValidationException("invalid protocol timestamp", exc)
        }

    private fun Map<String, Any?>.closed(
        required: Set<String> = emptySet(),
        optional: Set<String> = emptySet(),
    ) {
        if (!keys.containsAll(required)) invalid("missing required field")
        val unknown = keys - required - optional
        if (unknown.isNotEmpty()) invalid("unknown field: ${unknown.first()}")
    }

    private fun Map<String, Any?>.string(name: String): String =
        this[name] as? String ?: invalid("$name must be a string")

    private fun Map<String, Any?>.optionalString(name: String): String? {
        if (this[name] == null) return null
        return this[name] as? String ?: invalid("$name must be a string")
    }

    private fun Map<String, Any?>.long(name: String): Long =
        when (val value = this[name]) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> invalid("$name must be an integer")
        }

    private fun Map<String, Any?>.optionalLong(name: String): Long? {
        if (this[name] == null) return null
        return long(name)
    }

    private fun Map<String, Any?>.objectValue(name: String): Map<String, Any?> =
        optionalObject(name) ?: invalid("$name must be an object")

    private fun Map<String, Any?>.optionalObject(name: String): Map<String, Any?>? {
        val raw = this[name] ?: return null
        val value = raw as? Map<*, *> ?: invalid("$name must be an object")
        if (value.keys.any { it !is String }) invalid("$name object keys must be strings")
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private fun Map<String, Any?>.list(name: String): List<Any?> =
        optionalList(name) ?: invalid("$name must be an array")

    private fun Map<String, Any?>.optionalList(name: String): List<Any?>? =
        when (val value = this[name]) {
            null -> null
            is List<*> -> value
            else -> invalid("$name must be an array")
        }

    private fun Long.requireRange(
        minimum: Long,
        maximum: Long,
        name: String,
    ) {
        if (this !in minimum..maximum) invalid("$name is outside the allowed range")
    }

    private fun String.requireEnum(
        allowed: Set<String>,
        name: String,
    ) {
        if (this !in allowed) invalid("invalid $name")
    }

    private fun String.requireLength(
        minimum: Int,
        maximum: Int,
        name: String,
    ) {
        if (length !in minimum..maximum) invalid("$name has invalid length")
    }

    private fun invalid(message: String): Nothing = throw ProtocolValidationException(message)
}

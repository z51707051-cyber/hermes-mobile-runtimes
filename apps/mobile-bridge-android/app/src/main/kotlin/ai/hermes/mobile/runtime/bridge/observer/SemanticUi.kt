package ai.hermes.mobile.runtime.bridge.observer

import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.artifact.ArtifactReference

internal data class SemanticUiLimits(
    val maxNodes: Int,
    val maxTextChars: Int,
) {
    init {
        require(maxNodes in 1..500) { "maxNodes must be within 1..500" }
        require(maxTextChars in 1..20_000) { "maxTextChars must be within 1..20000" }
    }
}

internal data class UiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal data class SemanticNodeInput(
    val className: String?,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val bounds: UiBounds,
    val childCount: Int,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val selected: Boolean,
    val visibleToUser: Boolean,
    val password: Boolean,
)

internal data class SemanticNodeAdmission(
    val nodeId: String,
    val descend: Boolean,
)

/** Primitive-only execution binding retained briefly for semantic target re-resolution. */
internal data class SemanticActionTargetDescriptor(
    val nodeId: String,
    val className: String?,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val bounds: UiBounds,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val visibleToUser: Boolean,
    val password: Boolean,
)

internal data class NormalizedSemanticUiTree(
    val payload: ByteArray,
    val nodeCount: Int,
    val captureErrors: List<String>,
    val redactions: List<String>,
    val actionTargets: List<SemanticActionTargetDescriptor>,
    val visibleText: List<String>,
)

internal data class SemanticUiCapture(
    val state: PhoneStateSnapshot,
    val artifact: ArtifactReference,
    val redactions: List<String>,
    val visibleText: List<String> = emptyList(),
)

internal interface SemanticUiCaptureSource {
    fun availability(): PhoneStateUnavailableReason?

    fun capture(limits: SemanticUiLimits): SemanticUiCapture
}

/** Short-lived semantic evidence for condition evaluation; no UI artifact or node escapes. */
internal data class SemanticUiProbe(
    val state: PhoneStateSnapshot,
    val visibleText: List<String>,
    val redactions: List<String>,
)

internal interface SemanticUiProbeSource {
    fun availability(): PhoneStateUnavailableReason?

    fun probe(limits: SemanticUiLimits): SemanticUiProbe
}

/** Bounded collector that receives primitives only; Android node objects never escape the service. */
internal class SemanticUiTreeBuilder(
    private val limits: SemanticUiLimits,
) {
    private val nodes = mutableListOf<Map<String, Any?>>()
    private val captureErrors = linkedSetOf<String>()
    private val redactions = linkedSetOf<String>()
    private val actionTargets = mutableListOf<SemanticActionTargetDescriptor>()
    private val visibleText = mutableListOf<String>()
    private var remainingTextChars = limits.maxTextChars

    fun add(
        input: SemanticNodeInput,
        parentId: String?,
        depth: Int,
        childIndex: Int,
    ): SemanticNodeAdmission? {
        if (nodes.size >= limits.maxNodes) {
            captureErrors += NODE_LIMIT_REACHED
            return null
        }
        if (depth > MAX_DEPTH) {
            captureErrors += DEPTH_LIMIT_REACHED
            return null
        }
        val nodeId = "node-${nodes.size + 1}"
        val text = protectedText(input.text, input.password)
        val description = protectedText(input.contentDescription, input.password)
        val targetText = protectedTargetText(input.text, input.password)
        val targetDescription = protectedTargetText(input.contentDescription, input.password)
        visibleText += listOfNotNull(text, description)
        actionTargets +=
            SemanticActionTargetDescriptor(
                nodeId = nodeId,
                className = boundedMetadata(input.className),
                resourceId = boundedMetadata(input.resourceId),
                text = targetText,
                contentDescription = targetDescription,
                bounds = input.bounds,
                clickable = input.clickable,
                longClickable = input.longClickable,
                editable = input.editable,
                enabled = input.enabled,
                visibleToUser = input.visibleToUser,
                password = input.password,
            )
        nodes +=
            linkedMapOf(
                "node_id" to nodeId,
                "parent_id" to parentId,
                "depth" to depth,
                "child_index" to childIndex,
                "child_count" to input.childCount.coerceIn(0, MAX_CHILDREN_REPORTED),
                "class_name" to boundedMetadata(input.className),
                "resource_id" to boundedMetadata(input.resourceId),
                "role" to role(input),
                "text" to text,
                "content_description" to description,
                "bounds" to
                    mapOf(
                        "left" to input.bounds.left,
                        "top" to input.bounds.top,
                        "right" to input.bounds.right,
                        "bottom" to input.bounds.bottom,
                    ),
                "clickable" to input.clickable,
                "long_clickable" to input.longClickable,
                "editable" to input.editable,
                "scrollable" to input.scrollable,
                "checkable" to input.checkable,
                "checked" to input.checked,
                "enabled" to input.enabled,
                "focused" to input.focused,
                "selected" to input.selected,
                "visible_to_user" to input.visibleToUser,
                "password" to input.password,
            )
        return SemanticNodeAdmission(nodeId, depth < MAX_DEPTH)
    }

    fun markUnavailableChild() {
        captureErrors += NODE_UNAVAILABLE
    }

    fun hasNodeCapacity(): Boolean = nodes.size < limits.maxNodes

    fun markNodeLimit() {
        captureErrors += NODE_LIMIT_REACHED
    }

    fun markDepthLimit() {
        captureErrors += DEPTH_LIMIT_REACHED
    }

    fun build(packageName: String): NormalizedSemanticUiTree {
        val document =
            mapOf(
                "schema_version" to 1,
                "foreground_package" to packageName,
                "node_count" to nodes.size,
                "truncated" to captureErrors.isNotEmpty(),
                "capture_errors" to captureErrors.sorted(),
                "redactions" to redactions.sorted(),
                "nodes" to nodes,
            )
        return NormalizedSemanticUiTree(
            payload = CanonicalJson.encode(document),
            nodeCount = nodes.size,
            captureErrors = captureErrors.sorted(),
            redactions = redactions.sorted(),
            actionTargets = actionTargets.toList(),
            visibleText = visibleText.toList(),
        )
    }

    private fun protectedText(
        value: String?,
        password: Boolean,
    ): String? {
        if (password && value != null) {
            redactions += PASSWORD_CONTENT_WITHHELD
            return null
        }
        val normalized = normalizeText(value) ?: return null
        if (remainingTextChars == 0) {
            captureErrors += TEXT_LIMIT_REACHED
            return null
        }
        val accepted =
            unicodeSafeTake(
                normalized,
                remainingTextChars.coerceAtMost(MAX_TEXT_FIELD_CHARS),
            )
        remainingTextChars -= accepted.length
        if (accepted.length < normalized.length) captureErrors += TEXT_LIMIT_REACHED
        return accepted
    }

    private fun boundedMetadata(value: String?): String? =
        normalizeText(value)?.let { unicodeSafeTake(it, MAX_METADATA_CHARS) }

    private fun protectedTargetText(
        value: String?,
        password: Boolean,
    ): String? =
        if (password) {
            null
        } else {
            normalizeText(value)?.let { unicodeSafeTake(it, MAX_TEXT_FIELD_CHARS) }
        }

    private fun unicodeSafeTake(
        value: String,
        maximumChars: Int,
    ): String {
        var end = maximumChars.coerceAtMost(value.length)
        if (
            end in 1 until value.length &&
            Character.isHighSurrogate(value[end - 1]) &&
            Character.isLowSurrogate(value[end])
        ) {
            end -= 1
        }
        return value.substring(0, end)
    }

    private fun normalizeText(value: String?): String? =
        value
            ?.map { character -> if (character.isISOControl()) ' ' else character }
            ?.joinToString("")
            ?.trim()
            ?.replace(WHITESPACE, " ")
            ?.takeIf { it.isNotEmpty() }

    private fun role(input: SemanticNodeInput): String =
        when {
            input.editable -> "TEXT_FIELD"
            input.checkable -> "CHECKBOX"
            input.className?.endsWith("Button") == true -> "BUTTON"
            input.className?.endsWith("ImageView") == true -> "IMAGE"
            input.scrollable -> "SCROLL_CONTAINER"
            else -> "GENERIC"
        }

    private companion object {
        const val MAX_DEPTH = 64
        const val MAX_CHILDREN_REPORTED = 10_000
        const val MAX_TEXT_FIELD_CHARS = 4_096
        const val MAX_METADATA_CHARS = 512
        const val NODE_LIMIT_REACHED = "NODE_LIMIT_REACHED"
        const val TEXT_LIMIT_REACHED = "TEXT_LIMIT_REACHED"
        const val DEPTH_LIMIT_REACHED = "DEPTH_LIMIT_REACHED"
        const val NODE_UNAVAILABLE = "NODE_UNAVAILABLE"
        const val PASSWORD_CONTENT_WITHHELD = "PASSWORD_CONTENT_WITHHELD"
        val WHITESPACE = Regex("\\s+")
    }
}

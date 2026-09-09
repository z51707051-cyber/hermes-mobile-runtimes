package ai.hermes.mobile.runtime.bridge.observer

import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal enum class SemanticTargetFailureReason {
    GENERATION_UNAVAILABLE,
    TARGET_NOT_FOUND,
    TARGET_CHANGED,
    WINDOW_MISMATCH,
}

internal class SemanticTargetException(
    val reason: SemanticTargetFailureReason,
) : IllegalStateException("semantic target is unavailable: $reason")

internal data class ResolvedSemanticTarget(
    val descriptor: SemanticActionTargetDescriptor,
    val requiredRisk: String,
)

internal fun interface SemanticTargetClock {
    fun nowMillis(): Long
}

/**
 * Bounded process-local index used to re-resolve a node without retaining an
 * AccessibilityNodeInfo or raw semantic text after the capture call returns.
 */
internal class SemanticTargetRegistry(
    signatureKey: ByteArray = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes),
    private val clock: SemanticTargetClock = SemanticTargetClock { System.currentTimeMillis() },
) {
    private data class TargetBinding(
        val signature: ByteArray,
        val requiredRisk: String,
    )

    private data class Generation(
        val packageName: String,
        val windowId: Int,
        val expiresAtMillis: Long,
        val targets: Map<String, TargetBinding>,
    )

    private val signatureKey = signatureKey.copyOf()
    private val generations = linkedMapOf<String, Generation>()

    init {
        require(this.signatureKey.size >= KEY_BYTES) {
            "semantic target signature key must be at least 256-bit"
        }
    }

    @Synchronized
    fun register(
        stateId: String,
        packageName: String,
        windowId: Int,
        targets: List<SemanticActionTargetDescriptor>,
        timeToLiveMillis: Long = DEFAULT_TTL_MILLIS,
    ) {
        require(targets.size in 1..MAX_TARGETS) { "semantic target count is invalid" }
        require(targets.map { it.nodeId }.distinct().size == targets.size) {
            "semantic target ids must be unique"
        }
        require(timeToLiveMillis in 1..MAX_TTL_MILLIS) { "semantic target TTL is invalid" }
        purgeExpiredLocked()
        generations.remove(stateId)?.targets?.values?.forEach { it.signature.fill(0) }
        while (generations.size >= MAX_GENERATIONS) {
            generations.remove(generations.entries.first().key)
                ?.targets
                ?.values
                ?.forEach { it.signature.fill(0) }
        }
        generations[stateId] =
            Generation(
                packageName = packageName,
                windowId = windowId,
                expiresAtMillis = Math.addExact(clock.nowMillis(), timeToLiveMillis),
                targets =
                    targets.associate { target ->
                        target.nodeId to
                            TargetBinding(
                                signature = signature(target),
                                requiredRisk = SemanticRiskClassifier.requiredRisk(target),
                            )
                    },
            )
    }

    @Synchronized
    fun resolve(
        stateId: String,
        packageName: String,
        windowId: Int,
        current: SemanticActionTargetDescriptor,
    ): ResolvedSemanticTarget {
        purgeExpiredLocked()
        val generation =
            generations[stateId]
                ?: throw SemanticTargetException(
                    SemanticTargetFailureReason.GENERATION_UNAVAILABLE,
                )
        if (generation.packageName != packageName || generation.windowId != windowId) {
            throw SemanticTargetException(SemanticTargetFailureReason.WINDOW_MISMATCH)
        }
        val binding =
            generation.targets[current.nodeId]
                ?: throw SemanticTargetException(SemanticTargetFailureReason.TARGET_NOT_FOUND)
        val currentSignature = signature(current)
        try {
            if (!java.security.MessageDigest.isEqual(binding.signature, currentSignature)) {
                throw SemanticTargetException(SemanticTargetFailureReason.TARGET_CHANGED)
            }
        } finally {
            currentSignature.fill(0)
        }
        return ResolvedSemanticTarget(current, binding.requiredRisk)
    }

    @Synchronized
    fun clear() {
        generations.values.forEach { generation ->
            generation.targets.values.forEach { it.signature.fill(0) }
        }
        generations.clear()
    }

    private fun purgeExpiredLocked() {
        val now = clock.nowMillis()
        val expired = generations.filterValues { it.expiresAtMillis <= now }.keys
        expired.forEach { stateId ->
            generations.remove(stateId)?.targets?.values?.forEach { it.signature.fill(0) }
        }
    }

    private fun signature(target: SemanticActionTargetDescriptor): ByteArray {
        val material =
            CanonicalJson.encode(
                mapOf(
                    "node_id" to target.nodeId,
                    "class_name" to target.className,
                    "resource_id" to target.resourceId,
                    "text" to target.text,
                    "content_description" to target.contentDescription,
                    "bounds" to
                        mapOf(
                            "left" to target.bounds.left,
                            "top" to target.bounds.top,
                            "right" to target.bounds.right,
                            "bottom" to target.bounds.bottom,
                        ),
                    "clickable" to target.clickable,
                    "long_clickable" to target.longClickable,
                    "editable" to target.editable,
                    "enabled" to target.enabled,
                    "visible" to target.visibleToUser,
                    "password" to target.password,
                ),
            )
        return try {
            Mac.getInstance(HMAC_ALGORITHM).run {
                init(SecretKeySpec(signatureKey, HMAC_ALGORITHM))
                doFinal(material)
            }
        } finally {
            material.fill(0)
        }
    }

    private companion object {
        const val KEY_BYTES = 32
        const val MAX_TARGETS = 500
        const val MAX_GENERATIONS = 16
        const val DEFAULT_TTL_MILLIS = 300_000L
        const val MAX_TTL_MILLIS = 300_000L
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}

internal object SemanticRiskClassifier {
    private val level4 =
        listOf(
            "delete",
            "remove",
            "purchase",
            "buy",
            "pay",
            "transfer",
            "install",
            "uninstall",
            "factory reset",
            "删除",
            "移除",
            "购买",
            "支付",
            "转账",
            "安装",
            "卸载",
            "清除数据",
        )
    private val level3 =
        listOf(
            "send",
            "post",
            "publish",
            "call",
            "email",
            "comment",
            "message",
            "发送",
            "发布",
            "拨打",
            "邮件",
            "评论",
            "消息",
        )
    private val level2 =
        listOf(
            "save",
            "submit",
            "confirm",
            "allow",
            "agree",
            "保存",
            "提交",
            "确认",
            "允许",
            "同意",
        )

    fun requiredRisk(target: SemanticActionTargetDescriptor): String {
        if (target.password) return "L3"
        val accessibleSemantic =
            listOfNotNull(
                target.text,
                target.contentDescription,
            ).joinToString(" ").lowercase(Locale.ROOT)
        val semantic =
            listOfNotNull(
                target.text,
                target.contentDescription,
                target.resourceId,
            ).joinToString(" ").lowercase(Locale.ROOT)
        return when {
            level4.any(semantic::contains) -> "L4"
            level3.any(semantic::contains) -> "L3"
            level2.any(semantic::contains) -> "L2"
            accessibleSemantic.isBlank() -> "L4"
            else -> "L1"
        }
    }
}

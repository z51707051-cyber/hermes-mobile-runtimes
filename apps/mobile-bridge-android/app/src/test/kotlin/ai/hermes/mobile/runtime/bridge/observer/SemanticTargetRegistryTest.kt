package ai.hermes.mobile.runtime.bridge.observer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SemanticTargetRegistryTest {
    @Test
    fun registryReResolvesExactTargetAndRejectsDrift() {
        var now = 100L
        val registry =
            SemanticTargetRegistry(
                signatureKey = ByteArray(32) { 7 },
                clock = SemanticTargetClock { now },
            )
        val send = target(text = "Send message")
        registry.register("state-1", "com.example.chat", 42, listOf(send), 1_000)

        val resolved = registry.resolve("state-1", "com.example.chat", 42, send)
        assertEquals("L3", resolved.requiredRisk)

        val changed = send.copy(bounds = UiBounds(0, 0, 101, 100))
        val drift =
            assertThrows(SemanticTargetException::class.java) {
                registry.resolve("state-1", "com.example.chat", 42, changed)
            }
        assertEquals(SemanticTargetFailureReason.TARGET_CHANGED, drift.reason)

        now += 1_000
        val expired =
            assertThrows(SemanticTargetException::class.java) {
                registry.resolve("state-1", "com.example.chat", 42, send)
            }
        assertEquals(SemanticTargetFailureReason.GENERATION_UNAVAILABLE, expired.reason)
    }

    @Test
    fun destructiveAndUnknownTargetsAreConservativelyClassified() {
        assertEquals("L4", SemanticRiskClassifier.requiredRisk(target(text = "确认支付")))
        assertEquals("L4", SemanticRiskClassifier.requiredRisk(target(text = "修改密码")))
        assertEquals("L4", SemanticRiskClassifier.requiredRisk(target(text = "Enable two-factor")))
        assertEquals("L4", SemanticRiskClassifier.requiredRisk(target(text = null, resourceId = null)))
        assertEquals(
            "L4",
            SemanticRiskClassifier.requiredRisk(target(text = null, resourceId = "com.example:id/action")),
        )
        assertEquals("L4", SemanticRiskClassifier.requiredRisk(target(text = "ordinary", password = true)))
        assertEquals(
            "L4",
            SemanticRiskClassifier.requiredRisk(
                target(
                    text = null,
                    resourceId = "com.example:id/sms_verification_code",
                    editable = true,
                ),
            ),
        )
        assertEquals(
            "L4",
            SemanticRiskClassifier.requiredRisk(target(text = "输入验证码", editable = true)),
        )
        assertEquals("L1", SemanticRiskClassifier.requiredRisk(target(text = "Work playlist")))
    }

    private fun target(
        text: String?,
        resourceId: String? = "com.example:id/action",
        password: Boolean = false,
        editable: Boolean = false,
    ): SemanticActionTargetDescriptor =
        SemanticActionTargetDescriptor(
            nodeId = "node-1",
            className = "android.widget.Button",
            resourceId = resourceId,
            text = text,
            contentDescription = null,
            bounds = UiBounds(0, 0, 100, 100),
            clickable = true,
            longClickable = true,
            editable = editable,
            enabled = true,
            visibleToUser = true,
            password = password,
        )
}

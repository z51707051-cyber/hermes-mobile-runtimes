package ai.hermes.mobile.runtime.bridge.observer

import ai.hermes.mobile.runtime.bridge.protocol.StrictJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticUiTreeBuilderTest {
    @Test
    fun builderNormalizesSemanticFieldsAndWithholdsPasswordContent() {
        val builder = SemanticUiTreeBuilder(SemanticUiLimits(maxNodes = 3, maxTextChars = 20))
        val root = builder.add(node(text = "  Sign\n in  ", className = "android.widget.Button"), null, 0, 0)
        builder.add(
            node(
                text = "secret",
                description = "account password",
                className = "android.widget.EditText",
                password = true,
                editable = true,
            ),
            root!!.nodeId,
            1,
            0,
        )

        val tree = builder.build("com.example.app")
        val document = StrictJson.decodeObject(tree.payload)
        val nodes = document["nodes"] as List<*>
        val button = nodes[0] as Map<*, *>
        val password = nodes[1] as Map<*, *>

        assertEquals("Sign in", button["text"])
        assertEquals("BUTTON", button["role"])
        assertEquals("TEXT_FIELD", password["role"])
        assertNull(password["text"])
        assertNull(password["content_description"])
        assertEquals(listOf("PASSWORD_CONTENT_WITHHELD"), tree.redactions)
        assertEquals("node-1", tree.actionTargets[0].nodeId)
        assertEquals("Sign in", tree.actionTargets[0].text)
        assertNull(tree.actionTargets[1].text)
        assertTrue(tree.captureErrors.isEmpty())
        assertFalse(document["truncated"] as Boolean)
    }

    @Test
    fun builderEnforcesNodeAndUnicodeSafeTextBudgets() {
        val builder = SemanticUiTreeBuilder(SemanticUiLimits(maxNodes = 2, maxTextChars = 5))
        val first = builder.add(node(text = "abcd😀"), null, 0, 0)
        builder.add(node(text = "z"), first!!.nodeId, 1, 0)
        assertNull(builder.add(node(text = "overflow"), first.nodeId, 1, 1))

        val tree = builder.build("com.example.app")
        val document = StrictJson.decodeObject(tree.payload)
        val nodes = document["nodes"] as List<*>

        assertEquals("abcd", (nodes[0] as Map<*, *>)["text"])
        assertEquals("z", (nodes[1] as Map<*, *>)["text"])
        assertEquals("abcd😀", tree.actionTargets[0].text)
        assertEquals(listOf("NODE_LIMIT_REACHED", "TEXT_LIMIT_REACHED"), tree.captureErrors)
        assertTrue(document["truncated"] as Boolean)
    }

    @Test
    fun builderRejectsNodesBeyondTheDepthLimit() {
        val builder = SemanticUiTreeBuilder(SemanticUiLimits(maxNodes = 2, maxTextChars = 10))

        assertNull(builder.add(node(), parentId = "node-1", depth = 65, childIndex = 0))

        val tree = builder.build("com.example.app")
        assertEquals(listOf("DEPTH_LIMIT_REACHED"), tree.captureErrors)
        assertEquals(0, tree.nodeCount)
    }

    @Test
    fun equivalentSemanticTreesHaveStableFingerprintContent() {
        fun build(): ByteArray {
            val builder = SemanticUiTreeBuilder(SemanticUiLimits(maxNodes = 2, maxTextChars = 20))
            builder.add(node(text = "Play"), null, 0, 0)
            return builder.build("com.example.app").payload
        }

        assertTrue(build().contentEquals(build()))
        assertFalse(StrictJson.decodeObject(build()).containsKey("captured_at_epoch_ms"))
    }

    private fun node(
        text: String? = null,
        description: String? = null,
        className: String? = "android.view.View",
        password: Boolean = false,
        editable: Boolean = false,
    ): SemanticNodeInput =
        SemanticNodeInput(
            className = className,
            resourceId = "com.example.app:id/item",
            text = text,
            contentDescription = description,
            bounds = UiBounds(0, 0, 100, 100),
            childCount = 0,
            clickable = true,
            longClickable = false,
            editable = editable,
            scrollable = false,
            checkable = false,
            checked = false,
            enabled = true,
            focused = false,
            selected = false,
            visibleToUser = true,
            password = password,
        )
}

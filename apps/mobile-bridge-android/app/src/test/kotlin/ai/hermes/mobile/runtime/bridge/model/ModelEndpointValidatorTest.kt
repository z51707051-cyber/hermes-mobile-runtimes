package ai.hermes.mobile.runtime.bridge.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelEndpointValidatorTest {
    @Test
    fun acceptsOfficialAndRelayHttpsPaths() {
        assertEquals(
            ModelEndpoint("https://api.openai.com/v1", "gpt-5"),
            ModelEndpointValidator.validate(" https://api.openai.com/v1/ ", " gpt-5 "),
        )
        assertEquals(
            "https://relay.example.cn/api/v1",
            ModelEndpointValidator.validate(
                "https://relay.example.cn/api/v1",
                "deepseek-chat",
            ).baseUrl,
        )
        assertEquals(
            ModelEndpoint("https://api.deepseek.com", "deepseek-chat"),
            ModelEndpointValidator.validate(
                "https://api.deepseek.com/",
                "deepseek-chat",
            ),
        )
    }

    @Test
    fun rejectsCleartextCredentialsAndAmbiguousSuffixes() {
        listOf(
            "http://api.example.com/v1",
            "https://key@example.com/v1",
            "https://api.example.com/v1?route=other",
            "https://api.example.com/v1#fragment",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                ModelEndpointValidator.validate(value, "model")
            }
        }
    }

    @Test
    fun requiresModelAndHost() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelEndpointValidator.validate("https://api.example.com/v1", " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelEndpointValidator.validate("https:///v1", "model")
        }
    }
}

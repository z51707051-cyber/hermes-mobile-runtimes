package ai.hermes.mobile.runtime.bridge.model

import java.net.URI

data class ModelEndpoint(
    val baseUrl: String,
    val model: String,
)

object ModelEndpointValidator {
    fun validate(baseUrlInput: String, modelInput: String): ModelEndpoint {
        val baseUrl = baseUrlInput.trim().trimEnd('/')
        val model = modelInput.trim()
        require(model.isNotEmpty()) { "model is required" }
        val uri = runCatching { URI(baseUrl) }.getOrNull()
        require(
            uri != null &&
                uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        ) { "base URL must be an HTTPS origin or API path without credentials, query, or fragment" }
        return ModelEndpoint(baseUrl = baseUrl, model = model)
    }
}

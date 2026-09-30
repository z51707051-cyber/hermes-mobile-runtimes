package ai.hermes.mobile.runtime.bridge.model

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ModelConfigStatus(
    val endpoint: ModelEndpoint?,
    val hasApiKey: Boolean,
)

internal data class ModelRuntimeConfig(
    val endpoint: ModelEndpoint,
    val apiKey: CharArray,
)

class ModelConfigStore(private val context: Context) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun status(): ModelConfigStatus {
        val baseUrl = preferences.getString(BASE_URL, null)
        val model = preferences.getString(MODEL, null)
        val endpoint =
            if (baseUrl != null && model != null) {
                runCatching { ModelEndpointValidator.validate(baseUrl, model) }.getOrNull()
            } else {
                null
            }
        return ModelConfigStatus(endpoint, preferences.contains(API_KEY_CIPHERTEXT))
    }

    fun save(endpoint: ModelEndpoint, newApiKey: CharArray?) {
        val editor =
            preferences.edit()
                .putString(BASE_URL, endpoint.baseUrl)
                .putString(MODEL, endpoint.model)
        if (newApiKey != null && newApiKey.isNotEmpty()) {
            try {
                val encrypted =
                    runCatching { encrypt(newApiKey) }.getOrElse { error ->
                        throw IllegalStateException("model API key encryption failed", error)
                    }
                editor.putString(API_KEY_CIPHERTEXT, encrypted)
            } finally {
                newApiKey.fill('\u0000')
            }
        }
        check(editor.commit()) { "model configuration could not be stored" }
    }

    internal fun <T> withRuntimeConfig(block: (ModelRuntimeConfig) -> T): T {
        val endpoint = status().endpoint ?: error("model endpoint is not configured")
        val apiKey = readApiKey() ?: error("model API key is not configured")
        return try {
            block(ModelRuntimeConfig(endpoint, apiKey))
        } finally {
            apiKey.fill('\u0000')
        }
    }

    private fun readApiKey(): CharArray? {
        val encoded = preferences.getString(API_KEY_CIPHERTEXT, null) ?: return null
        return runCatching { decrypt(encoded) }.getOrElse { error ->
            throw IllegalStateException("model API key decryption failed", error)
        }
    }

    fun clearApiKey() {
        check(preferences.edit().remove(API_KEY_CIPHERTEXT).commit()) {
            "model API key could not be cleared"
        }
    }

    private fun encrypt(value: CharArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(AAD)
        val plaintext = value.concatToString().toByteArray(Charsets.UTF_8)
        val ciphertext =
            try {
                cipher.doFinal(plaintext)
            } finally {
                plaintext.fill(0)
            }
        val packed =
            ByteBuffer.allocate(2 + cipher.iv.size + ciphertext.size)
                .put(FORMAT_VERSION)
                .put(cipher.iv.size.toByte())
                .put(cipher.iv)
                .put(ciphertext)
                .array()
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): CharArray {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size >= 2) { "invalid encrypted API key" }
        val buffer = ByteBuffer.wrap(packed)
        require(buffer.get() == FORMAT_VERSION) { "unsupported encrypted API key format" }
        val ivLength = buffer.get().toInt() and 0xff
        require(ivLength in 12..32 && buffer.remaining() > ivLength) { "invalid encrypted API key" }
        val iv = ByteArray(ivLength).also { buffer.get(it) }
        val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        val plaintext = cipher.doFinal(ciphertext)
        return try {
            plaintext.toString(Charsets.UTF_8).toCharArray()
        } finally {
            plaintext.fill(0)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "hermes_model_config"
        const val BASE_URL = "base_url"
        const val MODEL = "model"
        const val API_KEY_CIPHERTEXT = "api_key_ciphertext"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "hermes.model.api-key.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
        val AAD: ByteArray = "hermes-model-api-key-v1".toByteArray(Charsets.US_ASCII)
    }
}

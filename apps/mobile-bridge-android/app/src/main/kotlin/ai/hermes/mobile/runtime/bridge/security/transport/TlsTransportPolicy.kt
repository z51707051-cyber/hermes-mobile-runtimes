package ai.hermes.mobile.runtime.bridge.security.transport

import java.net.URI
import javax.net.ssl.SSLSocket

internal data class EnrollmentEndpoint private constructor(val uri: URI) {
    companion object {
        private const val ENROLLMENT_PATH = "/v0/enroll"

        fun parse(raw: String): EnrollmentEndpoint {
            require(raw == raw.trim()) { "endpoint must not contain surrounding whitespace" }
            val uri = URI(raw)
            require(!uri.isOpaque) { "endpoint must be a hierarchical URI" }
            require(uri.scheme.equals("https", ignoreCase = true)) {
                "enrollment requires HTTPS"
            }
            require(!uri.host.isNullOrBlank()) { "endpoint must contain an ASCII host" }
            require(uri.userInfo == null) { "endpoint credentials are forbidden" }
            require(uri.query == null) { "endpoint query parameters are forbidden" }
            require(uri.fragment == null) { "endpoint fragments are forbidden" }
            require(uri.rawPath == ENROLLMENT_PATH) {
                "endpoint path must be $ENROLLMENT_PATH"
            }
            require(uri.port == -1 || uri.port in 1..65535) { "endpoint port is invalid" }
            return EnrollmentEndpoint(uri.normalize())
        }
    }
}

internal object TlsTransportPolicy {
    val allowedProtocols: List<String> = listOf("TLSv1.3", "TLSv1.2")

    fun configure(socket: SSLSocket) {
        val enabled = selectSupportedProtocols(socket.supportedProtocols.asIterable())
        require(enabled.isNotEmpty()) { "TLS 1.2 or TLS 1.3 support is required" }
        val cipherSuites = selectCipherSuites(socket.enabledCipherSuites.asIterable())
        require(cipherSuites.isNotEmpty()) { "an approved AEAD cipher suite is required" }
        socket.enabledProtocols = enabled.toTypedArray()
        socket.enabledCipherSuites = cipherSuites.toTypedArray()
        socket.sslParameters =
            socket.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
    }

    fun selectSupportedProtocols(supported: Iterable<String>): List<String> {
        val available = supported.toSet()
        return allowedProtocols.filter(available::contains)
    }

    fun selectCipherSuites(enabledByProvider: Iterable<String>): List<String> =
        enabledByProvider.filter(APPROVED_CIPHER_SUITES::contains)

    fun requireNegotiatedProtocol(protocol: String) {
        require(protocol in allowedProtocols) { "forbidden negotiated TLS protocol" }
    }

    private val APPROVED_CIPHER_SUITES =
        setOf(
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        )
}

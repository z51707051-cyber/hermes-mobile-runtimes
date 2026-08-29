package ai.hermes.mobile.runtime.bridge.security.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TlsTransportPolicyTest {
    @Test
    fun acceptsOnlyTheClosedHttpsEnrollmentEndpoint() {
        val endpoint = EnrollmentEndpoint.parse("https://broker.example:8443/v0/enroll")

        assertEquals("https", endpoint.uri.scheme)
        assertEquals("broker.example", endpoint.uri.host)
        assertEquals(8443, endpoint.uri.port)
    }

    @Test
    fun rejectsCleartextCredentialsAndUnboundPaths() {
        listOf(
            "http://broker.example/v0/enroll",
            "wss://broker.example/v0/enroll",
            "https://user:secret@broker.example/v0/enroll",
            "https://broker.example/v0/enroll?token=secret",
            "https://broker.example/v0/enroll#fragment",
            "https://broker.example/anything-else",
        ).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                EnrollmentEndpoint.parse(raw)
            }
        }
    }

    @Test
    fun enablesOnlyTls12AndTls13InPreferenceOrder() {
        assertEquals(
            listOf("TLSv1.3", "TLSv1.2"),
            TlsTransportPolicy.selectSupportedProtocols(
                listOf("SSLv3", "TLSv1.2", "TLSv1.1", "TLSv1.3"),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TlsTransportPolicy.requireNegotiatedProtocol("TLSv1.1")
        }
    }

    @Test
    fun retainsOnlyAeadForwardSecretCipherSuites() {
        assertEquals(
            listOf(
                "TLS_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            ),
            TlsTransportPolicy.selectCipherSuites(
                listOf(
                    "TLS_AES_128_GCM_SHA256",
                    "TLS_RSA_WITH_AES_128_CBC_SHA",
                    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                ),
            ),
        )
    }
}

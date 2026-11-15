package io.github.lorddetson.piplayer.fakes

import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/**
 * HTTPS for the fakes: Android blocks cleartext at targetSdk 36 and PiPlayer adds no cleartext exception anywhere, so
 * the instrumented tests serve the fakes over TLS with a certificate made for the test run. Only a client built with
 * [trustTestServers] trusts it; the app's own clients never do.
 */
object TestTls {

    private val certificate: HeldCertificate by lazy {
        HeldCertificate.Builder()
            .commonName("PiPlayer test server")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
    }

    private val server: HandshakeCertificates by lazy {
        HandshakeCertificates.Builder().heldCertificate(certificate).build()
    }

    /** Trusts the test certificate and nothing else, not even the platform's authorities. */
    private val client: HandshakeCertificates by lazy {
        HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    }

    /** Serves [server] over HTTPS with the test certificate; call before it starts. */
    fun useHttps(server: MockWebServer) {
        server.useHttps(this.server.sslSocketFactory())
    }

    fun trustTestServers(builder: OkHttpClient.Builder) {
        builder.sslSocketFactory(client.sslSocketFactory(), client.trustManager)
    }
}

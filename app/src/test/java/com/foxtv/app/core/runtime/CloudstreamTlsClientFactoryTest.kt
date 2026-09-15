package com.foxtv.app.core.runtime

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.Security
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class CloudstreamTlsClientFactoryTest {
    @Test
    fun `creating scoped TLS leaves global provider order and identity unchanged`() {
        val before = Security.getProviders()
        val material = CloudstreamTlsClientFactory.createTlsMaterial(
            provider = SSLContext.getDefault().provider,
        )
        CloudstreamTlsClientFactory.build(OkHttpClient.Builder(), material)
        val after = Security.getProviders()

        assertArrayEquals(before.map { it.name }.toTypedArray(), after.map { it.name }.toTypedArray())
        assertTrue(before.indices.all { before[it] === after[it] })
        assertFalse(after.any { it === material.provider && before.none { old -> old === material.provider } })
    }

    @Test
    fun `client receives the explicit system trust manager and socket factory`() {
        val trustManager = CloudstreamTlsClientFactory.systemTrustManager()
        val material = CloudstreamTlsClientFactory.createTlsMaterial(
            provider = SSLContext.getDefault().provider,
            trustManager = trustManager,
        )
        val platformDefaults = OkHttpClient()
        val client = CloudstreamTlsClientFactory.build(OkHttpClient.Builder(), material)

        assertSame(trustManager, client.x509TrustManager)
        assertSame(material.socketFactory, client.sslSocketFactory)
        assertSame(platformDefaults.hostnameVerifier, client.hostnameVerifier)
    }

    @Test
    fun `system trust rejects an untrusted server certificate`() = withMockWebServer { server ->
        val heldCertificate = localhostCertificate()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(heldCertificate)
            .build()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("must not be returned"))

        val material = CloudstreamTlsClientFactory.createTlsMaterial(
            provider = SSLContext.getDefault().provider,
        )
        val client = CloudstreamTlsClientFactory.build(OkHttpClient.Builder(), material)

        val failure = assertThrows(IOException::class.java) {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { }
        }
        assertTrue(failure.hasCause<SSLHandshakeException>())
    }

    @Test
    fun `default hostname verification rejects a trusted certificate for another host`() =
        withMockWebServer { server ->
            val heldCertificate = localhostCertificate()
            val serverCertificates = HandshakeCertificates.Builder()
                .heldCertificate(heldCertificate)
                .build()
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("must not be returned"))

            val clientCertificates = HandshakeCertificates.Builder()
                .addTrustedCertificate(heldCertificate.certificate)
                .build()
            val material = CloudstreamTlsClientFactory.createTlsMaterial(
                provider = SSLContext.getDefault().provider,
                trustManager = clientCertificates.trustManager,
            )
            val client = CloudstreamTlsClientFactory.build(OkHttpClient.Builder(), material)
            val wrongHostUrl = server.url("/").newBuilder().host("127.0.0.1").build()

            val failure = assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url(wrongHostUrl).build()).execute().use { }
            }
            assertTrue(failure.hasCause<SSLPeerUnverifiedException>())
        }

    @Test
    fun `concurrent initialization publishes exactly once`() {
        val initializer = RetriableInitializer()
        val starts = CountDownLatch(1)
        val calls = AtomicInteger()
        val workers = Executors.newFixedThreadPool(8)

        try {
            val futures = (1..32).map {
                workers.submit {
                    starts.await(5, TimeUnit.SECONDS)
                    initializer.ensure {
                        calls.incrementAndGet()
                        Thread.sleep(20)
                    }
                }
            }
            starts.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
        }

        assertTrue(initializer.isInitialized)
        assertEquals(1, calls.get())
    }

    @Test
    fun `failed initialization remains retryable`() {
        val initializer = RetriableInitializer()
        val calls = AtomicInteger()

        assertThrows(IllegalStateException::class.java) {
            initializer.ensure {
                calls.incrementAndGet()
                error("first attempt fails")
            }
        }
        assertFalse(initializer.isInitialized)

        initializer.ensure { calls.incrementAndGet() }

        assertTrue(initializer.isInitialized)
        assertEquals(2, calls.get())
    }

    private fun withMockWebServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        try {
            block(server)
        } finally {
            server.close()
        }
    }

    private fun localhostCertificate(): HeldCertificate = HeldCertificate.Builder()
        .commonName("localhost")
        .addSubjectAlternativeName("localhost")
        .build()

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
        generateSequence(this) { it.cause }.any { it is T }
}

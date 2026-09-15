package com.foxtv.app.core.plugin.aniyomi

import com.foxtv.app.core.plugin.aniyomi.host.AniyomiCookieJar
import com.foxtv.app.core.plugin.aniyomi.host.AniyomiNetworkClientFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager

class AniyomiNetworkClientFactoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun factoryBuildsIsolatedBoundedClientFromPlatformDefaults() {
        val cookieJar = AniyomiCookieJar()
        val factory = factory(cookieJar, "7.4.2")
        val client = factory.createClient()
        try {
            assertSame(cookieJar, client.cookieJar)
            assertEquals(30_000L, client.connectTimeoutMillis.toLong())
            assertEquals(30_000L, client.readTimeoutMillis.toLong())
            assertEquals(120_000L, client.callTimeoutMillis.toLong())
            assertTrue(client.followRedirects)
            assertTrue(client.followSslRedirects)
            assertFalse(client.retryOnConnectionFailure)
            assertEquals(5L * 1024L * 1024L, client.cache?.maxSize())
            assertEquals("aniyomi_host_http_cache", client.cache?.directory?.name)
            assertEquals("FOX.TV/7.4.2 (Aniyomi host)", factory.defaultUserAgentProvider())

            val platform = OkHttpClient()
            assertSame(platform.hostnameVerifier, client.hostnameVerifier)
            assertEquals(platform.connectionSpecs, client.connectionSpecs)
            assertEquals(platform.protocols, client.protocols)
        } finally {
            client.cache?.close()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun factoryUserAgentIsAppliedOnlyWhenRequestDoesNotProvideOne() = withServer { server ->
        server.enqueue(MockResponse().setBody("first"))
        server.enqueue(MockResponse().setBody("second"))
        val client = factory(AniyomiCookieJar(), "1.2.3").createClient()
        try {
            client.newCall(Request.Builder().url(server.url("/default")).build()).execute().use { response ->
                assertEquals(200, response.code)
            }
            client.newCall(
                Request.Builder().url(server.url("/explicit")).header("User-Agent", "Pinned-Test-UA").build(),
            ).execute().use { response -> assertEquals(200, response.code) }

            assertEquals("FOX.TV/1.2.3 (Aniyomi host)", server.takeRequest().getHeader("User-Agent"))
            assertEquals("Pinned-Test-UA", server.takeRequest().getHeader("User-Agent"))
        } finally {
            client.cache?.close()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun normalTlsRejectsSelfSignedEndpointAndNeverFallsBackWhileTestOnlyControlConnects() =
        withServer { server ->
            val certificate = HeldCertificate.Builder()
                .commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .build()
            val serverCertificates = HandshakeCertificates.Builder()
                .heldCertificate(certificate)
                .build()
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("reachable only by explicit test control"))

            val normalClient = factory(AniyomiCookieJar(), "tls-test").createClient()
            val normalFailure = try {
                assertThrows(IOException::class.java) {
                    normalClient.newCall(Request.Builder().url(server.url("/tls")).build()).execute().use { }
                }
            } finally {
                normalClient.cache?.close()
                normalClient.dispatcher.executorService.shutdownNow()
                normalClient.connectionPool.evictAll()
            }
            assertTrue(normalFailure.hasCause<SSLHandshakeException>())
            assertEquals(0, server.requestCount)

            val trustAllManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustAllManager), SecureRandom())
            }
            val explicitTestOnlyControl = OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllManager)
                .hostnameVerifier { _, _ -> true }
                .build()
            explicitTestOnlyControl.newCall(Request.Builder().url(server.url("/tls")).build()).execute().use { response ->
                assertEquals("reachable only by explicit test control", response.body.string())
            }
            explicitTestOnlyControl.dispatcher.executorService.shutdownNow()
            explicitTestOnlyControl.connectionPool.evictAll()
            assertEquals(1, server.requestCount)
        }

    @Test
    fun blankVersionUsesExplicitDevelopmentTokenRatherThanBlankUserAgent() {
        val factory = factory(AniyomiCookieJar(), "   ")
        assertEquals("FOX.TV/dev (Aniyomi host)", factory.defaultUserAgentProvider())
    }

    private fun factory(cookieJar: AniyomiCookieJar, version: String): AniyomiNetworkClientFactory =
        AniyomiNetworkClientFactory(
            applicationCacheDirectory = temporaryFolder.newFolder(),
            cookieJar = cookieJar,
            versionNameProvider = { version },
        )

    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        try {
            block(server)
        } finally {
            server.close()
        }
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
        generateSequence(this) { it.cause }.any { it is T }
}

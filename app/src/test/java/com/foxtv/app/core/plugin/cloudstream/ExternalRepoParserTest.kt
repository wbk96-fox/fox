package com.foxtv.app.core.plugin.cloudstream

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ExternalRepoParserTest {
    @Test
    fun `redirected repo and plugin list use their effective HTTPS URLs as relative bases`() =
        withTlsServer { server, trustedClient ->
            val finalRepoUrl = server.url("/published/catalog/repo.json")
            val finalPluginListUrl = server.url("/cdn/releases/plugins.json")
            val repositoryUrl = server.url("/cdn/source").toString()

            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", finalRepoUrl),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"name":"Test Repo","pluginLists":["../list-entry/plugins.json"]}""",
                ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .addHeader("Location", finalPluginListUrl),
            )
            server.enqueue(
                MockResponse().setBody(
                    """
                    [
                      {
                        "name":"Provider",
                        "internalName":"Provider",
                        "version":1,
                        "apiVersion":1,
                        "status":1,
                        "authors":["Author"],
                        "url":"../artifacts/Provider.cs3",
                        "fileSize":128,
                        "fileHash":"sha256-${"ab".repeat(32)}",
                        "language":"pl",
                        "repositoryUrl":"../source"
                      }
                    ]
                    """.trimIndent(),
                ),
            )

            val injectedClient = trustedClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(true)
                .build()
            val parser = parser(injectedClient)
            val parsed = runBlocking {
                parser.tryParse(server.url("/entry/repo.json").toString())
            }

            assertNotNull(parsed)
            requireNotNull(parsed)
            assertEquals("Test Repo", parsed.name)
            assertEquals(0, parsed.rejectedPluginCount)
            assertTrue(parsed.isComplete)
            assertTrue(parsed.isAuthoritativeForRemoval)
            assertEquals(
                server.url("/cdn/artifacts/Provider.cs3").toString(),
                parsed.plugins.single().url,
            )
            assertEquals(repositoryUrl, parsed.plugins.single().repositoryUrl)
            assertEquals("pl", parsed.plugins.single().language)
            assertEquals("/entry/repo.json", server.takeRequest().path)
            assertEquals("/published/catalog/repo.json", server.takeRequest().path)
            assertEquals("/published/list-entry/plugins.json", server.takeRequest().path)
            assertEquals("/cdn/releases/plugins.json", server.takeRequest().path)
        }

    @Test
    fun `rejects initial HTTP URL before creating a request`() {
        val server = MockWebServer()
        try {
            val parser = parser(OkHttpClient())
            val parsed = runBlocking {
                parser.tryParse(server.url("/repo.json").toString())
            }

            assertNull(parsed)
            assertEquals(0, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun `rejects HTTPS to HTTP redirect without requesting cleartext target`() =
        withTlsServer { httpsServer, trustedClient ->
            val httpServer = MockWebServer()
            try {
                httpsServer.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", httpServer.url("/repo.json")),
                )

                val parsed = runBlocking {
                    parser(trustedClient).tryParse(httpsServer.url("/repo.json").toString())
                }

                assertNull(parsed)
                assertEquals(1, httpsServer.requestCount)
                assertEquals(0, httpServer.requestCount)
            } finally {
                httpServer.close()
            }
        }

    @Test
    fun `rejects HTTP plugin list reference before request and marks result incomplete`() =
        withTlsServer { httpsServer, trustedClient ->
            val httpServer = MockWebServer()
            try {
                httpsServer.enqueue(
                    MockResponse().setBody(
                        """{"name":"Test Repo","pluginLists":["${httpServer.url("/plugins.json")}"]}""",
                    ),
                )

                val parsed = runBlocking {
                    parser(trustedClient).tryParse(httpsServer.url("/repo.json").toString())
                }

                assertNotNull(parsed)
                requireNotNull(parsed)
                assertFalse(parsed.isComplete)
                assertFalse(parsed.isAuthoritativeForRemoval)
                assertEquals(0, httpServer.requestCount)
            } finally {
                httpServer.close()
            }
        }

    @Test
    fun `exposes inactive names and active invalid state without authorizing stale cleanup`() =
        withTlsServer { server, trustedClient ->
            val repositoryUrl = server.url("/source").toString()
            server.enqueue(
                MockResponse().setBody(
                    """
                    [
                      {
                        "name":"Provider",
                        "internalName":"Provider",
                        "authors":["Author"],
                        "url":"Provider.cs3",
                        "fileSize":128,
                        "fileHash":"sha256-${"ab".repeat(32)}",
                        "repositoryUrl":"$repositoryUrl"
                      },
                      {
                        "name":"Retired",
                        "internalName":"Retired",
                        "status":0,
                        "url":"Retired.cs3"
                      },
                      {
                        "name":"Future",
                        "internalName":"Future",
                        "apiVersion":2,
                        "authors":["Author"],
                        "url":"Future.cs3",
                        "fileSize":128,
                        "fileHash":"sha256-${"cd".repeat(32)}",
                        "repositoryUrl":"$repositoryUrl"
                      }
                    ]
                    """.trimIndent(),
                ),
            )

            val parsed = runBlocking {
                parser(trustedClient).tryParse(server.url("/plugins.json").toString())
            }

            assertNotNull(parsed)
            requireNotNull(parsed)
            assertEquals(listOf("Provider"), parsed.plugins.map { it.internalName })
            assertEquals(setOf("Retired"), parsed.explicitlyInactiveInternalNames)
            assertTrue(parsed.hasActiveInvalidEntries)
            assertFalse(parsed.hasDuplicateAmbiguity)
            assertTrue(parsed.isComplete)
            assertFalse(parsed.isAuthoritativeForRemoval)
            assertEquals(2, parsed.rejectedPluginCount)
        }

    @Test
    fun `duplicate ambiguity blocks authoritative removal`() =
        withTlsServer { server, trustedClient ->
            val repositoryUrl = server.url("/source").toString()
            server.enqueue(
                MockResponse().setBody(
                    """
                    [
                      {
                        "name":"First",
                        "internalName":"Provider",
                        "authors":["Author"],
                        "url":"First.cs3",
                        "fileSize":128,
                        "fileHash":"sha256-${"ab".repeat(32)}",
                        "repositoryUrl":"$repositoryUrl"
                      },
                      {
                        "name":"Second",
                        "internalName":"Provider",
                        "authors":["Author"],
                        "url":"Second.cs3",
                        "fileSize":128,
                        "fileHash":"sha256-${"cd".repeat(32)}",
                        "repositoryUrl":"$repositoryUrl"
                      }
                    ]
                    """.trimIndent(),
                ),
            )

            val parsed = runBlocking {
                parser(trustedClient).tryParse(server.url("/plugins.json").toString())
            }

            assertNotNull(parsed)
            requireNotNull(parsed)
            assertEquals(listOf("First"), parsed.plugins.map { it.name })
            assertTrue(parsed.hasDuplicateAmbiguity)
            assertFalse(parsed.hasActiveInvalidEntries)
            assertFalse(parsed.isAuthoritativeForRemoval)
            assertEquals(1, parsed.rejectedPluginCount)
        }

    @Test
    fun `coroutine cancellation cancels the underlying HTTP call`() =
        withTlsServer { server, trustedClient ->
            val callFailed = CountDownLatch(1)
            val observedClient = trustedClient.newBuilder()
                .eventListener(
                    object : EventListener() {
                        override fun callFailed(call: Call, ioe: IOException) {
                            callFailed.countDown()
                        }
                    },
                )
                .build()
            server.enqueue(
                MockResponse()
                    .setHeadersDelay(30, TimeUnit.SECONDS)
                    .setBody("[]"),
            )

            runBlocking {
                val parseJob = launch(Dispatchers.Default) {
                    parser(observedClient).tryParse(server.url("/plugins.json").toString())
                }
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                withTimeout(5_000) {
                    parseJob.cancelAndJoin()
                }
                assertTrue(parseJob.isCancelled)
            }

            assertTrue("OkHttp call was not cancelled", callFailed.await(5, TimeUnit.SECONDS))
        }

    private fun parser(client: OkHttpClient): ExternalRepoParser = ExternalRepoParser(
        Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build(),
        client,
    )

    private fun withTlsServer(block: (MockWebServer, OkHttpClient) -> Unit) {
        val localhostCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(localhostCertificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(localhostCertificate.certificate)
            .build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        val client = OkHttpClient.Builder()
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager,
            )
            .build()

        try {
            block(server, client)
        } finally {
            server.close()
        }
    }
}

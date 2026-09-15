package com.foxtv.app.core.anime.extractors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AniNekoTransportTest {

    @Test
    fun `get snapshots redirected response and preserves configured client policy`() = withServer { server, client ->
        val finalUrl = server.url("/final/master.m3u8?session=fixture-session")
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", finalUrl),
        )
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8")
                .setBody("#EXTM3U\n#EXT-X-VERSION:3\n"),
        )

        val snapshot = OkHttpAniNekoTransport(client).get(
            Request.Builder()
                .url(server.url("/start?token=request-secret"))
                .header("Cookie", "session=cookie-secret")
                .build(),
        )
        awaitNoActiveCalls(client)

        assertEquals(200, snapshot.code)
        assertEquals(finalUrl, snapshot.finalUrl)
        assertEquals("application/vnd.apple.mpegurl; charset=utf-8", snapshot.contentType.toString())
        assertEquals("#EXTM3U\n#EXT-X-VERSION:3\n", snapshot.body)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `declared oversized body fails with typed query-free diagnostics`() = withServer { server, client ->
        val secretToken = "token-value-must-not-leak"
        val secretCookie = "cookie-value-must-not-leak"
        val secretAuthorization = "authorization-value-must-not-leak"
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody("x".repeat(33)),
        )
        val request = Request.Builder()
            .url(
                server.url("/manifest/master.m3u8?token=$secretToken")
                    .newBuilder()
                    .username("diagnostic-user")
                    .password("diagnostic-password")
                    .build(),
            )
            .header("Cookie", "session=$secretCookie")
            .header("Authorization", "Bearer $secretAuthorization")
            .build()

        val failure = runCatching {
            OkHttpAniNekoTransport(client, maxResponseBodyBytes = 32L).get(request)
        }.exceptionOrNull()
        awaitNoActiveCalls(client)

        assertTrue(failure is AniNekoTransportException.ResponseBodyTooLarge)
        val typed = failure as AniNekoTransportException.ResponseBodyTooLarge
        assertEquals(32L, typed.limitBytes)
        assertEquals(33L, typed.observedBytes)
        assertTrue(typed.safeUrl.endsWith("/manifest/master.m3u8"))
        listOf(
            secretToken,
            secretCookie,
            secretAuthorization,
            "diagnostic-user",
            "diagnostic-password",
        ).forEach { secret ->
            assertFalse("Diagnostic leaked $secret", typed.toString().contains(secret))
            assertFalse("Safe URL leaked $secret", typed.safeUrl.contains(secret))
        }
        assertFalse(typed.safeUrl.contains('?'))
    }

    @Test
    fun `network failure is typed and does not expose request or upstream secrets`() = runBlocking {
        val requestSecret = "request-secret-value"
        val upstreamSecret = "upstream-secret-value"
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor {
                    throw IOException("failure containing $upstreamSecret")
                },
            )
            .build()

        try {
            val request = Request.Builder()
                .url("https://fixture.invalid/embed/player?token=$requestSecret")
                .header("Cookie", "session=cookie-secret")
                .build()
            val failure = runCatching {
                OkHttpAniNekoTransport(client).get(request)
            }.exceptionOrNull()
            awaitNoActiveCalls(client)

            assertTrue(failure is AniNekoTransportException.Network)
            val typed = failure as AniNekoTransportException.Network
            assertEquals("https://fixture.invalid/embed/player", typed.safeUrl)
            assertFalse(typed.toString().contains(requestSecret))
            assertFalse(typed.toString().contains(upstreamSecret))
            assertFalse(typed.toString().contains("cookie-secret"))
        } finally {
            closeClient(client)
        }
    }

    @Test
    fun `cancellation during response body cancels call and produces no snapshot`() {
        val listener = CancellationListener()
        withServer(listener) { server, client ->
            server.enqueue(
                MockResponse()
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBodyDelay(30, TimeUnit.SECONDS)
                    .setBody("<html><body>late response</body></html>"),
            )
            val snapshotsAfterCancellation = AtomicInteger(0)

            coroutineScope {
                val requestJob = launch(Dispatchers.Default) {
                    OkHttpAniNekoTransport(client).get(
                        Request.Builder().url(server.url("/blocked?token=secret"))
                            .build(),
                    )
                    snapshotsAfterCancellation.incrementAndGet()
                }

                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertTrue("Response headers were not received", listener.headersReceived.await(5, TimeUnit.SECONDS))
                requestJob.cancelAndJoin()
                assertTrue("Canceled OkHttp call did not finish", listener.failed.await(5, TimeUnit.SECONDS))
                awaitNoActiveCalls(client)

                assertTrue(requestJob.isCancelled)
                assertTrue(listener.wasCanceled.get())
                assertEquals(0, client.dispatcher.runningCallsCount())
                assertEquals(0, snapshotsAfterCancellation.get())
            }
        }
    }

    /**
     * Property 1 boundary: for generated HTML and manifest bodies, a closed snapshot exists exactly
     * when the byte count does not exceed the named limit; every response body is closed.
     *
     * **Validates: Requirements 2.1, 2.3, 2.5**
     */
    @Test
    fun `seeded HTML and manifest sizes obey the named body limit and close every response`() = runBlocking {
        val bodies = ConcurrentLinkedQueue<TrackingResponseBody>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val size = requireNotNull(chain.request().url.queryParameter("size")).toInt()
                val knownLength = chain.request().url.queryParameter("known")!!.toBoolean()
                val mediaType = requireNotNull(chain.request().url.queryParameter("type")).toMediaType()
                val body = TrackingResponseBody(
                    bytes = ByteArray(size) { ('a'.code + (it % 26)).toByte() },
                    mediaType = mediaType,
                    knownLength = knownLength,
                )
                bodies += body
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", mediaType.toString())
                    .body(body)
                    .build()
            }
            .build()
        val transport = OkHttpAniNekoTransport(client, maxResponseBodyBytes = PROPERTY_LIMIT_BYTES)
        val cases = generatedBodyCases(PROPERTY_SEED)

        try {
            assertTrue("Generator must cover multiple boundary cases", cases.size >= 32)
            cases.forEachIndexed { index, case ->
                val request = Request.Builder()
                    .url(
                        "https://fixture.invalid/body/$index" +
                            "?size=${case.size}&known=${case.knownLength}&type=${case.contentType}",
                    )
                    .build()
                val outcome = runCatching { transport.get(request) }

                if (case.size.toLong() <= PROPERTY_LIMIT_BYTES) {
                    assertTrue("Expected accepted case $case", outcome.isSuccess)
                    assertEquals(case.size, outcome.getOrThrow().body.toByteArray().size)
                } else {
                    assertTrue(
                        "Expected typed limit failure for $case but got ${outcome.exceptionOrNull()}",
                        outcome.exceptionOrNull() is AniNekoTransportException.ResponseBodyTooLarge,
                    )
                }
            }
            awaitNoActiveCalls(client)

            assertEquals(cases.size, bodies.size)
            assertTrue("At least one generated response body remained open", bodies.all { it.closed.get() })
        } finally {
            closeClient(client)
        }
    }

    private class CancellationListener : EventListener() {
        val headersReceived = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val wasCanceled = AtomicBoolean(false)

        override fun responseHeadersEnd(call: Call, response: Response) {
            headersReceived.countDown()
        }

        override fun callFailed(call: Call, ioe: IOException) {
            wasCanceled.set(call.isCanceled())
            failed.countDown()
        }
    }

    private class TrackingResponseBody(
        bytes: ByteArray,
        private val mediaType: MediaType,
        private val knownLength: Boolean,
    ) : ResponseBody() {
        val closed = AtomicBoolean(false)
        private val byteCount = bytes.size.toLong()
        private val bufferedSource: BufferedSource = object : ForwardingSource(Buffer().write(bytes)) {
            override fun close() {
                closed.set(true)
                super.close()
            }
        }.buffer()

        override fun contentType(): MediaType = mediaType

        override fun contentLength(): Long = if (knownLength) byteCount else -1L

        override fun source(): BufferedSource = bufferedSource
    }

    private data class BodyCase(
        val size: Int,
        val knownLength: Boolean,
        val contentType: String,
    )

    private fun generatedBodyCases(seed: Long): List<BodyCase> {
        val random = Random(seed)
        val boundarySizes = listOf(
            0,
            1,
            PROPERTY_LIMIT_BYTES.toInt() - 1,
            PROPERTY_LIMIT_BYTES.toInt(),
            PROPERTY_LIMIT_BYTES.toInt() + 1,
            PROPERTY_LIMIT_BYTES.toInt() * 2,
        )
        val generatedSizes = List(34) { random.nextInt(PROPERTY_LIMIT_BYTES.toInt() * 2 + 1) }
        return (boundarySizes + generatedSizes).mapIndexed { index, size ->
            BodyCase(
                size = size,
                knownLength = index % 2 == 0,
                contentType = if (index % 2 == 0) {
                    "text/html; charset=utf-8"
                } else {
                    "application/vnd.apple.mpegurl; charset=utf-8"
                },
            )
        }
    }

    private fun withServer(
        listener: EventListener? = null,
        block: suspend (MockWebServer, OkHttpClient) -> Unit,
    ) = runBlocking {
        val server = MockWebServer()
        val client = OkHttpClient.Builder().apply {
            if (listener != null) eventListener(listener)
        }.build()

        try {
            block(server, client)
        } finally {
            server.close()
            closeClient(client)
        }
    }

    private suspend fun awaitNoActiveCalls(client: OkHttpClient) {
        withTimeout(5_000) {
            while (client.dispatcher.runningCallsCount() != 0) {
                yield()
            }
        }
    }

    private fun closeClient(client: OkHttpClient) {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        client.cache?.close()
    }

    private companion object {
        private const val PROPERTY_SEED = 1_592_639_215L
        private const val PROPERTY_LIMIT_BYTES = 256L
    }
}

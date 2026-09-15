package com.foxtv.app.core.anime.extractors

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Live-network AniNeko integration gate.
 *
 * This androidTest class is invisible to the standard JVM suite. It runs only when the
 * instrumentation argument `foxtvLiveAnime=true` is supplied; without consent it emits exactly one
 * `FOXTV_LIVE_ANIME_STATUS=NOT_RUN` marker and stops via an assumption, never silently passing.
 * With consent, every prerequisite failure is classified honestly:
 *  - BLOCKED (and the instrumentation fails) for missing network/DNS/TLS reachability,
 *  - FAIL (and the instrumentation fails) for an evaluable provider/medium contract violation,
 *  - PASS only after HLS classification plus a bounded manifest probe with the extractor's own
 *    headers and either an HLS MIME type or a `#EXTM3U` body signature.
 *
 * **Validates: Requirements 1.4, 2.4, 3.6**
 */
@RunWith(AndroidJUnit4::class)
class AniNekoLiveIntegrationTest {

    @Test
    fun liveAniNekoContract() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        if (!arguments.getBoolean("foxtvLiveAnime", false)) {
            println(
                "FOXTV_LIVE_ANIME_STATUS=NOT_RUN reason=foxtvLiveAnime-instrumentation-argument-absent",
            )
            assumeTrue(
                "foxtvLiveAnime=true is required to run the live anime gate",
                arguments.getBoolean("foxtvLiveAnime", false),
            )
            return@runBlocking
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        try {
            val extractor = AniNekoExtractor(client)
            val outcome = extractor.extractWithOutcome(listOf(LIVE_TITLE), LIVE_EPISODE, LIVE_CATEGORY)
            val streams = outcome.streams
            if (streams.isEmpty()) {
                val reason = outcome.failures.joinToString("|") { "${it.stage}:${it.javaClass.simpleName}" }
                println("FOXTV_LIVE_ANIME_STATUS=FAIL reason=zero-streams; typed-failures=$reason")
                fail("AniNeko live contract violated: no supported HLS stream was extracted")
                return@runBlocking
            }

            val candidate = streams.first()
            when (val manifestEvidence = probeManifestEvidence(client, candidate.streamUrl, candidate.headers)) {
                is ManifestEvidence.Reachable -> {
                    val accepted = manifestEvidence.isHlsMime || manifestEvidence.startsWithExtm3u
                    if (accepted) {
                        println(
                            "FOXTV_LIVE_ANIME_STATUS=PASS reason=hls-classified; " +
                                "mime-ok=${manifestEvidence.isHlsMime}; extm3u-ok=${manifestEvidence.startsWithExtm3u}",
                        )
                    } else {
                        println(
                            "FOXTV_LIVE_ANIME_STATUS=FAIL reason=manifest-without-hls-evidence; " +
                                "mime=${manifestEvidence.mimeType}",
                        )
                        fail("AniNeko live contract violated: manifest lacks HLS MIME and #EXTM3U evidence")
                    }
                }
                is ManifestEvidence.Blocked -> {
                    println("FOXTV_LIVE_ANIME_STATUS=BLOCKED reason=${manifestEvidence.reason}")
                    fail("AniNeko live gate blocked: ${manifestEvidence.reason}")
                }
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private suspend fun probeManifestEvidence(
        client: OkHttpClient,
        streamUrl: String,
        headers: Map<String, String>,
    ): ManifestEvidence = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(streamUrl)
            .get()
            .header("Range", "bytes=0-1024")
            .header("Accept", "*/*")
        headers.forEach { (key, value) ->
            if (!key.equals("Range", ignoreCase = true) && !key.equals("Accept", ignoreCase = true)) {
                requestBuilder.header(key, value)
            }
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val contentType: MediaType? = response.body.contentType()
                val bodyStart = response.body.source().readUtf8(MANIFEST_PROBE_BYTES)
                ManifestEvidence.Reachable(
                    mimeType = contentType?.toString(),
                    isHlsMime = contentType != null && HLS_MIME_TYPES.contains(
                        "${contentType.type}/${contentType.subtype}".lowercase(),
                    ),
                    startsWithExtm3u = bodyStart.lineSequence()
                        .map { it.trim() }
                        .firstOrNull { it.isNotEmpty() } == "#EXTM3U",
                )
            }
        } catch (e: SSLHandshakeException) {
            ManifestEvidence.Blocked("tls-handshake-failed")
        } catch (e: SSLException) {
            ManifestEvidence.Blocked("tls-failed")
        } catch (e: UnknownHostException) {
            ManifestEvidence.Blocked("dns-unresolved")
        } catch (e: SocketTimeoutException) {
            ManifestEvidence.Blocked("network-timeout")
        } catch (e: ConnectException) {
            ManifestEvidence.Blocked("connection-refused")
        } catch (e: IOException) {
            ManifestEvidence.Blocked("network-unreachable")
        }
    }

    private sealed interface ManifestEvidence {
        data class Reachable(
            val mimeType: String?,
            val isHlsMime: Boolean,
            val startsWithExtm3u: Boolean,
        ) : ManifestEvidence

        data class Blocked(val reason: String) : ManifestEvidence
    }

    private companion object {
        private const val LIVE_TITLE = "Solo Leveling"
        private const val LIVE_EPISODE = 1
        private const val LIVE_CATEGORY = "sub"
        private const val MANIFEST_PROBE_BYTES = 1024L
        private val HLS_MIME_TYPES = setOf(
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
        )
    }
}

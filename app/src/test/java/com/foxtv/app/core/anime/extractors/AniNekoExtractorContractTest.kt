package com.foxtv.app.core.anime.extractors

import com.foxtv.app.core.anime.model.AnimeStreamResult
import com.foxtv.app.core.anime.model.AnimeStreamTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Persistent AniNeko contract tests backed by the real [OkHttpAniNekoTransport] and a local
 * [MockWebServer]. Every request in this file terminates on localhost; a network interceptor
 * sentinel fails the test the moment any request leaves the fixture server, so public-provider
 * transport is observable as zero by construction.
 *
 * Property 1: Bug Condition - Deterministic Anime Gate and Supported AniNeko Media.
 * **Validates: Requirements 2.1, 2.3, 2.4, 2.5**
 *
 * Property 2: Preservation - providers, fallback, player metadata, and supply-chain boundary.
 * **Validates: Requirements 2.2, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9**
 */
class AniNekoExtractorContractTest {

    private fun FixtureHarness.watchUrl(slug: String, episode: Int = 1): HttpUrl =
        base.newBuilder()
            .addPathSegment("watch")
            .addPathSegment(slug)
            .addPathSegment("ep-$episode")
            .build()

    private fun FixtureHarness.seriesUrl(slug: String): HttpUrl =
        base.newBuilder()
            .addPathSegment("watch")
            .addPathSegment(slug)
            .build()

    private fun FixtureHarness.searchUrl(keyword: String): HttpUrl =
        base.newBuilder()
            .addPathSegment("browser")
            .addQueryParameter("keyword", keyword)
            .build()

    /** Mirrors the production slug derivation: lowercase, non-alphanumerics to dashes. */
    private fun slugOf(title: String): String =
        title.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-')

    @Test
    fun `fixture flow resolves search fallback relative iframe HLS with sub selection and headers`() =
        runBlocking {
            FixtureHarness().use { harness ->
                val title = "Search Fixture Title"
                val probe = harness.watchUrl(slugOf(title))
                val search = harness.searchUrl(title)
                val series = harness.seriesUrl("fixture-series")
                val watch = harness.watchUrl("fixture-series")
                val watchFinal = harness.base.newBuilder()
                    .addPathSegment("watch")
                    .addPathSegment("fixture-series")
                    .addPathSegment("ep-1-final")
                    .build()
                val iframe = harness.base.newBuilder()
                    .addPathSegment("watch")
                    .addPathSegment("fixture-series")
                    .addPathSegment("player-frame.html")
                    .build()
                val embedFinal = harness.base.newBuilder()
                    .addPathSegment("embed")
                    .addPathSegment("player-frame-final.html")
                    .build()
                val schemeEmbed = harness.base.newBuilder()
                    .addPathSegment("embed")
                    .addPathSegment("scheme-relative.html")
                    .build()
                val masterUrl = harness.base.newBuilder()
                    .addPathSegment("hls")
                    .addPathSegment("relative-master.m3u8")
                    .addQueryParameter("v", "1")
                    .build()
                val expectedTracks = harness.embedFixtureTracks(embedFinal)
                val expectedStream = AnimeStreamResult(
                    streamUrl = masterUrl.toString(),
                    serverName = "AniNeko",
                    category = "SUB",
                    quality = "1080p",
                    headers = harness.expectedPlaybackHeaders(),
                    tracks = expectedTracks,
                    audioLanguage = "jp",
                    subtitleLanguages = listOf("pl", "en"),
                    originalLanguage = "jp",
                )

                harness.router.expectMiss(probe)
                harness.router.serve(search) { harness.htmlResponse(harness.fixture("search-results.html")) }
                harness.router.serve(watch) { harness.redirect(watchFinal) }
                harness.router.serve(watchFinal) { harness.htmlResponse(harness.fixture("watch-relative-iframe.html")) }
                harness.router.serve(iframe) { harness.redirect(embedFinal) }
                harness.router.serve(embedFinal) { harness.htmlResponse(harness.fixture("embed-relative-hls.html")) }
                harness.router.serve(schemeEmbed) { harness.htmlResponse(harness.fixture("embed-html-without-media.html")) }

                val client = harness.client()
                val outcome = try {
                    harness.extractor(client).extractWithOutcome(listOf(title), 1, "sub")
                } finally {
                    closeClient(client)
                }

                val evidence = harness.evidence(
                    seed = PROPERTY_SEED,
                    name = "search-fallback-relative-iframe",
                    expected = "streams=[$expectedStream]; failures=[NoMatch]",
                    actualStreams = outcome.streams,
                    actualFailures = outcome.failures.map { it.javaClass.simpleName },
                )
                println(evidence)

                assertEquals(evidence, listOf(expectedStream), outcome.streams)
                assertEquals(
                    "$evidence\nThe scheme-relative embed without media must produce one typed NoMatch.",
                    listOf("NoMatch"),
                    outcome.failures.map { it.javaClass.simpleName }.distinct(),
                )
                assertEquals(
                    "$evidence\nNoMatch must point at the scheme-relative embed page.",
                    listOf(schemeEmbed.toString()),
                    outcome.failures.map { it.safeUrl },
                )
                assertEquals(
                    evidence,
                    listOf(probe, search, watch, watchFinal, iframe, embedFinal, schemeEmbed).map { it.toString() },
                    harness.router.requests.map { it.url },
                )
                harness.assertAllRequestsAreLocal(evidence)
                assertEquals(
                    "$evidence\nPath-evident HLS must never be fetched.",
                    0,
                    harness.router.requests.count { it.url == masterUrl.toString() },
                )
                assertEquals(evidence, title, harness.router.requests.first { it.keyword != null }.keyword)
                val snapshots = harness.router.requests.toList()
                // The probe carries the series URL of the slug it probes, not of the series
                // later selected by the search fallback.
                assertEquals(
                    evidence,
                    harness.seriesUrl(slugOf(title)).toString(),
                    snapshots[0].referer,
                )
                assertEquals(evidence, harness.base.toString(), snapshots[1].referer)
                assertEquals(evidence, series.toString(), snapshots[2].referer)
                assertEquals(evidence, watchFinal.toString(), snapshots[4].referer)
                assertEquals(evidence, watchFinal.toString(), snapshots[6].referer)
                harness.router.requests.forEach { snapshot ->
                    assertEquals(evidence, EXPECTED_USER_AGENT, snapshot.userAgent)
                }
                harness.assertPlaybackHeadersAreClean(expectedStream.headers, evidence)
            }
        }

    @Test
    fun `fixture flow selects dub via tab mapping and accepts direct HLS with query parameters`() =
        runBlocking {
            FixtureHarness().use { harness ->
                val title = "Search Fixture Title"
                val probe = harness.watchUrl(slugOf(title))
                val search = harness.searchUrl(title)
                val watch = harness.watchUrl("fixture-series")
                val watchFinal = harness.base.newBuilder()
                    .addPathSegment("watch")
                    .addPathSegment("fixture-series")
                    .addPathSegment("ep-1-final")
                    .build()
                val iframe = harness.base.newBuilder()
                    .addPathSegment("watch")
                    .addPathSegment("fixture-series")
                    .addPathSegment("player-frame.html")
                    .build()
                val embedFinal = harness.base.newBuilder()
                    .addPathSegment("embed")
                    .addPathSegment("player-frame-final.html")
                    .build()
                val masterUrl = harness.base.newBuilder()
                    .addPathSegment("hls")
                    .addPathSegment("relative-master.m3u8")
                    .addQueryParameter("v", "1")
                    .build()
                val dubDirect = harness.base.newBuilder()
                    .addPathSegment("hls")
                    .addPathSegment("dub-direct.m3u8")
                    .addQueryParameter("token", "dubfixture")
                    .build()

                harness.router.expectMiss(probe)
                harness.router.serve(search) { harness.htmlResponse(harness.fixture("search-results.html")) }
                harness.router.serve(watch) { harness.redirect(watchFinal) }
                harness.router.serve(watchFinal) { harness.htmlResponse(harness.fixture("watch-relative-iframe.html")) }
                harness.router.serve(iframe) { harness.redirect(embedFinal) }
                harness.router.serve(embedFinal) { harness.htmlResponse(harness.fixture("embed-relative-hls.html")) }

                val client = harness.client()
                val outcome = try {
                    harness.extractor(client).extractWithOutcome(listOf(title), 1, "dub")
                } finally {
                    closeClient(client)
                }

                val expectedStreams = listOf(
                    AnimeStreamResult(
                        streamUrl = masterUrl.toString(),
                        serverName = "AniNeko",
                        category = "DUB",
                        quality = "1080p",
                        headers = harness.expectedPlaybackHeaders(),
                        tracks = harness.embedFixtureTracks(embedFinal),
                        subtitleLanguages = listOf("pl", "en"),
                        originalLanguage = "jp",
                    ),
                    AnimeStreamResult(
                        streamUrl = dubDirect.toString(),
                        serverName = "AniNeko",
                        category = "DUB",
                        quality = "1080p",
                        headers = harness.expectedPlaybackHeaders(),
                        originalLanguage = "jp",
                    ),
                )
                val evidence = harness.evidence(
                    seed = PROPERTY_SEED,
                    name = "dub-direct-hls",
                    expected = "streams=$expectedStreams; failures=[]",
                    actualStreams = outcome.streams,
                    actualFailures = outcome.failures.map { it.javaClass.simpleName },
                )
                println(evidence)

                assertEquals(evidence, expectedStreams, outcome.streams)
                assertEquals(evidence, emptyList<String>(), outcome.failures.map { it.javaClass.simpleName })
                assertEquals(
                    evidence,
                    listOf(probe, search, watch, watchFinal, iframe, embedFinal).map { it.toString() },
                    harness.router.requests.map { it.url },
                )
                harness.assertAllRequestsAreLocal(evidence)
                assertEquals(
                    "$evidence\nDub category is served from tab mapping without fetching the direct HLS.",
                    0,
                    harness.router.requests.count { it.url == dubDirect.toString() },
                )
                harness.router.requests.forEach { snapshot ->
                    assertEquals(evidence, EXPECTED_USER_AGENT, snapshot.userAgent)
                }
                outcome.streams.forEach { harness.assertPlaybackHeadersAreClean(it.headers, evidence) }
            }
        }

    @Test
    fun `bare HTML embed without HLS is rejected with typed NoMatch`() = runBlocking {
        FixtureHarness().use { harness ->
            val title = "Bare HTML"
            val watch = harness.watchUrl(slugOf(title))
            val embed = harness.base.newBuilder()
                .addPathSegment("embed")
                .addPathSegment("bare-embed.html")
                .build()
            harness.router.serve(watch) { harness.htmlResponse(watchPageWithVideo(embed.toString())) }
            harness.router.serve(embed) { harness.htmlResponse(harness.fixture("embed-html-without-media.html")) }

            val client = harness.client()
            val outcome = try {
                harness.extractor(client).extractWithOutcome(listOf(title), 1, "sub")
            } finally {
                closeClient(client)
            }

            val evidence = harness.evidence(
                seed = PROPERTY_SEED,
                name = "bare-html-embed",
                expected = "streams=[]; publicNetworkTransportRequests=0; unexpectedRequests=[]",
                actualStreams = outcome.streams,
                actualFailures = outcome.failures.map { it.javaClass.simpleName },
            )
            println(evidence)

            assertEquals(evidence, 0, outcome.streams.size)
            assertEquals(
                "$evidence\nBare HTTP(S) HTML embed is not supported HLS evidence and must not be emitted.",
                listOf("NoMatch"),
                outcome.failures.map { it.javaClass.simpleName }.distinct(),
            )
            assertEquals(
                evidence,
                listOf(watch, watch, embed).map { it.toString() },
                harness.router.requests.map { it.url },
            )
            harness.assertAllRequestsAreLocal(evidence)
            harness.router.requests.forEach { snapshot ->
                assertEquals(evidence, EXPECTED_USER_AGENT, snapshot.userAgent)
            }
        }
    }

    @Test
    fun `extensionless HLS accepted only with HLS MIME and EXTM3U evidence via GET probe`() = runBlocking {
        FixtureHarness().use { harness ->
            val title = "Fixture Series"
            val watch = harness.watchUrl(slugOf(title))
            val embed = harness.base.newBuilder()
                .addPathSegment("embed")
                .addPathSegment("d-ext.html")
                .build()
            // The embed page quotes a URL whose path has no .m3u8 extension; production must
            // probe it with GET and accept only on HLS MIME + "#EXTM3U" body evidence.
            val manifestUrl = harness.base.newBuilder()
                .addPathSegment("manifest")
                .addQueryParameter("src", harness.base.newBuilder()
                    .addPathSegment("hls")
                    .addPathSegment("plain.m3u8")
                    .build()
                    .toString())
                .build()
            harness.router.serve(watch) {
                harness.htmlResponse(watchPageWithVideo(embed.toString()))
            }
            harness.router.serve(embed) {
                harness.htmlResponse(
                    "<html><body><script>const src = '${manifestUrl}';</script></body></html>",
                )
            }
            harness.router.serve(manifestUrl) {
                MockResponse()
                    .addHeader("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8")
                    .setBody("#EXTM3U\n#EXT-X-VERSION:3\n")
            }

            val client = harness.client()
            val outcome = try {
                harness.extractor(client).extractWithOutcome(listOf(title), 1, "sub")
            } finally {
                closeClient(client)
            }

            val expectedStream = AnimeStreamResult(
                streamUrl = manifestUrl.toString(),
                serverName = "AniNeko",
                category = "SUB",
                quality = "1080p",
                headers = harness.expectedPlaybackHeaders(),
                audioLanguage = "jp",
                originalLanguage = "jp",
            )
            val evidence = harness.evidence(
                seed = PROPERTY_SEED,
                name = "extensionless-hls-mime-and-extm3u",
                expected = "streams=[$expectedStream]",
                actualStreams = outcome.streams,
                actualFailures = outcome.failures.map { it.javaClass.simpleName },
            )
            println(evidence)

            assertEquals(evidence, listOf(expectedStream), outcome.streams)
            assertEquals(evidence, emptyList<String>(), outcome.failures.map { it.javaClass.simpleName })
            assertEquals(
                evidence,
                listOf(watch, watch, embed, manifestUrl).map { it.toString() },
                harness.router.requests.map { it.url },
            )
            val manifestRequests = harness.router.requests.filter { it.url == manifestUrl.toString() }
            assertEquals("$evidence\nExactly one GET manifest probe is allowed.", 1, manifestRequests.size)
            assertEquals("$evidence\nManifest evidence must be probed with GET.", "GET", manifestRequests.single().method)
            assertEquals("$evidence\nManifest probe must use the final embed URL as Referer.", embed.toString(), manifestRequests.single().referer)
            harness.assertAllRequestsAreLocal(evidence)
            harness.router.requests.forEach { snapshot ->
                assertEquals(evidence, EXPECTED_USER_AGENT, snapshot.userAgent)
            }
            harness.assertPlaybackHeadersAreClean(expectedStream.headers, evidence)
        }
    }

    @Test
    fun `seeded unsupported media table satisfies Property 1`() = runBlocking {
        val cases = generatedRejectionCases(PROPERTY_SEED)
        val violations = mutableListOf<String>()
        assertTrue("Property 1 generator must exercise many cases", cases.size >= 12)

        for (case in cases) {
            FixtureHarness().use { harness ->
                val watch = harness.watchUrl(slugOf(case.title))
                val candidate = case.candidateTemplate
                    .replace("__BASE__", harness.base.toString())
                    .replace("__HOST__", harness.hostPort)
                    .replace("__TOK__", case.token)
                harness.router.servePrefix("/watch/") {
                    harness.htmlResponse(watchPageWithVideo(candidate))
                }
                for (route in case.routes) {
                    harness.router.servePrefix(route.pathPrefix) {
                        when (route.kind) {
                            RouteResponseKind.PLAIN_HTML_NO_MEDIA -> harness.htmlResponse(
                                "<html><body>not a manifest</body></html>",
                            )
                            RouteResponseKind.EMBED_NO_MEDIA -> harness.htmlResponse(
                                harness.fixture("embed-html-without-media.html"),
                            )
                            RouteResponseKind.HLS_MIME_HTML_BODY -> MockResponse()
                                .addHeader("Content-Type", "application/vnd.apple.mpegurl")
                                .setBody("<html><body><video data-state='not-ready'></video></body></html>")
                            RouteResponseKind.HTTP_500 -> MockResponse().setResponseCode(500)
                            RouteResponseKind.TRICKY_QUERY_EMBED -> harness.htmlResponse(
                                "<html><body><script>const src = '${harness.base}player/${case.token}" +
                                    "?src=${harness.base}hls/${case.token}.m3u8';</script></body></html>",
                            )
                            RouteResponseKind.TRICKY_FRAGMENT_EMBED -> harness.htmlResponse(
                                "<html><body><script>const src = '${harness.base}player/${case.token}" +
                                    "#${case.token}.m3u8';</script></body></html>",
                            )
                            RouteResponseKind.TRICKY_MANIFEST_EMBED -> harness.htmlResponse(
                                "<html><body><script>const src = '${harness.base}manifest" +
                                    "?src=${harness.base}hls/${case.token}.m3u8';</script></body></html>",
                            )
                            RouteResponseKind.NONE -> MockResponse().setResponseCode(404)
                        }
                    }
                }

                val client = harness.client()
                val outcome = try {
                    harness.extractor(client).extractWithOutcome(listOf(case.title), 1, "sub")
                } finally {
                    closeClient(client)
                }

                val evidence = harness.evidence(
                    seed = PROPERTY_SEED,
                    name = case.name,
                    input = "candidate=$candidate; candidateContentType=case-scoped",
                    expected = "streams=[]; failures=${case.expectedFailureKinds}; " +
                        "requests=${case.expectedRequestCount}; unexpectedRequests=[]; " +
                        "publicNetworkTransportRequests=0",
                    actualStreams = outcome.streams,
                    actualFailures = outcome.failures.map { it.javaClass.simpleName },
                )
                println(evidence)

                val actualFailureKinds = outcome.failures.map { it.javaClass.simpleName }.distinct().toSet()
                if (
                    outcome.streams.isNotEmpty() ||
                    actualFailureKinds != case.expectedFailureKinds ||
                    harness.router.unexpectedRequests.isNotEmpty() ||
                    harness.router.requests.size != case.expectedRequestCount ||
                    harness.router.publicNetworkTransportRequests.get() != 0 ||
                    harness.router.requests.any { !it.url.startsWith(harness.base.toString()) }
                ) {
                    violations += "$evidence; actualRequests=${harness.router.requests.map { it.url }}; " +
                        "actualUnexpected=${harness.router.unexpectedRequests}"
                }
            }
        }

        assertTrue(
            "Property 1 rejection violations (seed=$PROPERTY_SEED):\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `cancellation during blocked response leaves no active calls or late emissions`() = runBlocking {
        FixtureHarness().use { harness ->
            val watch = harness.watchUrl("fixture-series")
            harness.router.servePrefix("/watch/") {
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }

            val listener = CancellationListener()
            val client = harness.client(listener)
            try {
                val extractor = harness.extractor(client)
                val collected = AtomicInteger(0)
                val evidence = "PROPERTY1_CANCELLATION seed=$PROPERTY_SEED; " +
                    "input=collectorCancelledDuringBlockedResponse; " +
                    "expected=callCancelled=true,activeCalls=0,collected=0,publicNetworkTransportRequests=0"

                val job = launch {
                    extractor.extractWithOutcome(listOf("Fixture Series"), 1, "sub")
                    collected.incrementAndGet()
                }

                val sent = withContext(Dispatchers.IO) {
                    harness.server.takeRequest(5, TimeUnit.SECONDS)
                }
                assertTrue("$evidence\nRequest must reach the server before cancellation", sent != null)

                job.cancelAndJoin()

                withTimeout(5_000) {
                    while (client.dispatcher.runningCallsCount() != 0) {
                        yield()
                    }
                }
                assertTrue("$evidence\nCancellation must abort the underlying call", listener.wasCanceled.get())
                assertEquals("$evidence\nNo emission may happen after cancellation", 0, collected.get())
                assertEquals(
                    "$evidence\nNo transport may leave the fixture server",
                    0,
                    harness.router.publicNetworkTransportRequests.get(),
                )
                harness.assertAllRequestsAreLocal(evidence)
                println("$evidence; actual=callCancelled=${listener.wasCanceled.get()},collected=${collected.get()}")
            } finally {
                closeClient(client)
            }
        }
    }

    @Test
    fun `property2PreservesSupportedHlsResults`() = runBlocking {
        val inputs = generatedPreservationSpecs(PRESERVATION_SEED)
        val violations = mutableListOf<String>()
        assertTrue("Preservation generator must exercise many inputs", inputs.size >= 12)

        for (spec in inputs) {
            FixtureHarness().use { harness ->
                val materialized = harness.materialize(spec)
                materialized.routes(harness)

                val client = harness.client()
                val outcome = try {
                    harness.extractor(client).extractWithOutcome(listOf(spec.title), 1, spec.category)
                } finally {
                    closeClient(client)
                }

                val evidence = harness.evidence(
                    seed = PRESERVATION_SEED,
                    name = spec.name,
                    input = "mode=${spec.kind}; category=${spec.category}; duplicates=${spec.duplicateCount}",
                    expected = "streams=${materialized.expectedStreams}; requests=${materialized.expectedRequests}",
                    actualStreams = outcome.streams,
                    actualFailures = outcome.failures.map { it.javaClass.simpleName },
                )
                println(evidence)

                if (
                    outcome.streams != materialized.expectedStreams ||
                    harness.router.requests.map { it.url } != materialized.expectedRequests ||
                    harness.router.unexpectedRequests.isNotEmpty() ||
                    harness.router.publicNetworkTransportRequests.get() != 0 ||
                    !harness.router.requests.all { it.url.startsWith(harness.base.toString()) } ||
                    !harness.router.requests.all { it.userAgent == EXPECTED_USER_AGENT } ||
                    outcome.streams.any { stream ->
                        stream.headers.values.any { it.contains('\r') || it.contains('\n') }
                    }
                ) {
                    violations += "$evidence; actualRequests=${harness.router.requests.map { it.url }}; " +
                        "actualUnexpected=${harness.router.unexpectedRequests}"
                }
            }
        }

        assertTrue(
            "Property 2 preservation violations (seed=$PRESERVATION_SEED):\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    private class MaterializedPreservation(
        val expectedStreams: List<AnimeStreamResult>,
        val expectedRequests: List<String>,
        val routes: (FixtureHarness) -> Unit,
    )

    private fun FixtureHarness.materialize(spec: PreservationSpec): MaterializedPreservation {
        val watch = watchUrl(slugOf(spec.title))
        val buttonLabel = if (spec.category.equals("dub", ignoreCase = true)) "DUB" else "SUB"
        return when (spec.kind) {
            "direct-hls" -> {
                val hlsUrl = base.newBuilder()
                    .addPathSegment("hls")
                    .addPathSegment("gen-${spec.token}.m3u8")
                    .addQueryParameter("token", spec.token)
                    .addQueryParameter("variant", spec.variant)
                    .build()
                val page = watchPageWithVideo(
                    candidate = hlsUrl.toString(),
                    buttonLabel = buttonLabel,
                    duplicates = spec.duplicateCount,
                )
                MaterializedPreservation(
                    expectedStreams = listOf(
                        AnimeStreamResult(
                            streamUrl = hlsUrl.toString(),
                            serverName = "AniNeko",
                            category = spec.category.uppercase(),
                            quality = "1080p",
                            headers = expectedPlaybackHeaders(),
                            audioLanguage = if (spec.category.equals("dub", ignoreCase = true)) null else "jp",
                            originalLanguage = "jp",
                        ),
                    ),
                    expectedRequests = listOf(watch.toString(), watch.toString()),
                    routes = { h -> h.router.serve(watch) { h.htmlResponse(page) } },
                )
            }
            "direct-slug" -> {
                val hlsUrl = base.newBuilder()
                    .addPathSegment("hls")
                    .addPathSegment("marker-${spec.token}.m3u8")
                    .build()
                val page = """
                    <html><body>
                      <div class="nv-watch-page"></div>
                      <button class="server-video" data-tab="sub" data-video="${hlsUrl}">$buttonLabel</button>
                    </body></html>
                """.trimIndent()
                MaterializedPreservation(
                    expectedStreams = listOf(
                        AnimeStreamResult(
                            streamUrl = hlsUrl.toString(),
                            serverName = "AniNeko",
                            category = spec.category.uppercase(),
                            quality = "1080p",
                            headers = expectedPlaybackHeaders(),
                            audioLanguage = if (spec.category.equals("dub", ignoreCase = true)) null else "jp",
                            originalLanguage = "jp",
                        ),
                    ),
                    expectedRequests = listOf(watch.toString(), watch.toString()),
                    routes = { h -> h.router.serve(watch) { h.htmlResponse(page) } },
                )
            }
            else -> materializeEmbedWithTracks(spec)
        }
    }

    private fun FixtureHarness.materializeEmbedWithTracks(spec: PreservationSpec): MaterializedPreservation {
        val watch = watchUrl(slugOf(spec.title))
        val buttonLabel = if (spec.category.equals("dub", ignoreCase = true)) "DUB" else "SUB"
        val embed = base.newBuilder()
            .addPathSegment("embed")
            .addPathSegment("gen-${spec.token}.html")
            .build()
        val embedFinal = base.newBuilder()
            .addPathSegment("embed")
            .addPathSegment("gen-${spec.token}-final.html")
            .build()
        val hlsUrl = base.newBuilder()
            .addPathSegment("hls")
            .addPathSegment("gen-${spec.token}.m3u8")
            .addQueryParameter("token", spec.token)
            .addQueryParameter("variant", spec.variant)
            .build()
        val entityTrack = "https://subs.example/anime/${spec.token}/pl-${spec.token}.vtt?token=${spec.token}&amp;format=vtt"
        val schemeTrack = "//$hostPort/subs/${spec.token}-scheme.vtt"
        val relativeTrack = "../subs/${spec.token}-rel.vtt"
        val embedBody = """
            <html><body>
              <script>const src = '${hlsUrl}';</script>
              <track src="$entityTrack" label="Polski ${spec.token}" srclang="pl" default>
              <track src="$schemeTrack" label="Schemat ${spec.token}" srclang="en">
              <track src="$relativeTrack" label="Wzgledny ${spec.token}" srclang="pl">
              <track src="${base}subs/${spec.token}-thumb.vtt" label="Miniatury" kind="thumbnails">
            </body></html>
        """.trimIndent()
        val page = watchPageWithVideo(embed.toString(), buttonLabel = buttonLabel)
        val expectedTracks = listOf(
            AnimeStreamTrack(
                url = entityTrack.replace("&amp;", "&"),
                label = "Polski ${spec.token}",
                lang = "pl",
            ),
            AnimeStreamTrack(
                url = "http://$hostPort/subs/${spec.token}-scheme.vtt",
                label = "Schemat ${spec.token}",
                lang = "en",
            ),
            AnimeStreamTrack(
                url = embedFinal.resolve(relativeTrack)!!.toString(),
                label = "Wzgledny ${spec.token}",
                lang = "pl",
            ),
        )
        return MaterializedPreservation(
            expectedStreams = listOf(
                AnimeStreamResult(
                    streamUrl = hlsUrl.toString(),
                    serverName = "AniNeko",
                    category = spec.category.uppercase(),
                    quality = "1080p",
                    headers = expectedPlaybackHeaders(),
                    tracks = expectedTracks,
                    audioLanguage = if (spec.category.equals("dub", ignoreCase = true)) null else "jp",
                    subtitleLanguages = listOf("pl", "en"),
                    originalLanguage = "jp",
                ),
            ),
            expectedRequests = listOf(
                watch.toString(),
                watch.toString(),
                embed.toString(),
                embedFinal.toString(),
            ),
            routes = { h ->
                h.router.serve(watch) { h.htmlResponse(page) }
                h.router.serve(embed) { h.redirect(embedFinal) }
                h.router.serve(embedFinal) { h.htmlResponse(embedBody) }
            },
        )
    }

    private enum class RouteResponseKind {
        PLAIN_HTML_NO_MEDIA,
        EMBED_NO_MEDIA,
        HLS_MIME_HTML_BODY,
        HTTP_500,
        TRICKY_QUERY_EMBED,
        TRICKY_FRAGMENT_EMBED,
        TRICKY_MANIFEST_EMBED,
        NONE,
    }

    private data class RejectionRoute(
        val pathPrefix: String,
        val kind: RouteResponseKind,
    )

    private data class RejectionCaseSpec(
        val name: String,
        val title: String,
        val candidateTemplate: String,
        val routes: List<RejectionRoute>,
        val expectedFailureKinds: Set<String>,
        val expectedRequestCount: Int,
        val token: String = "",
    )

    private data class PreservationSpec(
        val name: String,
        val kind: String,
        val category: String,
        val title: String,
        val token: String,
        val variant: String,
        val duplicateCount: Int,
    )

    private fun generatedRejectionCases(seed: Long): List<RejectionCaseSpec> {
        val random = Random(seed)
        return (0 until 2).flatMap { round ->
            listOf(
                RejectionCaseSpec(
                    name = "m3u8-query-only-direct-r$round",
                    title = "Reject Q$round",
                    candidateTemplate = "__BASE__player/q$round-__TOK__?target=__BASE__hls/__TOK__.m3u8",
                    routes = listOf(RejectionRoute("/player/", RouteResponseKind.PLAIN_HTML_NO_MEDIA)),
                    expectedFailureKinds = setOf("NoMatch"),
                    expectedRequestCount = 3,
                ),
                RejectionCaseSpec(
                    name = "m3u8-query-only-embed-r$round",
                    title = "Reject QE$round",
                    candidateTemplate = "__BASE__embed/q$round-__TOK__.html",
                    routes = listOf(
                        RejectionRoute("/embed/", RouteResponseKind.TRICKY_QUERY_EMBED),
                        RejectionRoute("/player/", RouteResponseKind.PLAIN_HTML_NO_MEDIA),
                    ),
                    expectedFailureKinds = setOf("UnsupportedMedia"),
                    expectedRequestCount = 4,
                ),
                RejectionCaseSpec(
                    name = "m3u8-fragment-only-direct-r$round",
                    title = "Reject F$round",
                    candidateTemplate = "__BASE__player/f$round-__TOK__#__TOK__.m3u8",
                    routes = listOf(RejectionRoute("/player/", RouteResponseKind.PLAIN_HTML_NO_MEDIA)),
                    expectedFailureKinds = setOf("NoMatch"),
                    expectedRequestCount = 3,
                ),
                RejectionCaseSpec(
                    name = "m3u8-fragment-only-embed-r$round",
                    title = "Reject FE$round",
                    candidateTemplate = "__BASE__embed/fe$round-__TOK__.html",
                    routes = listOf(
                        RejectionRoute("/embed/", RouteResponseKind.TRICKY_FRAGMENT_EMBED),
                        RejectionRoute("/player/", RouteResponseKind.PLAIN_HTML_NO_MEDIA),
                    ),
                    expectedFailureKinds = setOf("UnsupportedMedia"),
                    expectedRequestCount = 4,
                ),
                RejectionCaseSpec(
                    name = "html-embed-no-media-r$round",
                    title = "Reject H$round",
                    candidateTemplate = "__BASE__embed/h$round-__TOK__.html",
                    routes = listOf(RejectionRoute("/embed/", RouteResponseKind.EMBED_NO_MEDIA)),
                    expectedFailureKinds = setOf("NoMatch"),
                    expectedRequestCount = 3,
                ),
                RejectionCaseSpec(
                    name = "malformed-url-r$round",
                    title = "Reject M$round",
                    candidateTemplate = "http://__TOK__-mal formed/x",
                    routes = emptyList(),
                    expectedFailureKinds = setOf("InvalidUrl"),
                    expectedRequestCount = 2,
                ),
                RejectionCaseSpec(
                    name = "non-http-scheme-r$round",
                    title = "Reject S$round",
                    candidateTemplate = "ftp://192.0.2.123/__TOK__/master.m3u8",
                    routes = emptyList(),
                    expectedFailureKinds = setOf("InvalidUrl"),
                    expectedRequestCount = 2,
                ),
                RejectionCaseSpec(
                    name = "hls-mime-without-extm3u-r$round",
                    title = "Reject W$round",
                    candidateTemplate = "__BASE__embed/w$round-__TOK__.html",
                    routes = listOf(
                        RejectionRoute("/embed/", RouteResponseKind.TRICKY_MANIFEST_EMBED),
                        RejectionRoute("/manifest", RouteResponseKind.HLS_MIME_HTML_BODY),
                    ),
                    expectedFailureKinds = setOf("UnsupportedMedia"),
                    expectedRequestCount = 4,
                ),
                RejectionCaseSpec(
                    name = "http-500-r$round",
                    title = "Reject E$round",
                    candidateTemplate = "__BASE__hls/e$round-__TOK__",
                    routes = listOf(RejectionRoute("/hls/", RouteResponseKind.HTTP_500)),
                    expectedFailureKinds = setOf("HttpStatus"),
                    expectedRequestCount = 3,
                ),
            )
        }.map { spec -> spec.copy(token = random.nextInt(1_000_000).toString().padStart(6, '0')) }
    }

    private fun generatedPreservationSpecs(seed: Long): List<PreservationSpec> {
        val random = Random(seed)
        return (0 until 4).flatMap { round ->
            listOf("direct-hls", "embed-with-tracks", "direct-slug").mapIndexed { index, kind ->
                val token = "${random.nextInt(1_000_000).toString().padStart(6, '0')}r$round"
                PreservationSpec(
                    name = "$kind-r$round",
                    kind = kind,
                    category = if ((round + index) % 2 == 0) "sub" else "dub",
                    title = if (kind == "direct-slug") "Marker $token" else "Preservation $token",
                    token = token,
                    variant = random.nextInt(9).toString(),
                    duplicateCount = 2 + random.nextInt(3),
                )
            }
        }
    }

    private data class RecordedRequestSnapshot(
        val url: String,
        val method: String?,
        val userAgent: String?,
        val referer: String?,
        val keyword: String?,
    )

    private class FixtureRouter(
        private val baseProvider: () -> HttpUrl,
    ) : Dispatcher() {
        val requests = ConcurrentLinkedQueue<RecordedRequestSnapshot>()
        val unexpectedRequests = ConcurrentLinkedQueue<String>()
        val publicNetworkTransportRequests = AtomicInteger(0)

        private val exactRoutes = java.util.concurrent.ConcurrentHashMap<String, () -> MockResponse>()
        private val pathRoutes = java.util.concurrent.CopyOnWriteArrayList<Pair<String, () -> MockResponse>>()
        private val expectedMisses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun serve(url: HttpUrl, response: () -> MockResponse) {
            exactRoutes[url.toString()] = response
        }

        fun servePrefix(path: String, response: () -> MockResponse) {
            pathRoutes += path to response
        }

        fun expectMiss(url: HttpUrl) {
            expectedMisses += url.toString()
        }

        override fun dispatch(request: RecordedRequest): MockResponse {
            // The legacy mockwebserver API exposes no URL; reconstruct it from the wire path.
            val wirePath = requireNotNull(request.path) { "fixture request without a path" }
            val url = baseProvider().resolve(wirePath)
                ?: error("Unresolvable fixture request path: $wirePath")
            requests += RecordedRequestSnapshot(
                url = url.toString(),
                method = request.method,
                userAgent = request.getHeader("User-Agent"),
                referer = request.getHeader("Referer"),
                keyword = url.queryParameter("keyword"),
            )
            exactRoutes[url.toString()]?.let { return it() }
            pathRoutes
                .firstOrNull { (prefix) -> url.encodedPath.startsWith(prefix) }
                ?.let { return it.second() }
            if (url.toString() in expectedMisses) {
                return MockResponse().setResponseCode(404)
            }
            unexpectedRequests += url.toString()
            return MockResponse().setResponseCode(404).setBody("unexpected fixture request")
        }
    }

    private class FixtureHarness : AutoCloseable {
        val server = MockWebServer()
        val router = FixtureRouter { server.url("/") }
        val hostPort: String
            get() = "${server.hostName}:${server.port}"
        val base: HttpUrl
            get() = server.url("/")

        init {
            server.dispatcher = router
            server.start()
        }

        fun fixture(name: String): String =
            javaClass.classLoader
                ?.getResourceAsStream("anineko/$name")
                ?.readBytes()
                ?.toString(Charsets.UTF_8)
                ?.replace("__BASE__", base.toString())
                ?.replace("__HOST__", hostPort)
                ?: error("Missing anineko fixture resource: anineko/$name")

        fun client(eventListener: EventListener? = null): OkHttpClient =
            OkHttpClient.Builder()
                .apply { if (eventListener != null) eventListener(eventListener) }
                .addNetworkInterceptor { chain ->
                    val url = chain.request().url
                    if (url.host != server.hostName || url.port != server.port) {
                        router.publicNetworkTransportRequests.incrementAndGet()
                        throw AssertionError("Unexpected public network transport for $url")
                    }
                    chain.proceed(chain.request())
                }
                .build()

        fun extractor(client: OkHttpClient): AniNekoExtractor =
            AniNekoExtractor(OkHttpAniNekoTransport(client), base)

        fun htmlResponse(body: String): MockResponse =
            MockResponse()
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(body)

        fun redirect(target: HttpUrl): MockResponse =
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", target.toString())

        fun expectedPlaybackHeaders(): Map<String, String> = mapOf(
            "User-Agent" to EXPECTED_USER_AGENT,
            "Referer" to base.toString(),
            "Origin" to "${base.scheme}://${server.hostName}",
        )

        fun embedFixtureTracks(embedFinal: HttpUrl): List<AnimeStreamTrack> = listOf(
            AnimeStreamTrack(
                url = embedFinal.resolve("subs/pl.vtt")!!.toString(),
                label = "Polski",
                lang = "pl",
            ),
            AnimeStreamTrack(
                url = embedFinal.resolve("subs/pl.vtt")!!.toString(),
                label = "Polski duplikat",
                lang = "pl",
            ),
            AnimeStreamTrack(
                url = embedFinal.resolve("subs/en.vtt")!!.toString(),
                label = "English",
                lang = "en",
            ),
            AnimeStreamTrack(
                url = "${embedFinal.scheme}://$hostPort/subs/scheme-relative.vtt",
                label = "Scheme relative",
                lang = "en",
            ),
        )

        override fun close() {
            server.close()
        }
    }

    private fun FixtureHarness.evidence(
        seed: Long,
        name: String,
        expected: String,
        actualStreams: List<AnimeStreamResult>,
        actualFailures: List<String>,
        input: String = "",
    ): String = "PROPERTY seed=$seed; name=$name; input=$input; expected=$expected; " +
        "actualStreams=$actualStreams; actualFailures=$actualFailures; " +
        "requests=${router.requests.map { it.url }}; unexpected=${router.unexpectedRequests}; " +
        "publicNetworkTransportRequests=${router.publicNetworkTransportRequests.get()}"

    private fun FixtureHarness.assertAllRequestsAreLocal(evidence: String) {
        assertTrue(
            "$evidence\nAll requests must terminate on the fixture server",
            router.requests.all { it.url.startsWith(base.toString()) },
        )
        assertEquals(
            "$evidence\nPublic network transport must be zero",
            0,
            router.publicNetworkTransportRequests.get(),
        )
        assertTrue(
            "$evidence\nNo unexpected route may be hit",
            router.unexpectedRequests.isEmpty(),
        )
    }

    private fun FixtureHarness.assertPlaybackHeadersAreClean(headers: Map<String, String>, evidence: String) {
        assertEquals("$evidence\nUser-Agent must be exact", EXPECTED_USER_AGENT, headers["User-Agent"])
        assertEquals("$evidence\nReferer must be the canonical base", base.toString(), headers["Referer"])
        assertEquals(
            "$evidence\nOrigin must be scheme://host",
            "${base.scheme}://${server.hostName}",
            headers["Origin"],
        )
        assertTrue(
            "$evidence\nPlayback headers must not contain CR/LF",
            headers.values.none { it.contains('\r') || it.contains('\n') },
        )
    }

    private fun watchPageWithVideo(
        candidate: String,
        buttonLabel: String = "SUB",
        duplicates: Int = 1,
    ): String {
        val buttons = (1..duplicates).joinToString("\n") {
            """<button class="server-video" data-tab="sub" data-video="$candidate">$buttonLabel</button>"""
        }
        return """
            <html><body>
              <div class="nv-watch-page"></div>
              $buttons
            </body></html>
        """.trimIndent()
    }

    private class CancellationListener : EventListener() {
        val wasCanceled = AtomicBoolean(false)

        override fun callFailed(call: Call, ioe: IOException) {
            wasCanceled.set(call.isCanceled())
        }
    }

    private fun closeClient(client: OkHttpClient) {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        client.cache?.close()
    }

    private companion object {
        private const val PROPERTY_SEED = 1_047_293_511L
        private const val PRESERVATION_SEED = 2_314_859_677L

        /** Mirrors the extractor's private USER_AGENT so requests can be pinned exactly. */
        private const val EXPECTED_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

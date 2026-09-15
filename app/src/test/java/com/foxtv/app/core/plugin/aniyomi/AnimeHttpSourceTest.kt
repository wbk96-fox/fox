package com.foxtv.app.core.plugin.aniyomi

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.LicensedEntryItemsException
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.Subscription
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AnimeHttpSourceTest {
    @Test
    fun suspendFlowsExecuteExpectedRequestsRouteParsersCloseResponsesAndInitializeDetails() =
        withServerAndInjectedClient { server, client ->
            repeat(7) { index -> server.enqueue(MockResponse().setBody("body-$index")) }
            val source = TestSource(server)
            val anime = SAnime.create().apply {
                url = "/details/item"
                title = "Input"
            }
            val episode = SEpisode.create().apply {
                url = "/episodes/one"
                name = "Episode"
            }

            runBlocking {
                val popular = source.getPopularAnime(1)
                val search = source.getSearchAnime(2, "Matrix", AnimeFilterList())
                val latest = source.getLatestUpdates(3)
                val details = source.getAnimeDetails(anime)
                val episodes = source.getEpisodeList(anime)
                val videos = source.getVideoList(episode)
                val resolved = source.getVideoUrl(videos.single())

                assertEquals("body-0", popular.animes.single().title)
                assertEquals("body-1", search.animes.single().title)
                assertEquals("body-2", latest.animes.single().title)
                assertEquals("body-3", details.title)
                assertTrue(details.initialized)
                assertEquals(12.5f, episodes.single().episode_number)
                assertEquals("body-5", videos.single().quality)
                assertEquals(videos.single().url, resolved)
                assertEquals(resolved, videos.single().videoUrl)
            }

            val requests = List(7) { requireNotNull(server.takeRequest()) }
            assertEquals(
                listOf(
                    "/popular/1",
                    "/search/2?q=Matrix",
                    "/latest/3",
                    "/details/item",
                    "/episodes",
                    "/videos",
                    "/resolved/video.m3u8",
                ),
                requests.map { it.path },
            )
            assertTrue(requests.all { it.getHeader("User-Agent") == "FOX.TV/aniyomi-test" })
            assertEquals(
                listOf("popular", "search", "latest", "details", "episodes", "videos"),
                source.parserCalls,
            )
            assertFalse(client.retryOnConnectionFailure)
        }

    @Test
    fun parserResponseIsClosedEvenWhenParserDoesNotConsumeBody() = withServerAndInjectedClient { server, _ ->
        server.enqueue(MockResponse().setBody("unconsumed"))
        val source = TestSource(server, capturePopularBodyWithoutConsuming = true)

        runBlocking { source.getPopularAnime(1) }

        val body = requireNotNull(source.capturedBody)
        assertThrows(IllegalStateException::class.java) { body.source().readByte() }
    }

    @Test
    fun unsuccessfulHttpResponseFailsWithoutInvokingParserOrReturningFakeData() =
        withServerAndInjectedClient { server, _ ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            val source = TestSource(server)

            val failure = assertThrows(IOException::class.java) {
                runBlocking { source.getPopularAnime(1) }
            }

            assertTrue(failure.message.orEmpty().contains("503"))
            assertTrue(source.parserCalls.isEmpty())
        }

    @Test
    fun rxCompatibilityFlowExecutesAsynchronouslyRoutesParserAndClosesResponse() =
        withServerAndInjectedClient { server, _ ->
            server.enqueue(MockResponse().setBody("rx-body"))
            val source = TestSource(server, capturePopularBodyWithoutConsuming = true)

            val page = source.fetchPopularAnime(4).toBlocking().single()

            assertEquals("unconsumed", page.animes.single().title)
            assertEquals("/popular/4", server.takeRequest().path)
            val body = requireNotNull(source.capturedBody)
            assertThrows(IllegalStateException::class.java) { body.source().readByte() }
        }

    @Test
    fun suspendCancellationCancelsUnderlyingOkHttpCall() {
        val listener = CancellationListener()
        val client = OkHttpClient.Builder().eventListener(listener).build()
        withServerAndInjectedClient(client) { server, _ ->
            server.enqueue(
                MockResponse()
                    .setHeadersDelay(30, TimeUnit.SECONDS)
                    .setBody("must not parse"),
            )
            val source = TestSource(server)

            runBlocking {
                val request = launch(Dispatchers.Default) { source.getPopularAnime(1) }
                assertNotNull(server.takeRequest(10, TimeUnit.SECONDS))
                request.cancelAndJoin()
            }

            assertTrue(listener.failed.await(10, TimeUnit.SECONDS))
            assertTrue(listener.wasCanceled.get())
            assertTrue(source.parserCalls.isEmpty())
        }
    }

    @Test
    fun rxUnsubscribeCancelsUnderlyingOkHttpCall() {
        val listener = CancellationListener()
        val client = OkHttpClient.Builder().eventListener(listener).build()
        withServerAndInjectedClient(client) { server, _ ->
            server.enqueue(
                MockResponse()
                    .setHeadersDelay(30, TimeUnit.SECONDS)
                    .setBody("must not parse"),
            )
            val source = TestSource(server)
            val errors = AtomicInteger()
            val subscription: Subscription = source.fetchPopularAnime(1).subscribe(
                { error("Canceled observable emitted data") },
                { errors.incrementAndGet() },
            )
            assertNotNull(server.takeRequest(10, TimeUnit.SECONDS))

            subscription.unsubscribe()

            assertTrue(listener.failed.await(10, TimeUnit.SECONDS))
            assertTrue(listener.wasCanceled.get())
            assertEquals(0, errors.get())
            assertTrue(source.parserCalls.isEmpty())
        }
    }

    @Test
    fun licensedAnimeShortCircuitsBothApisWithoutNetworkRequest() = withServerAndInjectedClient { server, _ ->
        val source = TestSource(server)
        val anime = SAnime.create().apply {
            url = "/licensed"
            title = "Licensed"
            status = SAnime.LICENSED
        }

        assertThrows(LicensedEntryItemsException::class.java) {
            runBlocking { source.getEpisodeList(anime) }
        }
        assertThrows(LicensedEntryItemsException::class.java) {
            source.fetchEpisodeList(anime).toBlocking().single()
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun sourceIdentityUrlHelpersAndVersionDefaultsMatchHistoricalContract() =
        withServerAndInjectedClient { server, _ ->
            val source = TestSource(server)
            val expectedId = MessageDigest.getInstance("MD5")
                .digest("jellyfin/all/1".toByteArray())
                .take(8)
                .fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xFFL) } and Long.MAX_VALUE
            assertEquals(expectedId, source.id)
            assertEquals(1, source.versionId)
            assertEquals("Jellyfin (ALL)", source.toString())

            val anime = SAnime.create().apply {
                title = "Item"
                source.run { setUrlWithoutDomain("https://example.test/path/item?q=1#fragment") }
            }
            val episode = SEpisode.create().apply {
                name = "Episode"
                source.run { setUrlWithoutDomain("https://example.test/episode/1?token=a") }
            }
            assertEquals("/path/item?q=1#fragment", anime.url)
            assertEquals("/episode/1?token=a", episode.url)
            assertEquals(source.baseUrl + anime.url, source.getAnimeUrl(anime))
            assertEquals(episode.url, source.getEpisodeUrl(episode))
            assertTrue(source.getFilterList().isEmpty())
        }

    private fun withServerAndInjectedClient(
        client: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
        block: (MockWebServer, OkHttpClient) -> Unit,
    ) {
        synchronized(AniyomiResearchContracts.globalInjektLock) {
            val previousScope = Injekt
            val server = MockWebServer()
            try {
                Injekt = InjektScope(DefaultRegistrar())
                Injekt.addSingleton(NetworkHelper(client) { "FOX.TV/aniyomi-test" })
                block(server, client)
            } finally {
                Injekt = previousScope
                server.close()
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
                client.cache?.close()
            }
        }
    }

    private class CancellationListener : EventListener() {
        val failed = CountDownLatch(1)
        val wasCanceled = AtomicBoolean()

        override fun callFailed(call: Call, ioe: IOException) {
            wasCanceled.set(call.isCanceled())
            failed.countDown()
        }
    }

    private class TestSource(
        server: MockWebServer,
        private val capturePopularBodyWithoutConsuming: Boolean = false,
    ) : AnimeHttpSource() {
        override val baseUrl: String = server.url("/").toString().removeSuffix("/")
        override val name: String = "Jellyfin"
        override val lang: String = "all"
        override val supportsLatest: Boolean = true
        val parserCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var capturedBody: ResponseBody? = null

        override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/popular/$page", headers)

        override fun popularAnimeParse(response: Response): AnimesPage {
            parserCalls += "popular"
            if (capturePopularBodyWithoutConsuming) {
                capturedBody = response.body
                return page("unconsumed")
            }
            return page(response.body.string())
        }

        override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
            GET("$baseUrl/search/$page?q=$query", headers)

        override fun searchAnimeParse(response: Response): AnimesPage {
            parserCalls += "search"
            return page(response.body.string())
        }

        override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest/$page", headers)

        override fun latestUpdatesParse(response: Response): AnimesPage {
            parserCalls += "latest"
            return page(response.body.string())
        }

        override fun animeDetailsParse(response: Response): SAnime {
            parserCalls += "details"
            return SAnime.create().apply {
                url = "/details/result"
                title = response.body.string()
            }
        }

        override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl/episodes", headers)

        override fun episodeListParse(response: Response): List<SEpisode> {
            parserCalls += "episodes"
            response.body.string()
            return listOf(
                SEpisode.create().apply {
                    url = "/episodes/result"
                    name = "Episode 12.5"
                    episode_number = 12.5f
                },
            )
        }

        override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl/videos", headers)

        override fun videoListParse(response: Response): List<Video> {
            parserCalls += "videos"
            return listOf(
                Video(
                    url = "$baseUrl/resolved/video.m3u8",
                    quality = response.body.string(),
                ),
            )
        }

        private fun page(title: String): AnimesPage = AnimesPage(
            animes = listOf(
                SAnime.create().apply {
                    url = "/item"
                    this.title = title
                },
            ),
            hasNextPage = false,
        )
    }
}

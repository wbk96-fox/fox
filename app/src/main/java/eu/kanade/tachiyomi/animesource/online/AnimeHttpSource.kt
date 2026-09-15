package eu.kanade.tachiyomi.animesource.online

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import rx.Subscriber
import rx.exceptions.Exceptions
import rx.subscriptions.Subscriptions
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/** Functional parent implementation of the historical Aniyomi HTTP source contract. */
@Suppress("unused")
abstract class AnimeHttpSource : AnimeCatalogueSource {
    protected val network: NetworkHelper by injectLazy()

    abstract val baseUrl: String

    open val versionId: Int = 1

    override val id: Long by lazy { generateId(name, lang, versionId) }

    val headers: Headers by lazy { headersBuilder().build() }

    open val client: OkHttpClient
        get() = network.client

    protected fun generateId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val digest = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return digest.take(8).fold(0L) { value, byte ->
            (value shl 8) or (byte.toLong() and 0xffL)
        } and Long.MAX_VALUE
    }

    protected open fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", network.defaultUserAgentProvider())

    override fun toString(): String = "$name (${lang.uppercase()})"

    override suspend fun getPopularAnime(page: Int): AnimesPage =
        executeAndParse(popularAnimeRequest(page), ::popularAnimeParse)

    @Deprecated("Use getPopularAnime")
    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> =
        executeAsObservable({ popularAnimeRequest(page) }, ::popularAnimeParse)

    protected abstract fun popularAnimeRequest(page: Int): Request
    protected abstract fun popularAnimeParse(response: Response): AnimesPage

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = executeAndParse(
        searchAnimeRequest(page, query, filters),
        ::searchAnimeParse,
    )

    @Deprecated("Use getSearchAnime")
    override fun fetchSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Observable<AnimesPage> = executeAsObservable(
        { searchAnimeRequest(page, query, filters) },
        ::searchAnimeParse,
    )

    protected abstract fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request

    protected abstract fun searchAnimeParse(response: Response): AnimesPage

    override suspend fun getLatestUpdates(page: Int): AnimesPage =
        executeAndParse(latestUpdatesRequest(page), ::latestUpdatesParse)

    @Deprecated("Use getLatestUpdates")
    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> =
        executeAsObservable({ latestUpdatesRequest(page) }, ::latestUpdatesParse)

    protected abstract fun latestUpdatesRequest(page: Int): Request
    protected abstract fun latestUpdatesParse(response: Response): AnimesPage

    override suspend fun getAnimeDetails(anime: SAnime): SAnime =
        executeAndParse(animeDetailsRequest(anime)) { response ->
            animeDetailsParse(response).apply { initialized = true }
        }

    @Deprecated("Use getAnimeDetails")
    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> =
        executeAsObservable({ animeDetailsRequest(anime) }) { response ->
            animeDetailsParse(response).apply { initialized = true }
        }

    open fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    protected abstract fun animeDetailsParse(response: Response): SAnime

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        if (anime.status == SAnime.LICENSED) throw LicensedEntryItemsException()
        return executeAndParse(episodeListRequest(anime), ::episodeListParse)
    }

    @Deprecated("Use getEpisodeList")
    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> =
        if (anime.status == SAnime.LICENSED) {
            Observable.error(LicensedEntryItemsException())
        } else {
            executeAsObservable({ episodeListRequest(anime) }, ::episodeListParse)
        }

    protected open fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    protected abstract fun episodeListParse(response: Response): List<SEpisode>

    override suspend fun getVideoList(episode: SEpisode): List<Video> =
        executeAndParse(videoListRequest(episode)) { response ->
            videoListParse(response).sort()
        }

    @Deprecated("Use getVideoList")
    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> =
        executeAsObservable({ videoListRequest(episode) }) { response ->
            videoListParse(response).sort()
        }

    protected open fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    protected abstract fun videoListParse(response: Response): List<Video>

    protected open fun List<Video>.sort(): List<Video> = this

    open suspend fun getVideoUrl(video: Video): String =
        executeAndParse(videoUrlRequest(video)) { response -> resolveVideoUrl(video, response) }

    open fun fetchVideoUrl(video: Video): Observable<String> =
        executeAsObservable({ videoUrlRequest(video) }) { response -> resolveVideoUrl(video, response) }

    protected open fun videoUrlRequest(video: Video): Request = GET(video.url, headers)

    protected open fun videoUrlParse(response: Response): String = response.request.url.toString()

    fun SEpisode.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SAnime.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    open fun getAnimeUrl(anime: SAnime): String = animeDetailsRequest(anime).url.toString()

    open fun getEpisodeUrl(episode: SEpisode): String = episode.url

    open fun prepareNewEpisode(episode: SEpisode, anime: SAnime) = Unit

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    private fun resolveVideoUrl(video: Video, response: Response): String {
        val resolved = videoUrlParse(response).takeIf(String::isNotBlank)
            ?: throw IOException("Aniyomi source returned a blank video URL")
        video.videoUrl = resolved
        return resolved
    }

    private fun getUrlWithoutDomain(original: String): String = try {
        val uri = URI(original)
        buildString {
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    } catch (_: URISyntaxException) {
        original
    }

    private suspend fun <T> executeAndParse(
        request: Request,
        parser: (Response) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        val callbackClaimed = java.util.concurrent.atomic.AtomicBoolean(false)
        continuation.invokeOnCancellation {
            callbackClaimed.compareAndSet(false, true)
            call.cancel()
        }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (callbackClaimed.compareAndSet(false, true)) {
                        continuation.resumeWith(Result.failure(error))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!callbackClaimed.compareAndSet(false, true)) {
                        response.close()
                        return
                    }
                    val result = runCatching {
                        response.use { parseSuccessful(it, parser) }
                    }
                    continuation.resumeWith(result)
                }
            },
        )
    }

    private fun <T> executeAsObservable(
        requestFactory: () -> Request,
        parser: (Response) -> T,
    ): Observable<T> = Observable.create { subscriber: Subscriber<in T> ->
        val call = try {
            client.newCall(requestFactory())
        } catch (error: Throwable) {
            signalError(subscriber, error)
            return@create
        }

        subscriber.add(Subscriptions.create { call.cancel() })
        if (subscriber.isUnsubscribed) return@create

        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (!subscriber.isUnsubscribed) subscriber.onError(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val parsed = response.use { parseSuccessful(it, parser) }
                        if (!subscriber.isUnsubscribed) {
                            subscriber.onNext(parsed)
                            if (!subscriber.isUnsubscribed) subscriber.onCompleted()
                        }
                    } catch (error: Throwable) {
                        signalError(subscriber, error)
                    }
                }
            },
        )
    }

    private fun <T> parseSuccessful(response: Response, parser: (Response) -> T): T {
        if (!response.isSuccessful) {
            throw IOException("Aniyomi source HTTP request failed with status ${response.code}")
        }
        return parser(response)
    }

    private fun signalError(subscriber: Subscriber<*>, error: Throwable) {
        Exceptions.throwIfFatal(error)
        if (!subscriber.isUnsubscribed) subscriber.onError(error)
    }
}

class LicensedEntryItemsException : RuntimeException("Licensed entries do not expose episodes")

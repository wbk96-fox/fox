package com.foxtv.app.core.anime.scraper

import android.util.Log
import com.foxtv.app.core.anime.extractors.*
import com.foxtv.app.core.anime.model.AnimeMedia
import com.foxtv.app.core.anime.model.AnimeStreamResult
import com.foxtv.app.core.scraper.HttpStreamLivenessValidator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import okhttp3.OkHttpClient
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnimeScraperService @Inject constructor(
    private val livenessValidator: HttpStreamLivenessValidator
) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Contracts - production wiring. The fields are `var` only so the internal test constructor
    // can replace them without reflection; production code never reassigns them.
    internal var providerCallFactory: AnimeProviderCallFactory = ProductionAnimeProviderCallFactory(
        watchHentaiExtractor = WatchHentaiExtractor(httpClient),
        hentainiExtractor = HentainiExtractor(httpClient),
        megaPlayExtractor = MegaPlayExtractor(httpClient),
        reCloudExtractor = ReCloudExtractor(httpClient),
        tryEmbedExtractor = TryEmbedExtractor(httpClient),
        lunaExtractor = LunaExtractor(httpClient),
        aniDbExtractor = AniDbExtractor(httpClient),
        aniNekoExtractor = AniNekoExtractor(httpClient),
        aniHQExtractor = AniHQExtractor(httpClient),
        aniPMExtractor = AniPMExtractor(httpClient),
        vidNestExtractor = VidNestExtractor(httpClient),
        duloExtractor = DuloExtractor(httpClient),
        oneTwoThreeExtractor = OneTwoThreeAnimeExtractor(httpClient)
    )

    internal var livenessProbe: StreamLivenessProbe = HttpStreamLivenessProbeAdapter(livenessValidator)

    internal var diagnosticSink: AnimeDiagnosticSink = LoggingDiagnosticSink()

    /** Dispatcher for provider child jobs; tests inject a virtual dispatcher, production uses IO. */
    internal var providerDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Internal constructor for testing with injectable contracts.
     * Allows tests to provide a controlled factory, probe, sink and dispatcher without Hilt or
     * public network. The real validator instance is never invoked because the injected probe
     * replaces the production adapter before any collection happens.
     */
    internal constructor(
        factory: AnimeProviderCallFactory,
        probe: StreamLivenessProbe,
        sink: AnimeDiagnosticSink,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(HttpStreamLivenessValidator()) {
        providerCallFactory = factory
        livenessProbe = probe
        diagnosticSink = sink
        providerDispatcher = dispatcher
    }

    companion object {
        private const val TAG = "AnimeScraperService"

        fun cleanAnimeTitle(raw: String): String {
            var s = raw
            s = s.replace(Regex("""\s*-\s*Episode\s*\d+.*""", RegexOption.IGNORE_CASE), "")
            s = s.replace(Regex("""\s*•\s*Ep\s*\d+.*""", RegexOption.IGNORE_CASE), "")
            s = s.replace(Regex("""\s*•\s*Episode\s*\d+.*""", RegexOption.IGNORE_CASE), "")
            s = s.replace(Regex("""\(TV\)""", RegexOption.IGNORE_CASE), "")
            s = s.replace(Regex("""\[.*?]"""), "")
            return s.trim()
        }
    }

    fun scrapeStreams(
        anime: AnimeMedia,
        episodeNumber: Int,
        categoryFilter: String? = null // "sub", "dub", or null for both
    ): Flow<AnimeStreamResult> = channelFlow {
        // Build title candidates using existing logic
        val rawTitles = listOf(
            anime.titleEnglish,
            anime.titleRomaji,
            anime.titleUserPreferred,
            anime.titleNative
        ).filter { it.isNotBlank() }

        val titleCandidates = buildList {
            for (t in rawTitles) {
                val cleaned = cleanAnimeTitle(t)
                if (cleaned.isNotBlank() && !contains(cleaned)) {
                    add(cleaned)
                }
                if (!contains(t)) {
                    add(t)
                }
            }
        }

        val isAdultContent = anime.isAdult ||
            anime.genres.any {
                it.contains("hentai", ignoreCase = true) ||
                it.contains("erotica", ignoreCase = true) ||
                it.contains("ecchi", ignoreCase = true)
            }

        // Create scrape request
        val request = AnimeScrapeRequest(
            anime = anime,
            episodeNumber = episodeNumber,
            categoryFilter = categoryFilter,
            titleCandidates = titleCandidates,
            isAdultContent = isAdultContent
        )

        // Create provider plan using factory
        val plan = providerCallFactory.createPlan(request)

        // Thread-safe set for URL deduplication
        val seenUrls = Collections.synchronizedSet(mutableSetOf<String>())

        // Execute all provider calls in parallel with structured concurrency
        coroutineScope {
            // Launch each provider call as a child job
            val jobs = plan.map { call ->
                launch(providerDispatcher) {
                    try {
                        // Execute provider call
                        val outcome = call.execute()

                        // Record diagnostics (non-blocking)
                        diagnosticSink.record(call.providerId, outcome)

                        // Process each stream from this provider
                        for (stream in outcome.streams) {
                            // Deduplicate by URL
                            if (!seenUrls.add(stream.streamUrl)) {
                                continue
                            }

                            // Check liveness before emitting
                            if (livenessProbe.isStreamAlive(stream.streamUrl, stream.headers)) {
                                send(stream)
                            }
                        }
                    } catch (e: CancellationException) {
                        // Propagate cancellation - this terminates the job
                        throw e
                    } catch (e: Exception) {
                        // Provider failure doesn't block other providers
                        // Error already recorded in outcome by AnimeProviderCall.execute()
                        Log.w(TAG, "${call.providerId.id} unexpected error: ${e.message}")
                    }
                }
            }

            // Wait for all provider jobs to complete naturally
            jobs.joinAll()
        }
        // Flow closes naturally after all providers complete
    }
}

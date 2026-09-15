package com.foxtv.app.core.scraper.p2p

import android.util.Log
import com.foxtv.app.core.scraper.ScraperMediaRequest
import com.foxtv.app.domain.model.Stream
import com.foxtv.app.core.torrent.TorrentSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FoxTvP2PScraperManager @Inject constructor(
    private val torrentSettings: TorrentSettings,
    private val authorizationGate: P2PAuthorizationGate
) {
    companion object {
        private const val TAG = "FoxTvP2PManager"
        const val ADDON_NAME = "FoxTv"
    }

    private val knabenScraper = KnabenScraper()
    private val torrentGalaxyScraper = TorrentGalaxyScraper()

    suspend fun scrapeStreamsDynamic(
        type: String,
        title: String,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        imdbId: String? = null,
        tmdbId: Int? = null,
        onStreamsFound: suspend (List<Stream>) -> Unit
    ) = withContext(Dispatchers.IO) {
        val settings = torrentSettings.settings.first()
        if (!authorizationGate.allowDiscovery(settings.p2pEnabled)) {
            Log.d(TAG, "P2P streaming is disabled in settings, skipping FoxTv P2P scrape")
            return@withContext
        }
        val request = ScraperMediaRequest(
            type = type,
            title = title,
            year = year,
            season = season,
            episode = episode,
            imdbId = imdbId,
            tmdbId = tmdbId
        )

        Log.d(TAG, "Starting dynamic FoxTv P2P scrape for '${request.title}' (type: ${request.type})")

        val seenHashes = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val scrapers = listOf(knabenScraper, torrentGalaxyScraper)

        val jobs = scrapers.map { scraper ->
            launch {
                try {
                    val results = withTimeoutOrNull(15000L) {
                        when (scraper) {
                            is KnabenScraper -> scraper.scrape(request)
                            is TorrentGalaxyScraper -> scraper.scrape(request)
                            else -> emptyList()
                        }
                    } ?: emptyList()

                    if (results.isNotEmpty()) {
                        val newStreams = mutableListOf<Stream>()
                        for (s in results) {
                            val hash = s.infoHash?.lowercase()?.trim()
                            if (hash.isNullOrBlank()) continue
                            if (seenHashes.add(hash)) {
                                newStreams.add(s)
                            }
                        }
                        if (newStreams.isNotEmpty()) {
                            onStreamsFound(newStreams)
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.d(TAG, "Scraper ${scraper.javaClass.simpleName} error: ${e.message}")
                }
            }
        }

        jobs.joinAll()
        Log.d(TAG, "Dynamic FoxTv P2P scrape finished with ${seenHashes.size} unique infoHashes")
    }
}

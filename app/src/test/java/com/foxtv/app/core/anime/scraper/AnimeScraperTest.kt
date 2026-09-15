package com.foxtv.app.core.anime.scraper

import com.foxtv.app.core.anime.extractors.AniPMExtractor
import com.foxtv.app.core.anime.model.AnimeMedia
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Deterministic JVM-only AnimeScraper tests.
 *
 * The two former live-network/time tests (`aniNekoExtractsStreams`, `animeScraperServiceCollectsStreams`)
 * were removed; their contracts moved to the transport-backed AniNekoExtractorContractTest and the
 * plan-driven AnimeScraperServiceAggregationTest, which cover far more deterministic cases than the
 * removed network and delay(15000)+cancel variants.
 */
class AnimeScraperTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Test
    fun cleanAnimeTitleRemovesNoise() {
        assertEquals("Solo Leveling", AnimeScraperService.cleanAnimeTitle("Solo Leveling (TV)"))
        assertEquals("Solo Leveling Season 2", AnimeScraperService.cleanAnimeTitle("Solo Leveling Season 2 - Episode 1 [English Sub]"))
        assertEquals("Demon Slayer", AnimeScraperService.cleanAnimeTitle("Demon Slayer • Ep 1"))
    }

    @Test
    fun aniPmParsesStreams() {
        val extractor = AniPMExtractor(client)
        val results = extractor.parseServers(
            body = """
                {
                  "sub": [
                    {
                      "url": "https://cdn.example/anime/master.m3u8",
                      "provider": "Helios",
                      "tracks": [
                        {
                          "file": "https://cdn.example/subtitles/en.vtt",
                          "label": "English",
                          "lang": "en",
                          "kind": "subtitles",
                          "default": true
                        },
                        {
                          "file": "https://cdn.example/thumbnails.vtt",
                          "kind": "thumbnails"
                        }
                      ]
                    }
                  ],
                  "dub": []
                }
            """.trimIndent(),
            category = "sub"
        )

        assertEquals(1, results.size)
        assertEquals("https://cdn.example/anime/master.m3u8", results.single().streamUrl)
        assertEquals("AniPM (Helios)", results.single().serverName)
        assertEquals("SUB", results.single().category)
        assertEquals(1, results.single().tracks.size)
        assertTrue(results.single().tracks.single().isDefault)
    }
}

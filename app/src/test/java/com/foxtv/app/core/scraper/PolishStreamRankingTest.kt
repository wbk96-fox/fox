package com.foxtv.app.core.scraper

import com.foxtv.app.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Test

class PolishStreamRankingTest {

    private fun stream(
        audioLanguage: String? = null,
        subtitleLanguages: List<String> = emptyList(),
        qualityValue: Int = 0,
    ): Stream = Stream(
        name = "FoxTvHTTP",
        title = "t",
        description = null,
        url = "https://example.invalid/stream.m3u8",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "FoxTvHTTP",
        addonLogo = null,
        qualityValue = qualityValue,
        audioLanguage = audioLanguage,
        subtitleLanguages = subtitleLanguages,
    )

    @Test
    fun `pl audio ranks above original with pl subtitles which ranks above unknown`() {
        val plAudio = stream(audioLanguage = "pl")
        val originalPlSub = stream(subtitleLanguages = listOf("en", "pl"))
        val unknown = stream()

        val ranked = listOf(unknown, originalPlSub, plAudio).sortedWith(PolishStreamRanking.comparator())

        assertEquals(listOf(plAudio, originalPlSub, unknown), ranked)
    }

    @Test
    fun `within the same language tier higher quality ranks first`() {
        val plLow = stream(audioLanguage = "pl", qualityValue = 2)
        val plHigh = stream(audioLanguage = "PL", qualityValue = 5)

        val ranked = listOf(plLow, plHigh).sortedWith(PolishStreamRanking.comparator())

        assertEquals(listOf(plHigh, plLow), ranked)
    }

    @Test
    fun `case insensitive polish language matching`() {
        val stream = stream(audioLanguage = "PL")

        assertEquals(0, PolishStreamRanking.languageTier(stream))
    }

    @Test
    fun `unknown language never removes the stream from the ranking`() {
        val unknown = stream()
        val ranked = listOf(unknown).sortedWith(PolishStreamRanking.comparator())

        assertEquals(listOf(unknown), ranked)
        assertEquals(2, PolishStreamRanking.languageTier(unknown))
    }
}

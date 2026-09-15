package com.foxtv.app.core.plugin.aniyomi

import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AniyomiHostModelsTest {
    @Test
    fun animeFactoriesReturnIndependentMutableInstancesWithHistoricalDefaults() {
        val first = SAnime.create()
        val second = SAnime.create()

        assertNotSame(first, second)
        assertNull(first.artist)
        assertNull(first.author)
        assertNull(first.description)
        assertNull(first.genre)
        assertNull(first.thumbnail_url)
        assertEquals(SAnime.UNKNOWN, first.status)
        assertEquals(AnimeUpdateStrategy.ALWAYS_UPDATE, first.update_strategy)
        assertFalse(first.initialized)

        first.url = "/items/one"
        first.title = "First"
        first.author = "Author"
        first.genre = "Drama, Sci-Fi, Drama"
        first.status = SAnime.ONGOING
        first.initialized = true
        second.url = "/items/two"
        second.title = "Second"

        assertEquals("First", first.title)
        assertEquals("Second", second.title)
        assertEquals(listOf("Drama", "Sci-Fi"), first.getGenres())
        assertNull(second.author)
        assertFalse(second.initialized)

        val copy = first.copy()
        assertNotSame(first, copy)
        assertEquals(first.url, copy.url)
        assertEquals(first.title, copy.title)
        assertEquals(first.genre, copy.genre)
        assertEquals(first.initialized, copy.initialized)
    }

    @Test
    fun episodeFactoriesPreserveFractionalNumbersAndNullableMetadata() {
        val first = SEpisode.create()
        val second = SEpisode.create()

        assertNotSame(first, second)
        assertEquals(0L, first.date_upload)
        assertEquals(-1f, first.episode_number)
        assertNull(first.scanlator)

        first.url = "/episode/125"
        first.name = "Episode 12.5"
        first.date_upload = 1_717_171_717_000L
        first.episode_number = 12.5f
        first.scanlator = "Jellyfin"
        second.url = "/episode/13"
        second.name = "Episode 13"

        assertEquals(12.5f, first.episode_number)
        assertEquals(-1f, second.episode_number)
        second.copyFrom(first)
        assertEquals(12.5f, second.episode_number)
        assertEquals("Jellyfin", second.scanlator)
    }

    @Test
    fun videoRetainsHeadersSubtitleAudioAndNullableResolvedUrlWithoutFlattening() {
        val headers = Headers.headersOf("Referer", "https://example.test/detail")
        val subtitles = listOf(Track("https://example.test/sub-pl.vtt", "pl"))
        val audio = listOf(Track("https://example.test/audio-ja.m4a", "ja"))
        val video = Video(
            url = "12000000",
            quality = "1080p",
            videoUrl = null,
            headers = headers,
            subtitleTracks = subtitles,
            audioTracks = audio,
        )

        assertEquals("12000000", video.url)
        assertEquals("1080p", video.quality)
        assertNull(video.videoUrl)
        assertSame(headers, video.headers)
        assertSame(subtitles, video.subtitleTracks)
        assertSame(audio, video.audioTracks)
        assertEquals(Video.State.QUEUE, video.status)

        video.videoUrl = "https://example.test/video.m3u8"
        video.status = Video.State.READY
        assertEquals("https://example.test/video.m3u8", video.videoUrl)
        assertEquals(Video.State.READY, video.status)
        assertTrue(video.audioTracks.single().lang == "ja")
    }
}

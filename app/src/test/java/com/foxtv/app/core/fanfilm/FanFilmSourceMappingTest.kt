package com.foxtv.app.core.fanfilm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source normalisation: what crosses the Python bridge must arrive as a descriptor the
 * player can use, with headers, cookies, referer and DRM intact rather than collapsed into
 * a URL string.
 */
class FanFilmSourceMappingTest {

    private fun source(
        token: String = "tok",
        hosting: String = "cda.pl",
        quality: String = "1080p",
        placeholder: Boolean = false,
        sizeBytes: Long = 1_500_000_000,
    ) = FanFilmSource(
        batch = "batch",
        token = token,
        provider = "cda",
        hosting = hosting,
        label = "[1080p] CDA",
        info = "PL",
        info2 = "",
        quality = quality,
        size = "1.4 GB",
        sizeBytes = sizeBytes,
        filename = "movie.mkv",
        languages = listOf("pl"),
        debrid = "",
        isPremium = false,
        isOnAccount = false,
        isDirect = false,
        isLocal = false,
        isExternal = false,
        alreadyResolved = false,
        playMode = "auto",
        icon = "",
        isPlaceholder = placeholder,
    )

    // ── FanFilmSource ───────────────────────────────────────────────────────

    @Test
    fun `quality strings map onto sortable values`() {
        assertEquals(2160, source(quality = "4K").qualityValue)
        assertEquals(2160, source(quality = "2160p").qualityValue)
        assertEquals(1080, source(quality = "1080p").qualityValue)
        assertEquals(720, source(quality = "HD").qualityValue)
        assertEquals(480, source(quality = "SD").qualityValue)
        assertEquals(-1, source(quality = "").qualityValue)
    }

    @Test
    fun `a listing separates the placeholder entry from playable sources`() {
        val listing = FanFilmSourceListing(
            batch = "batch",
            mediaRef = "movie/100000603",
            title = "The Matrix",
            year = 1999,
            sources = listOf(source(token = "a"), source(token = "b", placeholder = true)),
        )
        assertEquals(2, listing.sources.size)
        assertEquals(1, listing.playable.size)
        assertEquals("a", listing.playable.single().token)
    }

    @Test
    fun `deserialises a source from the bridge payload`() {
        val json = JSONObject(
            """
            {"token":"t1","provider":"cda","hosting":"cda.pl","label":"[1080p] CDA",
             "info":"PL","info2":"","quality":"1080p","size":"1.4 GB",
             "sizeBytes":1500000000,"filename":"m.mkv","languages":["pl","en"],
             "debrid":"","premium":true,"onAccount":false,"direct":false,"local":false,
             "external":false,"resolved":false,"playMode":"isa","icon":"",
             "placeholder":false}
            """.trimIndent()
        )
        val parsed = FanFilmSource.fromJson("batch", json)

        assertEquals("t1", parsed.token)
        assertEquals(listOf("pl", "en"), parsed.languages)
        assertTrue(parsed.isPremium)
        assertEquals("isa", parsed.playMode)
        assertEquals(1_500_000_000L, parsed.sizeBytes)
    }

    // ── FanFilmResolvedMedia ────────────────────────────────────────────────

    @Test
    fun `deserialises a direct stream with headers`() {
        val media = FanFilmResolvedMedia.fromJson(
            JSONObject(
                """
                {"url":"https://cdn.example/movie.mp4","streamType":"direct",
                 "mimeType":"","headers":{"User-Agent":"UA","Referer":"https://cda.pl/"},
                 "cookies":{"sid":"abc"},"referer":"https://cda.pl/","host":"cdn.example",
                 "adaptive":false,"subtitles":[],"provider":"cda","hosting":"cda.pl",
                 "label":"[1080p] CDA","quality":"1080p","batch":"b","token":"t",
                 "properties":{},"parts":[]}
                """.trimIndent()
            )
        )

        assertEquals(FanFilmResolvedMedia.StreamType.DIRECT, media.streamType)
        assertEquals("UA", media.headers["User-Agent"])
        assertEquals("abc", media.cookies["sid"])
        assertEquals("https://cda.pl/", media.referer)
        assertFalse(media.requiresAdaptivePipeline)
        assertNull(media.drm)
        assertTrue(media.isPlayable)
    }

    @Test
    fun `deserialises a Widevine DASH stream with its licence details`() {
        val media = FanFilmResolvedMedia.fromJson(
            JSONObject(
                """
                {"url":"https://cdn.example/manifest.mpd","streamType":"dash",
                 "mimeType":"application/dash+xml","headers":{},"cookies":{},
                 "referer":"","host":"cdn.example","adaptive":true,
                 "drm":{"scheme":"com.widevine.alpha",
                        "licenseUrl":"https://lic.example/wv",
                        "licenseHeaders":{"Authorization":"Bearer x"},
                        "postData":"R{SSM}","responseData":""},
                 "subtitles":[{"url":"https://s.example/pl.vtt","language":"pl","label":"PL"}],
                 "provider":"p","hosting":"h","label":"l","quality":"1080p",
                 "batch":"b","token":"t","properties":{},"parts":[]}
                """.trimIndent()
            )
        )

        assertEquals(FanFilmResolvedMedia.StreamType.DASH, media.streamType)
        assertTrue(media.requiresAdaptivePipeline)
        assertNotNull(media.drm)
        assertEquals("com.widevine.alpha", media.drm!!.scheme)
        assertEquals("https://lic.example/wv", media.drm.licenseUrl)
        assertEquals("Bearer x", media.drm.licenseHeaders["Authorization"])
        assertEquals("R{SSM}", media.drm.postData)
        assertEquals(1, media.subtitles.size)
        assertEquals("pl", media.subtitles.single().language)
    }

    @Test
    fun `a plugin url is not directly playable`() {
        val media = FanFilmResolvedMedia.fromJson(
            JSONObject(
                """
                {"url":"plugin://plugin.video.youtube/play/?video_id=abc",
                 "streamType":"plugin","mimeType":"","headers":{},"cookies":{},
                 "referer":"","host":"","adaptive":false,"subtitles":[],
                 "provider":"p","hosting":"youtube","label":"l","quality":"",
                 "batch":"b","token":"t","properties":{},"parts":[],
                 "pluginTarget":{"addonId":"plugin.video.youtube","path":"/play/",
                                 "query":"video_id=abc","videoId":"abc"}}
                """.trimIndent()
            )
        )

        assertEquals(FanFilmResolvedMedia.StreamType.PLUGIN, media.streamType)
        assertFalse("a plugin:// url needs translation before playback", media.isPlayable)
        assertEquals("abc", media.pluginTarget?.videoId)
    }

    @Test
    fun `an unknown stream type falls back to direct rather than throwing`() {
        assertEquals(
            FanFilmResolvedMedia.StreamType.DIRECT,
            FanFilmResolvedMedia.StreamType.fromWire("something-new"),
        )
    }

    // ── FanFilmStreamMapper ─────────────────────────────────────────────────

    private val mapper = FanFilmStreamMapper()

    @Test
    fun `a discovered source becomes a deferred resolve stream`() {
        val stream = mapper.toStream(source())

        assertEquals(FanFilmProvider.PROVIDER_NAME, stream.addonName)
        assertTrue(
            "the picker must not receive the raw host link",
            mapper.isDeferredResolveUri(stream.url),
        )
        assertEquals(1080, stream.qualityValue)
        assertTrue(stream.name!!.contains("cda.pl"))
        assertTrue(stream.description!!.contains("1.4 GB"))
    }

    @Test
    fun `the deferred resolve uri round trips batch and token`() {
        val original = source(token = "abc123")
        val uri = mapper.deferredResolveUri(original)
        val parsed = mapper.parseDeferredResolveUri(uri)

        assertNotNull(parsed)
        assertEquals("batch", parsed!!.batch)
        assertEquals("abc123", parsed.token)
    }

    @Test
    fun `a foreign url is not treated as a deferred resolve`() {
        assertFalse(mapper.isDeferredResolveUri("https://cdn.example/movie.mp4"))
        assertNull(mapper.parseDeferredResolveUri("https://cdn.example/movie.mp4"))
    }

    @Test
    fun `placeholders are excluded from the picker`() {
        val listing = FanFilmSourceListing(
            batch = "b",
            mediaRef = "movie/1",
            title = "t",
            year = 2000,
            sources = listOf(source(token = "a"), source(token = "p", placeholder = true)),
        )
        val streams = mapper.toStreams(listing, listing.sources)
        assertEquals(1, streams.size)
    }

    @Test
    fun `a resolved stream carries headers cookies and referer to the player`() {
        val media = FanFilmResolvedMedia(
            url = "https://cdn.example/movie.mp4",
            streamType = FanFilmResolvedMedia.StreamType.DIRECT,
            mimeType = "",
            headers = mapOf("User-Agent" to "UA"),
            cookies = mapOf("sid" to "abc", "lang" to "pl"),
            referer = "https://cda.pl/",
            host = "cdn.example",
            requiresAdaptivePipeline = false,
            drm = null,
            subtitles = emptyList(),
            provider = "cda",
            hosting = "cda.pl",
            label = "[1080p] CDA",
            quality = "1080p",
            batch = "b",
            token = "t",
            properties = emptyMap(),
            parts = emptyList(),
            pluginTarget = null,
        )

        val stream = mapper.toPlayableStream(media, displayTitle = "The Matrix")
        val request = stream.behaviorHints?.proxyHeaders?.request

        assertNotNull(request)
        assertEquals("UA", request!!["User-Agent"])
        assertEquals("https://cda.pl/", request["Referer"])
        assertTrue(request["Cookie"]!!.contains("sid=abc"))
        assertTrue(request["Cookie"]!!.contains("lang=pl"))
        assertEquals("https://cdn.example/movie.mp4", stream.url)
    }

    @Test
    fun `an existing referer header is not overwritten`() {
        val media = FanFilmResolvedMedia(
            url = "https://cdn.example/movie.mp4",
            streamType = FanFilmResolvedMedia.StreamType.DIRECT,
            mimeType = "",
            headers = mapOf("Referer" to "https://explicit.example/"),
            cookies = emptyMap(),
            referer = "https://derived.example/",
            host = "cdn.example",
            requiresAdaptivePipeline = false,
            drm = null,
            subtitles = emptyList(),
            provider = "p",
            hosting = "h",
            label = "l",
            quality = "",
            batch = "b",
            token = "t",
            properties = emptyMap(),
            parts = emptyList(),
            pluginTarget = null,
        )

        val request = mapper.toPlayableStream(media, "title").behaviorHints?.proxyHeaders?.request
        assertEquals("https://explicit.example/", request!!["Referer"])
    }

    // ── FanFilmError ────────────────────────────────────────────────────────

    @Test
    fun `bridge failure kinds map onto typed errors`() {
        assertTrue(FanFilmError.fromKind("configuration", "x") is FanFilmError.Configuration)
        assertTrue(FanFilmError.fromKind("cancelled", "x") is FanFilmError.Cancelled)
        assertTrue(FanFilmError.fromKind("busy", "x") is FanFilmError.Busy)
        assertTrue(FanFilmError.fromKind("resolver", "x", host = "h") is FanFilmError.Resolver)
        assertTrue(FanFilmError.fromKind("drm", "x") is FanFilmError.Drm)
        assertTrue(FanFilmError.fromKind("playback", "x") is FanFilmError.Playback)
        assertTrue(FanFilmError.fromKind("nosources", "x") is FanFilmError.NoSources)
        assertTrue(FanFilmError.fromKind("update", "x") is FanFilmError.Update)
    }

    @Test
    fun `an unknown kind becomes a plugin error rather than being swallowed`() {
        val error = FanFilmError.fromKind("brand-new-kind", "boom", detail = "trace")
        assertTrue(error is FanFilmError.Plugin)
        assertEquals("trace", (error as FanFilmError.Plugin).traceback)
    }

    @Test
    fun `retryability distinguishes transient from terminal failures`() {
        assertTrue(FanFilmError.Network("timeout").isRetryable)
        assertTrue(FanFilmError.Resolver("h", "dead").isRetryable)
        assertTrue(FanFilmError.NoSources().isRetryable)
        assertFalse(FanFilmError.Drm("com.widevine.alpha", "no cdm").isRetryable)
        assertFalse(FanFilmError.Configuration("bad tree").isRetryable)
    }
}

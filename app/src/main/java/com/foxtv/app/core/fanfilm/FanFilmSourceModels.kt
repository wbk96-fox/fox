package com.foxtv.app.core.fanfilm

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** What FOX.TV asks FanFilm to scan for. */
@Immutable
data class FanFilmMediaRequest(
    val mediaType: MediaType,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val timeoutSeconds: Int? = null,
) {
    enum class MediaType(val wireValue: String) { MOVIE("movie"), SERIES("series") }

    init {
        require(tmdbId != null || !imdbId.isNullOrBlank()) {
            "FanFilm needs a TMDB or IMDb id to identify the title"
        }
    }

    fun toJson(): String = JSONObject().apply {
        put("mediaType", mediaType.wireValue)
        tmdbId?.let { put("tmdbId", it) }
        imdbId?.takeIf { it.isNotBlank() }?.let { put("imdbId", it) }
        season?.let { put("season", it) }
        episode?.let { put("episode", it) }
        timeoutSeconds?.let { put("timeoutSeconds", it) }
    }.toString()
}

/**
 * One source FanFilm found, before resolution.
 *
 * [token] plus [batch] address the underlying Python `Source` object, which stays on
 * the Python side. That is deliberate: the object carries the provider closure needed
 * to resolve it, and serialising it would lose that.
 */
@Immutable
data class FanFilmSource(
    val batch: String,
    val token: String,
    val provider: String,
    val hosting: String,
    val label: String,
    val info: String,
    val info2: String,
    val quality: String,
    val size: String,
    val sizeBytes: Long,
    val filename: String,
    val languages: List<String>,
    val debrid: String,
    val isPremium: Boolean,
    val isOnAccount: Boolean,
    val isDirect: Boolean,
    val isLocal: Boolean,
    val isExternal: Boolean,
    val alreadyResolved: Boolean,
    val playMode: String,
    val icon: String,
    /**
     * FanFilm's own "no sources found" filler entry.
     *
     * The provider returns a placeholder item pointing at bundled media when a scan
     * times out or every host fails. It must never be offered as a playable source;
     * the repository turns a list that contains only placeholders into
     * [FanFilmError.NoSources].
     */
    val isPlaceholder: Boolean,
) {
    /** Numeric quality for sorting, matching the app's existing convention. */
    val qualityValue: Int
        get() {
            val lower = quality.lowercase()
            return when {
                lower.contains("4k") || lower.contains("2160") -> 2160
                lower.contains("1440") -> 1440
                lower.contains("1080") -> 1080
                lower.contains("720") -> 720
                lower.contains("576") -> 576
                lower.contains("480") -> 480
                lower.contains("360") -> 360
                lower.contains("hd") -> 720
                lower.contains("sd") -> 480
                lower.contains("cam") || lower.contains("ts") -> 240
                else -> -1
            }
        }

    companion object {
        fun fromJson(batch: String, json: JSONObject): FanFilmSource = FanFilmSource(
            batch = batch,
            token = json.optString("token"),
            provider = json.optString("provider"),
            hosting = json.optString("hosting"),
            label = json.optString("label"),
            info = json.optString("info"),
            info2 = json.optString("info2"),
            quality = json.optString("quality"),
            size = json.optString("size"),
            sizeBytes = json.optLong("sizeBytes", 0),
            filename = json.optString("filename"),
            languages = json.optJSONArray("languages").toStringList(),
            debrid = json.optString("debrid"),
            isPremium = json.optBoolean("premium", false),
            isOnAccount = json.optBoolean("onAccount", false),
            isDirect = json.optBoolean("direct", false),
            isLocal = json.optBoolean("local", false),
            isExternal = json.optBoolean("external", false),
            alreadyResolved = json.optBoolean("resolved", false),
            playMode = json.optString("playMode"),
            icon = json.optString("icon"),
            isPlaceholder = json.optBoolean("placeholder", false),
        )
    }
}

/** Result of a provider scan. */
@Immutable
data class FanFilmSourceListing(
    val batch: String,
    val mediaRef: String,
    val title: String,
    val year: Int,
    val sources: List<FanFilmSource>,
) {
    /** Sources that can actually be played. */
    val playable: List<FanFilmSource> get() = sources.filterNot { it.isPlaceholder }
}

/** DRM requirements attached to a resolved stream. */
@Immutable
data class FanFilmDrm(
    val scheme: String,
    val licenseUrl: String,
    val licenseHeaders: Map<String, String>,
    val postData: String,
    val responseData: String,
)

/** A subtitle track a provider supplied alongside the stream. */
@Immutable
data class FanFilmSubtitle(
    val url: String,
    val language: String,
    val label: String,
)

/**
 * A resolved FanFilm stream, in the shape the FOX.TV player contract needs.
 *
 * This is the normalised descriptor AGENTS.md §32/§33 asks for: URL plus headers,
 * cookies, referer, MIME type, stream type, DRM and subtitles — not a bare string.
 */
@Immutable
data class FanFilmResolvedMedia(
    val url: String,
    val streamType: StreamType,
    val mimeType: String,
    val headers: Map<String, String>,
    val cookies: Map<String, String>,
    val referer: String,
    val host: String,
    val requiresAdaptivePipeline: Boolean,
    val drm: FanFilmDrm?,
    val subtitles: List<FanFilmSubtitle>,
    val provider: String,
    val hosting: String,
    val label: String,
    val quality: String,
    val batch: String,
    val token: String,
    /** Extra `key=value` pairs Kodi carried in the pipe suffix that are not headers. */
    val properties: Map<String, String>,
    /** Parts of a `stack://` multi-file item, in order. */
    val parts: List<String>,
    /** Target of a `plugin://` URL, when the provider produced one. */
    val pluginTarget: PluginTarget?,
) {
    enum class StreamType(val wireValue: String) {
        DIRECT("direct"),
        HLS("hls"),
        DASH("dash"),
        SMOOTH_STREAMING("smoothstreaming"),
        STACK("stack"),
        PLUGIN("plugin"),
        LOCAL("local");

        companion object {
            fun fromWire(value: String): StreamType =
                entries.firstOrNull { it.wireValue == value } ?: DIRECT
        }
    }

    @Immutable
    data class PluginTarget(
        val addonId: String,
        val path: String,
        val query: String,
        val videoId: String,
    )

    /** Whether the app can hand this straight to its own player. */
    val isPlayable: Boolean
        get() = url.isNotBlank() && streamType != StreamType.PLUGIN

    companion object {
        fun fromJson(json: JSONObject): FanFilmResolvedMedia {
            val drmJson = json.optJSONObject("drm")
            return FanFilmResolvedMedia(
                url = json.optString("url"),
                streamType = StreamType.fromWire(json.optString("streamType")),
                mimeType = json.optString("mimeType"),
                headers = json.optJSONObject("headers").toStringMap(),
                cookies = json.optJSONObject("cookies").toStringMap(),
                referer = json.optString("referer"),
                host = json.optString("host"),
                requiresAdaptivePipeline = json.optBoolean("adaptive", false),
                drm = drmJson?.let {
                    FanFilmDrm(
                        scheme = it.optString("scheme"),
                        licenseUrl = it.optString("licenseUrl"),
                        licenseHeaders = it.optJSONObject("licenseHeaders").toStringMap(),
                        postData = it.optString("postData"),
                        responseData = it.optString("responseData"),
                    )
                },
                subtitles = json.optJSONArray("subtitles").toSubtitles(),
                provider = json.optString("provider"),
                hosting = json.optString("hosting"),
                label = json.optString("label"),
                quality = json.optString("quality"),
                batch = json.optString("batch"),
                token = json.optString("token"),
                properties = json.optJSONObject("properties").toStringMap(),
                parts = json.optJSONArray("parts").toStringList(),
                pluginTarget = json.optJSONObject("pluginTarget")?.let {
                    PluginTarget(
                        addonId = it.optString("addonId"),
                        path = it.optString("path"),
                        query = it.optString("query"),
                        videoId = it.optString("videoId"),
                    )
                },
            )
        }
    }
}

internal fun JSONObject?.toStringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    val result = LinkedHashMap<String, String>(length())
    for (key in keys()) result[key] = optString(key)
    return result
}

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotEmpty) }
}

private fun JSONArray?.toSubtitles(): List<FanFilmSubtitle> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        val entry = optJSONObject(index) ?: return@mapNotNull null
        val url = entry.optString("url")
        if (url.isEmpty()) return@mapNotNull null
        FanFilmSubtitle(
            url = url,
            language = entry.optString("language"),
            label = entry.optString("label"),
        )
    }
}

package com.foxtv.app.core.anime.extractors

import android.util.Log
import com.foxtv.app.core.anime.model.AnimeStreamResult
import com.foxtv.app.core.anime.model.AnimeStreamTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class AniPMExtractor(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "AniPMExtractor"
        private const val BASE_URL = "https://ani.pm"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    suspend fun extract(
        anilistId: Int,
        episodeNumber: Int,
        category: String, // "sub" or "dub"
        title: String? = null
    ): List<AnimeStreamResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<AnimeStreamResult>()
        try {
            val titleParam = if (!title.isNullOrBlank()) "&title=" + java.net.URLEncoder.encode(title, "UTF-8") else ""
            val uri = "$BASE_URL/api/anime/src/servers?ep=$episodeNumber&anilistId=$anilistId$titleParam"
            val req = Request.Builder()
                .url(uri)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Referer", "$BASE_URL/")
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext results
                results += parseServers(res.body?.string().orEmpty(), category)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AniPM error: ${e.message}")
        }
        results
    }

    /** Parse AniPM's server payload independently of transport for deterministic tests. */
    internal fun parseServers(body: String, category: String): List<AnimeStreamResult> {
        val results = mutableListOf<AnimeStreamResult>()
        val data = JSONObject(body)
        val targetAud = if (category.equals("dub", ignoreCase = true)) "dub" else "sub"
        val list = data.optJSONArray(targetAud) ?: return emptyList()

        for (i in 0 until list.length()) {
            val srv = list.optJSONObject(i) ?: continue
            val rawUrl = srv.optString("url")
            if (rawUrl.isBlank()) continue

            val streamUrl = if (rawUrl.startsWith("/")) "$BASE_URL$rawUrl" else rawUrl
            val provider = srv.optString("provider").ifBlank { "AniPM" }

            val tracks = mutableListOf<AnimeStreamTrack>()
            val subArr = srv.optJSONArray("tracks")
                ?: srv.optJSONArray("subtitles")
                ?: srv.optJSONArray("captions")
            if (subArr != null) {
                for (tIdx in 0 until subArr.length()) {
                    val track = subArr.optJSONObject(tIdx) ?: continue
                    val file = track.optString("url").ifBlank { track.optString("file") }
                    val kind = track.optString("kind", "subtitles")
                    if (file.isNotBlank() && !kind.equals("thumbnails", ignoreCase = true)) {
                        val fullTrackUrl = if (file.startsWith("/")) "$BASE_URL$file" else file
                        tracks += AnimeStreamTrack(
                            url = fullTrackUrl,
                            label = track.optString("label", track.optString("lang", "Subtitles")),
                            lang = track.optString("lang", "en"),
                            kind = kind,
                            isDefault = track.optBoolean("default", false)
                        )
                    }
                }
            }

            results += AnimeStreamResult(
                streamUrl = streamUrl,
                serverName = "AniPM ($provider)",
                category = targetAud.uppercase(),
                quality = "1080p",
                tracks = tracks,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$BASE_URL/",
                    "Origin" to BASE_URL
                )
            )
        }
        return results
    }
}

package com.foxtv.app.core.anime.extractors

import android.util.Log
import com.foxtv.app.core.anime.model.AnimeStreamResult
import com.foxtv.app.core.anime.model.AnimeStreamTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder

/**
 * Typed extraction failures for AniNeko.
 * Each failure contains a safe diagnostic URL (query-free, credential-free).
 */
sealed class AniNekoExtractionFailure(
    open val safeUrl: String,
    open val stage: String,
    message: String,
) : IOException(message) {

    data class Network(
        override val safeUrl: String,
        override val stage: String,
    ) : AniNekoExtractionFailure(
        safeUrl = safeUrl,
        stage = stage,
        message = "Network failure at $stage for $safeUrl",
    )

    data class HttpStatus(
        override val safeUrl: String,
        override val stage: String,
        val code: Int,
    ) : AniNekoExtractionFailure(
        safeUrl = safeUrl,
        stage = stage,
        message = "HTTP $code at $stage for $safeUrl",
    )

    data class InvalidUrl(
        override val safeUrl: String,
        override val stage: String,
        val reason: String,
    ) : AniNekoExtractionFailure(
        safeUrl = safeUrl,
        stage = stage,
        message = "Invalid URL at $stage for $safeUrl: $reason",
    )

    data class Parse(
        override val safeUrl: String,
        override val stage: String,
        val reason: String,
    ) : AniNekoExtractionFailure(
        safeUrl = safeUrl,
        stage = stage,
        message = "Parse failure at $stage for $safeUrl: $reason",
    )

    data class UnsupportedMedia(
        override val safeUrl: String,
        override val stage: String,
        val reason: String,
    ) : AniNekoExtractionFailure(
        safeUrl = safeUrl,
        stage = stage,
        message = "Unsupported media at $stage for $safeUrl: $reason",
    )

    data class NoMatch(
        override val safeUrl: String,
        override val stage: String,
    ) : AniNekoExtractionFailure(
        safeUrl = safeUrl,
        stage = stage,
        message = "No match at $stage for $safeUrl",
    )
}

/**
 * Complete extraction outcome: successful streams and typed failures.
 * Cancellation is NOT a failure outcome - it's always re-thrown.
 */
data class AniNekoExtractionOutcome(
    val streams: List<AnimeStreamResult>,
    val failures: List<AniNekoExtractionFailure>,
)

/**
 * Media policy: accepts only HTTP(S) HLS with evidence.
 * Evidence: canonical path ends with .m3u8 (case-insensitive)
 *       OR (response has HLS MIME AND first non-empty line is #EXTM3U).
 */
internal object AniNekoMediaPolicy {
    private val HLS_MIME_TYPES = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
    )

    fun isAcceptedHls(url: HttpUrl, response: AniNekoHttpResponse?): Boolean {
        // Must be HTTP or HTTPS
        if (url.scheme != "http" && url.scheme != "https") {
            return false
        }

        // Evidence 1: canonical path ends with .m3u8
        val path = url.encodedPath
        if (path.endsWith(".m3u8", ignoreCase = true)) {
            return true
        }

        // Evidence 2: response has HLS MIME AND body starts with #EXTM3U
        if (response != null) {
            val mimeType = response.contentType?.type + "/" + response.contentType?.subtype
            if (HLS_MIME_TYPES.contains(mimeType.lowercase())) {
                val firstNonEmptyLine = response.body.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                if (firstNonEmptyLine == "#EXTM3U") {
                    return true
                }
            }
        }

        return false
    }
}

class AniNekoExtractor internal constructor(
    private val transport: AniNekoTransport,
    private val baseUrl: HttpUrl,
) {
    /**
     * Production constructor: uses OkHttpClient with production base URL.
     */
    constructor(client: OkHttpClient) : this(
        transport = OkHttpAniNekoTransport(client),
        baseUrl = "https://anineko.to/".toHttpUrl(),
    )

    companion object {
        private const val TAG = "AniNekoExtractor"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private fun cleanTitle(t: String): String =
        t.lowercase().replace(Regex("""[^a-z0-9]"""), "")

    private fun decodeEntities(str: String): String {
        return str
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun sanitizeHeaderValue(value: String): String {
        return value.replace("\r", "").replace("\n", "")
    }

    private fun HttpUrl.toSafeDiagnosticUrl(): String {
        return newBuilder()
            .username("")
            .password("")
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    private suspend fun search(
        query: String,
        parentReferer: HttpUrl,
    ): Pair<List<Pair<String, String>>, List<AniNekoExtractionFailure>> {
        val results = mutableListOf<Pair<String, String>>()
        val failures = mutableListOf<AniNekoExtractionFailure>()
        try {
            val url = baseUrl.newBuilder()
                .addPathSegment("browser")
                .addQueryParameter("keyword", query)
                .build()

            val req = Request.Builder()
                .url(url)
                .addHeader("User-Agent", sanitizeHeaderValue(USER_AGENT))
                .addHeader("Referer", sanitizeHeaderValue(parentReferer.toString()))
                .build()

            val response = try {
                transport.get(req)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: AniNekoTransportException) {
                failures.add(
                    AniNekoExtractionFailure.Network(
                        safeUrl = url.toSafeDiagnosticUrl(),
                        stage = "search",
                    )
                )
                return results to failures
            }

            if (response.code !in 200..299) {
                failures.add(
                    AniNekoExtractionFailure.HttpStatus(
                        safeUrl = url.toSafeDiagnosticUrl(),
                        stage = "search",
                        code = response.code,
                    )
                )
                return results to failures
            }

            val doc = try {
                Jsoup.parse(response.body)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                failures.add(
                    AniNekoExtractionFailure.Parse(
                        safeUrl = url.toSafeDiagnosticUrl(),
                        stage = "search",
                        reason = "Jsoup parse failed",
                    )
                )
                return results to failures
            }

            val links = doc.select("a[href*=/watch/]")
            val seenSlugs = mutableSetOf<String>()

            for (link in links) {
                val href = link.attr("href")
                val slugMatch = Regex("""/watch/([^/?#"]+)""").find(href)
                val slug = slugMatch?.groupValues?.get(1) ?: continue
                if (seenSlugs.add(slug)) {
                    val text = link.text().trim().ifBlank { slug }
                    results.add(slug to text)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            failures.add(
                AniNekoExtractionFailure.Network(
                    safeUrl = baseUrl.toSafeDiagnosticUrl(),
                    stage = "search",
                )
            )
        }

        return results to failures
    }

    private data class HlsExtractResult(
        val url: HttpUrl,
        val tracks: List<AnimeStreamTrack> = emptyList(),
    )

    private suspend fun extractHls(
        embedUrl: HttpUrl,
        watchReferer: HttpUrl,
    ): Pair<HlsExtractResult?, List<AniNekoExtractionFailure>> {
        val failures = mutableListOf<AniNekoExtractionFailure>()

        try {
            val req = Request.Builder()
                .url(embedUrl)
                .addHeader("User-Agent", sanitizeHeaderValue(USER_AGENT))
                .addHeader("Referer", sanitizeHeaderValue(watchReferer.toString()))
                .build()

            val response = try {
                transport.get(req)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: AniNekoTransportException) {
                failures.add(
                    AniNekoExtractionFailure.Network(
                        safeUrl = embedUrl.toSafeDiagnosticUrl(),
                        stage = "embed",
                    )
                )
                return null to failures
            }

            if (response.code !in 200..299) {
                failures.add(
                    AniNekoExtractionFailure.HttpStatus(
                        safeUrl = embedUrl.toSafeDiagnosticUrl(),
                        stage = "embed",
                        code = response.code,
                    )
                )
                return null to failures
            }

            val html = response.body
            val finalEmbedUrl = response.finalUrl

            // Try multiple HLS URL patterns
            val m = Regex("""const\s+src\s*=\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""["'](https?://[^"']+/master\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""["'](\.\.?/[^"']*\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE).find(html) // relative

            val match = m?.groupValues?.get(1)
            if (match.isNullOrBlank()) {
                failures.add(
                    AniNekoExtractionFailure.NoMatch(
                        safeUrl = embedUrl.toSafeDiagnosticUrl(),
                        stage = "embed",
                    )
                )
                return null to failures
            }

            val decodedMatch = decodeEntities(match)

            // Resolve relative URLs against final embed URL after redirects
            val resolvedUrl = if (decodedMatch.startsWith("//")) {
                "${finalEmbedUrl.scheme}:$decodedMatch".toHttpUrlOrNull()
            } else if (decodedMatch.startsWith("http://") || decodedMatch.startsWith("https://")) {
                decodedMatch.toHttpUrlOrNull()
            } else {
                finalEmbedUrl.resolve(decodedMatch)
            }

            if (resolvedUrl == null) {
                failures.add(
                    AniNekoExtractionFailure.InvalidUrl(
                        safeUrl = embedUrl.toSafeDiagnosticUrl(),
                        stage = "embed",
                        reason = "Failed to resolve HLS URL: $decodedMatch",
                    )
                )
                return null to failures
            }

            // Verify media policy - check if it's accepted HLS
            // Probe manifest if needed for evidence. The probe must be a GET: a HEAD response
            // carries no body, so the MIME + "#EXTM3U" evidence could never be observed.
            val manifestResponse = if (!AniNekoMediaPolicy.isAcceptedHls(resolvedUrl, null)) {
                val manifestReq = Request.Builder()
                    .url(resolvedUrl)
                    .get()
                    .addHeader("User-Agent", sanitizeHeaderValue(USER_AGENT))
                    .addHeader("Referer", sanitizeHeaderValue(finalEmbedUrl.toString()))
                    .build()

                try {
                    transport.get(manifestReq)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: AniNekoTransportException) {
                    null
                }
            } else {
                null
            }

            if (!AniNekoMediaPolicy.isAcceptedHls(resolvedUrl, manifestResponse)) {
                failures.add(
                    AniNekoExtractionFailure.UnsupportedMedia(
                        safeUrl = embedUrl.toSafeDiagnosticUrl(),
                        stage = "embed",
                        reason = "URL does not meet HLS evidence criteria",
                    )
                )
                return null to failures
            }

            // Extract subtitle tracks
            val tracks = mutableListOf<AnimeStreamTrack>()
            val trackRegex = Regex("""<track[^>]+src=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
            for (tr in trackRegex.findAll(html)) {
                val trackTag = tr.value
                val src = tr.groupValues[1]
                if (src.isNotBlank() && !trackTag.contains("thumbnails", ignoreCase = true) && tracks.none { it.url == src }) {
                    val label = Regex("""label=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(trackTag)?.groupValues?.get(1) ?: "Subtitles"
                    val srclang = Regex("""srclang=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(trackTag)?.groupValues?.get(1) ?: "en"

                    // Resolve relative track URLs against final embed URL
                    val trackSrc = decodeEntities(src)
                    val resolvedTrackUrl = if (trackSrc.startsWith("//")) {
                        "${finalEmbedUrl.scheme}:$trackSrc"
                    } else if (trackSrc.startsWith("http://") || trackSrc.startsWith("https://")) {
                        trackSrc
                    } else {
                        finalEmbedUrl.resolve(trackSrc)?.toString() ?: trackSrc
                    }

                    tracks.add(AnimeStreamTrack(url = resolvedTrackUrl, label = label, lang = srclang))
                }
            }

            return HlsExtractResult(url = resolvedUrl, tracks = tracks) to failures
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            failures.add(
                AniNekoExtractionFailure.Network(
                    safeUrl = embedUrl.toSafeDiagnosticUrl(),
                    stage = "embed",
                )
            )
        }

        return null to failures
    }

    /**
     * Extract anime streams using canonical URLs, typed diagnostics, and HLS media policy.
     * Returns an outcome with successful streams and typed failures.
     * Cancellation is always propagated, never swallowed.
     */
    suspend fun extractWithOutcome(
        titleCandidates: List<String>,
        episodeNumber: Int,
        category: String,
    ): AniNekoExtractionOutcome = withContext(Dispatchers.IO) {
        val streams = mutableListOf<AnimeStreamResult>()
        val failures = mutableListOf<AniNekoExtractionFailure>()
        val targetCat = if (category.equals("dub", ignoreCase = true)) "dub" else "sub"

        try {
            var seriesSlug: String? = null

            // 1. Direct slug probe
            for (title in titleCandidates) {
                val potentialSlug = title
                    .lowercase()
                    .replace(Regex("""[^a-z0-9]+"""), "-")
                    .trim('-')
                if (potentialSlug.isBlank()) continue

                val seriesUrl = baseUrl.newBuilder()
                    .addPathSegment("watch")
                    .addPathSegment(potentialSlug)
                    .build()

                val probeUrl = baseUrl.newBuilder()
                    .addPathSegment("watch")
                    .addPathSegment(potentialSlug)
                    .addPathSegment("ep-$episodeNumber")
                    .build()

                val probeReq = Request.Builder()
                    .url(probeUrl)
                    .addHeader("User-Agent", sanitizeHeaderValue(USER_AGENT))
                    .addHeader("Referer", sanitizeHeaderValue(seriesUrl.toString()))
                    .build()

                try {
                    val response = transport.get(probeReq)
                    if (response.code in 200..299 && response.body.contains("nv-watch-page")) {
                        seriesSlug = potentialSlug
                        break
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: AniNekoTransportException) {
                    // Continue to next candidate
                }
            }

            // 2. Search fallback
            if (seriesSlug == null) {
                for (title in titleCandidates) {
                    val (searchResults, searchFailures) = search(title, baseUrl)
                    failures.addAll(searchFailures)

                    if (searchResults.isEmpty()) continue

                    val targetClean = cleanTitle(title)
                    for ((slug, text) in searchResults) {
                        val sClean = cleanTitle(slug)
                        val tClean = cleanTitle(text)
                        if (sClean == targetClean || tClean == targetClean || tClean.contains(targetClean)) {
                            seriesSlug = slug
                            break
                        }
                    }
                    if (seriesSlug != null) break
                }
            }

            if (seriesSlug == null) {
                return@withContext AniNekoExtractionOutcome(streams, failures)
            }

            // 3. Fetch watch page
            val seriesUrl = baseUrl.newBuilder()
                .addPathSegment("watch")
                .addPathSegment(seriesSlug)
                .build()

            val watchUrl = baseUrl.newBuilder()
                .addPathSegment("watch")
                .addPathSegment(seriesSlug)
                .addPathSegment("ep-$episodeNumber")
                .build()

            val watchReq = Request.Builder()
                .url(watchUrl)
                .addHeader("User-Agent", sanitizeHeaderValue(USER_AGENT))
                .addHeader("Referer", sanitizeHeaderValue(seriesUrl.toString()))
                .build()

            val watchResponse = try {
                transport.get(watchReq)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: AniNekoTransportException) {
                failures.add(
                    AniNekoExtractionFailure.Network(
                        safeUrl = watchUrl.toSafeDiagnosticUrl(),
                        stage = "watch",
                    )
                )
                return@withContext AniNekoExtractionOutcome(streams, failures)
            }

            if (watchResponse.code !in 200..299) {
                failures.add(
                    AniNekoExtractionFailure.HttpStatus(
                        safeUrl = watchUrl.toSafeDiagnosticUrl(),
                        stage = "watch",
                        code = watchResponse.code,
                    )
                )
                return@withContext AniNekoExtractionOutcome(streams, failures)
            }

            val watchHtml = watchResponse.body
            val watchFinalUrl = watchResponse.finalUrl

            if (watchHtml.isBlank()) {
                return@withContext AniNekoExtractionOutcome(streams, failures)
            }

            val doc = try {
                Jsoup.parse(watchHtml)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                failures.add(
                    AniNekoExtractionFailure.Parse(
                        safeUrl = watchUrl.toSafeDiagnosticUrl(),
                        stage = "watch",
                        reason = "Jsoup parse failed",
                    )
                )
                return@withContext AniNekoExtractionOutcome(streams, failures)
            }

            val byAudio = mutableMapOf("sub" to mutableListOf<String>(), "dub" to mutableListOf<String>())
            // Tracks extracted from an embed page must survive until the final stream is emitted;
            // byAudio stores only URL strings, so extracted tracks are keyed by the resolved URL.
            val tracksByUrl = mutableMapOf<String, List<AnimeStreamTrack>>()

            // Parse iframe if present
            val iframeSrc = doc.selectFirst("iframe")?.attr("src")
            if (!iframeSrc.isNullOrBlank()) {
                val decodedIframe = decodeEntities(iframeSrc)

                // Resolve relative iframe URL against watch page final URL
                val embedUrl = if (decodedIframe.startsWith("//")) {
                    "${watchFinalUrl.scheme}:$decodedIframe".toHttpUrlOrNull()
                } else if (decodedIframe.startsWith("http://") || decodedIframe.startsWith("https://")) {
                    decodedIframe.toHttpUrlOrNull()
                } else {
                    watchFinalUrl.resolve(decodedIframe)
                }

                if (embedUrl != null) {
                    val (extractResult, extractFailures) = extractHls(embedUrl, watchFinalUrl)
                    failures.addAll(extractFailures)

                    if (extractResult != null) {
                        byAudio[targetCat]?.add(extractResult.url.toString())
                        tracksByUrl[extractResult.url.toString()] = extractResult.tracks
                    }
                }
            }

            // Parse tabs to map tab classes to sub/dub
            val dubTabs = mutableSetOf<String>()
            val tabButtons = doc.select(".nv-server-tab, .tab")
            for (tab in tabButtons) {
                val tabText = tab.text().lowercase()
                val tabId = tab.attr("data-id").lowercase()
                if (tabText.contains("dub") || tabId.contains("dub")) {
                    for (c in tab.classNames()) {
                        if (c.startsWith("tab_")) {
                            dubTabs.add(c)
                        }
                    }
                }
            }

            // Parse server-video buttons
            val serverButtons = doc.select("button.server-video, .server-video")
            for (btn in serverButtons) {
                val video = btn.attr("data-video")
                if (video.isBlank()) continue
                val videoUrl = decodeEntities(video)
                val tabAttr = btn.attr("data-tab")
                val btnText = btn.text().lowercase()

                val isDub = dubTabs.contains(tabAttr) ||
                    tabAttr.contains("dub", ignoreCase = true) ||
                    btnText.contains("dub")
                val cat = if (isDub) "dub" else "sub"

                val list = byAudio.getOrPut(cat) { mutableListOf() }
                if (!list.contains(videoUrl)) {
                    list.add(videoUrl)
                }
            }

            // Process extracted URLs for the target category
            val targetUrls = byAudio[targetCat].orEmpty()
            for (urlString in targetUrls) {
                // Resolve data-video URLs (may be absolute or relative)
                val candidateUrl = if (urlString.startsWith("//")) {
                    "${watchFinalUrl.scheme}:$urlString".toHttpUrlOrNull()
                } else if (urlString.startsWith("http://") || urlString.startsWith("https://")) {
                    urlString.toHttpUrlOrNull()
                } else {
                    watchFinalUrl.resolve(urlString)
                }

                if (candidateUrl == null) {
                    failures.add(
                        AniNekoExtractionFailure.InvalidUrl(
                            safeUrl = watchUrl.toSafeDiagnosticUrl(),
                            stage = "watch",
                            reason = "Failed to resolve candidate URL: $urlString",
                        )
                    )
                    continue
                }

                var finalHlsUrl: HttpUrl? = null
                var tracks = emptyList<AnimeStreamTrack>()

                // Check if it's already HLS
                if (AniNekoMediaPolicy.isAcceptedHls(candidateUrl, null)) {
                    finalHlsUrl = candidateUrl
                } else {
                    // Try to extract HLS from embed page
                    val (extractResult, extractFailures) = extractHls(candidateUrl, watchFinalUrl)
                    failures.addAll(extractFailures)

                    if (extractResult != null) {
                        finalHlsUrl = extractResult.url
                        tracks = extractResult.tracks
                    }
                }

                // Only emit if we have a valid HLS URL
                if (finalHlsUrl != null) {
                    val effectiveTracks = if (tracks.isNotEmpty()) {
                        tracks
                    } else {
                        tracksByUrl[finalHlsUrl.toString()].orEmpty()
                    }
                    // Canonical referer and origin for playback
                    val canonicalReferer = baseUrl.newBuilder().build().toString()
                    val canonicalOrigin = baseUrl.scheme + "://" + baseUrl.host

                    streams.add(
                        AnimeStreamResult(
                            streamUrl = finalHlsUrl.toString(),
                            serverName = "AniNeko",
                            category = targetCat.uppercase(),
                            quality = "1080p",
                            tracks = effectiveTracks,
                            audioLanguage = if (targetCat == "sub") "jp" else null,
                            subtitleLanguages = effectiveTracks
                                .map { it.lang }
                                .filter { it.isNotBlank() }
                                .distinct(),
                            originalLanguage = "jp",
                            headers = mapOf(
                                "User-Agent" to sanitizeHeaderValue(USER_AGENT),
                                "Referer" to sanitizeHeaderValue(canonicalReferer),
                                "Origin" to sanitizeHeaderValue(canonicalOrigin)
                            )
                        )
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            failures.add(
                AniNekoExtractionFailure.Network(
                    safeUrl = baseUrl.toSafeDiagnosticUrl(),
                    stage = "extract",
                )
            )
        }

        AniNekoExtractionOutcome(streams, failures)
    }

    /**
     * Backward-compatible adapter: extracts streams and logs failures.
     * Preserves the original public API for existing consumers.
     */
    suspend fun extract(
        titleCandidates: List<String>,
        episodeNumber: Int,
        category: String,
    ): List<AnimeStreamResult> = withContext(Dispatchers.IO) {
        val outcome = extractWithOutcome(titleCandidates, episodeNumber, category)

        // Log failures for diagnostics
        for (failure in outcome.failures) {
            Log.w(TAG, "AniNeko extraction failure: ${failure.message}")
        }

        outcome.streams
    }
}

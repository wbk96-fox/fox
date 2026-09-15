package com.foxtv.app.core.fanfilm

import com.foxtv.app.domain.model.ProxyHeaders
import com.foxtv.app.domain.model.Stream
import com.foxtv.app.domain.model.StreamBehaviorHints
import com.foxtv.app.domain.model.Subtitle
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges FanFilm onto the app's existing source contract.
 *
 * FOX.TV already has one descriptor every provider funnels into — [Stream], grouped
 * into `AddonStreams` and rendered by the Source Picker — and one player behind it.
 * FanFilm reuses both (AGENTS.md §33/§44); it does not get a parallel player.
 *
 * The one wrinkle is that FanFilm sources are *unresolved*: turning a host link into
 * a stream means running ResolveURL, which is slow and can prompt, so it must happen
 * when the user picks a row, not for all 40 rows during discovery. Deferred
 * resolution is expressed with a private URI:
 *
 * ```
 * foxtv-fanfilm://resolve/<batch>/<token>
 * ```
 *
 * [isDeferredResolveUri] recognises it and [parseDeferredResolveUri] takes it apart,
 * so playback code can resolve just before it starts the player. The scheme is
 * private to the app and never leaves it.
 */
@Singleton
class FanFilmStreamMapper @Inject constructor() {

    /**
     * Represent one discovered source as a [Stream] for the Source Picker.
     *
     * The URL is the deferred-resolve marker rather than the raw host link: handing
     * the picker the unresolved link would make it look playable, and clicking it
     * would send an HTML page to the player.
     */
    fun toStream(source: FanFilmSource): Stream {
        val qualityLabel = source.quality.takeIf { it.isNotBlank() }
        val displayName = buildString {
            append(source.hosting.ifBlank { source.provider }.ifBlank { PROVIDER_NAME })
            qualityLabel?.let { append(" • ").append(it) }
            if (source.isPremium || source.isOnAccount) append(" • PREMIUM")
        }
        val descriptionParts = buildList {
            source.size.takeIf { it.isNotBlank() }?.let(::add)
            source.languages.takeIf { it.isNotEmpty() }?.let { add(it.joinToString("/")) }
            source.info.takeIf { it.isNotBlank() }?.let(::add)
            source.info2.takeIf { it.isNotBlank() }?.let(::add)
            source.debrid.takeIf { it.isNotBlank() }?.let { add("debrid: $it") }
        }

        return Stream(
            name = displayName,
            title = source.label.takeIf { it.isNotBlank() } ?: displayName,
            description = descriptionParts.takeIf { it.isNotEmpty() }?.joinToString(" • "),
            url = deferredResolveUri(source),
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = true,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                videoHash = null,
                videoSize = source.sizeBytes.takeIf { it > 0 },
                filename = source.filename.takeIf { it.isNotBlank() },
            ),
            addonName = PROVIDER_NAME,
            addonLogo = null,
            sources = null,
            quality = qualityLabel,
            qualityValue = source.qualityValue,
            clientResolve = null,
            debridCacheStatus = null,
            badges = emptyList(),
            subtitles = emptyList(),
        )
    }

    /** Group a whole listing for the Source Picker. */
    fun toStreams(listing: FanFilmSourceListing, ranked: List<FanFilmSource>): List<Stream> =
        ranked.filterNot { it.isPlaceholder }.map(::toStream)

    // ── deferred resolution ─────────────────────────────────────────────────

    fun deferredResolveUri(source: FanFilmSource): String =
        "$SCHEME://$AUTHORITY_RESOLVE/${encodeSegment(source.batch)}/${encodeSegment(source.token)}"

    fun isDeferredResolveUri(url: String?): Boolean =
        url != null && url.startsWith(RESOLVE_PREFIX)

    data class DeferredResolve(val batch: String, val token: String)

    /** Take apart a deferred-resolve URI, or return null when it is malformed. */
    fun parseDeferredResolveUri(url: String): DeferredResolve? {
        if (!isDeferredResolveUri(url)) return null
        // Strip the fixed scheme/authority prefix, then any query/fragment, and split the
        // remaining path. This is intentionally free of android.net.Uri: the marker is a
        // private, fixed-shape token pair we build ourselves, so a plain string split is
        // both correct and testable on the JVM.
        val path = url.removePrefix(RESOLVE_PREFIX)
            .substringBefore('#')
            .substringBefore('?')
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        val batch = decodeSegment(segments[0]).takeIf { it.isNotBlank() } ?: return null
        val token = decodeSegment(segments[1]).takeIf { it.isNotBlank() } ?: return null
        return DeferredResolve(batch = batch, token = token)
    }

    private fun encodeSegment(value: String): String =
        // URLEncoder targets application/x-www-form-urlencoded, which turns spaces into '+'
        // and leaves '*' alone; normalise both to their percent-encoded path forms so the
        // marker survives a round trip through path-segment decoding.
        URLEncoder.encode(value, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("*", "%2A")

    private fun decodeSegment(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())

    // ── resolved media → player inputs ──────────────────────────────────────

    /**
     * Turn a resolved FanFilm stream into a [Stream] the player accepts directly.
     *
     * Headers, cookies and referer travel in `behaviorHints.proxyHeaders.request`,
     * which is the channel the existing player already reads for addon streams, so no
     * new player path is introduced.
     */
    fun toPlayableStream(media: FanFilmResolvedMedia, displayTitle: String): Stream {
        val requestHeaders = buildMap {
            putAll(media.headers)
            if (media.referer.isNotBlank() && keys.none { it.equals("Referer", true) }) {
                put("Referer", media.referer)
            }
            if (media.cookies.isNotEmpty() && keys.none { it.equals("Cookie", true) }) {
                put("Cookie", media.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
        }

        return Stream(
            name = media.label.takeIf { it.isNotBlank() }
                ?: media.hosting.takeIf { it.isNotBlank() }
                ?: PROVIDER_NAME,
            title = displayTitle,
            description = media.quality.takeIf { it.isNotBlank() },
            url = media.url,
            ytId = media.pluginTarget?.videoId?.takeIf { it.isNotBlank() },
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = media.requiresAdaptivePipeline,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = requestHeaders.takeIf { it.isNotEmpty() }
                    ?.let { ProxyHeaders(request = it, response = null) },
                videoHash = null,
                videoSize = null,
                filename = null,
            ),
            addonName = PROVIDER_NAME,
            addonLogo = null,
            sources = null,
            quality = media.quality.takeIf { it.isNotBlank() },
            qualityValue = -1,
            clientResolve = null,
            debridCacheStatus = null,
            badges = emptyList(),
            subtitles = media.subtitles.map { subtitle ->
                Subtitle(
                    id = subtitle.url,
                    url = subtitle.url,
                    lang = subtitle.language.ifBlank { subtitle.label },
                    addonName = PROVIDER_NAME,
                    addonLogo = null,
                    isStreamProvided = true,
                )
            },
        )
    }

    companion object {
        const val PROVIDER_NAME = FanFilmProvider.PROVIDER_NAME

        /** Private scheme; never leaves the app. */
        const val SCHEME = "foxtv-fanfilm"
        const val AUTHORITY_RESOLVE = "resolve"
        private const val RESOLVE_PREFIX = "$SCHEME://$AUTHORITY_RESOLVE/"
    }
}

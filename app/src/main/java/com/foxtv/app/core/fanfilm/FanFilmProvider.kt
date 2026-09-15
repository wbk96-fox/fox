package com.foxtv.app.core.fanfilm

import android.util.Log
import com.foxtv.app.core.source.PolishSourcePriority
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * FanFilm as a source provider, in FOX.TV terms.
 *
 * Two operations, both cancellable and both mapped onto [FanFilmError]:
 *
 * [discover] runs FanFilm's own `get_sources()` and returns source *data*, so the
 * results appear in FOX.TV's Source Picker beside every other provider instead of in
 * a plugin-owned dialog (AGENTS.md §35/§39).
 *
 * [resolve] runs FanFilm's `resolve_source()` for one chosen source, which is what
 * drives ResolveURL, and returns the normalised [FanFilmResolvedMedia].
 *
 * Host reputation is updated here, at the only place that knows whether a specific
 * host produced a stream, so the fallback chain can skip hosts that just failed.
 */
@Singleton
class FanFilmProvider @Inject constructor(
    private val runtime: FanFilmRuntime,
    private val hostHealth: HostHealthTracker,
) {

    /**
     * Scan every enabled FanFilm provider.
     *
     * @param runId caller-owned run id; pass the same value to [FanFilmRuntime.cancelRun]
     *   to abort. Use [FanFilmRuntime.newRunId] to allocate one.
     */
    suspend fun discover(
        request: FanFilmMediaRequest,
        runId: Int,
    ): Result<FanFilmSourceListing> {
        val outcome = runtime.callBridge(
            "discover_sources",
            request.toJson(),
            runId,
        ) { raw -> JSONObject(raw) }

        val payload = outcome.getOrElse { return Result.failure(it) }

        if (!payload.optBoolean("ok", false)) {
            return Result.failure(payload.toFailure())
        }

        val batch = payload.optString("batch")
        val sourcesJson = payload.optJSONArray("sources")
        val sources = buildList {
            if (sourcesJson != null) {
                for (index in 0 until sourcesJson.length()) {
                    val entry = sourcesJson.optJSONObject(index) ?: continue
                    add(FanFilmSource.fromJson(batch, entry))
                }
            }
        }

        val listing = FanFilmSourceListing(
            batch = batch,
            mediaRef = payload.optString("mediaRef"),
            title = payload.optString("title"),
            year = payload.optInt("year", 0),
            sources = sources,
        )

        if (listing.playable.isEmpty()) {
            // Either nothing came back, or only FanFilm's placeholder entry did. Both
            // mean "no sources"; reporting it as such lets the UI say so and lets the
            // fallback chain move on instead of trying to play bundled filler media.
            Log.i(TAG, "no playable FanFilm sources for ${listing.title} (${listing.year})")
            return Result.failure(
                FanFilmStartupException(
                    FanFilmError.NoSources(
                        "FanFilm found no usable source for ${listing.title}"
                    )
                )
            )
        }

        Log.i(
            TAG,
            "FanFilm returned ${listing.playable.size} source(s) for " +
                "${listing.title} (${listing.year})",
        )
        return Result.success(listing)
    }

    /**
     * Resolve one source through ResolveURL.
     *
     * On success the host is recorded as healthy along with the measured latency; on a
     * resolver failure the host enters a temporary cooldown so [rank] deprioritises it
     * for the next attempt.
     */
    suspend fun resolve(source: FanFilmSource, runId: Int): Result<FanFilmResolvedMedia> {
        if (source.isPlaceholder) {
            return Result.failure(
                FanFilmStartupException(
                    FanFilmError.NoSources("that entry is FanFilm's placeholder, not a stream")
                )
            )
        }

        val request = JSONObject()
            .put("batch", source.batch)
            .put("token", source.token)
            .toString()

        val startedAt = System.currentTimeMillis()
        val outcome = runtime.callBridge("resolve_source", request, runId) { raw ->
            JSONObject(raw)
        }
        val elapsed = System.currentTimeMillis() - startedAt

        val payload = outcome.getOrElse { throwable ->
            hostHealth.recordFailure(source.hosting)
            return Result.failure(throwable)
        }

        if (!payload.optBoolean("ok", false)) {
            hostHealth.recordFailure(source.hosting)
            return Result.failure(payload.toFailure(host = source.hosting))
        }

        val media = FanFilmResolvedMedia.fromJson(payload)
        if (media.url.isBlank()) {
            hostHealth.recordFailure(source.hosting)
            return Result.failure(
                FanFilmStartupException(
                    FanFilmError.Resolver(source.hosting, "resolver returned an empty URL")
                )
            )
        }

        hostHealth.recordSuccess(media.host.ifBlank { source.hosting }, elapsed)
        Log.i(
            TAG,
            "resolved ${source.hosting} in ${elapsed}ms → ${media.streamType.wireValue}" +
                if (media.drm != null) " (DRM ${media.drm.scheme})" else "",
        )
        return Result.success(media)
    }

    /**
     * Order [sources] the way the picker and the fallback chain should try them:
     * quality first, then host reputation, then size.
     *
     * Hosts in cooldown are pushed down rather than removed — if every host just
     * failed the user still gets the full list.
     */
    fun rank(sources: List<FanFilmSource>): List<FanFilmSource> {
        val byHealth = hostHealth.rank(sources) { it.hosting }
        return byHealth.sortedWith(
            compareBy<FanFilmSource> {
                PolishSourcePriority.priority(it.provider.ifBlank { it.hosting })
            }
                .thenByDescending { it.qualityValue }
                .thenBy { hostHealth.isCoolingDown(it.hosting) }
                .thenByDescending { it.sizeBytes }
        )
    }

    private fun JSONObject.toFailure(host: String = ""): Throwable {
        val error = FanFilmError.fromKind(
            kind = optString("kind", "provider"),
            message = optString("message"),
            detail = optString("detail"),
            host = host,
        )
        return FanFilmStartupException(error)
    }

    companion object {
        private const val TAG = "FanFilmProvider"

        /** Display name used wherever FanFilm appears as a source group. */
        const val PROVIDER_NAME = "FanFilm"
    }
}

package com.foxtv.app.core.fanfilm

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live playback state, published by the player and read by the Kodi layer.
 *
 * FanFilm's service thread polls Kodi info labels to follow what is on screen
 * (`Player.Progress`, `VideoPlayer.UniqueID(tmdb)`, `Player.FilenameAndPath`, …) and
 * uses them for its watched-state bookkeeping. Those answers live in the player, so
 * the player pushes a [Session] here and the compatibility layer reads it. Without
 * this, `getInfoLabel` would return empty strings and FanFilm would never mark
 * anything as watched.
 *
 * The registry holds a single immutable snapshot in an [AtomicReference]: writes come
 * from the player's coroutine, reads from a Python worker thread, and neither should
 * block the other.
 */
@Singleton
class FanFilmPlaybackStateRegistry @Inject constructor() {

    data class Session(
        val url: String,
        val title: String,
        val tmdbId: String = "",
        val imdbId: String = "",
        val season: Int? = null,
        val episode: Int? = null,
        val year: Int? = null,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val isPlaying: Boolean = false,
    ) {
        val percentPlayed: Double
            get() = if (durationMs <= 0) 0.0 else (positionMs.toDouble() / durationMs) * 100.0
    }

    private val current = AtomicReference<Session?>(null)

    fun update(session: Session) {
        current.set(session)
    }

    fun updatePosition(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        current.getAndUpdate { existing ->
            existing?.copy(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
            )
        }
    }

    fun clear() {
        current.set(null)
    }

    fun session(): Session? = current.get()
}

/**
 * [FanFilmPlaybackStateProvider] backed by [FanFilmPlaybackStateRegistry].
 *
 * Named "No…" for nothing: it answers from whatever the player last published, and
 * reports "not playing" when there is no session — the same thing Kodi does.
 */
@Singleton
class NoPlaybackStateProvider @Inject constructor(
    private val registry: FanFilmPlaybackStateRegistry,
) : FanFilmPlaybackStateProvider {

    override fun isPlaybackActive(): Boolean = registry.session()?.isPlaying == true

    override fun infoLabel(label: String): String {
        val session = registry.session() ?: return ""
        return when (label.lowercase()) {
            "player.filenameandpath", "listitem.filenameandpath" -> session.url
            "player.filename" -> session.url.substringAfterLast('/')
            "player.title", "videoplayer.title", "listitem.label" -> session.title
            "player.progress", "listitem.percentplayed" -> session.percentPlayed.toInt().toString()
            "videoplayer.year", "listitem.year" -> session.year?.toString().orEmpty()
            "videoplayer.episode" -> session.episode?.toString().orEmpty()
            "videoplayer.season" -> session.season?.toString().orEmpty()
            "videoplayer.imdbnumber", "listitem.imdbnumber" -> session.imdbId
            "videoplayer.uniqueid(tmdb)", "listitem.uniqueid(tmdb)" -> session.tmdbId
            "videoplayer.uniqueid(imdb)", "listitem.uniqueid(imdb)" -> session.imdbId
            // FOX.TV has no Kodi video database, so there is no DBID to report. An
            // empty string is the correct answer, and FanFilm treats it as "not in the
            // library" rather than as an error.
            "videoplayer.dbid", "listitem.dbid" -> ""
            else -> ""
        }
    }
}

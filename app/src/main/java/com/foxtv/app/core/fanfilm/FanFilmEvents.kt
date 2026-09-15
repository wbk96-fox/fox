package com.foxtv.app.core.fanfilm

import androidx.compose.runtime.Immutable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One row in a FanFilm directory listing. */
@Immutable
data class FanFilmListItem(
    val url: String,
    val label: String,
    val label2: String = "",
    val isFolder: Boolean,
    val art: Map<String, String> = emptyMap(),
    val info: Map<String, String> = emptyMap(),
    val uniqueIds: Map<String, String> = emptyMap(),
    val contextMenu: List<ContextAction> = emptyList(),
) {
    @Immutable
    data class ContextAction(val label: String, val action: String)

    val poster: String? get() = art["poster"] ?: art["thumb"]
    val backdrop: String? get() = art["fanart"] ?: art["landscape"]
    val plot: String? get() = info["plot"]
    val year: String? get() = info["year"]
    val mediaType: String? get() = info["mediaType"]
    val tmdbId: String? get() = uniqueIds["tmdb"]
    val imdbId: String? get() = uniqueIds["imdb"]
}

/** Properties FanFilm set on the resolved ListItem (inputstream directives, MIME). */
@Immutable
data class FanFilmPlaybackProperties(val values: Map<String, String>) {
    val mimeType: String? get() = values["mimetype"]
    val usesAdaptive: Boolean get() = values.containsKey("inputstream")
    val streamHeaders: String? get() = values["inputstream.adaptive.stream_headers"]
    val manifestHeaders: String? get() = values["inputstream.adaptive.manifest_headers"]
    val drmConfig: String? get() = values["inputstream.adaptive.drm"]
    val licenseKey: String? get() = values["inputstream.adaptive.license_key"]
    val licenseType: String? get() = values["inputstream.adaptive.license_type"]

    companion object {
        val EMPTY = FanFilmPlaybackProperties(emptyMap())
    }
}

/**
 * Everything the Python side pushes at the app, tagged with the run it belongs to.
 *
 * Consumers must compare [runId] against the run they started; the bus itself does
 * not filter, because more than one screen can be listening (the browser, the
 * source picker, the player hand-off).
 */
enum class FanFilmDirectoryOutcome {
    SUCCESS_DIRECTORY,
    EMPTY_DIRECTORY,
}

sealed interface FanFilmEvent {
    val runId: Int
    val navigationGeneration: Long
    val pluginUrl: String?

    data class Directory(
        override val runId: Int,
        val category: String,
        val view: String,
        val items: List<FanFilmListItem>,
        val outcome: FanFilmDirectoryOutcome = FanFilmDirectoryOutcome.SUCCESS_DIRECTORY,
        override val navigationGeneration: Long = 0L,
        override val pluginUrl: String? = null,
    ) : FanFilmEvent

    data class Resolved(
        override val runId: Int,
        val succeeded: Boolean,
        val url: String,
        val label: String,
        val mimeType: String,
        val properties: FanFilmPlaybackProperties,
        override val navigationGeneration: Long = 0L,
        override val pluginUrl: String? = null,
    ) : FanFilmEvent

    data class Play(
        override val runId: Int,
        val url: String,
        val properties: FanFilmPlaybackProperties,
        override val navigationGeneration: Long = 0L,
        override val pluginUrl: String? = null,
    ) : FanFilmEvent

    data class Navigate(
        override val runId: Int,
        val url: String,
        val replaceCurrent: Boolean,
        override val navigationGeneration: Long = 0L,
        override val pluginUrl: String? = null,
    ) : FanFilmEvent

    data class Refresh(
        override val runId: Int,
        override val navigationGeneration: Long = 0L,
        override val pluginUrl: String? = null,
    ) : FanFilmEvent

    data class ScanProgress(
        override val runId: Int,
        val percent: Int,
        val message: String,
        val providers: List<String>,
        override val navigationGeneration: Long = 0L,
        override val pluginUrl: String? = null,
    ) : FanFilmEvent

    data class Finished(
        override val runId: Int,
        override val navigationGeneration: Long = 0L,
        override val pluginUrl: String? = null,
    ) : FanFilmEvent

    data class Failed(
        override val runId: Int,
        val error: FanFilmError,
        override val navigationGeneration: Long = 0L,
        override val pluginUrl: String? = null,
    ) : FanFilmEvent

    data class Notification(
        override val runId: Int,
        val heading: String,
        val message: String,
        val level: Level,
        override val navigationGeneration: Long = 0L,
        override val pluginUrl: String? = null,
    ) : FanFilmEvent {
        enum class Level { INFO, WARNING, ERROR }
    }
}

/**
 * Hot bus carrying [FanFilmEvent]s from the Python worker threads to the UI.
 *
 * A [MutableSharedFlow] with a replay of 0 is deliberate: a late subscriber must
 * not be handed a stale directory from a previous run. Screens start their run
 * *after* subscribing, so nothing is missed.
 *
 * The buffer drops the oldest entry under pressure rather than suspending, because
 * the producer is a Python thread inside a plugin call: suspending it would stall
 * the scraper.
 */
@Singleton
class FanFilmEventBus @Inject constructor() {

    private val _events = MutableSharedFlow<FanFilmEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<FanFilmEvent> = _events.asSharedFlow()

    /** Publish without suspending. Returns false when the event was dropped. */
    fun publish(event: FanFilmEvent): Boolean = _events.tryEmit(event)
}

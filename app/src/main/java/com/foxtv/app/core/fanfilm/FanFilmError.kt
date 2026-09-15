package com.foxtv.app.core.fanfilm

/**
 * Typed failures from the FanFilm provider.
 *
 * The UI has to tell "nothing was found" apart from "the host is dead" apart from
 * "this device cannot decrypt the stream" — a single error string cannot carry
 * that (AGENTS.md §42). Every failure that crosses the Python bridge arrives with
 * a `kind` discriminator which [fromKind] maps onto one of these.
 *
 * [userMessageRes] points at the string the UI shows; [technicalDetail] is kept
 * for diagnostics and is never displayed on its own.
 */
sealed class FanFilmError(
    open val technicalDetail: String,
    open val cause: Throwable? = null,
) {
    /** The runtime is not usable: missing addon tree, unwritable data dir, bad config. */
    data class Configuration(
        override val technicalDetail: String,
        override val cause: Throwable? = null,
    ) : FanFilmError(technicalDetail, cause)

    /** The Python interpreter or the addon itself raised. */
    data class Plugin(
        override val technicalDetail: String,
        val traceback: String = "",
        override val cause: Throwable? = null,
    ) : FanFilmError(technicalDetail, cause)

    /** Provider scan completed but produced no usable source. */
    data class NoSources(
        override val technicalDetail: String = "provider scan returned no sources",
    ) : FanFilmError(technicalDetail)

    /** A host was reachable but no resolver produced a stream. */
    data class Resolver(
        val host: String,
        override val technicalDetail: String,
    ) : FanFilmError(technicalDetail)

    /** Transport-level failure (DNS, TLS, timeout, 4xx/5xx). */
    data class Network(
        override val technicalDetail: String,
        val statusCode: Int? = null,
        override val cause: Throwable? = null,
    ) : FanFilmError(technicalDetail, cause)

    /** The resolved stream needs DRM this device cannot satisfy. */
    data class Drm(
        val scheme: String,
        override val technicalDetail: String,
    ) : FanFilmError(technicalDetail)

    /** The descriptor cannot be handed to the player (unknown scheme, empty URL). */
    data class Playback(
        override val technicalDetail: String,
        override val cause: Throwable? = null,
    ) : FanFilmError(technicalDetail, cause)

    /** A previous FanFilm call is still running and would not yield the lock. */
    data class Busy(
        override val technicalDetail: String,
    ) : FanFilmError(technicalDetail)

    /** Kodi-style termination produced no directory/playback output. */
    data class SystemExitNoOutput(
        override val technicalDetail: String,
    ) : FanFilmError(technicalDetail)

    /** A browser run returned without publishing a directory. */
    data class NoDirectoryPublished(
        override val technicalDetail: String,
    ) : FanFilmError(technicalDetail)

    /** The caller cancelled; not an error the user needs to see. */
    data object Cancelled : FanFilmError("cancelled")

    /** An addon/resolver update could not be applied. */
    data class Update(
        override val technicalDetail: String,
        override val cause: Throwable? = null,
    ) : FanFilmError(technicalDetail, cause)

    val isRetryable: Boolean
        get() = when (this) {
            is Network, is Resolver, is Busy -> true
            is NoSources -> true
            is SystemExitNoOutput, is NoDirectoryPublished -> true
            is Configuration, is Plugin, is Drm, is Playback, is Update, Cancelled -> false
        }

    companion object {
        /**
         * Map the `kind` discriminator produced by `foxtv_fanfilm` onto a typed error.
         *
         * Unknown kinds become [Plugin] rather than being swallowed, so a bridge
         * change that adds a kind shows up as a real error instead of silence.
         */
        fun fromKind(
            kind: String,
            message: String,
            detail: String = "",
            host: String = "",
        ): FanFilmError = when (kind.lowercase()) {
            "configuration" -> Configuration(message)
            "cancelled" -> Cancelled
            "busy" -> Busy(message)
            "resolver" -> Resolver(host = host, technicalDetail = message)
            "network" -> Network(message)
            "drm" -> Drm(scheme = host, technicalDetail = message)
            "playback" -> Playback(message)
            "nosources", "no_sources" -> NoSources(message)
            "system_exit_no_output" -> SystemExitNoOutput(message)
            "no_directory_published" -> NoDirectoryPublished(message)
            "update" -> Update(message)
            else -> Plugin(message, traceback = detail)
        }
    }
}

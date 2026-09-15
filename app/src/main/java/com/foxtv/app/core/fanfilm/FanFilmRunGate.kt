package com.foxtv.app.core.fanfilm

/** Generation-aware lifecycle guard for the long-lived FanFilm interpreter. */
class FanFilmRunGate {
    data class Context(val generation: Long, val runId: Int, val pluginUrl: String)

    private val lock = Any()
    private var active: Context? = null
    private var inFlight = false

    fun isInFlightFor(url: String): Boolean = synchronized(lock) {
        inFlight && active?.pluginUrl == url
    }

    fun begin(generation: Long, runId: Int, url: String) {
        synchronized(lock) {
            active = Context(generation, runId, url)
            inFlight = true
        }
    }

    fun accepts(generation: Long, runId: Int, url: String?): Boolean = synchronized(lock) {
        if (runId == 0) return@synchronized true
        val current = active ?: return@synchronized false
        current.runId == runId && current.generation == generation &&
            (url == null || url == current.pluginUrl)
    }

    fun accepts(runId: Int): Boolean = synchronized(lock) {
        runId == 0 || active?.runId == runId
    }

    fun context(): Context? = synchronized(lock) { active }

    fun finish(runId: Int) {
        synchronized(lock) {
            if (active?.runId == runId) inFlight = false
        }
    }

    fun cancel(runId: Int) {
        synchronized(lock) {
            if (active?.runId == runId) {
                active = null
                inFlight = false
            }
        }
    }
}

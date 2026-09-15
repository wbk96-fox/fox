package com.foxtv.app.core.fanfilm

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Blocking dialog bridge between the FanFilm Python worker and Compose.
 *
 * FanFilm asks questions synchronously — `Dialog.select()` returns an index, and
 * the scraper it came from continues on the next line. So the Python worker thread
 * has to block while a human answers. That is safe because it is never the Android
 * main thread, and because the Python side releases its execution lock for the
 * duration (see `foxtv_fanfilm.runner.released_lock`).
 *
 * What this class guarantees (AGENTS.md §19):
 *
 * *Correlation.* Every request gets a monotonic id and carries the run id that
 * produced it. A response for a different id, or for a run that has since been
 * retired, is rejected instead of being applied to the wrong question.
 *
 * *Cancellation.* [cancelRun] releases every waiter belonging to that run at once,
 * so cancelling a search does not leave a Python thread parked for the full
 * timeout.
 *
 * *Timeout.* A request nobody answers resolves as `timeout` rather than blocking
 * the worker forever.
 *
 * *Concurrency.* Requests are queued. FanFilm's service thread can raise a
 * notification while a scan is asking something; the UI renders one dialog at a
 * time and the rest wait their turn.
 *
 * *Cleanup.* Answered, cancelled and timed-out entries are always removed, in a
 * `finally`, so the map cannot grow across a session.
 */
@Singleton
class FanFilmDialogCoordinator @Inject constructor() {

    enum class Kind { OK, YES_NO, SELECT, MULTI_SELECT, INPUT, NUMERIC }

    /** Kodi input types, from `xbmcgui.INPUT_*`. */
    enum class InputType { TEXT, PASSWORD, NUMBER, DATE, TIME, IP_ADDRESS, ALPHANUM }

    data class Request(
        val id: Long,
        val runId: Int,
        val kind: Kind,
        val heading: String,
        val message: String = "",
        val options: List<String> = emptyList(),
        val preselected: List<Int> = emptyList(),
        val defaultValue: String = "",
        val inputType: InputType = InputType.TEXT,
        val positiveLabel: String = "",
        val negativeLabel: String = "",
    )

    private sealed interface Outcome {
        data class Answered(val value: Any?) : Outcome
        data object Cancelled : Outcome
        data object Stale : Outcome
        data object TimedOut : Outcome
    }

    private class Pending(
        val request: Request,
        val latch: CountDownLatch = CountDownLatch(1),
    ) {
        @Volatile
        var outcome: Outcome = Outcome.TimedOut
    }

    private val nextId = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val order = ArrayDeque<Long>()
    private val orderLock = Any()

    private val _visible = MutableStateFlow<Request?>(null)

    /** The dialog the UI should currently show, or null. */
    val visible: StateFlow<Request?> = _visible

    /**
     * Block the caller until the user answers [request], or until it is cancelled
     * or times out. Returns the JSON envelope the Python side expects.
     */
    fun await(
        runId: Int,
        kind: Kind,
        heading: String,
        message: String = "",
        options: List<String> = emptyList(),
        preselected: List<Int> = emptyList(),
        defaultValue: String = "",
        inputType: InputType = InputType.TEXT,
        positiveLabel: String = "",
        negativeLabel: String = "",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): String {
        if (retiredRuns.contains(runId)) {
            return envelope(STATE_STALE, null)
        }

        val id = nextId.incrementAndGet()
        val entry = Pending(
            Request(
                id = id,
                runId = runId,
                kind = kind,
                heading = heading,
                message = message,
                options = options,
                preselected = preselected,
                defaultValue = defaultValue,
                inputType = inputType,
                positiveLabel = positiveLabel,
                negativeLabel = negativeLabel,
            )
        )

        pending[id] = entry
        enqueue(id)
        try {
            val answered = entry.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!answered) {
                entry.outcome = Outcome.TimedOut
                Log.w(TAG, "dialog $id ($kind) timed out after ${timeoutMs}ms")
            }
            return when (val outcome = entry.outcome) {
                is Outcome.Answered -> envelope(STATE_OK, outcome.value)
                Outcome.Cancelled -> envelope(STATE_CANCELLED, null)
                Outcome.Stale -> envelope(STATE_STALE, null)
                Outcome.TimedOut -> envelope(STATE_TIMEOUT, null)
            }
        } finally {
            pending.remove(id)
            dequeue(id)
        }
    }

    /** Answer the dialog currently shown. Ignored when [id] is no longer pending. */
    fun respond(id: Long, value: Any?) {
        complete(id, Outcome.Answered(value))
    }

    /** The user dismissed the dialog. */
    fun dismiss(id: Long) {
        complete(id, Outcome.Cancelled)
    }

    /**
     * Retire every dialog belonging to [runId].
     *
     * Called when a run is cancelled or superseded so its Python thread stops
     * waiting immediately instead of at the timeout.
     */
    fun cancelRun(runId: Int) {
        retiredRuns.add(runId)
        if (retiredRuns.size > RETIRED_RUN_HISTORY) {
            retiredRuns.sorted().take(RETIRED_RUN_HISTORY / 2).forEach(retiredRuns::remove)
        }
        pending.values
            .filter { it.request.runId == runId }
            .forEach { complete(it.request.id, Outcome.Stale) }
    }

    /** Release everything; used when the runtime shuts down. */
    fun cancelAll() {
        pending.keys.toList().forEach { complete(it, Outcome.Cancelled) }
        synchronized(orderLock) {
            order.clear()
        }
        _visible.value = null
    }

    private fun complete(id: Long, outcome: Outcome) {
        val entry = pending[id] ?: return
        entry.outcome = outcome
        entry.latch.countDown()
        dequeue(id)
    }

    private fun enqueue(id: Long) {
        synchronized(orderLock) {
            order.addLast(id)
            publishHead()
        }
    }

    private fun dequeue(id: Long) {
        synchronized(orderLock) {
            order.remove(id)
            publishHead()
        }
    }

    private fun publishHead() {
        // Called under orderLock.
        var head: Request? = null
        while (order.isNotEmpty()) {
            val candidate = order.first()
            val entry = pending[candidate]
            if (entry == null) {
                order.removeFirst()
                continue
            }
            head = entry.request
            break
        }
        _visible.value = head
    }

    private fun envelope(state: String, value: Any?): String {
        val payload = JSONObject().put("state", state)
        when (value) {
            null -> payload.put("value", JSONObject.NULL)
            is List<*> -> payload.put("value", JSONArray(value))
            else -> payload.put("value", value)
        }
        return payload.toString()
    }

    private val retiredRuns = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    companion object {
        private const val TAG = "FanFilmDialogs"

        /**
         * How long a question waits for an answer.
         *
         * Long enough that a user can read a source list and pick with a remote,
         * short enough that an abandoned session does not pin a Python thread.
         */
        const val DEFAULT_TIMEOUT_MS = 120_000L

        private const val RETIRED_RUN_HISTORY = 256

        const val STATE_OK = "ok"
        const val STATE_CANCELLED = "cancelled"
        const val STATE_STALE = "stale"
        const val STATE_TIMEOUT = "timeout"
        const val STATE_UNAVAILABLE = "unavailable"
    }
}

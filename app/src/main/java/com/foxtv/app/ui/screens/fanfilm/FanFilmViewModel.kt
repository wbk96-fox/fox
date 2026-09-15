package com.foxtv.app.ui.screens.fanfilm

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtv.app.core.fanfilm.FanFilmDialogCoordinator
import com.foxtv.app.core.fanfilm.FanFilmError
import com.foxtv.app.core.fanfilm.FanFilmEvent
import com.foxtv.app.core.fanfilm.FanFilmEventBus
import com.foxtv.app.core.fanfilm.FanFilmListItem
import com.foxtv.app.core.fanfilm.FanFilmRuntime
import com.foxtv.app.core.fanfilm.FanFilmRunGate
import com.foxtv.app.core.fanfilm.FanFilmStartupException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Drives the FanFilm browser: the plugin's own directory tree, rendered by FOX.TV.
 *
 * The plugin is a directory-based Kodi addon, so browsing is "run the plugin for a
 * URL, receive a listing". Each invocation gets a run id; navigating away or opening
 * a new folder cancels the previous run so a slow scrape cannot repaint a screen the
 * user has already left (AGENTS.md §60/§63).
 *
 * Back navigation is a local stack of plugin URLs rather than the Compose backstack:
 * a FanFilm folder is not a navigation destination, it is a state of this screen, and
 * modelling it as such is what keeps state restoration working when the user leaves
 * and returns.
 */
@HiltViewModel
class FanFilmViewModel @Inject constructor(
    private val runtime: FanFilmRuntime,
    private val events: FanFilmEventBus,
    private val dialogs: FanFilmDialogCoordinator,
) : ViewModel() {

    @Immutable
    data class UiState(
        val isStarting: Boolean = true,
        val isLoading: Boolean = false,
        val title: String = "",
        val items: List<FanFilmListItem> = emptyList(),
        val breadcrumbs: List<String> = emptyList(),
        val error: FanFilmError? = null,
        val progress: Progress? = null,
        val notice: String? = null,
        val startupSummary: String = "",
        val navigationGeneration: Long = 0L,
        val activeRunId: Int = 0,
        val activePluginUrl: String? = null,
        val directoryOutcome: FanFilmDirectoryOutcome? = null,
    ) {
        val isEmpty: Boolean get() = !isLoading && !isStarting && items.isEmpty() && error == null
        val canGoBack: Boolean get() = breadcrumbs.size > 1
    }

    @Immutable
    data class Progress(val percent: Int, val message: String, val providers: List<String>)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Blocking question the plugin is currently asking, rendered by the dialog host. */
    val dialog: StateFlow<FanFilmDialogCoordinator.Request?> = dialogs.visible

    /** Plugin URL stack; the last entry is what is on screen. */
    private val stack = ArrayDeque<String>()

    /** Guards against duplicate activations superseding an in-flight run. */
    private val runGate = FanFilmRunGate()

    private var activeRunId = 0
    private val generationCounter = AtomicLong(0L)
    private var activeGeneration = 0L
    private var eventJob: Job? = null

    init {
        observeEvents()
        viewModelScope.launch { start() }
    }

    private fun observeEvents() {
        eventJob?.cancel()
        eventJob = viewModelScope.launch {
            events.events.collect { event ->
                // Ignore anything belonging to a superseded run. Notifications carry
                // run 0 because they are not tied to an invocation.
                if (!runGate.accepts(event.navigationGeneration, event.runId, event.pluginUrl)) return@collect
                when (event) {
                    is FanFilmEvent.Directory -> {
                        runGate.finish(event.runId)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                items = event.items,
                                title = event.category.ifBlank { it.title },
                                error = null,
                                progress = null,
                                navigationGeneration = event.navigationGeneration,
                                activeRunId = event.runId,
                                activePluginUrl = event.pluginUrl,
                                directoryOutcome = event.outcome,
                                notice = event.outcome.takeIf { it == FanFilmDirectoryOutcome.EMPTY_DIRECTORY }?.let {
                                    "FanFilm returned an empty directory"
                                },
                            )
                        }
                    }

                    is FanFilmEvent.Finished -> {
                        runGate.finish(event.runId)
                        _uiState.update { it.copy(isLoading = false, progress = null) }
                    }

                    is FanFilmEvent.Failed -> {
                        runGate.finish(event.runId)
                        _uiState.update { it.copy(isLoading = false, error = event.error, progress = null) }
                    }

                    is FanFilmEvent.ScanProgress -> _uiState.update {
                        it.copy(
                            progress = Progress(event.percent, event.message, event.providers)
                        )
                    }

                    is FanFilmEvent.Notification -> _uiState.update {
                        it.copy(notice = "${event.heading}: ${event.message}".trim(':', ' '))
                    }

                    is FanFilmEvent.Navigate -> if (event.replaceCurrent) {
                        replace(event.url)
                    } else {
                        open(event.url)
                    }

                    is FanFilmEvent.Refresh -> reload()

                    // Playback outcomes are consumed by the playback layer, not the
                    // browser; marking the run terminal here only releases the same-URL
                    // guard so the user may retry the exact same row afterwards.
                    is FanFilmEvent.Resolved, is FanFilmEvent.Play -> runGate.finish(event.runId)
                }
            }
        }
    }

    private suspend fun start() {
        _uiState.update { it.copy(isStarting = true, error = null) }
        runtime.ensureStarted().fold(
            onSuccess = { report ->
                _uiState.update {
                    it.copy(
                        isStarting = false,
                        startupSummary = buildString {
                            append("FanFilm ")
                            append(report.addonVersions[FANFILM_ADDON_ID] ?: "?")
                            append(" · Python ").append(report.pythonVersion)
                            if (!report.serviceStarted) append(" · service not running")
                        },
                    )
                }
                open(ROOT_URL)
            },
            onFailure = { throwable ->
                _uiState.update {
                    it.copy(isStarting = false, error = throwable.toFanFilmError())
                }
            },
        )
    }

    /** Open a plugin URL, pushing it on the stack. */
    fun open(pluginUrl: String) {
        if (pluginUrl.isBlank()) return
        run(pluginUrl, push = true)
    }

    /** Replace the current entry (Kodi's `Container.Update` semantics). */
    fun replace(pluginUrl: String) {
        if (pluginUrl.isBlank()) return
        if (stack.isNotEmpty()) stack.removeLast()
        run(pluginUrl, push = true)
    }

    /** Re-run the current URL. */
    fun reload() {
        stack.lastOrNull()?.let { run(it, push = false, force = true) }
    }

    /**
     * Go up one level.
     *
     * @return false when already at the root, so the caller can let the system Back
     *   leave the screen instead of swallowing the press.
     */
    fun goBack(): Boolean {
        if (stack.size <= 1) return false
        cancelActiveRun()
        stack.removeLast()
        val target = stack.lastOrNull() ?: return false
        run(target, push = false)
        return true
    }

    /** Called when the user selects a row. */
    fun onItemSelected(item: FanFilmListItem) {
        if (item.isFolder) {
            open(item.url)
        } else {
            // A non-folder row is a playable item: running its URL makes the plugin
            // resolve it and emit Resolved/Play, which the playback layer handles.
            run(item.url, push = false)
        }
    }

    fun onDialogResponse(id: Long, value: Any?) {
        dialogs.respond(id, value)
    }

    fun onDialogDismissed(id: Long) {
        dialogs.dismiss(id)
    }

    fun clearNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    fun retry() {
        viewModelScope.launch {
            if (runtime.state.value is FanFilmRuntime.State.Ready) reload() else start()
        }
    }

    private fun run(pluginUrl: String, push: Boolean = true, force: Boolean = false) {
        // A repeated press on the row that is already loading must not supersede the
        // in-flight run: cancelling it discards a scrape that may already have
        // finished and is how a rapid second press used to end in an empty folder.
        if (!force && runGate.isInFlightFor(pluginUrl)) {
            Log.i(VM_TAG, "run($pluginUrl) ignored: same URL already in flight")
            return
        }
        cancelActiveRun()
        if (push) stack.addLast(pluginUrl)
        val generation = generationCounter.incrementAndGet()
        activeGeneration = generation
        val runId = runtime.newRunId()
        activeRunId = runId
        runtime.registerRunContext(generation, runId, pluginUrl)
        runGate.begin(generation, runId, pluginUrl)
        Log.i(VM_TAG, "run generation=$generation url=$pluginUrl push=$push force=$force runId=$runId")
        _uiState.update {
            it.copy(
                isLoading = true,
                error = null,
                progress = null,
                directoryOutcome = null,
                breadcrumbs = stack.toList(),
                navigationGeneration = generation,
                activeRunId = runId,
                activePluginUrl = pluginUrl,
            )
        }
        viewModelScope.launch {
            runtime.callBridge("run_plugin", pluginUrl, runId) { it }
                .onFailure { throwable ->
                    // The run never reached the plugin (startup/busy/transport), so no
                    // terminal event will arrive; release the same-URL guard here.
                    runGate.finish(runId)
                    if (runGate.accepts(generation, runId, pluginUrl)) {
                        runtime.clearRunContext(runId)
                        _uiState.update {
                            it.copy(isLoading = false, error = throwable.toFanFilmError())
                        }
                    }
                }
        }
    }

    private fun cancelActiveRun() {
        val runId = activeRunId
        if (runId != 0) {
            runtime.cancelRun(runId)
            runtime.clearRunContext(runId)
            runGate.cancel(runId)
            activeRunId = 0
        }
    }

    override fun onCleared() {
        cancelActiveRun()
        eventJob?.cancel()
        super.onCleared()
    }

    private fun Throwable.toFanFilmError(): FanFilmError =
        (this as? FanFilmStartupException)?.error
            ?: FanFilmError.Plugin(message ?: this::class.java.simpleName, cause = this)

    private inline fun MutableStateFlow<UiState>.update(block: (UiState) -> UiState) {
        value = block(value)
    }

    companion object {
        private const val VM_TAG = "FanFilmVM"
        private const val FANFILM_ADDON_ID = "plugin.video.fanfilm"

        /** The plugin's root directory, exactly as Kodi invokes it. */
        const val ROOT_URL = "plugin://plugin.video.fanfilm/"
    }
}

package com.foxtv.app.core.fanfilm

import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Owns the embedded CPython interpreter and everything that talks to it.
 *
 * Threading model
 * ---------------
 * Chaquopy runs FanFilm on the calling thread, and FanFilm assumes Kodi's
 * one-call-at-a-time contract (`sys.argv` and `xbmcplugin._directory` are module
 * state). Every entry point therefore runs on [dispatcher], a single-threaded
 * executor, and the Python side additionally holds its own execution lock. The
 * Android main thread is never used: interpreter startup alone is hundreds of
 * milliseconds, and a plugin call can block on a user dialog.
 *
 * Lifecycle
 * ---------
 * [ensureStarted] is idempotent and safe to race: the first caller installs the
 * addon tree, starts the interpreter and configures the bridge; later callers reuse
 * the result. A failure is cached as a [FanFilmError] so every subsequent call
 * reports the same actionable message instead of retrying an unusable setup on each
 * screen.
 *
 * Cancellation
 * ------------
 * Runs are identified by a monotonic id. [cancelRun] retires the id, which the
 * Python side observes through `bridge.is_cancelled` — polled by FanFilm's own
 * provider-scan loop through the injected progress dialog — and which releases any
 * dialog that run is blocked on. Cancellation therefore stops real work rather than
 * just detaching the UI.
 */
@Singleton
class FanFilmRuntime @Inject constructor(
    private val paths: FanFilmPaths,
    private val installer: FanFilmAssetInstaller,
    private val dialogs: FanFilmDialogCoordinator,
    private val events: FanFilmEventBus,
    private val capabilities: FanFilmPlayerCapabilities,
    private val playbackState: FanFilmPlaybackStateProvider,
) : FanFilmBridgeHost {

    /** Startup outcome, observable so the UI can show progress and errors. */
    sealed interface State {
        data object Idle : State
        data object Starting : State
        data class Ready(val report: StartupReport) : State
        data class Failed(val error: FanFilmError) : State
    }

    data class StartupReport(
        val bridgeVersion: String,
        val pythonVersion: String,
        val kodiHome: String,
        val addonVersions: Map<String, String>,
        val serviceStarted: Boolean,
        val settingsPath: String,
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val runCounter = AtomicInteger(0)
    private val retiredRuns = ConcurrentHashMap.newKeySet<Int>()
    private val runContexts = ConcurrentHashMap<Int, FanFilmRunGate.Context>()

    private val executor = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, "foxtv-fanfilm").apply {
                isDaemon = true
                // Below default so a long provider scan cannot starve the UI thread
                // on a single-core TV box.
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    )

    /** Single-threaded dispatcher every Python call runs on. */
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    @Volatile
    private var module: PyObject? = null

    private val startLock = Any()

    /** Allocate the next run id and retire nothing; the caller owns cancellation. */
    fun newRunId(): Int = runCounter.incrementAndGet()

    fun registerRunContext(generation: Long, runId: Int, pluginUrl: String) {
        runContexts[runId] = FanFilmRunGate.Context(generation, runId, pluginUrl)
    }

    fun clearRunContext(runId: Int) {
        runContexts.remove(runId)
    }

    /**
     * Retire [runId]. Idempotent.
     *
     * Releases dialogs immediately, tells the Python side (so the scan loop stops),
     * and drops the id from the live set.
     */
    fun cancelRun(runId: Int) {
        if (runId <= 0) return
        retiredRuns.add(runId)
        runContexts.remove(runId)
        trimRetired()
        dialogs.cancelRun(runId)
        val target = module ?: return
        // Fire and forget on the Python thread: cancel must not block the caller,
        // and the retired-set above already makes the cancellation observable.
        executor.execute {
            runCatching { target.callAttr("cancel_run", runId) }
                .onFailure { Log.w(TAG, "cancel_run($runId) failed", it) }
        }
    }

    private fun trimRetired() {
        if (retiredRuns.size <= RETIRED_HISTORY) return
        val cutoff = runCounter.get() - RETIRED_HISTORY
        retiredRuns.removeAll { it < cutoff }
    }

    /**
     * Start the interpreter if needed.
     *
     * Returns the report on success. On failure the error is both returned and
     * published on [state]; it is not retried automatically, because every failure
     * mode here (missing addon tree, unwritable data dir, incompatible bridge) needs
     * a code or environment fix rather than another attempt.
     */
    suspend fun ensureStarted(): Result<StartupReport> {
        (state.value as? State.Ready)?.let { return Result.success(it.report) }
        (state.value as? State.Failed)?.let { return Result.failure(FanFilmStartupException(it.error)) }

        return withContext(dispatcher) {
            synchronized(startLock) {
                (state.value as? State.Ready)?.let { return@withContext Result.success(it.report) }
                (state.value as? State.Failed)?.let {
                    return@withContext Result.failure(FanFilmStartupException(it.error))
                }
                _state.value = State.Starting
            }

            val outcome = runCatching { startBlocking() }
            outcome.fold(
                onSuccess = { report ->
                    _state.value = State.Ready(report)
                    Result.success(report)
                },
                onFailure = { throwable ->
                    val error = throwable.asFanFilmError()
                    Log.e(TAG, "FanFilm runtime start failed: ${error.technicalDetail}", throwable)
                    _state.value = State.Failed(error)
                    Result.failure(FanFilmStartupException(error))
                },
            )
        }
    }

    private suspend fun startBlocking(): StartupReport {
        val installed = installer.ensureInstalled().getOrElse { throwable ->
            throw FanFilmStartupException(
                FanFilmError.Configuration(
                    "could not install the FanFilm addon tree: ${throwable.message}",
                    throwable,
                )
            )
        }
        Log.i(TAG, "addon tree revision=${installed.revision} versions=${installed.addonVersions}")

        FanFilmBridge.attach(this)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(paths.appContext))
        }
        val python = Python.getInstance()
        val bridgeModule = python.getModule(PYTHON_MODULE)
        module = bridgeModule

        val options = JSONObject()
            .put("locale", java.util.Locale.getDefault().toLanguageTag())
            .put("verbose", com.foxtv.app.BuildConfig.IS_DEBUG_BUILD)
            .toString()

        val raw = bridgeModule.callAttr("configure", paths.kodiHome.absolutePath, options).toString()
        val report = JSONObject(raw)
        if (!report.optBoolean("ok", false)) {
            throw FanFilmStartupException(
                FanFilmError.Configuration(
                    report.optString("error").ifEmpty { "unknown configuration failure" },
                )
            )
        }

        val bridgeVersion = report.optString("bridgeVersion")
        if (bridgeVersion != EXPECTED_BRIDGE_VERSION) {
            // A mismatch means the installed Python package predates this build's
            // contract. Failing here is far cheaper to diagnose than a missing
            // attribute error inside a scraper.
            throw FanFilmStartupException(
                FanFilmError.Configuration(
                    "FanFilm bridge version mismatch: installed=$bridgeVersion " +
                        "expected=$EXPECTED_BRIDGE_VERSION",
                )
            )
        }

        val addonsJson = report.optJSONObject("addons")
        val addonVersions = buildMap {
            if (addonsJson != null) {
                for (key in addonsJson.keys()) put(key, addonsJson.optString(key))
            }
        }

        return StartupReport(
            bridgeVersion = bridgeVersion,
            pythonVersion = report.optString("python"),
            kodiHome = report.optString("kodiHome"),
            addonVersions = addonVersions,
            serviceStarted = report.optBoolean("serviceStarted", false),
            settingsPath = report.optString("settingsPath"),
        )
    }

    /**
     * Invoke `foxtv_fanfilm.<function>` on the Python thread.
     *
     * The bridge functions all return a JSON string, so the raw result is handed to
     * [parse]. Failures are normalised into [FanFilmError] before they leave, which
     * is what lets callers branch on the failure kind instead of on a message.
     */
    suspend fun <T> callBridge(
        function: String,
        vararg arguments: Any?,
        parse: (String) -> T,
    ): Result<T> {
        val started = ensureStarted()
        started.exceptionOrNull()?.let { return Result.failure(it) }

        return withContext(dispatcher) {
            val target = module
                ?: return@withContext Result.failure(
                    FanFilmStartupException(FanFilmError.Configuration("Python module not loaded"))
                )
            try {
                Result.success(parse(target.callAttr(function, *arguments).toString()))
            } catch (throwable: Throwable) {
                Log.e(TAG, "$function failed", throwable)
                Result.failure(FanFilmStartupException(throwable.asFanFilmError()))
            }
        }
    }

    /** Stop background work, flush settings and release waiters. */
    suspend fun shutdown() {
        val target = module ?: run {
            dialogs.cancelAll()
            FanFilmBridge.detach()
            return
        }
        withContext(dispatcher) {
            runCatching { target.callAttr("shutdown").toString() }
                .onFailure { Log.w(TAG, "shutdown failed", it) }
        }
        dialogs.cancelAll()
        FanFilmBridge.detach()
        _state.value = State.Idle
        module = null
    }

    // ───────────────────────────────────────────────────────────────────────
    // FanFilmBridgeHost — everything the Python side pushes back at the app.
    //
    // All of these run on a Python worker thread. None may throw across the JNI
    // boundary, and none may block, except the dialog family whose whole purpose
    // is to block the caller until a human answers.
    // ───────────────────────────────────────────────────────────────────────

    override fun nextRunId(): Int = newRunId()

    override fun isRunCancelled(runId: Int): Boolean = runId > 0 && retiredRuns.contains(runId)

    override fun onRunFinished(runId: Int) {
        publish(FanFilmEvent.Finished(runId))
    }

    override fun onRunError(runId: Int, kind: String, message: String, detail: String) {
        publish(FanFilmEvent.Failed(runId, FanFilmError.fromKind(kind, message, detail)))
    }

    override fun onDirectory(
        runId: Int,
        category: String,
        view: String,
        items: List<FanFilmListItem>,
        outcome: FanFilmDirectoryOutcome,
    ) {
        publish(FanFilmEvent.Directory(runId, category, view, items, outcome))
    }

    override fun onResolved(
        runId: Int,
        succeeded: Boolean,
        url: String,
        label: String,
        mimeType: String,
        properties: FanFilmPlaybackProperties,
    ) {
        publish(FanFilmEvent.Resolved(runId, succeeded, url, label, mimeType, properties))
    }

    override fun onPlay(runId: Int, url: String, properties: FanFilmPlaybackProperties) {
        publish(FanFilmEvent.Play(runId, url, properties))
    }

    override fun onNavigate(runId: Int, url: String, replaceCurrent: Boolean) {
        publish(FanFilmEvent.Navigate(runId, url, replaceCurrent))
    }

    override fun onRefresh(runId: Int) {
        publish(FanFilmEvent.Refresh(runId))
    }

    override fun onScanProgress(
        runId: Int,
        percent: Int,
        message: String,
        providers: List<String>,
    ) {
        publish(FanFilmEvent.ScanProgress(runId, percent, message, providers))
    }

    override fun onNotification(heading: String, message: String, level: String, timeoutMs: Int) {
        publish(
            FanFilmEvent.Notification(
                runId = 0,
                heading = heading,
                message = message,
                level = when (level.lowercase()) {
                    "error" -> FanFilmEvent.Notification.Level.ERROR
                    "warning" -> FanFilmEvent.Notification.Level.WARNING
                    else -> FanFilmEvent.Notification.Level.INFO
                },
            )
        )
    }

    override fun onPythonLog(message: String, level: Int) {
        // Android logging already happened in FanFilmBridge.log; this hook exists so
        // the diagnostics screen can mirror the addon's log without a second tap
        // into logcat. Only warnings and above are retained, bounded.
        if (level >= 2) {
            synchronized(recentWarnings) {
                recentWarnings.addLast(message)
                while (recentWarnings.size > MAX_RETAINED_WARNINGS) recentWarnings.removeFirst()
            }
        }
    }

    override fun dialogOk(runId: Int, heading: String, message: String): String =
        dialogs.await(runId, FanFilmDialogCoordinator.Kind.OK, heading, message)

    override fun dialogYesNo(
        runId: Int,
        heading: String,
        message: String,
        yesLabel: String,
        noLabel: String,
    ): String = dialogs.await(
        runId = runId,
        kind = FanFilmDialogCoordinator.Kind.YES_NO,
        heading = heading,
        message = message,
        positiveLabel = yesLabel,
        negativeLabel = noLabel,
    )

    override fun dialogSelect(
        runId: Int,
        heading: String,
        options: List<String>,
        preselect: Int,
        multi: Boolean,
    ): String = dialogs.await(
        runId = runId,
        kind = FanFilmDialogCoordinator.Kind.SELECT,
        heading = heading,
        options = options,
        preselected = if (preselect >= 0) listOf(preselect) else emptyList(),
    )

    override fun dialogMultiSelect(
        runId: Int,
        heading: String,
        options: List<String>,
        preselected: List<Int>,
    ): String = dialogs.await(
        runId = runId,
        kind = FanFilmDialogCoordinator.Kind.MULTI_SELECT,
        heading = heading,
        options = options,
        preselected = preselected,
    )

    override fun dialogInput(
        runId: Int,
        heading: String,
        defaultValue: String,
        inputType: Int,
    ): String = dialogs.await(
        runId = runId,
        kind = FanFilmDialogCoordinator.Kind.INPUT,
        heading = heading,
        defaultValue = defaultValue,
        inputType = inputType.toKodiInputType(),
    )

    override fun dialogNumeric(
        runId: Int,
        heading: String,
        defaultValue: String,
        inputType: Int,
    ): String = dialogs.await(
        runId = runId,
        kind = FanFilmDialogCoordinator.Kind.NUMERIC,
        heading = heading,
        defaultValue = defaultValue,
        inputType = inputType.toKodiNumericType(),
    )

    override fun playerCapabilities(): String = capabilities.toJson()

    override fun isPlaybackActive(): Boolean = playbackState.isPlaybackActive()

    override fun playbackInfoLabel(label: String): String = playbackState.infoLabel(label)

    private val recentWarnings = ArrayDeque<String>()

    /** Last warnings/errors the addon logged; shown on the FanFilm diagnostics row. */
    fun recentAddonWarnings(): List<String> = synchronized(recentWarnings) {
        recentWarnings.toList()
    }

    private fun publish(event: FanFilmEvent) {
        val context = runContexts[event.runId]
        val enriched = if (context != null && event.navigationGeneration == 0L) {
            when (event) {
                is FanFilmEvent.Directory -> event.copy(navigationGeneration = context.generation, pluginUrl = context.pluginUrl)
                is FanFilmEvent.Resolved -> event.copy(navigationGeneration = context.generation, pluginUrl = context.pluginUrl)
                is FanFilmEvent.Play -> event.copy(navigationGeneration = context.generation, pluginUrl = context.pluginUrl)
                is FanFilmEvent.Navigate -> event.copy(navigationGeneration = context.generation, pluginUrl = context.pluginUrl)
                is FanFilmEvent.Refresh -> event.copy(navigationGeneration = context.generation, pluginUrl = context.pluginUrl)
                is FanFilmEvent.ScanProgress -> event.copy(navigationGeneration = context.generation, pluginUrl = context.pluginUrl)
                is FanFilmEvent.Finished -> event.copy(navigationGeneration = context.generation, pluginUrl = context.pluginUrl)
                is FanFilmEvent.Failed -> event.copy(navigationGeneration = context.generation, pluginUrl = context.pluginUrl)
                is FanFilmEvent.Notification -> event
            }
        } else event
        if (enriched is FanFilmEvent.Finished || enriched is FanFilmEvent.Failed || enriched is FanFilmEvent.Resolved || enriched is FanFilmEvent.Play) {
            runContexts.remove(enriched.runId)
        }
        if (!events.publish(enriched)) {
            Log.w(TAG, "dropped ${enriched::class.simpleName} for run ${enriched.runId}")
        }
    }

    companion object {
        private const val TAG = "FanFilmRuntime"
        internal const val PYTHON_MODULE = "foxtv_fanfilm"
        private const val MAX_RETAINED_WARNINGS = 50

        /** Must match `foxtv_fanfilm.version.BRIDGE_VERSION`. */
        internal const val EXPECTED_BRIDGE_VERSION = "1.0.0"

        private const val RETIRED_HISTORY = 256
    }
}

/** Carries a [FanFilmError] through Kotlin's `Result` machinery. */
class FanFilmStartupException(val error: FanFilmError) :
    RuntimeException(error.technicalDetail, error.cause)

internal fun Throwable.asFanFilmError(): FanFilmError = when (this) {
    is FanFilmStartupException -> error
    is com.chaquo.python.PyException -> FanFilmError.Plugin(
        message ?: "Python error",
        traceback = stackTraceToString().lineSequence().take(20).joinToString("\n"),
        cause = this,
    )
    is java.io.IOException -> FanFilmError.Network(message ?: "I/O failure", cause = this)
    else -> FanFilmError.Plugin(message ?: this::class.java.simpleName, cause = this)
}

/**
 * Provides live player state to the Kodi compatibility layer.
 *
 * FanFilm's service polls `getInfoLabel('Player.Progress')` and friends to follow
 * playback, and `getCondVisibility('Player.Playing')` to know whether anything is
 * on screen. Those answers live in the player layer, so the runtime asks through
 * this seam instead of reaching into the player.
 */
interface FanFilmPlaybackStateProvider {
    fun isPlaybackActive(): Boolean

    /**
     * Resolve a Kodi info label against the current playback session.
     *
     * Return an empty string for anything unknown; the Python side treats that as
     * "not available", which is what Kodi returns when nothing is playing.
     */
    fun infoLabel(label: String): String
}

/**
 * Map Kodi's `xbmcgui.INPUT_*` constants onto the coordinator's input types.
 *
 * `xbmcgui.INPUT_ALPHANUM = 0`, `INPUT_NUMERIC = 1`, `INPUT_DATE = 2`,
 * `INPUT_TIME = 3`, `INPUT_IPADDRESS = 4`, `INPUT_PASSWORD = 5`. FanFilm's shim
 * also passes `1` from `Keyboard(hidden=True)`, which is why a hidden keyboard is
 * treated as a password field.
 */
internal fun Int.toKodiInputType(): FanFilmDialogCoordinator.InputType = when (this) {
    1 -> FanFilmDialogCoordinator.InputType.PASSWORD
    2 -> FanFilmDialogCoordinator.InputType.DATE
    3 -> FanFilmDialogCoordinator.InputType.TIME
    4 -> FanFilmDialogCoordinator.InputType.IP_ADDRESS
    5 -> FanFilmDialogCoordinator.InputType.PASSWORD
    else -> FanFilmDialogCoordinator.InputType.ALPHANUM
}

/** `Dialog.numeric` types: 0 number, 1 date, 2 time, 3 IP, 4 password. */
internal fun Int.toKodiNumericType(): FanFilmDialogCoordinator.InputType = when (this) {
    1 -> FanFilmDialogCoordinator.InputType.DATE
    2 -> FanFilmDialogCoordinator.InputType.TIME
    3 -> FanFilmDialogCoordinator.InputType.IP_ADDRESS
    4 -> FanFilmDialogCoordinator.InputType.PASSWORD
    else -> FanFilmDialogCoordinator.InputType.NUMBER
}

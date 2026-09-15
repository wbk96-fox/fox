package com.foxtv.app.core.fanfilm

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The only class the FanFilm Python code calls into.
 *
 * Chaquopy resolves it by name (`foxtv_fanfilm.bridge._BRIDGE_CLASS`) and invokes
 * static members, so this has to be an object with `@JvmStatic` methods rather than
 * an injected instance. The actual work is delegated to [FanFilmBridgeHost], which
 * *is* injected: [attach] is called once by [FanFilmRuntime] during startup.
 *
 * Everything here must tolerate being called from a Python worker thread, must never
 * throw across the JNI boundary (a Python-side exception inside a scraper is far
 * harder to diagnose than a logged no-op), and must never block the Android main
 * thread. The blocking calls — the dialogs — block the *calling* Python thread only.
 *
 * ProGuard: `-keep` rules for this class live in `app/proguard-rules.pro`; it is
 * reached only by reflection from native code.
 */
object FanFilmBridge {

    private const val TAG = "FanFilm"

    @Volatile
    private var host: FanFilmBridgeHost? = null

    fun attach(target: FanFilmBridgeHost) {
        host = target
    }

    fun detach() {
        host = null
    }

    private inline fun <T> withHost(default: T, action: (FanFilmBridgeHost) -> T): T {
        val target = host
        if (target == null) {
            Log.w(TAG, "bridge call arrived before the runtime was attached")
            return default
        }
        return try {
            action(target)
        } catch (t: Throwable) {
            Log.e(TAG, "bridge call failed", t)
            default
        }
    }

    // ── logging ─────────────────────────────────────────────────────────────

    /** Kodi log levels: 0 debug, 1 info, 2 warning, 3 error, 4 fatal. */
    @JvmStatic
    fun log(message: String, level: Int) {
        when (level) {
            0 -> Log.d(TAG, message)
            1 -> Log.i(TAG, message)
            2 -> Log.w(TAG, message)
            else -> Log.e(TAG, message)
        }
        host?.onPythonLog(message, level)
    }

    // ── run lifecycle ───────────────────────────────────────────────────────

    @JvmStatic
    fun nextRunId(): Int = withHost(0) { it.nextRunId() }

    @JvmStatic
    fun isRunCancelled(runId: Int): Boolean = withHost(false) { it.isRunCancelled(runId) }

    @JvmStatic
    fun onRunFinished(runId: Int) = withHost(Unit) { it.onRunFinished(runId) }

    @JvmStatic
    fun onRunError(runId: Int, payloadJson: String) = withHost(Unit) { target ->
        val payload = runCatching { JSONObject(payloadJson) }.getOrNull()
        target.onRunError(
            runId = runId,
            kind = payload?.optString("kind").orEmpty().ifEmpty { "plugin" },
            message = payload?.optString("message").orEmpty(),
            detail = payload?.optString("detail").orEmpty(),
        )
    }

    // ── directory / playback ────────────────────────────────────────────────

    @JvmStatic
    fun onDirectory(payloadJson: String) = withHost(Unit) { target ->
        val payload = JSONObject(payloadJson)
        val runId = payload.optInt("runId", 0)
        val itemsJson = payload.optJSONArray("items") ?: JSONArray()
        val items = ArrayList<FanFilmListItem>(itemsJson.length())
        for (index in 0 until itemsJson.length()) {
            val entry = itemsJson.optJSONObject(index) ?: continue
            items += FanFilmListItem(
                url = entry.optString("url"),
                label = entry.optString("label"),
                label2 = entry.optString("label2"),
                isFolder = entry.optBoolean("folder", false),
                art = entry.optJSONObject("art").toStringMap(),
                info = entry.optJSONObject("info").toStringMap(),
                uniqueIds = entry.optJSONObject("uniqueIds").toStringMap(),
                contextMenu = entry.optJSONArray("contextMenu").toContextActions(),
            )
        }
        target.onDirectory(
            runId = runId,
            category = payload.optString("category"),
            view = payload.optString("view"),
            items = items,
            outcome = when (payload.optString("outcome").uppercase()) {
                "EMPTY_DIRECTORY" -> FanFilmDirectoryOutcome.EMPTY_DIRECTORY
                else -> FanFilmDirectoryOutcome.SUCCESS_DIRECTORY
            },
        )
    }

    @JvmStatic
    fun onResolved(
        runId: Int,
        succeeded: Boolean,
        url: String,
        label: String,
        mimeType: String,
        propertiesJson: String,
    ) = withHost(Unit) { target ->
        target.onResolved(
            runId = runId,
            succeeded = succeeded,
            url = url,
            label = label,
            mimeType = mimeType,
            properties = FanFilmPlaybackProperties(
                runCatching { JSONObject(propertiesJson) }.getOrNull().toStringMap()
            ),
        )
    }

    @JvmStatic
    fun onPlay(runId: Int, url: String, propertiesJson: String) = withHost(Unit) { target ->
        target.onPlay(
            runId = runId,
            url = url,
            properties = FanFilmPlaybackProperties(
                runCatching { JSONObject(propertiesJson) }.getOrNull().toStringMap()
            ),
        )
    }

    @JvmStatic
    fun onNavigate(runId: Int, url: String, replaceCurrent: Boolean) =
        withHost(Unit) { it.onNavigate(runId, url, replaceCurrent) }

    @JvmStatic
    fun onRefresh(runId: Int) = withHost(Unit) { it.onRefresh(runId) }

    @JvmStatic
    fun onScanProgress(runId: Int, percent: Int, message: String, providersJson: String) =
        withHost(Unit) { target ->
            val providers = runCatching { JSONArray(providersJson) }.getOrNull()
            val names = buildList {
                if (providers != null) {
                    for (index in 0 until providers.length()) {
                        providers.optString(index).takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }
            }
            target.onScanProgress(runId, percent, message, names)
        }

    @JvmStatic
    fun notification(heading: String, message: String, level: String, timeoutMs: Int) =
        withHost(Unit) { it.onNotification(heading, message, level, timeoutMs) }

    // ── blocking dialogs (JSON envelope back to Python) ──────────────────────

    @JvmStatic
    fun dialogOk(runId: Int, heading: String, message: String): String =
        withHost(unavailable()) { it.dialogOk(runId, heading, message) }

    @JvmStatic
    fun dialogYesNo(
        runId: Int,
        heading: String,
        message: String,
        yesLabel: String,
        noLabel: String,
    ): String = withHost(unavailable()) {
        it.dialogYesNo(runId, heading, message, yesLabel, noLabel)
    }

    @JvmStatic
    fun dialogSelect(
        runId: Int,
        heading: String,
        optionsJson: String,
        preselect: Int,
        multi: Boolean,
    ): String = withHost(unavailable()) {
        it.dialogSelect(runId, heading, optionsJson.toStringList(), preselect, multi)
    }

    @JvmStatic
    fun dialogMultiSelect(
        runId: Int,
        heading: String,
        optionsJson: String,
        preselectedJson: String,
    ): String = withHost(unavailable()) {
        it.dialogMultiSelect(
            runId,
            heading,
            optionsJson.toStringList(),
            preselectedJson.toIntList(),
        )
    }

    @JvmStatic
    fun dialogInput(runId: Int, heading: String, defaultValue: String, inputType: Int): String =
        withHost(unavailable()) { it.dialogInput(runId, heading, defaultValue, inputType) }

    @JvmStatic
    fun dialogNumeric(runId: Int, heading: String, defaultValue: String, inputType: Int): String =
        withHost(unavailable()) { it.dialogNumeric(runId, heading, defaultValue, inputType) }

    // ── player interrogation ────────────────────────────────────────────────

    @JvmStatic
    fun playerCapabilities(): String = withHost("{}") { it.playerCapabilities() }

    @JvmStatic
    fun isPlaybackActive(): Boolean = withHost(false) { it.isPlaybackActive() }

    @JvmStatic
    fun playbackInfoLabel(label: String): String = withHost("") { it.playbackInfoLabel(label) }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun unavailable(): String = JSONObject()
        .put("state", FanFilmDialogCoordinator.STATE_UNAVAILABLE)
        .put("value", JSONObject.NULL)
        .toString()

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val result = LinkedHashMap<String, String>(length())
        for (key in keys()) {
            result[key] = optString(key)
        }
        return result
    }

    private fun JSONArray?.toContextActions(): List<FanFilmListItem.ContextAction> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            val entry = optJSONObject(index) ?: return@mapNotNull null
            FanFilmListItem.ContextAction(
                label = entry.optString("label"),
                action = entry.optString("action"),
            )
        }
    }

    private fun String.toStringList(): List<String> {
        val array = runCatching { JSONArray(this) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).map { array.optString(it) }
    }

    private fun String.toIntList(): List<Int> {
        val array = runCatching { JSONArray(this) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).map { array.optInt(it, -1) }.filter { it >= 0 }
    }
}

/**
 * What [FanFilmBridge] delegates to. Implemented by [FanFilmRuntime].
 *
 * Split out from the object so the bridge stays a thin, testable adapter and the
 * behaviour is reachable from unit tests without a live interpreter.
 */
interface FanFilmBridgeHost {
    fun nextRunId(): Int
    fun isRunCancelled(runId: Int): Boolean
    fun onRunFinished(runId: Int)
    fun onRunError(runId: Int, kind: String, message: String, detail: String)

    fun onDirectory(
        runId: Int,
        category: String,
        view: String,
        items: List<FanFilmListItem>,
        outcome: FanFilmDirectoryOutcome,
    )
    fun onResolved(
        runId: Int,
        succeeded: Boolean,
        url: String,
        label: String,
        mimeType: String,
        properties: FanFilmPlaybackProperties,
    )
    fun onPlay(runId: Int, url: String, properties: FanFilmPlaybackProperties)
    fun onNavigate(runId: Int, url: String, replaceCurrent: Boolean)
    fun onRefresh(runId: Int)
    fun onScanProgress(runId: Int, percent: Int, message: String, providers: List<String>)
    fun onNotification(heading: String, message: String, level: String, timeoutMs: Int)
    fun onPythonLog(message: String, level: Int)

    fun dialogOk(runId: Int, heading: String, message: String): String
    fun dialogYesNo(
        runId: Int,
        heading: String,
        message: String,
        yesLabel: String,
        noLabel: String,
    ): String
    fun dialogSelect(
        runId: Int,
        heading: String,
        options: List<String>,
        preselect: Int,
        multi: Boolean,
    ): String
    fun dialogMultiSelect(
        runId: Int,
        heading: String,
        options: List<String>,
        preselected: List<Int>,
    ): String
    fun dialogInput(runId: Int, heading: String, defaultValue: String, inputType: Int): String
    fun dialogNumeric(runId: Int, heading: String, defaultValue: String, inputType: Int): String

    fun playerCapabilities(): String
    fun isPlaybackActive(): Boolean
    fun playbackInfoLabel(label: String): String
}

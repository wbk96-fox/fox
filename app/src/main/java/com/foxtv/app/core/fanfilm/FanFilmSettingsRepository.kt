package com.foxtv.app.core.fanfilm

import android.util.Log
import androidx.compose.runtime.Immutable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * FanFilm's settings, end to end.
 *
 * The chain is real at every link (AGENTS.md §18/§53): the declaration comes from the
 * addon's own `resources/settings.xml`, the value store is the Kodi
 * `userdata/addon_data/plugin.video.fanfilm/settings.xml` the addon itself reads, and
 * a write is flushed to that file *and* pushed into the running interpreter's setting
 * cache before this repository reports success. There is no FOX.TV-side shadow copy
 * that could drift from what the addon sees.
 *
 * `action` settings are declarations that run addon code (clear cache, log in to a
 * host). They are surfaced as buttons and executed through the plugin, not written.
 */
@Singleton
class FanFilmSettingsRepository @Inject constructor(
    private val runtime: FanFilmRuntime,
) {

    @Immutable
    data class Definition(
        val id: String,
        val type: Type,
        val label: String,
        val help: String,
        val default: String,
        val level: Int,
        val controlType: String,
        val controlFormat: String,
        val options: List<Option>,
        val minimum: Double?,
        val maximum: Double?,
        val step: Double?,
        val dependencies: Map<String, List<Condition>>,
        val isSecret: Boolean,
        val isAction: Boolean,
    ) {
        enum class Type { BOOLEAN, INTEGER, NUMBER, STRING, PATH, ACTION, UNKNOWN;

            companion object {
                fun fromWire(value: String): Type = when (value.lowercase()) {
                    "boolean" -> BOOLEAN
                    "integer" -> INTEGER
                    "number" -> NUMBER
                    "string", "urlencodedstring", "date", "time" -> STRING
                    "path" -> PATH
                    "action" -> ACTION
                    else -> UNKNOWN
                }
            }
        }

        @Immutable
        data class Option(val label: String, val value: String)

        /** One `<condition>` from a `<dependency>`, already normalised. */
        @Immutable
        data class Condition(
            val operator: String,
            val setting: String,
            val value: String,
            val operands: List<Condition>,
        )
    }

    @Immutable
    data class Group(val id: String, val label: String, val settings: List<Definition>)

    @Immutable
    data class Category(
        val id: String,
        val label: String,
        val help: String,
        val groups: List<Group>,
    )

    @Immutable
    data class Snapshot(
        val addonId: String,
        val addonVersion: String,
        val categories: List<Category>,
        val values: Map<String, String>,
        val valuesPath: String,
    ) {
        /** Flat lookup, for dependency evaluation and tests. */
        val definitions: Map<String, Definition> =
            categories.asSequence()
                .flatMap { it.groups.asSequence() }
                .flatMap { it.settings.asSequence() }
                .associateBy { it.id }

        fun value(id: String): String = values[id] ?: definitions[id]?.default.orEmpty()

        fun boolean(id: String): Boolean = value(id).equals("true", ignoreCase = true)

        fun integer(id: String): Int = value(id).toIntOrNull() ?: 0

        /**
         * Whether [id] should be interactive, per its `<dependency type="enable">`.
         *
         * Kodi hides or greys out settings whose dependencies are unmet; reproducing
         * that here keeps the FOX.TV screen from offering a toggle that the addon will
         * ignore.
         */
        fun isEnabled(id: String): Boolean {
            val definition = definitions[id] ?: return true
            val conditions = definition.dependencies["enable"].orEmpty()
            if (conditions.isEmpty()) return true
            return conditions.all { evaluate(it) }
        }

        fun isVisible(id: String): Boolean {
            val definition = definitions[id] ?: return true
            val conditions = definition.dependencies["visible"].orEmpty()
            if (conditions.isEmpty()) return true
            return conditions.all { evaluate(it) }
        }

        private fun evaluate(condition: Definition.Condition): Boolean = when (condition.operator) {
            "and" -> condition.operands.all { evaluate(it) }
            "or" -> condition.operands.any { evaluate(it) }
            "not" -> condition.operands.none { evaluate(it) }
            "is" -> value(condition.setting).equals(condition.value, ignoreCase = true)
            "!is" -> !value(condition.setting).equals(condition.value, ignoreCase = true)
            "gt" -> compare(condition) { a, b -> a > b }
            "lt" -> compare(condition) { a, b -> a < b }
            // An operator the parser did not recognise must not silently disable a
            // setting; leaving it enabled matches Kodi's permissive behaviour.
            else -> true
        }

        private fun compare(
            condition: Definition.Condition,
            predicate: (Double, Double) -> Boolean,
        ): Boolean {
            val left = value(condition.setting).toDoubleOrNull() ?: return true
            val right = condition.value.toDoubleOrNull() ?: return true
            return predicate(left, right)
        }
    }

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot

    /** Read declarations and current values from the addon. */
    suspend fun load(): Result<Snapshot> =
        runtime.callBridge("read_settings") { raw -> parse(JSONObject(raw)) }
            .onSuccess { _snapshot.value = it }
            .onFailure { Log.e(TAG, "could not read FanFilm settings", it) }

    /**
     * Write one setting and refresh the cached snapshot.
     *
     * The returned value is what the addon reports *after* the write, so a rejected
     * or coerced value is visible to the caller rather than being assumed.
     */
    suspend fun write(id: String, value: String): Result<String> {
        val outcome = runtime.callBridge("write_setting", id, value) { raw -> JSONObject(raw) }
        val payload = outcome.getOrElse { return Result.failure(it) }
        if (!payload.optBoolean("ok", false)) {
            val message = payload.optString("error").ifEmpty { "setting was not written" }
            return Result.failure(FanFilmStartupException(FanFilmError.Configuration(message)))
        }
        val effective = payload.optString("value")
        _snapshot.value = _snapshot.value?.let { current ->
            current.copy(values = current.values + (id to effective))
        }
        return Result.success(effective)
    }

    suspend fun writeBoolean(id: String, value: Boolean): Result<String> =
        write(id, if (value) "true" else "false")

    suspend fun writeInteger(id: String, value: Int): Result<String> = write(id, value.toString())

    /**
     * Run an `action` setting.
     *
     * Kodi executes these by invoking the plugin with the action's own URL. FanFilm
     * declares them as `RunPlugin(plugin://plugin.video.fanfilm/?action=…)`, so the
     * action string is handed to the plugin runner unchanged.
     */
    suspend fun runAction(definition: Definition, runId: Int): Result<Unit> {
        require(definition.isAction) { "${definition.id} is not an action setting" }
        val target = "plugin://${ADDON_ID}/?action=${definition.id}"
        return runtime.callBridge("run_plugin", target, runId) { }
    }

    internal fun parse(root: JSONObject): Snapshot {
        val categoriesJson = root.optJSONArray("categories") ?: JSONArray()
        val categories = (0 until categoriesJson.length()).mapNotNull { index ->
            val category = categoriesJson.optJSONObject(index) ?: return@mapNotNull null
            val groupsJson = category.optJSONArray("groups") ?: JSONArray()
            val groups = (0 until groupsJson.length()).mapNotNull { groupIndex ->
                val group = groupsJson.optJSONObject(groupIndex) ?: return@mapNotNull null
                val settingsJson = group.optJSONArray("settings") ?: JSONArray()
                val settings = (0 until settingsJson.length()).mapNotNull { settingIndex ->
                    settingsJson.optJSONObject(settingIndex)?.let(::parseDefinition)
                }
                Group(
                    id = group.optString("id"),
                    label = group.optString("label"),
                    settings = settings,
                )
            }
            Category(
                id = category.optString("id"),
                label = category.optString("label"),
                help = category.optString("help"),
                groups = groups,
            )
        }

        return Snapshot(
            addonId = root.optString("addonId", ADDON_ID),
            addonVersion = root.optString("addonVersion"),
            categories = categories,
            values = root.optJSONObject("values").toStringMap(),
            valuesPath = root.optString("valuesPath"),
        )
    }

    private fun parseDefinition(json: JSONObject): Definition {
        val optionsJson = json.optJSONArray("options") ?: JSONArray()
        val options = (0 until optionsJson.length()).mapNotNull { index ->
            val entry = optionsJson.optJSONObject(index) ?: return@mapNotNull null
            Definition.Option(label = entry.optString("label"), value = entry.optString("value"))
        }
        val control = json.optJSONObject("control")
        return Definition(
            id = json.optString("id"),
            type = Definition.Type.fromWire(json.optString("type")),
            label = json.optString("label"),
            help = json.optString("help"),
            default = json.optString("default"),
            level = json.optInt("level", 0),
            controlType = control?.optString("type").orEmpty(),
            controlFormat = control?.optString("format").orEmpty(),
            options = options,
            minimum = json.optDoubleOrNull("minimum"),
            maximum = json.optDoubleOrNull("maximum"),
            step = json.optDoubleOrNull("step"),
            dependencies = parseDependencies(json.optJSONObject("dependencies")),
            isSecret = json.optBoolean("secret", false),
            isAction = json.optBoolean("action", false),
        )
    }

    private fun parseDependencies(json: JSONObject?): Map<String, List<Definition.Condition>> {
        if (json == null) return emptyMap()
        val result = LinkedHashMap<String, List<Definition.Condition>>()
        for (key in json.keys()) {
            val array = json.optJSONArray(key) ?: continue
            result[key] = (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::parseCondition)
            }
        }
        return result
    }

    private fun parseCondition(json: JSONObject): Definition.Condition {
        val operandsJson = json.optJSONArray("operands") ?: JSONArray()
        return Definition.Condition(
            operator = json.optString("op", "is").lowercase(),
            setting = json.optString("setting"),
            value = json.optString("value"),
            operands = (0 until operandsJson.length()).mapNotNull { index ->
                operandsJson.optJSONObject(index)?.let(::parseCondition)
            },
        )
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

    companion object {
        private const val TAG = "FanFilmSettings"
        const val ADDON_ID = "plugin.video.fanfilm"
    }
}

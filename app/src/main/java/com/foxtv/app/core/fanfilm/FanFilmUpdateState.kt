package com.foxtv.app.core.fanfilm

import android.content.Context
import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent bookkeeping for addon updates.
 *
 * Holds the last-check timestamp (so a launch does not hit the network every time) and
 * which addons have a staged version waiting for a restart. Kept in its own
 * [SharedPreferences] file rather than in the app's DataStore because it must be readable
 * during runtime startup, before the DI graph has finished building the preference layer.
 */
@Singleton
class FanFilmUpdateState internal constructor(
    private val context: Context,
    private val clock: () -> Long,
) {
    /**
     * Injected constructor.
     *
     * Kept separate from the primary one so only a single `@Inject` constructor reaches
     * Hilt; a default argument on the primary constructor would produce two.
     */
    @Inject
    constructor(
        @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
    ) : this(context, System::currentTimeMillis)


    private val preferences: SharedPreferences
        get() = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Whether enough time has passed to check again.
     *
     * A fixed TTL, plus a backoff after a failed check, keeps FOX.TV from hammering the
     * repositories when the network is down (AGENTS.md §54).
     */
    fun isCheckDue(): Boolean {
        val last = preferences.getLong(KEY_LAST_CHECK, 0)
        val failures = preferences.getInt(KEY_CONSECUTIVE_FAILURES, 0)
        val interval = if (failures <= 0) {
            CHECK_INTERVAL_MS
        } else {
            // Exponential backoff, capped.
            minOf(CHECK_INTERVAL_MS shl failures.coerceAtMost(4), MAX_BACKOFF_MS)
        }
        return clock() - last >= interval
    }

    fun recordCheck() {
        preferences.edit()
            .putLong(KEY_LAST_CHECK, clock())
            .putInt(KEY_CONSECUTIVE_FAILURES, 0)
            .apply()
    }

    fun recordCheckFailure() {
        val failures = preferences.getInt(KEY_CONSECUTIVE_FAILURES, 0) + 1
        preferences.edit()
            .putLong(KEY_LAST_CHECK, clock())
            .putInt(KEY_CONSECUTIVE_FAILURES, failures)
            .apply()
    }

    fun recordStaged(addonId: String, version: String) {
        preferences.edit().putString(stagedKey(addonId), version).apply()
    }

    fun clearStaged(addonId: String) {
        preferences.edit().remove(stagedKey(addonId)).apply()
    }

    /** Addon id → staged version, for everything awaiting a restart. */
    fun staged(): Map<String, String> = preferences.all
        .filterKeys { it.startsWith(STAGED_PREFIX) }
        .mapNotNull { (key, value) ->
            val version = value as? String ?: return@mapNotNull null
            key.removePrefix(STAGED_PREFIX) to version
        }
        .toMap()

    /**
     * Mark staged versions as live.
     *
     * Called once by the runtime after a successful start, since that start is what
     * actually picked the new tree up.
     */
    fun confirmActivated() {
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith(STAGED_PREFIX) }
            .forEach(editor::remove)
        editor.apply()
    }

    private fun stagedKey(addonId: String) = "$STAGED_PREFIX$addonId"

    companion object {
        private const val FILE_NAME = "foxtv_fanfilm_updates"
        private const val KEY_LAST_CHECK = "last_check_at_ms"
        private const val KEY_CONSECUTIVE_FAILURES = "consecutive_failures"
        private const val STAGED_PREFIX = "staged:"

        /** Once a day is plenty: FanFilm publishes every few days. */
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val MAX_BACKOFF_MS = 7L * 24 * 60 * 60 * 1000
    }
}

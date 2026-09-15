package com.foxtv.app.core.fanfilm

import android.content.Context
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of every FOX.TV path the FanFilm runtime touches.
 *
 * Python needs real files on disk (imports, sqlite databases, `settings.xml`), and
 * assets are not files, so the vendored addon tree is materialised into the app's
 * data directory. Nothing else in the codebase is allowed to build these paths by
 * hand (AGENTS.md §16); the Python side mirrors this layout in
 * `foxtv_fanfilm.paths` and receives [kodiHome] as its only input.
 *
 * Layout:
 * ```
 * <filesDir>/fanfilm/                 kodiHome, also special://home
 *   addons/<addon.id>/                installed addon trees
 *   userdata/                         special://userdata, profile, masterprofile
 *     addon_data/<addon.id>/          settings.xml and per-addon state
 *     Database/                       special://database
 *   temp/                             special://temp and special://logpath
 *   system/certs/cacert.pem           CA bundle handed to ResolveURL
 * <filesDir>/fanfilm-staging/         downloads being verified by the updater
 * <filesDir>/fanfilm-backup/          previous addon version, for rollback
 * ```
 */
@Singleton
class FanFilmPaths @Inject constructor(
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    /**
     * Application context, needed by Chaquopy's [com.chaquo.python.android.AndroidPlatform].
     *
     * Exposed here rather than injected separately so there is exactly one holder of
     * the FanFilm filesystem identity.
     */
    val appContext: Context get() = context

    /** Kodi "home" for the embedded instance. */
    val kodiHome: File get() = File(context.filesDir, ROOT_DIR_NAME)

    /** Installed addon trees, one directory per addon id. */
    val addonsDir: File get() = File(kodiHome, "addons")

    /** Kodi userdata root (`special://userdata`, `special://profile`). */
    val userdataDir: File get() = File(kodiHome, "userdata")

    /** Per-addon writable state (`settings.xml`, caches, databases). */
    fun addonDataDir(addonId: String): File = File(userdataDir, "addon_data/$addonId")

    /** Installed tree for one addon. */
    fun addonDir(addonId: String): File = File(addonsDir, addonId)

    /** `special://temp` and `special://logpath`. */
    val tempDir: File get() = File(kodiHome, "temp")

    /** Where a downloaded update is unpacked and validated before activation. */
    val stagingDir: File get() = File(context.filesDir, "$ROOT_DIR_NAME-staging")

    /** Where the previous version is kept so activation can be rolled back. */
    val backupDir: File get() = File(context.filesDir, "$ROOT_DIR_NAME-backup")

    /** Marker recording which asset revision was installed. */
    val installMarker: File get() = File(kodiHome, ".install-revision")

    /** Manifest copied out of the assets, describing the bundled addon versions. */
    val installedManifest: File get() = File(kodiHome, "MANIFEST.json")

    /** Cache used for update downloads; on the cache partition so it can be evicted. */
    val downloadCacheDir: File get() = File(context.cacheDir, "$ROOT_DIR_NAME-downloads")

    fun createDirectories() {
        listOf(kodiHome, addonsDir, userdataDir, tempDir, stagingDir, backupDir, downloadCacheDir)
            .forEach { directory ->
                if (!directory.isDirectory && !directory.mkdirs()) {
                    error("could not create FanFilm directory: $directory")
                }
            }
    }

    /**
     * Whether [candidate] is inside [root].
     *
     * Used before any recursive delete so a bug in path construction cannot make
     * the runtime wipe an unrelated directory. Compares canonical paths with an
     * explicit separator boundary rather than a bare prefix test.
     */
    fun isInside(root: File, candidate: File): Boolean {
        val canonicalRoot = root.canonicalPath
        val canonicalCandidate = candidate.canonicalPath
        if (canonicalCandidate == canonicalRoot) return true
        val boundary = if (canonicalRoot.endsWith(File.separator)) {
            canonicalRoot
        } else {
            canonicalRoot + File.separator
        }
        return canonicalCandidate.startsWith(boundary)
    }

    /** Delete [target], refusing anything that is not under the app's files dir. */
    fun deleteRecursivelyWithinAppData(target: File): Boolean {
        if (!isInside(context.filesDir, target) && !isInside(context.cacheDir, target)) {
            error("refusing to delete outside app data: $target")
        }
        return !target.exists() || target.deleteRecursively()
    }

    companion object {
        const val ROOT_DIR_NAME = "fanfilm"

        /** Asset directory the vendored addon tree lives in. */
        const val ASSET_ROOT = "fanfilm"
    }
}

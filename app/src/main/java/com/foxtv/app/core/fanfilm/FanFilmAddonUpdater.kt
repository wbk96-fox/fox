package com.foxtv.app.core.fanfilm

import android.util.Log
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Staged, reversible updates for the vendored Kodi addons.
 *
 * FanFilm and its resolver registry are updated far more often than FOX.TV itself, and a
 * stale ResolveURL means dead hosts, so the addons must be updatable at runtime
 * (AGENTS.md §24/§25/§26/§54). The mechanism is deliberately conservative:
 *
 * ```
 * check  → read addons.xml, compare against what is installed
 * fetch  → download the package (size-capped)
 * verify → compare against the publisher's .sha256 when one exists
 * stage  → extract into a staging directory with SafeArchiveExtractor
 * validate → the staged tree must contain addon.xml declaring the expected id/version,
 *            the addon's own entry points, and no unmet mandatory <requires>
 * activate → back up the live tree, move the staged tree in, keep the backup
 * rollback → on any activation failure, restore the backup
 * ```
 *
 * Activation does not touch a running interpreter. Chaquopy cannot unload a module tree
 * that FanFilm has already imported, so replacing files under a live interpreter would
 * leave half-old, half-new modules in `sys.modules`. Instead the swap happens on disk and
 * takes effect at the next process start, and [pendingActivation] reports that so the UI
 * can say "restart to finish". This is a *staged update with rollback*, not an atomic
 * filesystem swap, and is described as such.
 */
@Singleton
class FanFilmAddonUpdater @Inject constructor(
    private val paths: FanFilmPaths,
    private val installer: FanFilmAssetInstaller,
    private val client: FanFilmUpdateRepositoryClient,
    private val runtime: FanFilmRuntime,
    private val state: FanFilmUpdateState,
) {

    data class Available(
        val addonId: String,
        val installedVersion: String,
        val remoteVersion: String,
        val repositoryId: String,
    )

    sealed interface Outcome {
        /** Nothing to do. */
        data object UpToDate : Outcome

        /** Staged successfully; takes effect after a restart. */
        data class Staged(val addonId: String, val version: String) : Outcome

        /** The package was rejected before anything was touched. */
        data class Rejected(val addonId: String, val error: FanFilmError) : Outcome

        /** Activation failed and the previous version was restored. */
        data class RolledBack(val addonId: String, val error: FanFilmError) : Outcome
    }

    /**
     * Which addons have a newer published version.
     *
     * Respects a TTL so a launch does not hit the network on every start, unless [force].
     */
    suspend fun check(force: Boolean = false): Result<List<Available>> =
        withContext(Dispatchers.IO) {
            if (!force && !state.isCheckDue()) {
                return@withContext Result.success(emptyList())
            }
            runCatching {
                val installed = installer.readInstalledVersions()
                val available = mutableListOf<Available>()
                for (repository in FanFilmUpdateRepositoryClient.ALL) {
                    val index = client.index(repository).getOrElse { throwable ->
                        // One unreachable repository must not hide updates from another.
                        Log.w(TAG, "skipping ${repository.id}: ${throwable.message}")
                        continue
                    }
                    for (remote in index) {
                        val current = installed[remote.id] ?: continue
                        if (isNewer(remote.version, current)) {
                            available += Available(
                                addonId = remote.id,
                                installedVersion = current,
                                remoteVersion = remote.version,
                                repositoryId = repository.id,
                            )
                        }
                    }
                }
                state.recordCheck()
                available
            }
        }

    /**
     * Download, verify, stage and activate one update.
     *
     * Returns [Outcome.Rejected] without touching the installed tree when anything about
     * the package is wrong, and [Outcome.RolledBack] if the swap itself failed.
     */
    suspend fun update(target: Available): Outcome = withContext(Dispatchers.IO) {
        val repository = FanFilmUpdateRepositoryClient.ALL
            .firstOrNull { it.id == target.repositoryId }
            ?: return@withContext Outcome.Rejected(
                target.addonId,
                FanFilmError.Update("unknown repository ${target.repositoryId}"),
            )

        val remote = client.index(repository).getOrNull()
            ?.firstOrNull { it.id == target.addonId && it.version == target.remoteVersion }
            ?: return@withContext Outcome.Rejected(
                target.addonId,
                FanFilmError.Update("${target.addonId} ${target.remoteVersion} is no longer published"),
            )

        val download = File(paths.downloadCacheDir, "${remote.id}-${remote.version}.zip")
        try {
            paths.downloadCacheDir.mkdirs()

            val actualDigest = client.download(remote, download).getOrElse { throwable ->
                return@withContext Outcome.Rejected(
                    remote.id,
                    FanFilmError.Update("download failed: ${throwable.message}", throwable),
                )
            }

            val expected = client.checksum(remote)
            if (expected != null && expected != actualDigest) {
                return@withContext Outcome.Rejected(
                    remote.id,
                    FanFilmError.Update(
                        "checksum mismatch: expected $expected, got $actualDigest"
                    ),
                )
            }
            if (expected == null) {
                // FanFilm publishes checksums; ResolveURL does not. Record the absence
                // rather than silently accepting an unverified package as if it had been
                // checked.
                Log.i(TAG, "${remote.id} ${remote.version}: publisher provides no checksum")
            }

            val staging = File(paths.stagingDir, remote.id)
            if (!paths.deleteRecursivelyWithinAppData(staging)) {
                return@withContext Outcome.Rejected(
                    remote.id,
                    FanFilmError.Update("could not clear staging directory"),
                )
            }
            if (!staging.mkdirs()) {
                return@withContext Outcome.Rejected(
                    remote.id,
                    FanFilmError.Update("could not create staging directory"),
                )
            }

            try {
                SafeArchiveExtractor.extract(
                    source = download,
                    destination = staging,
                    stripTopLevel = false,
                )
            } catch (rejected: SafeArchiveExtractor.RejectedException) {
                Log.w(TAG, "rejected ${remote.id} package: ${rejected.message}")
                return@withContext Outcome.Rejected(
                    remote.id,
                    FanFilmError.Update("package rejected: ${rejected.message}", rejected),
                )
            } catch (io: IOException) {
                return@withContext Outcome.Rejected(
                    remote.id,
                    FanFilmError.Update("could not unpack package: ${io.message}", io),
                )
            }

            val stagedRoot = File(staging, remote.id)
            validate(stagedRoot, remote)?.let { error ->
                return@withContext Outcome.Rejected(remote.id, error)
            }

            return@withContext activate(stagedRoot, remote)
        } finally {
            download.delete()
        }
    }

    /**
     * Structural checks on a staged tree, before it is allowed near the live directory.
     *
     * Returns null when the tree is acceptable.
     */
    internal fun validate(
        stagedRoot: File,
        remote: FanFilmUpdateRepositoryClient.RemoteAddon,
    ): FanFilmError? {
        if (!stagedRoot.isDirectory) {
            return FanFilmError.Update("package does not contain a ${remote.id}/ directory")
        }
        val manifest = File(stagedRoot, "addon.xml")
        if (!manifest.isFile) {
            return FanFilmError.Update("package has no addon.xml")
        }
        val declared = runCatching { manifest.readText() }.getOrNull()
            ?: return FanFilmError.Update("addon.xml is unreadable")
        if (!declared.contains("""id="${remote.id}"""")) {
            return FanFilmError.Update("addon.xml declares a different addon id")
        }
        if (!declared.contains("""version="${remote.version}"""")) {
            return FanFilmError.Update(
                "addon.xml does not declare version ${remote.version}"
            )
        }

        // Entry points the FOX.TV runtime calls. A package missing them would install
        // cleanly and then fail at the first plugin invocation.
        val requiredFiles = ENTRY_POINTS[remote.id].orEmpty()
        requiredFiles.firstOrNull { !File(stagedRoot, it).exists() }?.let { missing ->
            return FanFilmError.Update("package is missing $missing")
        }

        // Mandatory <requires> must already be satisfied by what is installed. FOX.TV does
        // not chase a dependency tree at runtime: a new mandatory dependency means the
        // compatibility layer has not been checked against it.
        val installed = installer.readInstalledVersions()
        val unmet = remote.requires
            .filterNot { it.optional }
            .map { it.addonId }
            .filterNot { it.startsWith("xbmc.") }
            .filterNot { it in installed || it in PROVIDED_BY_RUNTIME }
        if (unmet.isNotEmpty()) {
            return FanFilmError.Update(
                "package requires addons FOX.TV does not provide: ${unmet.joinToString()}"
            )
        }
        return null
    }

    /**
     * Swap the staged tree in, keeping the previous version for rollback.
     *
     * `File.renameTo` within the same filesystem is the closest thing to an atomic move
     * available here; both directories live under `filesDir`, so it does not fall back to
     * a copy.
     */
    private fun activate(
        stagedRoot: File,
        remote: FanFilmUpdateRepositoryClient.RemoteAddon,
    ): Outcome {
        val live = paths.addonDir(remote.id)
        val backup = File(paths.backupDir, remote.id)

        if (!paths.deleteRecursivelyWithinAppData(backup)) {
            return Outcome.Rejected(
                remote.id,
                FanFilmError.Update("could not clear the backup directory"),
            )
        }
        backup.parentFile?.mkdirs()

        val hadLive = live.exists()
        if (hadLive && !live.renameTo(backup)) {
            return Outcome.Rejected(
                remote.id,
                FanFilmError.Update("could not move the installed addon aside"),
            )
        }

        if (!stagedRoot.renameTo(live)) {
            // Roll back: put the previous tree back exactly where it was.
            val restored = !hadLive || backup.renameTo(live)
            val error = FanFilmError.Update(
                if (restored) {
                    "could not activate ${remote.id} ${remote.version}; previous version restored"
                } else {
                    "could not activate ${remote.id} ${remote.version} and the rollback failed; " +
                        "the bundled copy will be reinstalled on next start"
                }
            )
            if (!restored) {
                // Force the asset installer to lay the bundled tree down again next start
                // rather than leaving the addon missing.
                paths.installMarker.delete()
            }
            return Outcome.RolledBack(remote.id, error)
        }

        state.recordStaged(remote.id, remote.version)
        Log.i(
            TAG,
            "staged ${remote.id} ${remote.version} (previous ${remote.id} kept in ${backup.name}); " +
                "takes effect after restart",
        )
        return Outcome.Staged(remote.id, remote.version)
    }

    /**
     * Restore the backed-up copy of [addonId].
     *
     * Offered so a user whose update broke discovery can go back without clearing app
     * data. Like activation, it takes effect after a restart.
     */
    suspend fun rollback(addonId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val backup = File(paths.backupDir, addonId)
            if (!backup.isDirectory) {
                throw IOException("no backup is available for $addonId")
            }
            val live = paths.addonDir(addonId)
            val discarded = File(paths.stagingDir, "$addonId-discarded")
            paths.deleteRecursivelyWithinAppData(discarded)
            if (live.exists() && !live.renameTo(discarded)) {
                throw IOException("could not move the current $addonId aside")
            }
            if (!backup.renameTo(live)) {
                // Put the current tree back so the addon is never left missing.
                discarded.renameTo(live)
                throw IOException("could not restore the backup for $addonId")
            }
            paths.deleteRecursivelyWithinAppData(discarded)
            state.clearStaged(addonId)
            Log.i(TAG, "rolled $addonId back to the previous version; restart required")
            Unit
        }
    }

    /** Addons whose staged version is waiting for a restart. */
    fun pendingActivation(): Map<String, String> = state.staged()

    /** Whether the interpreter has already started, i.e. a restart is genuinely needed. */
    fun restartRequired(): Boolean =
        pendingActivation().isNotEmpty() && runtime.state.value is FanFilmRuntime.State.Ready

    /**
     * Compare Kodi addon versions.
     *
     * Kodi versions are dot-separated numbers, sometimes with a `~`/`+` suffix
     * (`2026.09.06.1`, `5.1.208`, `0.8.6+matrix.1`). Numeric segments are compared
     * numerically so `2026.09.10` beats `2026.09.9`, which a string comparison gets wrong.
     */
    internal fun isNewer(candidate: String, installed: String): Boolean =
        compareVersions(candidate, installed) > 0

    internal fun compareVersions(left: String, right: String): Int {
        val leftParts = splitVersion(left)
        val rightParts = splitVersion(right)
        val size = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until size) {
            val a = leftParts.getOrNull(index)
            val b = rightParts.getOrNull(index)
            val numericA = a?.toIntOrNull()
            val numericB = b?.toIntOrNull()
            val comparison = when {
                numericA != null && numericB != null -> numericA.compareTo(numericB)
                a == null -> -1
                b == null -> 1
                else -> a.compareTo(b)
            }
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun splitVersion(version: String): List<String> =
        version.trim().split('.', '~', '+', '-').filter { it.isNotEmpty() }

    companion object {
        private const val TAG = "FanFilmUpdater"

        /** Files each addon must ship for the FOX.TV runtime to be able to use it. */
        private val ENTRY_POINTS = mapOf(
            "plugin.video.fanfilm" to listOf(
                "default.py",
                "service.py",
                "lib/fake/xbmc.py",
                "resources/settings.xml",
            ),
            "script.module.resolveurl" to listOf("lib/resolveurl/__init__.py"),
            "script.fanfilm.media" to listOf("addon.xml"),
        )

        /** Dependencies satisfied by FOX.TV rather than by an installed addon. */
        private val PROVIDED_BY_RUNTIME = setOf(
            "script.module.requests",
            "script.module.six",
            "script.module.pyqrcode",
            "script.module.future",
        )
    }
}

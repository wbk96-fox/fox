package com.foxtv.app.core.fanfilm

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.foxtv.app.BuildConfig
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Materialises the vendored Kodi addon tree from assets into the app data dir.
 *
 * Python cannot import from an APK asset: `import lib.ff.sources` needs real
 * directories, sqlite needs a real file, and `settings.xml` needs to be writable.
 * So the tree is copied once per app version and left alone afterwards.
 *
 * User state is deliberately *not* part of the copy. `userdata/` lives beside
 * `addons/` and survives reinstalls of the addon tree, so a FOX.TV update does not
 * reset a user's FanFilm settings, search history or provider toggles.
 */
@Singleton
class FanFilmAssetInstaller @Inject constructor(
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val paths: FanFilmPaths,
) {

    data class Installed(
        val revision: String,
        val addonVersions: Map<String, String>,
        val filesCopied: Int,
        val bytesCopied: Long,
        val reinstalled: Boolean,
    )

    /**
     * Ensure the addon tree on disk matches the one shipped in this build.
     *
     * The revision key combines the app version with the bundled manifest, so both
     * an app upgrade and a change to the vendored addons force a reinstall, while a
     * plain restart does not.
     */
    suspend fun ensureInstalled(): Result<Installed> = withContext(Dispatchers.IO) {
        runCatching {
            paths.createDirectories()
            val manifestJson = readAssetText("${FanFilmPaths.ASSET_ROOT}/$MANIFEST_NAME")
                ?: throw IOException("$MANIFEST_NAME missing from assets; addon tree was not vendored")
            val revision = buildRevision(manifestJson)

            val existing = runCatching { paths.installMarker.readText().trim() }.getOrNull()
            if (existing == revision && paths.addonsDir.listFiles()?.isNotEmpty() == true) {
                return@runCatching Installed(
                    revision = revision,
                    addonVersions = readInstalledVersions(),
                    filesCopied = 0,
                    bytesCopied = 0,
                    reinstalled = false,
                )
            }

            Log.i(TAG, "installing FanFilm addon tree (revision=$revision, previous=$existing)")

            // Replace only the addon tree; userdata/temp are user state.
            if (paths.addonsDir.exists() && !paths.deleteRecursivelyWithinAppData(paths.addonsDir)) {
                throw IOException("could not clear ${paths.addonsDir}")
            }
            if (!paths.addonsDir.mkdirs()) {
                throw IOException("could not create ${paths.addonsDir}")
            }

            val stats = copyAssetTree(FanFilmPaths.ASSET_ROOT, paths.kodiHome)
            paths.installedManifest.writeText(manifestJson)
            paths.installMarker.writeText(revision)

            val versions = readInstalledVersions()
            Log.i(
                TAG,
                "installed ${stats.files} files (${stats.bytes / 1024} KiB); addons=$versions",
            )
            Installed(
                revision = revision,
                addonVersions = versions,
                filesCopied = stats.files,
                bytesCopied = stats.bytes,
                reinstalled = true,
            )
        }
    }

    /** Addon id → version, read from each installed `addon.xml`. */
    fun readInstalledVersions(): Map<String, String> {
        val directories = paths.addonsDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        return directories.mapNotNull { directory ->
            val manifest = File(directory, "addon.xml")
            if (!manifest.isFile) return@mapNotNull null
            val version = runCatching {
                ADDON_VERSION.find(manifest.readText())?.groupValues?.getOrNull(1)
            }.getOrNull()
            directory.name to (version ?: "?")
        }.sortedBy { it.first }.toMap()
    }

    /** Versions declared by the manifest that shipped in the APK. */
    fun readBundledManifest(): Map<String, BundledAddon> {
        val text = runCatching { paths.installedManifest.readText() }.getOrNull()
            ?: readAssetText("${FanFilmPaths.ASSET_ROOT}/$MANIFEST_NAME")
            ?: return emptyMap()
        return parseManifest(text)
    }

    data class BundledAddon(
        val id: String,
        val version: String,
        val origin: String,
        val archiveSha256: String,
        val role: String,
    )

    internal fun parseManifest(text: String): Map<String, BundledAddon> = runCatching {
        val root = JSONObject(text)
        val addons = root.optJSONArray("addons") ?: return@runCatching emptyMap()
        (0 until addons.length()).mapNotNull { index ->
            val entry = addons.optJSONObject(index) ?: return@mapNotNull null
            val id = entry.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            id to BundledAddon(
                id = id,
                version = entry.optString("version"),
                origin = entry.optString("origin"),
                archiveSha256 = entry.optString("archive_sha256"),
                role = entry.optString("role"),
            )
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun buildRevision(manifestJson: String): String {
        // A content hash of the manifest is enough to detect an addon change, and
        // the version code covers bridge/Python changes shipped with the same
        // manifest.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(manifestJson.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return "${BuildConfig.VERSION_CODE}-$digest"
    }

    private data class CopyStats(var files: Int = 0, var bytes: Long = 0)

    private fun copyAssetTree(assetPath: String, destinationRoot: File): CopyStats {
        val stats = CopyStats()
        val assets = context.assets
        copyAssetNode(assets, assetPath, assetPath, destinationRoot, stats)
        return stats
    }

    /**
     * Recursive asset copy.
     *
     * `AssetManager.list` returns an empty array for files *and* for empty
     * directories, so a failed `open` is what distinguishes them. That is the only
     * reliable discriminator the API offers.
     */
    private fun copyAssetNode(
        assets: AssetManager,
        rootPrefix: String,
        assetPath: String,
        destinationRoot: File,
        stats: CopyStats,
    ) {
        val children = runCatching { assets.list(assetPath) }.getOrNull().orEmpty()
        if (children.isEmpty()) {
            val relative = assetPath.removePrefix(rootPrefix).trimStart('/')
            if (relative.isEmpty()) return
            val target = File(destinationRoot, relative)
            if (!paths.isInside(destinationRoot, target)) {
                throw IOException("asset path escapes destination: $assetPath")
            }
            target.parentFile?.mkdirs()
            try {
                assets.open(assetPath).use { input ->
                    target.outputStream().buffered().use { output ->
                        stats.bytes += input.copyTo(output)
                    }
                }
                stats.files++
            } catch (io: IOException) {
                // An empty directory in the asset tree; nothing to copy.
                Log.d(TAG, "skipping non-file asset $assetPath (${io.message})")
            }
            return
        }
        for (child in children) {
            copyAssetNode(assets, rootPrefix, "$assetPath/$child", destinationRoot, stats)
        }
    }

    private fun readAssetText(path: String): String? = runCatching {
        context.assets.open(path).use { it.readBytes().decodeToString() }
    }.getOrNull()

    companion object {
        private const val TAG = "FanFilmAssets"
        private const val MANIFEST_NAME = "MANIFEST.json"
        private val ADDON_VERSION = Regex("""<addon\b[^>]*\bversion="([^"]+)"""")
    }
}

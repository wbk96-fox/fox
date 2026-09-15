package com.foxtv.app.core.fanfilm

import android.util.Log
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads Kodi addon repositories without assuming that their index and packages share a root.
 *
 * FanFilm publishes its index and packages under one `zips` directory. ResolveURL's smrzips
 * repository instead publishes `addons.xml` at the repository root and packages below `zips`:
 *
 * ```
 * <indexBase>/addons.xml
 * <indexBase>/addons.xml.sha256                    when published
 * <packageBase>/<addon.id>/<addon.id>-<version>.zip
 * <packageBase>/<addon.id>/<addon.id>-<version>.zip.sha256   when published
 * ```
 *
 * The index checksum is verified when the publisher provides one. It is not treated as
 * authentication — it is served from the same host as the index — but it does catch a
 * truncated or corrupted fetch before a package download is attempted.
 */
@Singleton
class FanFilmUpdateRepositoryClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {

    /** A repository FOX.TV knows how to read. */
    data class Repository(
        val id: String,
        val indexBaseUrl: String,
        val packageBaseUrl: String,
        /** Addon ids this repository is allowed to update. */
        val addonIds: Set<String>,
    ) {
        init {
            require(id.isNotBlank()) { "repository id must not be blank" }
            require(indexBaseUrl.startsWith("https://")) {
                "repository indexBaseUrl must use HTTPS"
            }
            require(packageBaseUrl.startsWith("https://")) {
                "repository packageBaseUrl must use HTTPS"
            }
            require(addonIds.isNotEmpty()) { "repository addon allow-list must not be empty" }
        }
    }

    data class RemoteAddon(
        val id: String,
        val version: String,
        val repositoryId: String,
        val archiveUrl: String,
        val checksumUrl: String,
        /** `<requires>` declared by the remote addon.xml. */
        val requires: List<Requirement>,
    ) {
        data class Requirement(val addonId: String, val minVersion: String, val optional: Boolean)
    }

    /** Returns the exact repository index URL used by [index]. */
    internal fun indexUrl(repository: Repository): String =
        "${repository.indexBaseUrl.trimEnd('/')}/addons.xml"

    /**
     * Fetch and parse `addons.xml`.
     *
     * Only the addons listed in [Repository.addonIds] are returned: a Kodi repository can
     * carry anything, and FOX.TV must not be talked into installing an addon it never
     * vendored and has no runtime contract with.
     */
    suspend fun index(repository: Repository): Result<List<RemoteAddon>> = runCatching {
        val indexUrl = indexUrl(repository)
        val body = fetchString(indexUrl)

        // Verify the index against its published checksum when there is one. A missing
        // checksum is not fatal (some repositories omit it) but a mismatching one is.
        runCatching { fetchString("$indexUrl.sha256") }.getOrNull()?.let { published ->
            val expected = published.trim().substringBefore(' ').lowercase()
            val actual = sha256(body.toByteArray())
            if (expected.isNotEmpty() && expected != actual) {
                throw IOException(
                    "addons.xml checksum mismatch for ${repository.id}: " +
                        "expected $expected, got $actual"
                )
            }
        }

        parseIndex(body, repository)
    }.onFailure { Log.w(TAG, "could not read ${repository.id} index", it) }

    internal fun parseIndex(xml: String, repository: Repository): List<RemoteAddon> {
        val packageBase = repository.packageBaseUrl.trimEnd('/')
        return ADDON_BLOCK.findAll(xml).mapNotNull { match ->
            val block = match.value
            val id = ADDON_ID.find(block)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            if (id !in repository.addonIds) return@mapNotNull null
            val version = ADDON_VERSION.find(block)?.groupValues?.getOrNull(1)
                ?: return@mapNotNull null
            val archive = "$packageBase/$id/$id-$version.zip"
            RemoteAddon(
                id = id,
                version = version,
                repositoryId = repository.id,
                archiveUrl = archive,
                checksumUrl = "$archive.sha256",
                requires = REQUIRES_BLOCK.find(block)?.groupValues?.getOrNull(1)
                    ?.let(::parseRequires)
                    .orEmpty(),
            )
        }.toList()
    }

    internal fun parseRequires(block: String): List<RemoteAddon.Requirement> =
        IMPORT_TAG.findAll(block).map { match ->
            val tag = match.value
            RemoteAddon.Requirement(
                addonId = IMPORT_ADDON.find(tag)?.groupValues?.getOrNull(1).orEmpty(),
                minVersion = IMPORT_VERSION.find(tag)?.groupValues?.getOrNull(1).orEmpty(),
                optional = tag.contains("optional=\"true\""),
            )
        }.filter { it.addonId.isNotEmpty() }.toList()

    /** Fetch the published checksum for a package, or null when there is none. */
    suspend fun checksum(addon: RemoteAddon): String? =
        runCatching { fetchString(addon.checksumUrl).trim().substringBefore(' ').lowercase() }
            .getOrNull()
            ?.takeIf { it.length == SHA256_HEX_LENGTH && it.all(Char::isLetterOrDigit) }

    /** Download [addon] into [target], returning the SHA-256 of what was written. */
    suspend fun download(addon: RemoteAddon, target: java.io.File): Result<String> = runCatching {
        val request = Request.Builder().url(addon.archiveUrl).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for ${addon.archiveUrl}")
            }
            val body = response.body ?: throw IOException("empty body for ${addon.archiveUrl}")
            target.parentFile?.mkdirs()
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_PACKAGE_BYTES) {
                            throw IOException(
                                "${addon.id} package exceeds the ${MAX_PACKAGE_BYTES} byte limit"
                            )
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private fun fetchString(url: String): String {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body?.string() ?: throw IOException("empty body for $url")
        }
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "FanFilmRepoClient"
        private const val SHA256_HEX_LENGTH = 64

        /** Largest addon package FOX.TV will download. FanFilm itself is ~2.6 MB. */
        const val MAX_PACKAGE_BYTES = 64L * 1024 * 1024

        private val ADDON_BLOCK = Regex("""<addon\b.*?</addon>""", RegexOption.DOT_MATCHES_ALL)
        private val ADDON_ID = Regex("""<addon\b[^>]*\bid="([^"]+)"""")
        private val ADDON_VERSION = Regex("""<addon\b[^>]*\bversion="([^"]+)"""")
        private val REQUIRES_BLOCK =
            Regex("""<requires>(.*?)</requires>""", RegexOption.DOT_MATCHES_ALL)
        private val IMPORT_TAG = Regex("""<import\b[^>]*/?>""")
        private val IMPORT_ADDON = Regex("""\baddon="([^"]+)"""")
        private val IMPORT_VERSION = Regex("""\bversion="([^"]+)"""")

        /**
         * The repositories FOX.TV updates from, and what each may update.
         *
         * FanFilm's stable channel is the production source; the beta repository exists
         * upstream but is not wired in, because a beta addon can require a Kodi API FOX.TV's
         * compatibility layer has not been checked against.
         */
        val FANFILM_STABLE = Repository(
            id = "fanfilm-stable",
            indexBaseUrl =
                "https://raw.githubusercontent.com/fanfilm-pl/repository.fanfilm/main/zips",
            packageBaseUrl =
                "https://raw.githubusercontent.com/fanfilm-pl/repository.fanfilm/main/zips",
            addonIds = setOf("plugin.video.fanfilm", "script.fanfilm.media"),
        )

        val RESOLVEURL = Repository(
            id = "resolveurl",
            indexBaseUrl = "https://raw.githubusercontent.com/Gujal00/smrzips/master",
            packageBaseUrl = "https://raw.githubusercontent.com/Gujal00/smrzips/master/zips",
            addonIds = setOf("script.module.resolveurl"),
        )

        val ALL = listOf(FANFILM_STABLE, RESOLVEURL)
    }
}

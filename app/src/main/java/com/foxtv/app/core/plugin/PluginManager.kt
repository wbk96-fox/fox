package com.foxtv.app.core.plugin

import android.util.Log
import com.foxtv.app.core.plugin.cloudstream.ExternalExtensionLoader
import com.foxtv.app.core.plugin.cloudstream.ExternalExtensionRunner
import com.foxtv.app.core.plugin.cloudstream.ExternalPluginLifecycleCoordinator
import com.foxtv.app.core.plugin.cloudstream.ExternalRepoParseResult
import com.foxtv.app.core.plugin.cloudstream.ExternalRepoParser
import com.foxtv.app.core.plugin.cloudstream.ExternalRepositorySynchronizer
import com.foxtv.app.data.local.PluginDataStore
import com.foxtv.app.domain.model.LocalScraperResult
import com.foxtv.app.domain.model.PluginManifest
import com.foxtv.app.domain.model.PluginRepository
import com.foxtv.app.domain.model.RemotePluginInfo
import com.foxtv.app.domain.model.RepositoryType
import com.foxtv.app.domain.model.ScraperInfo
import com.foxtv.app.domain.model.ScraperManifestInfo
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PluginManager"
// Scrapers are network-bound, not CPU-bound. Running more concurrently lets slow
// providers overlap with fast ones instead of batching in groups of 5. OkHttp's
// dispatcher + the providers' own internal parallelism stay the real bottleneck.
private const val MAX_CONCURRENT_SCRAPERS = 10
private const val MAX_RESULT_ITEMS = 150
private const val MAX_RESPONSE_SIZE = 5 * 1024 * 1024L
// Outer safety-net timeout for scrapers. The runner now internally caps loadLinks
// at 60s and returns partial links. This outer cap only fires if the runner hangs
// outside of loadLinks (e.g. slow TMDB enrichment, slow search). Generous to avoid
// cancelling the runner's coroutine before it can return accumulated links.
private const val SCRAPER_TIMEOUT_MS = 120_000L
private const val MANIFEST_SUFFIX = "/manifest.json"

@Singleton
class PluginManager @Inject constructor(
    private val dataStore: PluginDataStore,
    private val runtime: PluginRuntime,
    private val pluginSyncService: com.foxtv.app.core.sync.PluginSyncService,
    private val authManager: com.foxtv.app.core.auth.AuthManager,
    private val externalRepoParser: ExternalRepoParser,
    private val externalExtensionLoader: ExternalExtensionLoader,
    private val externalExtensionRunner: ExternalExtensionRunner,
    private val externalPluginLifecycle: ExternalPluginLifecycleCoordinator,
    private val externalRepositorySynchronizer: ExternalRepositorySynchronizer,
) {
    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    
    private val manifestAdapter = moshi.adapter(PluginManifest::class.java)
    
    private val httpClient = OkHttpClient.Builder()
        .dns(com.foxtv.app.core.network.IPv4FirstDns())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(((b.toInt() shr 4) and 0xF).toString(16))
            sb.append((b.toInt() and 0xF).toString(16))
        }
        return sb.toString()
    }

    /**
     * Normalize custom protocol schemes to https://.
     * External repos often use schemes like "cloudstreamrepo://" or "stremio://".
     */
    private fun sanitizeScheme(url: String): String {
        val trimmed = url.trim()
        // Replace any non-http(s) scheme with https://
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd > 0) {
            val scheme = trimmed.substring(0, schemeEnd).lowercase()
            if (scheme != "http" && scheme != "https") {
                return "https://${trimmed.substring(schemeEnd + 3)}"
            }
        }
        return trimmed
    }

    /**
     * Check if the input looks like a short code rather than a URL.
     * Short codes are alphanumeric strings without slashes, dots (other than in a domain),
     * or protocol schemes — e.g. "cspr", "0094", "megarepo".
     */
    private fun isShortCode(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return false
        // Has a scheme → not a short code
        if (trimmed.contains("://")) return false
        // Has path separators or dots → likely a URL or domain
        if (trimmed.contains("/") || trimmed.contains(".")) return false
        // Only alphanumeric + hyphens + underscores → short code
        return trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    /**
     * Resolve a short code by following the redirect from cutt.ly/{code}.
     * Returns the resolved URL or null if resolution fails.
     */
    private fun resolveShortCode(code: String): String? {
        return try {
            // Use a client that does NOT follow redirects so we can read the Location header
            val noRedirectClient = httpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

            val request = Request.Builder()
                .url("https://cutt.ly/$code")
                .header("User-Agent", "FOX.TV/1.0")
                .build()

            noRedirectClient.newCall(request).execute().use { response ->
                if (response.code in 301..302) {
                    val location = response.header("Location")
                    if (!location.isNullOrBlank()) {
                        Log.d(TAG, "Short code '$code' resolved to: $location")
                        return sanitizeScheme(location)
                    }
                }
                // Some shorteners return 200 with a meta refresh or JS redirect
                // Try following redirects as fallback
                Log.d(TAG, "Short code '$code' returned ${response.code}, trying with redirects")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve short code '$code': ${e.message}")
            null
        } ?: try {
            // Fallback: follow redirects and see where we end up
            val request = Request.Builder()
                .url("https://cutt.ly/$code")
                .header("User-Agent", "FOX.TV/1.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                if (finalUrl != "https://cutt.ly/$code" && response.isSuccessful) {
                    Log.d(TAG, "Short code '$code' resolved via redirect chain to: $finalUrl")
                    sanitizeScheme(finalUrl)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback resolve for short code '$code' failed: ${e.message}")
            null
        }
    }

    private fun canonicalizeManifestUrl(url: String): String {
        val trimmed = sanitizeScheme(url).trimEnd('/')
        return if (trimmed.endsWith(MANIFEST_SUFFIX, ignoreCase = true)) {
            trimmed
        } else {
            "$trimmed$MANIFEST_SUFFIX"
        }
    }

    /**
     * Canonicalize a URL for deduplication. For FOX.TV-style URLs (that don't end in .json),
     * appends /manifest.json. For URLs already ending in .json (external repos), keeps them as-is.
     */
    private fun canonicalizeRepoUrl(url: String): String {
        val trimmed = sanitizeScheme(url).trimEnd('/')
        // If URL already ends with a .json file, it's likely an external repo URL — keep as-is
        if (trimmed.substringAfterLast("/").endsWith(".json", ignoreCase = true)) {
            return trimmed
        }
        // Otherwise canonicalize as FOX.TV manifest
        return canonicalizeManifestUrl(trimmed)
    }

    private fun normalizeUrl(url: String): String = canonicalizeRepoUrl(url).lowercase()
    
    // Single-flight map to prevent duplicate scraper executions
    private val inFlightScrapers = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<LocalScraperResult>>>()
    
    // Semaphore to limit concurrent scrapers
    private val scraperSemaphore = Semaphore(MAX_CONCURRENT_SCRAPERS)

    
    @OptIn(ExperimentalCoroutinesApi::class)
    private val pluginDispatcher: CoroutineDispatcher =
        Executors.newFixedThreadPool(MAX_CONCURRENT_SCRAPERS) { runnable ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "plugin-worker").apply {
                isDaemon = true
            }
        }.asCoroutineDispatcher()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scraperOrchestrationDispatcher: CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_SCRAPERS)
    
    // Flow of all repositories
    val repositories: Flow<List<PluginRepository>> = dataStore.repositories
    
    // Flow of all scrapers
    val scrapers: Flow<List<ScraperInfo>> = dataStore.scrapers
    
    // Flow of plugins enabled state
    val pluginsEnabled: Flow<Boolean> = dataStore.pluginsEnabled

    val groupStreamsByRepository: Flow<Boolean> = dataStore.groupStreamsByRepository
    
    private val syncScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    var isSyncingFromRemote = false

    /** Prevents concurrent reconciliation from StartupSyncService and AccountViewModel */
    private val reconcileMutex = Mutex()

    @Volatile
    private var pendingPushAfterSync = false

    /**
     * Call after setting isSyncingFromRemote = false to push any changes
     * that were made during reconciliation (e.g. repo removals).
     */
    fun flushPendingSync() {
        if (pendingPushAfterSync) {
            pendingPushAfterSync = false
            Log.d(TAG, "flushPendingSync: firing deferred push")
            triggerRemoteSync()
        }
    }

    private var syncJob: kotlinx.coroutines.Job? = null

    private fun triggerRemoteSync() {
        if (isSyncingFromRemote) {
            Log.d(TAG, "triggerRemoteSync: skipped (syncing from remote), will push after sync")
            pendingPushAfterSync = true
            return
        }
        if (!authManager.isAuthenticated) {
            Log.d(TAG, "triggerRemoteSync: skipped (not authenticated, state=${authManager.authState.value})")
            return
        }
        Log.d(TAG, "triggerRemoteSync: scheduling push in 500ms")
        syncJob?.cancel()
        syncJob = syncScope.launch {
            kotlinx.coroutines.delay(500)
            val result = pluginSyncService.pushToRemote()
            Log.d(TAG, "triggerRemoteSync: push result=${result.isSuccess} ${result.exceptionOrNull()?.message ?: ""}")
        }
    }

    // Combined flow of enabled scrapers
    val enabledScrapers: Flow<List<ScraperInfo>> = combine(
        scrapers,
        pluginsEnabled
    ) { scraperList, enabled ->
        if (enabled) scraperList.filter { it.enabled && it.manifestEnabled } else emptyList()
    }
    
    /**
     * Add a new repository from manifest URL.
     * Auto-detects format: tries FOX.TV manifest first, then external repo format.
     */
    suspend fun addRepository(manifestUrl: String): Result<PluginRepository> = withContext(Dispatchers.IO) {
        try {
            // Resolve short codes (e.g. "cspr", "0094") via cutt.ly redirect
            val resolvedUrl = if (isShortCode(manifestUrl)) {
                Log.d(TAG, "Input looks like a short code: '$manifestUrl'")
                resolveShortCode(manifestUrl.trim())
                    ?: return@withContext Result.failure(
                        Exception("Failed to resolve short code: $manifestUrl")
                    )
            } else {
                sanitizeScheme(manifestUrl).trimEnd('/')
            }

            val sanitizedUrl = resolvedUrl.trimEnd('/')
            val existingRepository = dataStore.repositories.first()
                .find { normalizeUrl(it.url) == normalizeUrl(sanitizedUrl) }
            if (existingRepository != null) {
                return@withContext Result.success(existingRepository)
            }
            val filename = sanitizedUrl.substringAfterLast("/")
            val isExplicitJsonFile = filename.endsWith(".json", ignoreCase = true)
                    && !filename.equals("manifest.json", ignoreCase = true)

            // If the URL points to a specific .json file (not manifest.json),
            // try external format first to avoid a wasted 404 on the FOX.TV path.
            if (isExplicitJsonFile) {
                Log.d(TAG, "URL ends in .json — trying external format first: $sanitizedUrl")
                val externalResult = externalRepoParser.tryParse(sanitizedUrl)
                if (externalResult != null) {
                    return@withContext addExternalRepository(sanitizedUrl, externalResult)
                }
            }

            // Try FOX.TV format (with canonicalized /manifest.json URL)
            val canonicalManifestUrl = canonicalizeManifestUrl(sanitizedUrl)
            Log.d(TAG, "Trying FOX.TV manifest: $canonicalManifestUrl")

            val manifest = fetchManifest(canonicalManifestUrl)
            if (manifest != null) {
                return@withContext addFoxTvRepository(canonicalManifestUrl, manifest)
            }

            // If we haven't tried external format yet, try it now
            if (!isExplicitJsonFile) {
                Log.d(TAG, "FOX.TV manifest not found, trying external format: $sanitizedUrl")
                val externalResult = externalRepoParser.tryParse(sanitizedUrl)
                if (externalResult != null) {
                    return@withContext addExternalRepository(sanitizedUrl, externalResult)
                }
            }

            Result.failure(Exception("Failed to parse repository: unrecognized format"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add repository: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Add a repository with a type hint from Supabase sync.
     * Skips wrong detection paths when the type is already known,
     * making reconciliation faster and more resilient to network issues.
     */
    private suspend fun addRepositoryWithTypeHint(
        manifestUrl: String,
        typeHint: RepositoryType?
    ): Result<PluginRepository> = withContext(Dispatchers.IO) {
        try {
            val sanitizedUrl = sanitizeScheme(manifestUrl).trimEnd('/')

            when (typeHint) {
                RepositoryType.EXTERNAL_DEX -> {
                    Log.d(TAG, "addRepositoryWithTypeHint: EXTERNAL_DEX hint, trying external format: $sanitizedUrl")
                    val externalResult = externalRepoParser.tryParse(sanitizedUrl)
                    if (externalResult != null) {
                        return@withContext addExternalRepository(sanitizedUrl, externalResult)
                    }
                    // Hint was wrong or parse failed — fall through to auto-detect
                    Log.w(TAG, "addRepositoryWithTypeHint: EXTERNAL_DEX hint failed, falling back to auto-detect")
                }
                RepositoryType.FOXTV_JS -> {
                    Log.d(TAG, "addRepositoryWithTypeHint: FOXTV_JS hint, trying manifest: $sanitizedUrl")
                    val canonicalManifestUrl = canonicalizeManifestUrl(sanitizedUrl)
                    val manifest = fetchManifest(canonicalManifestUrl)
                    if (manifest != null) {
                        return@withContext addFoxTvRepository(canonicalManifestUrl, manifest)
                    }
                    Log.w(TAG, "addRepositoryWithTypeHint: FOXTV_JS hint failed, falling back to auto-detect")
                }
                null -> { /* No hint — use auto-detect */ }
            }

            // Fall back to full auto-detection
            addRepository(sanitizedUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add repository with hint: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun addFoxTvRepository(
        canonicalManifestUrl: String,
        manifest: PluginManifest
    ): Result<PluginRepository> = externalPluginLifecycle.serialized {
        val repo = PluginRepository(
            id = UUID.randomUUID().toString(),
            name = manifest.name,
            url = canonicalManifestUrl,
            enabled = true,
            lastUpdated = System.currentTimeMillis(),
            scraperCount = manifest.scrapers.size,
            type = RepositoryType.FOXTV_JS
        )

        dataStore.addRepository(repo)
        downloadJsScrapers(repo.id, canonicalManifestUrl, manifest.scrapers)

        Log.d(TAG, "FOX.TV repository added: ${repo.name} with ${manifest.scrapers.size} scrapers")
        triggerRemoteSync()
        Result.success(repo)
    }

    private suspend fun addExternalRepository(
        repoUrl: String,
        parseResult: ExternalRepoParseResult,
    ): Result<PluginRepository> = externalPluginLifecycle.serialized {
        val existingRepo = dataStore.repositories.first()
            .find { normalizeUrl(it.url) == normalizeUrl(repoUrl) }
        if (existingRepo != null) {
            Log.d(TAG, "External repository already exists: ${existingRepo.name} (${existingRepo.url})")
            return@serialized Result.success(existingRepo)
        }
        if (!parseResult.isAuthoritativeForRemoval) {
            return@serialized Result.failure(
                IllegalArgumentException(
                    "Repository metadata is incomplete, ambiguous, or contains an invalid active plugin",
                ),
            )
        }
        if (parseResult.plugins.isEmpty()) {
            return@serialized Result.failure(
                IllegalArgumentException(
                    "Repository contains no active, compatible plugins with verified metadata",
                ),
            )
        }

        val repo = PluginRepository(
            id = UUID.randomUUID().toString(),
            name = parseResult.name,
            url = repoUrl,
            description = parseResult.description,
            enabled = true,
            lastUpdated = System.currentTimeMillis(),
            scraperCount = parseResult.plugins.size,
            type = RepositoryType.EXTERNAL_DEX,
        )
        val syncResult = externalRepositorySynchronizer.synchronizeLocked(
            repository = repo,
            parseResult = parseResult,
            requireExistingRepository = false,
        )
        if (syncResult.failedPluginNames.isNotEmpty() ||
            syncResult.installed.size != parseResult.plugins.size
        ) {
            return@serialized Result.failure(
                IllegalStateException(
                    "External repository was not installed because its complete verified generation could not be staged",
                ),
            )
        }

        triggerRemoteSync()
        Result.success(repo)
    }
    
    /**
     * Remove a repository and its scrapers
     */
    suspend fun removeRepository(repoId: String) = externalPluginLifecycle.serialized {
        val scraperList = dataStore.scrapers.first()
        val repo = dataStore.repositories.first().find { it.id == repoId } ?: return@serialized
        val repositoryScrapers = scraperList.filter { it.repositoryId == repoId }
        val updatedScrapers = scraperList.filter { it.repositoryId != repoId }

        if (repo.type == RepositoryType.EXTERNAL_DEX) {
            val committed = dataStore.removeExternalRepositoryState(repoId, updatedScrapers)
            if (!committed) return@serialized
            repositoryScrapers.forEach { externalExtensionLoader.deleteExtension(it.id) }
        } else {
            if (!dataStore.saveScrapers(updatedScrapers)) return@serialized
            dataStore.removeRepository(repoId)
            repositoryScrapers.forEach { dataStore.deleteScraperCode(it.id) }
        }

        // Push synchronously when user-initiated (not during reconciliation)
        // to prevent the next sync pull from re-adding the removed repo.
        if (!isSyncingFromRemote && authManager.isAuthenticated) {
            Log.d(TAG, "removeRepository: pushing removal to remote synchronously")
            pluginSyncService.pushToRemote()
        } else if (isSyncingFromRemote) {
            pendingPushAfterSync = true
        }
    }

    
    /**
     * Reconcile local plugin repos with the remote list from Supabase.
     * @param remotePlugins list of remote plugin info (URL + optional type hint)
     * @param removeMissingLocal if true, remove local repos not in the remote list
     */
    suspend fun reconcileWithRemoteRepoUrls(
        remotePlugins: List<RemotePluginInfo>,
        removeMissingLocal: Boolean = true
    ) = reconcileMutex.withLock {
        val normalizedRemote = remotePlugins
            .map { it.copy(url = canonicalizeRepoUrl(it.url)) }
            .filter { it.url.isNotEmpty() }
            .distinctBy { normalizeUrl(it.url) }
        val remoteUrlSet = normalizedRemote.map { normalizeUrl(it.url) }.toSet()

        val initialLocalRepos = dataStore.repositories.first()
        val initialLocalByNormalizedUrl = initialLocalRepos.associateBy { normalizeUrl(it.url) }
        val shouldRemoveMissingLocal = if (removeMissingLocal && normalizedRemote.isEmpty() && initialLocalRepos.isNotEmpty()) {
            Log.w(
                TAG,
                "reconcileWithRemoteRepoUrls: remote list empty while local has ${initialLocalRepos.size} repos; preserving local plugins"
            )
            false
        } else {
            removeMissingLocal
        }

        if (shouldRemoveMissingLocal) {
            initialLocalRepos
                .filter { normalizeUrl(it.url) !in remoteUrlSet }
                .forEach { repo ->
                    Log.d(TAG, "reconcile: removing local repo not in remote: ${repo.name} (${repo.url})")
                    removeRepository(repo.id)
                }
        }

        normalizedRemote.forEach { remotePlugin ->
            if (initialLocalByNormalizedUrl[normalizeUrl(remotePlugin.url)] == null) {
                val typeHint = remotePlugin.repoType?.let {
                    try { RepositoryType.valueOf(it) } catch (_: Exception) { null }
                }
                val result = addRepositoryWithTypeHint(remotePlugin.url, typeHint)
                if (result.isFailure) {
                    Log.e(TAG, "reconcile: failed to add repo ${remotePlugin.url}: ${result.exceptionOrNull()?.message}")
                }
            }
        }

        val currentRepos = dataStore.repositories.first()
        val currentByNormalizedUrl = currentRepos.associateBy { normalizeUrl(it.url) }
        val remoteOrderedRepos = normalizedRemote
            .mapNotNull { currentByNormalizedUrl[normalizeUrl(it.url)] }
        val extras = currentRepos
            .filter { normalizeUrl(it.url) !in remoteUrlSet }

        val reordered = if (shouldRemoveMissingLocal) remoteOrderedRepos else remoteOrderedRepos + extras
        if (reordered.map { it.id } != currentRepos.map { it.id }) {
            dataStore.saveRepositories(reordered)
        }
    }

    /** Convenience overload for plain URL lists (no type hints) */
    @JvmName("reconcileWithRemoteRepoUrlStrings")
    suspend fun reconcileWithRemoteRepoUrls(
        remoteUrls: List<String>,
        removeMissingLocal: Boolean = true
    ) {
        reconcileWithRemoteRepoUrls(
            remotePlugins = remoteUrls.map { RemotePluginInfo(url = it) },
            removeMissingLocal = removeMissingLocal
        )
    }
    
    /**
     * Refresh a repository - re-download manifest and scrapers
     */
    suspend fun refreshRepository(repoId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val repo = dataStore.repositories.first().find { it.id == repoId }
                ?: return@withContext Result.failure(Exception("Repository not found"))

            if (repo.type == RepositoryType.EXTERNAL_DEX) {
                return@withContext externalPluginLifecycle.serialized {
                    val currentRepo = dataStore.repositories.first().find { it.id == repoId }
                        ?: return@serialized Result.failure(Exception("Repository not found"))
                    refreshExternalRepository(currentRepo)
                }
            }

            return@withContext externalPluginLifecycle.serialized {
                val currentRepo = dataStore.repositories.first().find { it.id == repoId }
                    ?: return@serialized Result.failure(Exception("Repository not found"))
                val manifest = fetchManifest(currentRepo.url)
                    ?: return@serialized Result.failure(Exception("Failed to fetch manifest"))

                val updatedRepo = currentRepo.copy(
                    name = manifest.name,
                    lastUpdated = System.currentTimeMillis(),
                    scraperCount = manifest.scrapers.size
                )
                dataStore.updateRepository(updatedRepo)
                downloadJsScrapers(currentRepo.id, currentRepo.url, manifest.scrapers)

                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh repository: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun refreshExternalRepository(repo: PluginRepository): Result<Unit> {
        val parseResult = externalRepoParser.tryParse(repo.url)
            ?: return Result.failure(Exception("Failed to parse external repository"))
        if (!parseResult.isAuthoritativeForRemoval) {
            return Result.failure(
                IllegalStateException(
                    "Repository metadata is incomplete, ambiguous, or contains an invalid active plugin; " +
                        "the previous generation was preserved",
                ),
            )
        }

        val updatedRepo = repo.copy(
            name = parseResult.name,
            description = parseResult.description,
            lastUpdated = System.currentTimeMillis(),
            scraperCount = parseResult.plugins.size,
        )
        val syncResult = externalRepositorySynchronizer.synchronizeLocked(
            repository = updatedRepo,
            parseResult = parseResult,
            requireExistingRepository = true,
        )
        if (syncResult.failedPluginNames.isNotEmpty()) {
            return Result.failure(
                IllegalStateException(
                    "Repository refresh failed validation or download for " +
                        syncResult.failedPluginNames.joinToString() +
                        "; the previous generation was preserved",
                ),
            )
        }
        return Result.success(Unit)
    }

    /**
     * Toggle scraper enabled state. Persistence is the commit point; unload happens only after it.
     */
    suspend fun toggleScraper(scraperId: String, enabled: Boolean) =
        externalPluginLifecycle.serialized {
            val scraperList = dataStore.scrapers.first()
            val current = scraperList.firstOrNull { it.id == scraperId } ?: return@serialized
            val effectiveEnabled = enabled && current.manifestEnabled
            val updatedScrapers = scraperList.map { scraper ->
                if (scraper.id == scraperId) scraper.copy(enabled = effectiveEnabled) else scraper
            }
            if (!dataStore.saveScrapers(updatedScrapers)) return@serialized

            if (current.type == RepositoryType.EXTERNAL_DEX) {
                externalExtensionLoader.setOwnersEnabled(listOf(scraperId), effectiveEnabled)
                if (!effectiveEnabled) cancelInFlightForOwners(setOf(scraperId))
            }
        }

    /**
     * Toggle all scrapers belonging to a repository in one persistent commit.
     */
    suspend fun toggleAllScrapersForRepo(repoId: String, enabled: Boolean) =
        externalPluginLifecycle.serialized {
            val scraperList = dataStore.scrapers.first()
            val affected = scraperList.filter { it.repositoryId == repoId }
            if (affected.isEmpty()) return@serialized
            val updatedScrapers = scraperList.map { scraper ->
                if (scraper.repositoryId == repoId) {
                    scraper.copy(enabled = enabled && scraper.manifestEnabled)
                } else {
                    scraper
                }
            }
            if (!dataStore.saveScrapers(updatedScrapers)) return@serialized

            val updatedById = updatedScrapers.associateBy(ScraperInfo::id)
            val externalAffected = affected.filter { it.type == RepositoryType.EXTERNAL_DEX }
            val enabledIds = externalAffected.mapNotNull { scraper ->
                scraper.id.takeIf { updatedById[scraper.id]?.enabled == true }
            }
            val disabledIds = externalAffected.mapNotNull { scraper ->
                scraper.id.takeIf { updatedById[scraper.id]?.enabled != true }
            }
            externalExtensionLoader.setOwnersEnabled(enabledIds, true)
            externalExtensionLoader.setOwnersEnabled(disabledIds, false)
            cancelInFlightForOwners(disabledIds.toSet())
        }

    /** Toggle plugins globally; re-enable remains lazy, disable unregisters every DEX owner. */
    suspend fun setPluginsEnabled(enabled: Boolean) = externalPluginLifecycle.serialized {
        val externalIds = dataStore.scrapers.first()
            .filter { it.type == RepositoryType.EXTERNAL_DEX }
            .map(ScraperInfo::id)
        if (!dataStore.setPluginsEnabled(enabled)) return@serialized
        externalExtensionLoader.setGloballyEnabled(enabled, externalIds)
        if (!enabled) cancelInFlightForOwners(externalIds.toSet())
    }

    private fun cancelInFlightForOwners(ownerIds: Set<String>) {
        if (ownerIds.isEmpty()) return
        inFlightScrapers.forEach { (key, deferred) ->
            if (ownerIds.any { ownerId -> key.startsWith("$ownerId:") }) {
                deferred.cancel(CancellationException("External extension disabled"))
            }
        }
    }

    suspend fun setGroupStreamsByRepository(enabled: Boolean) {
        dataStore.setGroupStreamsByRepository(enabled)
    }
    
    /**
     * Execute all enabled scrapers for a given media
     */
    suspend fun executeScrapers(
        tmdbId: String,
        mediaType: String,
        season: Int? = null,
        episode: Int? = null
    ): List<LocalScraperResult> = coroutineScope {
        if (!dataStore.pluginsEnabled.first()) {
            return@coroutineScope emptyList()
        }
        
        val enabledScraperList = enabledScrapers.first()
            .filter { it.supportsType(mediaType) }
        
        if (enabledScraperList.isEmpty()) {
            return@coroutineScope emptyList()
        }
        
        Log.d(TAG, "Executing ${enabledScraperList.size} scrapers for $mediaType:$tmdbId")

        // Preload all extractors from EXTERNAL_DEX repos before any scraper runs
        val dexScraperIds = enabledScraperList
            .filter { it.type == RepositoryType.EXTERNAL_DEX }
            .map { it.id }
        if (dexScraperIds.isNotEmpty()) {
            // Extractors may live in another user-enabled .cs3, but disabled or
            // manifest-disabled code is never loaded.
            val allDexIds = dataStore.scrapers.first()
                .filter {
                    it.type == RepositoryType.EXTERNAL_DEX && it.enabled && it.manifestEnabled
                }
                .map { it.id }
            withContext(Dispatchers.IO) {
                externalExtensionLoader.ensureExtractorsLoaded(allDexIds)
            }
        }

        val results = enabledScraperList.mapIndexed { index, scraper ->
            async(scraperOrchestrationDispatcher) {
                if (index > 0) {
                    kotlinx.coroutines.delay(index * 60L)
                }
                executeScraperWithSingleFlight(scraper, tmdbId, mediaType, season, episode)
            }
        }.awaitAll()
        
        results.flatten()
            .distinctBy { it.url }
            .take(MAX_RESULT_ITEMS)
    }
    
    /**
     * Execute all enabled scrapers and emit results as each scraper completes.
     * Returns a Flow that emits (scraperName, results) pairs.
     */
    fun executeScrapersStreaming(
        tmdbId: String,
        mediaType: String,
        season: Int? = null,
        episode: Int? = null
    ): Flow<Pair<ScraperInfo, List<LocalScraperResult>>> = channelFlow {
        val enabledList = enabledScrapers.first()
            .filter { it.supportsType(mediaType) }
        
        if (enabledList.isEmpty() || !dataStore.pluginsEnabled.first()) {
            return@channelFlow
        }
        
        Log.d(TAG, "Streaming execution of ${enabledList.size} scrapers for $mediaType:$tmdbId")
 
        // Preload all extractors from EXTERNAL_DEX repos before any scraper runs
        val dexScraperIds = enabledList.filter { it.type == RepositoryType.EXTERNAL_DEX }.map { it.id }
        if (dexScraperIds.isNotEmpty()) {
            val allDexIds = dataStore.scrapers.first()
                .filter {
                    it.type == RepositoryType.EXTERNAL_DEX && it.enabled && it.manifestEnabled
                }
                .map { it.id }
            withContext(Dispatchers.IO) {
                externalExtensionLoader.ensureExtractorsLoaded(allDexIds)
            }
        }
 
        enabledList.forEachIndexed { index, scraper ->
            launch(scraperOrchestrationDispatcher) {
                if (index > 0) {
                    kotlinx.coroutines.delay(index * 60L)
                }
                try {
                    val results = executeScraperWithSingleFlight(scraper, tmdbId, mediaType, season, episode)
                    if (results.isNotEmpty()) {
                        send(scraper to results)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Scraper ${scraper.name} failed in streaming: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Execute a single scraper with single-flight deduplication
     */
    private suspend fun executeScraperWithSingleFlight(
        scraper: ScraperInfo,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?
    ): List<LocalScraperResult> {
        val cacheKey = "${scraper.id}:$tmdbId:$mediaType:$season:$episode"
        
        // Check if already in flight
        val existing = inFlightScrapers[cacheKey]
        if (existing != null) {
            return try {
                existing.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
        
        // Create new deferred
        return coroutineScope {
            val deferred = async {
                scraperSemaphore.withPermit {
                    executeScraper(scraper, tmdbId, mediaType, season, episode)
                }
            }
            
            inFlightScrapers[cacheKey] = deferred
            
            try {
                deferred.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Scraper ${scraper.name} failed: ${e.message}")
                emptyList()
            } finally {
                inFlightScrapers.remove(cacheKey)
            }
        }
    }
    
    /**
     * Execute a single scraper, dispatching by type.
     */
    suspend fun executeScraper(
        scraper: ScraperInfo,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?
    ): List<LocalScraperResult> {
        if (!scraper.enabled || !scraper.manifestEnabled) {
            Log.w(TAG, "Refusing to execute disabled scraper: ${scraper.id}")
            return emptyList()
        }
        return when (scraper.type) {
            RepositoryType.EXTERNAL_DEX -> executeExternalDexScraper(scraper, tmdbId, mediaType, season, episode)
            RepositoryType.FOXTV_JS -> executeJsScraper(scraper, tmdbId, mediaType, season, episode)
        }
    }

    private suspend fun executeJsScraper(
        scraper: ScraperInfo,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?
    ): List<LocalScraperResult> {
        return try {
            val code = dataStore.getScraperCode(scraper.id)
            if (code.isNullOrBlank()) {
                Log.w(TAG, "No code found for scraper: ${scraper.name}")
                return emptyList()
            }

            // Debug: confirm which exact JS code is running on-device.
            try {
                val sha = sha256Hex(code)
                val bytes = code.toByteArray(Charsets.UTF_8).size
                val hasHrefliLogs = code.contains("[UHDMovies][Hrefli]", ignoreCase = false) ||
                    code.contains("[Hrefli]", ignoreCase = false)
                Log.d(
                    TAG,
                    "Scraper code loaded: ${scraper.name}(${scraper.id}) bytes=$bytes sha256=${sha.take(12)} hrefliLogs=$hasHrefliLogs"
                )
            } catch (_: Exception) {
                // ignore
            }

            val settings = dataStore.getScraperSettings(scraper.id)
            
            Log.d(TAG, "Executing scraper: ${scraper.name}")
            val results = withTimeoutOrNull(SCRAPER_TIMEOUT_MS) {
                // Run plugin JS on the dedicated low-priority pool so a buggy
                // scraper can't burn cores at the expense of ExoPlayer / UI.
                withContext(pluginDispatcher) {
                    runtime.executePlugin(
                        code = code,
                        tmdbId = tmdbId,
                        mediaType = mediaType,
                        season = season,
                        episode = episode,
                        scraperId = scraper.id,
                        scraperSettings = settings
                    )
                }
            }

            if (results == null) {
                Log.w(TAG, "Scraper ${scraper.name} timed out after ${SCRAPER_TIMEOUT_MS}ms")
                return emptyList()
            }

            Log.d(TAG, "Scraper ${scraper.name} returned ${results.size} results")
            results.map { it.copy(provider = scraper.name) }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute scraper ${scraper.name}: ${e.message}", e)
            emptyList()
        }
    }

    private suspend fun executeExternalDexScraper(
        scraper: ScraperInfo,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?
    ): List<LocalScraperResult> {
        return try {
            Log.d(TAG, "Executing DEX scraper: ${scraper.name}")
            val results = withTimeoutOrNull(SCRAPER_TIMEOUT_MS) {
                // DEX (.cs3) scrapers run arbitrary Kotlin from external repos.
                // Wrap on the low-priority pool for the same reason as the JS
                // path: keep their CPU footprint out of ExoPlayer's way.
                withContext(pluginDispatcher) {
                    externalExtensionRunner.execute(scraper.id, tmdbId, mediaType, season, episode)
                }
            }
            if (results == null) {
                Log.w(TAG, "DEX scraper ${scraper.name} timed out after ${SCRAPER_TIMEOUT_MS}ms")
                return emptyList()
            }
            Log.d(TAG, "DEX scraper ${scraper.name} returned ${results.size} results")
            results.map { it.copy(provider = scraper.name) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute DEX scraper ${scraper.name}: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Test a scraper with sample data, returning results along with diagnostic steps.
     */
    suspend fun testScraper(scraperId: String): Result<Pair<List<LocalScraperResult>, TestDiagnostics>> {
        val diagnostics = TestDiagnostics()
        val scraper = dataStore.scrapers.first().find { it.id == scraperId }
        if (scraper == null) {
            diagnostics.addStep("Scraper '$scraperId' not found in datastore")
            return Result.failure(Exception("Scraper not found"))
        }

        diagnostics.addStep("Scraper: ${scraper.name} (type=${scraper.type})")

        // Use a popular movie for testing (The Matrix - 603)
        val testTmdbId = "603"
        val testMediaType = if (scraper.supportsType("movie")) "movie" else "series"
        diagnostics.addStep("Test: TMDB $testTmdbId ($testMediaType)")

        // Preload extractors only from enabled, manifest-active .cs3 files.
        if (scraper.type == RepositoryType.EXTERNAL_DEX) {
            val allDexIds = dataStore.scrapers.first()
                .filter {
                    it.type == RepositoryType.EXTERNAL_DEX && it.enabled && it.manifestEnabled
                }
                .map { it.id }
            withContext(Dispatchers.IO) {
                externalExtensionLoader.ensureExtractorsLoaded(allDexIds, diagnostics)
            }
        }

        val testSeason = if (testMediaType == "movie") null else 1
        val testEpisode = if (testMediaType == "movie") null else 1

        return try {
            val results = when (scraper.type) {
                RepositoryType.EXTERNAL_DEX -> {
                    externalExtensionRunner.executeWithDiagnostics(
                        scraper.id, testTmdbId, testMediaType, testSeason, testEpisode, diagnostics
                    )
                }
                RepositoryType.FOXTV_JS -> {
                    diagnostics.addStep("Executing JS scraper...")
                    executeScraper(scraper, testTmdbId, testMediaType, testSeason, testEpisode)
                }
            }
            diagnostics.addStep("Result: ${results.size} streams")
            Result.success(results to diagnostics)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics.addStep("Exception: ${e.javaClass.simpleName}: ${e.message}")
            Result.success(emptyList<LocalScraperResult>() to diagnostics)
        }
    }
    
    private suspend fun fetchManifest(url: String): PluginManifest? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "FOX.TV/1.0")
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to fetch manifest: ${response.code}")
                    return@withContext null
                }

                val body = response.body.string()
                manifestAdapter.fromJson(body)
            }
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching manifest: ${e.message}", e)
            null
        }
    }
    
    private suspend fun downloadJsScrapers(
        repoId: String,
        manifestUrl: String,
        scraperInfos: List<ScraperManifestInfo>
    ) = withContext(Dispatchers.IO) {
        val baseUrl = manifestUrl.substringBeforeLast("/")
        val existingScrapers = dataStore.scrapers.first().toMutableList()
        
        scraperInfos.forEach { info ->
            try {
                val codeUrl = if (info.filename.startsWith("http")) {
                    info.filename
                } else {
                    "$baseUrl/${info.filename}"
                }
                
                // Check response size before downloading
                val headRequest = Request.Builder()
                    .url(codeUrl)
                    .head()
                    .build()
                
                val contentLength = httpClient.newCall(headRequest).execute().use { headResponse ->
                    headResponse.header("Content-Length")?.toLongOrNull() ?: 0
                }
                
                if (contentLength > MAX_RESPONSE_SIZE) {
                    Log.w(TAG, "Scraper ${info.name} too large: $contentLength bytes")
                    return@forEach
                }
                
                // Download code
                val codeRequest = Request.Builder()
                    .url(codeUrl)
                    .header("User-Agent", "FOX.TV/1.0")
                    .build()
                
                val code = httpClient.newCall(codeRequest).execute().use { codeResponse ->
                    if (!codeResponse.isSuccessful) {
                        Log.e(TAG, "Failed to download scraper ${info.name}: ${codeResponse.code}")
                        return@forEach
                    }

                    codeResponse.body.string()
                }

                try {
                    val sha = sha256Hex(code)
                    val hasHrefliLogs = code.contains("[UHDMovies][Hrefli]", ignoreCase = false) ||
                        code.contains("[Hrefli]", ignoreCase = false)
                    Log.d(
                        TAG,
                        "Downloaded scraper code: ${info.name}(${info.id}) bytes=${code.toByteArray(Charsets.UTF_8).size} sha256=${sha.take(12)} hrefliLogs=$hasHrefliLogs url=$codeUrl"
                    )
                } catch (_: Exception) {
                    // ignore
                }
                
                // Create scraper info
                val scraperId = "$repoId:${info.id}"
                val existingScraper = existingScrapers.firstOrNull { it.id == scraperId }
                val defaultEnabled = info.enabled &&
                    !PluginSafety.isVideoEasyScraper(
                        scraperId = info.id,
                        scraperName = info.name,
                        filename = info.filename
                    )
                val scraper = ScraperInfo(
                    id = scraperId,
                    repositoryId = repoId,
                    name = info.name,
                    description = info.description ?: "",
                    version = info.version,
                    filename = info.filename,
                    supportedTypes = info.supportedTypes,
                    enabled = existingScraper?.enabled ?: defaultEnabled,
                    manifestEnabled = info.enabled,
                    logo = info.logo,
                    contentLanguage = info.contentLanguage ?: emptyList(),
                    formats = info.formats
                )
                
                // Save code
                dataStore.saveScraperCode(scraperId, code)
                
                // Update scraper list
                existingScrapers.removeAll { it.id == scraperId }
                existingScrapers.add(scraper)
                
                Log.d(TAG, "Downloaded scraper: ${info.name}")
                
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading scraper ${info.name}: ${e.message}", e)
            }
        }
        
        dataStore.saveScrapers(existingScrapers)
    }

}

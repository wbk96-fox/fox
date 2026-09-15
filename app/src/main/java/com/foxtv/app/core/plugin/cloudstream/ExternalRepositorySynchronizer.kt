package com.foxtv.app.core.plugin.cloudstream

import android.util.Log
import com.foxtv.app.data.local.PluginDataStore
import com.foxtv.app.domain.model.ExternalPluginEntry
import com.foxtv.app.domain.model.PluginRepository
import com.foxtv.app.domain.model.RepositoryType
import com.foxtv.app.domain.model.ScraperInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExtRepoSynchronizer"
private const val MAX_PARALLEL_DOWNLOADS = 10

internal data class ExternalRepositorySyncResult(
    val installed: List<ScraperInfo>,
    val failedPluginNames: List<String>,
    val removedStaleCount: Int,
)

private data class StagingOutcome(
    val artifacts: List<ExternalExtensionLoader.PreparedExternalExtension>,
    val failedPluginNames: List<String>,
)

private data class GenerationPlan(
    val installed: List<ScraperInfo>,
    val mergedScrapers: List<ScraperInfo>,
    val staleScrapers: List<ScraperInfo>,
)

/**
 * Builds and commits one complete external repository generation. The caller must hold
 * [ExternalPluginLifecycleCoordinator.serialized], which also orders refresh against disable and
 * removal. Splitting staging, planning, persistence, and finalization keeps each compiler state
 * machine small and makes the transaction boundary explicit.
 */
@Singleton
class ExternalRepositorySynchronizer @Inject constructor(
    private val dataStore: PluginDataStore,
    private val extensionLoader: ExternalExtensionLoader,
    private val lifecycleCoordinator: ExternalPluginLifecycleCoordinator,
) {
    internal suspend fun synchronizeLocked(
        repository: PluginRepository,
        parseResult: ExternalRepoParseResult,
        requireExistingRepository: Boolean,
    ): ExternalRepositorySyncResult = withContext(Dispatchers.IO) {
        check(parseResult.isAuthoritativeForRemoval) {
            "Non-authoritative external metadata cannot be committed"
        }

        val existingScrapers = dataStore.scrapers.first()
        val staging = stageAll(repository.id, parseResult.plugins)
        try {
            if (staging.failedPluginNames.isNotEmpty() ||
                staging.artifacts.size != parseResult.plugins.size
            ) {
                Log.e(
                    TAG,
                    "Generation ${repository.id} rejected before activation: " +
                        "staged=${staging.artifacts.size}/${parseResult.plugins.size}, " +
                        "failed=${staging.failedPluginNames.joinToString()}",
                )
                return@withContext ExternalRepositorySyncResult(
                    installed = emptyList(),
                    failedPluginNames = staging.failedPluginNames,
                    removedStaleCount = 0,
                )
            }

            val plan = buildPlan(
                repository = repository,
                plugins = parseResult.plugins,
                existingScrapers = existingScrapers,
                stagedArtifacts = staging.artifacts,
            )
            val globallyEnabled = dataStore.pluginsEnabled.first()
            commitArtifactsAndMetadata(
                repository = repository,
                plan = plan,
                stagedArtifacts = staging.artifacts,
                requireExistingRepository = requireExistingRepository,
            )
            withContext(NonCancellable) {
                finalizeCommittedGeneration(plan, globallyEnabled)
            }

            Log.d(
                TAG,
                "Generation ${repository.id} committed: installed=${plan.installed.size}, " +
                    "staleRemoved=${plan.staleScrapers.size}, " +
                    "bytes=${staging.artifacts.sumOf { it.byteCount }}",
            )
            ExternalRepositorySyncResult(
                installed = plan.installed,
                failedPluginNames = emptyList(),
                removedStaleCount = plan.staleScrapers.size,
            )
        } finally {
            closeStagedArtifacts(staging.artifacts)
        }
    }

    private suspend fun stageAll(
        repositoryId: String,
        plugins: List<ExternalPluginEntry>,
    ): StagingOutcome {
        val semaphore = Semaphore(MAX_PARALLEL_DOWNLOADS)
        val staged = java.util.Collections.synchronizedList(
            mutableListOf<ExternalExtensionLoader.PreparedExternalExtension>(),
        )
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())

        try {
            coroutineScope {
                plugins.map { plugin ->
                    async {
                        semaphore.withPermit {
                            val scraperId = "$repositoryId:${plugin.internalName}"
                            val artifact = stageOne(scraperId, plugin)
                            if (artifact == null) failures += plugin.name else staged += artifact
                        }
                    }
                }.awaitAll()
            }
        } catch (failure: Throwable) {
            closeStagedArtifacts(staged.toList())
            throw failure
        }
        return StagingOutcome(
            artifacts = staged.toList(),
            failedPluginNames = failures.toList(),
        )
    }

    private suspend fun stageOne(
        scraperId: String,
        plugin: ExternalPluginEntry,
    ): ExternalExtensionLoader.PreparedExternalExtension? {
        return try {
            extensionLoader.stageExtension(scraperId, plugin)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error staging extension ${plugin.name}: ${e.message}", e)
            null
        } catch (e: LinkageError) {
            Log.e(TAG, "Incompatible extension ${plugin.name}: ${e.message}", e)
            null
        }
    }

    private fun buildPlan(
        repository: PluginRepository,
        plugins: List<ExternalPluginEntry>,
        existingScrapers: List<ScraperInfo>,
        stagedArtifacts: List<ExternalExtensionLoader.PreparedExternalExtension>,
    ): GenerationPlan {
        val existingById = existingScrapers.associateBy(ScraperInfo::id)
        val stagedById = stagedArtifacts.associateBy { it.scraperId }
        val installed = plugins.map { plugin ->
            val scraperId = "${repository.id}:${plugin.internalName}"
            checkNotNull(stagedById[scraperId]) {
                "Validated artifact disappeared before generation commit"
            }
            plugin.toScraperInfo(
                repositoryId = repository.id,
                scraperId = scraperId,
                previouslyEnabled = existingById[scraperId]?.enabled,
            )
        }
        val desiredIds = installed.map(ScraperInfo::id).toSet()
        val stale = existingScrapers.filter {
            it.repositoryId == repository.id && it.id !in desiredIds
        }
        val merged = existingScrapers.filter { it.repositoryId != repository.id } + installed
        return GenerationPlan(
            installed = installed,
            mergedScrapers = merged,
            staleScrapers = stale,
        )
    }

    private suspend fun commitArtifactsAndMetadata(
        repository: PluginRepository,
        plan: GenerationPlan,
        stagedArtifacts: List<ExternalExtensionLoader.PreparedExternalExtension>,
        requireExistingRepository: Boolean,
    ) {
        lifecycleCoordinator.activateAndCommit(
            stagedArtifacts = stagedArtifacts.map { it.stagedArtifact },
        ) {
            val committed = dataStore.commitExternalRepositoryState(
                repository = repository,
                scrapers = plan.mergedScrapers,
                requireExistingRepository = requireExistingRepository,
            )
            check(committed) { "External repository state is read-only for the active profile" }
        }
    }

    private fun finalizeCommittedGeneration(plan: GenerationPlan, globallyEnabled: Boolean) {
        plan.installed.forEach { scraper ->
            extensionLoader.evictCache(scraper.id)
            extensionLoader.setOwnersEnabled(
                ownerIds = listOf(scraper.id),
                enabled = scraper.enabled && scraper.manifestEnabled,
            )
        }
        plan.staleScrapers.forEach { extensionLoader.deleteExtension(it.id) }
        extensionLoader.setGloballyEnabled(
            enabled = globallyEnabled,
            installedOwnerIds = plan.mergedScrapers
                .filter { it.type == RepositoryType.EXTERNAL_DEX }
                .map(ScraperInfo::id),
        )
    }

    private suspend fun closeStagedArtifacts(
        artifacts: List<ExternalExtensionLoader.PreparedExternalExtension>,
    ) = withContext(NonCancellable) {
        artifacts.forEach { prepared ->
            try {
                prepared.stagedArtifact.close()
            } catch (_: Throwable) {
                // Closing only removes an unactivated private staging file.
            }
        }
    }

    private fun ExternalPluginEntry.toScraperInfo(
        repositoryId: String,
        scraperId: String,
        previouslyEnabled: Boolean?,
    ): ScraperInfo {
        val supportedTypes = tvTypes
            ?.mapNotNull(::tvTypeFromString)
            ?.map { it.toFoxTvType() }
            ?.distinct()
            ?.ifEmpty { listOf("movie", "tv") }
            ?: listOf("movie", "tv")
        return ScraperInfo(
            id = scraperId,
            repositoryId = repositoryId,
            name = name,
            description = description ?: "",
            version = version.toString(),
            filename = url,
            supportedTypes = supportedTypes,
            enabled = previouslyEnabled ?: true,
            manifestEnabled = true,
            logo = iconUrl,
            contentLanguage = listOfNotNull(language),
            formats = null,
            type = RepositoryType.EXTERNAL_DEX,
        )
    }
}

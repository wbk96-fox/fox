package com.foxtv.app.data.repository

import android.content.Context
import android.util.Log
import com.foxtv.app.R
import com.foxtv.app.core.network.NetworkResult
import com.foxtv.app.core.network.safeApiCall
import com.foxtv.app.core.debrid.DebridStreamPresentation
import com.foxtv.app.core.debrid.LocalDebridAvailabilityService
import com.foxtv.app.core.plugin.PluginManager
import com.foxtv.app.core.plugin.resolvePluginSeasonEpisode
import com.foxtv.app.core.profile.ProfileManager
import com.foxtv.app.core.tmdb.TmdbService
import com.foxtv.app.core.torrent.TorrentSettings
import com.foxtv.app.core.scraper.PolishStreamRanking
import com.foxtv.app.data.local.DebridSettingsDataStore
import com.foxtv.app.data.mapper.toDomain
import com.foxtv.app.data.remote.api.AddonApi
import com.foxtv.app.domain.model.Addon
import com.foxtv.app.core.fanfilm.FanFilmError
import com.foxtv.app.core.fanfilm.FanFilmMediaRequest
import com.foxtv.app.core.fanfilm.FanFilmProvider
import com.foxtv.app.core.fanfilm.FanFilmRuntime
import com.foxtv.app.core.fanfilm.FanFilmStartupException
import com.foxtv.app.core.fanfilm.FanFilmStreamMapper
import com.foxtv.app.domain.model.AddonStreams
import com.foxtv.app.domain.model.DebridSettings
import com.foxtv.app.domain.model.LocalScraperResult
import com.foxtv.app.domain.model.PluginRepository
import com.foxtv.app.domain.model.ProxyHeaders
import com.foxtv.app.domain.model.ScraperInfo
import com.foxtv.app.domain.model.Stream
import com.foxtv.app.domain.model.StreamBehaviorHints
import com.foxtv.app.domain.model.enabledAddons
import com.foxtv.app.domain.repository.AddonRepository
import com.foxtv.app.domain.repository.StreamRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.security.MessageDigest
import javax.inject.Inject

private const val TAG = "StreamRepositoryImpl"

class StreamRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addonRepository: AddonRepository,
    private val pluginManager: PluginManager,
    private val profileManager: ProfileManager,
    private val debridSettingsDataStore: DebridSettingsDataStore,
    private val tmdbService: TmdbService,
    private val debridStreamPresentation: DebridStreamPresentation,
    private val localDebridAvailabilityService: LocalDebridAvailabilityService,
    private val foxTvHttpScraperManager: com.foxtv.app.core.scraper.FoxTvHttpScraperManager,
    private val foxTvP2PScraperManager: com.foxtv.app.core.scraper.p2p.FoxTvP2PScraperManager,
    private val torrentSettings: TorrentSettings,
    private val fanFilmRuntime: FanFilmRuntime,
    private val fanFilmProvider: FanFilmProvider,
    private val fanFilmStreamMapper: FanFilmStreamMapper
) : StreamRepository {
    private val streamSearchSessions = StreamSearchSessionCache()
    private val localPluginSearchPaused = MutableStateFlow(false)

    /**
     * Build FanFilm's media request from the app's Stremio-shaped identifiers.
     *
     * FanFilm identifies titles by TMDB id (preferred) or IMDb id, and needs an explicit
     * season/episode for series. `videoId` here is either `ttNNNNNNN`,
     * `ttNNNNNNN:season:episode`, or a TMDB/Kitsu id, so both forms are handled and a
     * request that cannot be identified returns null rather than guessing.
     */
    private suspend fun fanFilmSourceRequest(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?
    ): FanFilmMediaRequest? {
        val isSeries = !type.equals("movie", ignoreCase = true)
        val imdbId = videoId.substringBefore(':').takeIf { it.startsWith("tt") }
        val tmdbId = tmdbService.ensureTmdbId(videoId, type)?.toIntOrNull()
        if (imdbId == null && tmdbId == null) return null

        val (resolvedSeason, resolvedEpisode) = if (isSeries) {
            resolvePluginSeasonEpisode(videoId = videoId, season = season, episode = episode)
        } else {
            null to null
        }
        if (isSeries && (resolvedSeason == null || resolvedEpisode == null)) {
            // FanFilm scrapes a specific episode; without one there is nothing to search
            // for, and asking for the whole show would return unrelated sources.
            return null
        }

        return FanFilmMediaRequest(
            mediaType = if (isSeries) {
                FanFilmMediaRequest.MediaType.SERIES
            } else {
                FanFilmMediaRequest.MediaType.MOVIE
            },
            tmdbId = tmdbId,
            imdbId = imdbId,
            season = resolvedSeason,
            episode = resolvedEpisode
        )
    }

    override fun setLocalPluginSearchPaused(paused: Boolean) {
        localPluginSearchPaused.value = paused
    }

    private enum class StreamFailureKind {
        MISSING,
        REQUEST_FAILED
    }

    private data class StreamAttemptFailure(
        val addonName: String,
        val kind: StreamFailureKind,
        val detail: String
    )

    private data class StreamSourceConfigurationSnapshot(
        val profileId: Int,
        val addons: List<Addon>,
        val pluginsEnabled: Boolean,
        val enabledScrapers: List<ScraperInfo>,
        val groupPluginsByRepository: Boolean,
        val pluginRepositories: List<PluginRepository>,
        val debridSettings: DebridSettings,
        val p2pEnabled: Boolean
    )

    override fun getStreamsFromAllAddons(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean,
        contentTitle: String?,
        contentYear: Int?
    ): Flow<NetworkResult<List<AddonStreams>>> = flow {
        val sourceConfiguration = captureSourceConfiguration()
        val requestKey = StreamSearchRequestKey(
            profileId = sourceConfiguration.profileId,
            type = type.lowercase(),
            videoId = videoId,
            season = season,
            episode = episode,
            sourceConfiguration = buildSourceConfigurationKey(
                addons = sourceConfiguration.addons,
                pluginsEnabled = sourceConfiguration.pluginsEnabled,
                enabledScrapers = sourceConfiguration.enabledScrapers,
                groupPluginsByRepository = sourceConfiguration.groupPluginsByRepository,
                pluginRepositories = sourceConfiguration.pluginRepositories,
                debridPresentationConfiguration = sourceConfiguration.debridSettings
                    .withoutRawCredentials()
                    .toString(),
                p2pEnabled = sourceConfiguration.p2pEnabled
            )
        )

        emitAll(
            streamSearchSessions.observe(
                key = requestKey,
                forceRefresh = forceRefresh
            ) {
                fetchStreamsFromAllSources(
                    type = type,
                    videoId = videoId,
                    season = season,
                    episode = episode,
                    addons = sourceConfiguration.addons,
                    debridSettings = sourceConfiguration.debridSettings,
                    hasCompatiblePlugins = sourceConfiguration.pluginsEnabled &&
                        sourceConfiguration.enabledScrapers.any { scraper -> scraper.supportsType(type) },
                    p2pEnabled = sourceConfiguration.p2pEnabled,
                    contentTitle = contentTitle,
                    contentYear = contentYear
                )
            }
        )
    }

    private suspend fun captureSourceConfiguration(): StreamSourceConfigurationSnapshot {
        while (true) {
            val profileId = profileManager.activeProfileId.value
            val addons = addonRepository.getInstalledAddons().first().enabledAddons()
            val pluginsEnabled = pluginManager.pluginsEnabled.first()
            val enabledScrapers = if (pluginsEnabled) pluginManager.enabledScrapers.first() else emptyList()
            val groupPluginsByRepository = pluginsEnabled && pluginManager.groupStreamsByRepository.first()
            val pluginRepositories = if (groupPluginsByRepository) pluginManager.repositories.first() else emptyList()
            val debridSettings = debridSettingsDataStore.settings.first()
            val p2pEnabled = torrentSettings.settings.first().p2pEnabled

            if (profileManager.activeProfileId.value != profileId) continue

            return StreamSourceConfigurationSnapshot(
                profileId = profileId,
                addons = addons,
                pluginsEnabled = pluginsEnabled,
                enabledScrapers = enabledScrapers,
                groupPluginsByRepository = groupPluginsByRepository,
                pluginRepositories = pluginRepositories,
                debridSettings = debridSettings,
                p2pEnabled = p2pEnabled
            )
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun fetchStreamsFromAllSources(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        addons: List<Addon>,
        debridSettings: DebridSettings,
        hasCompatiblePlugins: Boolean,
        p2pEnabled: Boolean,
        contentTitle: String? = null,
        contentYear: Int? = null
    ): Flow<NetworkResult<List<AddonStreams>>> = flow {
        emit(NetworkResult.Loading)

        try {
            // Filter addons that support streams for this type and id
            val streamAddons = addons.filter { addon ->
                addon.supportsStreamResource(type, videoId)
            }

            val attemptedAddonNames = streamAddons.map { it.displayName }
            val attemptedFailures = java.util.Collections.synchronizedList(
                mutableListOf<StreamAttemptFailure>()
            )

            // Accumulate results as they arrive
            val accumulatedResults = mutableListOf<AddonStreams>()

            coroutineScope {
                // Channel to receive results as they complete
                val resultChannel = Channel<AddonStreams>(Channel.UNLIMITED)
                
                // Track number of pending jobs
                // (addons + local plugins + FoxTvHTTP + FanFilm + FoxTv P2P if enabled)
                val totalJobs = streamAddons.size + 3 + (if (p2pEnabled) 1 else 0)
                val completedJobs = java.util.concurrent.atomic.AtomicInteger(0)

                // Launch addon jobs
                streamAddons.forEach { addon ->
                    launch {
                        try {
                            val streamsResult = getStreamsFromAddon(addon, type, videoId)
                            when (streamsResult) {
                                is NetworkResult.Success -> {
                                    if (streamsResult.data.isNotEmpty()) {
                                        val namedStreams = streamsResult.data.map {
                                            it.copy(addonName = addon.displayName, addonLogo = addon.logo)
                                        }
                                        resultChannel.send(
                                            AddonStreams(
                                                addonName = addon.displayName,
                                                addonLogo = addon.logo,
                                                streams = namedStreams
                                            )
                                        )
                                    } else {
                                        // Stream endpoint returned empty - try inline
                                        // streams from meta response as fallback.
                                        val inlineStreams = fetchInlineStreamsFromMeta(
                                            addon, type, videoId
                                        )
                                        if (inlineStreams.isNotEmpty()) {
                                            resultChannel.send(
                                                AddonStreams(
                                                    addonName = addon.displayName,
                                                    addonLogo = addon.logo,
                                                    streams = inlineStreams
                                                )
                                            )
                                        } else {
                                            attemptedFailures += buildMissingStreamFailure(addon)
                                        }
                                    }
                                }
                                is NetworkResult.Error -> {
                                    attemptedFailures += buildAddonFailure(addon, streamsResult)
                                }
                                NetworkResult.Loading -> Unit
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e(TAG, "Addon ${addon.name} failed: ${e.message}")
                            attemptedFailures += StreamAttemptFailure(
                                addonName = addon.displayName,
                                kind = StreamFailureKind.REQUEST_FAILED,
                                detail = e.message ?: context.getString(com.foxtv.app.R.string.stream_error_detail_addon_request_failed)
                            )
                        } finally {
                            if (completedJobs.incrementAndGet() >= totalJobs) {
                                resultChannel.close()
                            }
                        }
                    }
                }

                // Launch FoxTvHTTP built-in scraper job
                launch {
                    try {
                        val tmdbIdStr = tmdbService.ensureTmdbId(videoId, type)
                        val tmdbIdInt = tmdbIdStr?.toIntOrNull()
                        val imdbId = if (videoId.startsWith("tt")) videoId.substringBefore(":") else null
                        val (pluginSeason, pluginEpisode) = resolvePluginSeasonEpisode(
                            videoId = videoId,
                            season = season,
                            episode = episode
                        )

                        var mediaTitle = contentTitle?.takeIf { it.isNotBlank() && !it.startsWith("tt") } ?: videoId
                        var mediaYear: Int? = contentYear

                        if ((mediaTitle == videoId || mediaTitle.startsWith("tt")) && tmdbIdInt != null) {
                            tmdbService.getMediaDetails(tmdbIdInt, type)?.let { (resolvedTitle, resolvedYear) ->
                                if (resolvedTitle.isNotBlank()) {
                                    mediaTitle = resolvedTitle
                                    mediaYear = resolvedYear
                                }
                            }
                        }

                        foxTvHttpScraperManager.scrapeStreamsDynamic(
                            type = type,
                            title = mediaTitle,
                            year = mediaYear,
                            season = pluginSeason,
                            episode = pluginEpisode,
                            imdbId = imdbId,
                            tmdbId = tmdbIdInt
                        ) { streamsBatch ->
                            if (streamsBatch.isNotEmpty()) {
                                resultChannel.send(
                                    AddonStreams(
                                        addonName = com.foxtv.app.core.scraper.FoxTvHttpScraperManager.ADDON_NAME,
                                        addonLogo = null,
                                        streams = streamsBatch
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "FoxTvHTTP scraping failed: ${e.message}")
                    } finally {
                        if (completedJobs.incrementAndGet() >= totalJobs) {
                            resultChannel.close()
                        }
                    }
                }

                // Launch FoxTv (P2P torrent scraper) built-in scraper job only if P2P streaming is enabled
                if (p2pEnabled) {
                    launch {
                        try {
                            val tmdbIdStr = tmdbService.ensureTmdbId(videoId, type)
                            val tmdbIdInt = tmdbIdStr?.toIntOrNull()
                            val imdbId = if (videoId.startsWith("tt")) videoId.substringBefore(":") else null
                            val (pluginSeason, pluginEpisode) = resolvePluginSeasonEpisode(
                                videoId = videoId,
                                season = season,
                                episode = episode
                            )

                            var mediaTitle = contentTitle?.takeIf { it.isNotBlank() && !it.startsWith("tt") } ?: videoId
                            var mediaYear: Int? = contentYear

                            if ((mediaTitle == videoId || mediaTitle.startsWith("tt")) && tmdbIdInt != null) {
                                tmdbService.getMediaDetails(tmdbIdInt, type)?.let { (resolvedTitle, resolvedYear) ->
                                    if (resolvedTitle.isNotBlank()) {
                                        mediaTitle = resolvedTitle
                                        mediaYear = resolvedYear
                                    }
                                }
                            }

                            foxTvP2PScraperManager.scrapeStreamsDynamic(
                                type = type,
                                title = mediaTitle,
                                year = mediaYear,
                                season = pluginSeason,
                                episode = pluginEpisode,
                                imdbId = imdbId,
                                tmdbId = tmdbIdInt
                            ) { streamsBatch ->
                                if (streamsBatch.isNotEmpty()) {
                                    resultChannel.send(
                                        AddonStreams(
                                            addonName = com.foxtv.app.core.scraper.p2p.FoxTvP2PScraperManager.ADDON_NAME,
                                            addonLogo = null,
                                            streams = streamsBatch
                                        )
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e(TAG, "FoxTv P2P scraping failed: ${e.message}")
                        } finally {
                            if (completedJobs.incrementAndGet() >= totalJobs) {
                                resultChannel.close()
                            }
                        }
                    }
                }

                launch {
                    try {
                        if (!hasCompatiblePlugins) return@launch

                        val tmdbId = tmdbService.ensureTmdbId(videoId, type)
                        Log.d(TAG, "Video ID: $videoId -> TMDB ID: $tmdbId (type: $type)")
                        val pluginRequest = buildPluginRequest(tmdbId, type, videoId)
                            ?: return@launch
                        val (pluginSeason, pluginEpisode) = resolvePluginSeasonEpisode(
                            videoId = videoId,
                            season = season,
                            episode = episode
                        )
                        localPluginSearchPaused
                            .transformLatest { paused ->
                                if (!paused) {
                                    streamLocalPlugins(
                                        pluginId = pluginRequest.id,
                                        mediaType = pluginRequest.mediaType,
                                        pluginSource = pluginRequest.source,
                                        season = pluginSeason,
                                        episode = pluginEpisode,
                                        resultChannel = resultChannel
                                    )
                                    emit(Unit)
                                }
                            }
                            .first()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Plugin execution failed: ${e.message}")
                    } finally {
                        if (completedJobs.incrementAndGet() >= totalJobs) {
                            resultChannel.close()
                        }
                    }
                }

                // FanFilm is a first-class provider: its sources appear in the same
                // Source Picker as everything else, not in a plugin-owned dialog
                // (AGENTS.md §35/§39). Discovery runs concurrently with the other jobs;
                // resolution happens later, when the user picks a row, because it means
                // running ResolveURL and can prompt.
                launch {
                    val runId = fanFilmRuntime.newRunId()
                    try {
                        val request = fanFilmSourceRequest(
                            type = type,
                            videoId = videoId,
                            season = season,
                            episode = episode,
                        ) ?: return@launch

                        fanFilmProvider.discover(request, runId).fold(
                            onSuccess = { listing ->
                                val ranked = fanFilmProvider.rank(listing.playable)
                                val streams = fanFilmStreamMapper.toStreams(listing, ranked)
                                if (streams.isNotEmpty()) {
                                    resultChannel.send(
                                        AddonStreams(
                                            addonName = FanFilmProvider.PROVIDER_NAME,
                                            addonLogo = null,
                                            streams = streams,
                                        )
                                    )
                                }
                            },
                            onFailure = { throwable ->
                                val error = (throwable as? FanFilmStartupException)?.error
                                // "No sources" is a normal outcome for a provider, not a
                                // failure worth surfacing as an addon error row.
                                if (error is FanFilmError.NoSources) {
                                    Log.d(TAG, "FanFilm has no sources for $videoId")
                                } else {
                                    Log.w(
                                        TAG,
                                        "FanFilm discovery failed: ${error?.technicalDetail ?: throwable.message}",
                                    )
                                    attemptedFailures += StreamAttemptFailure(
                                        addonName = FanFilmProvider.PROVIDER_NAME,
                                        kind = StreamFailureKind.REQUEST_FAILED,
                                        detail = error?.technicalDetail
                                            ?: throwable.message
                                            ?: context.getString(com.foxtv.app.R.string.stream_error_detail_addon_request_failed),
                                    )
                                }
                            },
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) {
                            // Propagate, but stop the provider scan on the Python side
                            // too: cancelling the flow must cancel real work.
                            fanFilmRuntime.cancelRun(runId)
                            throw e
                        }
                        Log.e(TAG, "FanFilm job failed: ${e.message}")
                    } finally {
                        if (completedJobs.incrementAndGet() >= totalJobs) {
                            resultChannel.close()
                        }
                    }
                }

                // Emit results as they arrive
                for (result in resultChannel) {
                    val checkingResult = localDebridAvailabilityService.markChecking(listOf(result)).firstOrNull() ?: result
                    val checkedResult = localDebridAvailabilityService.annotateCachedAvailability(listOf(checkingResult)).firstOrNull() ?: checkingResult
                    mergePresentedResult(accumulatedResults, checkedResult, debridSettings)
                    emit(NetworkResult.Success(accumulatedResults.toList()))
                    Log.d(TAG, "Emitted ${accumulatedResults.size} addon(s), latest: ${checkedResult.addonName} with ${checkedResult.streams.size} streams")
                }
            }

            // Emit final result (even if empty)
            if (accumulatedResults.isEmpty()) {
                val errorMessage = buildAggregateFailureMessage(
                    type = type,
                    id = videoId,
                    attemptedAddonNames = attemptedAddonNames,
                    failures = attemptedFailures.toList()
                )
                if (errorMessage != null) {
                    emit(NetworkResult.Error(errorMessage))
                } else {
                    emit(NetworkResult.Success(emptyList()))
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to fetch streams: ${e.message}", e)
            emit(NetworkResult.Error(e.message ?: context.getString(com.foxtv.app.R.string.stream_error_fetch_failed)))
        }
    }

    private fun buildSourceConfigurationKey(
        addons: List<Addon>,
        pluginsEnabled: Boolean,
        enabledScrapers: List<ScraperInfo>,
        groupPluginsByRepository: Boolean,
        pluginRepositories: List<PluginRepository>,
        debridPresentationConfiguration: String,
        p2pEnabled: Boolean
    ): String = buildString {
        append("addons:")
        addons.forEach { addon ->
            append("|addon:").append(addon)
        }
        append("plugins:").append(pluginsEnabled)
        append("|grouped:").append(groupPluginsByRepository)
        enabledScrapers.forEach { scraper ->
            append("|scraper:").append(scraper)
        }
        if (groupPluginsByRepository) {
            pluginRepositories.forEach { repository ->
                append("|repo:").append(repository)
            }
        }
        append("|debrid:").append(debridPresentationConfiguration)
        append("|p2p:").append(p2pEnabled)
    }.sha256()

    private fun DebridSettings.withoutRawCredentials(): DebridSettings = copy(
        torboxApiKey = torboxApiKey.sha256(),
        premiumizeApiKey = premiumizeApiKey.sha256(),
        realDebridApiKey = realDebridApiKey.sha256()
    )

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class PluginRequest(
        val id: String,
        val mediaType: String,
        val source: String
    )

    private fun buildPluginRequest(tmdbId: String?, type: String, videoId: String): PluginRequest? {
        if (tmdbId != null) {
            return PluginRequest(
                id = tmdbId,
                mediaType = normalizeTmdbPluginType(type),
                source = "TMDB"
            )
        }

        if (!videoId.canRunLocalPlugins()) return null

        return PluginRequest(
            id = if (videoId.startsWith("kitsu:", ignoreCase = true)) {
                cleanKitsuPluginId(videoId)
            } else {
                videoId
            },
            mediaType = type.lowercase(),
            source = videoId.substringBefore(":").uppercase()
        )
    }

    private fun normalizeTmdbPluginType(type: String): String {
        return when (type.lowercase()) {
            "series", "tv", "show" -> "tv"
            else -> type.lowercase()
        }
    }

    private fun cleanKitsuPluginId(videoId: String): String {
        val parts = videoId.split(":")
        return if (parts.size > 2 && parts.last().toIntOrNull() != null) {
            parts.dropLast(1).joinToString(":")
        } else {
            videoId
        }
    }

    private suspend fun mergePresentedResult(
        accumulatedResults: MutableList<AddonStreams>,
        result: AddonStreams,
        debridSettings: DebridSettings
    ) {
        val existingIndex = accumulatedResults.indexOfFirst { it.addonName == result.addonName }
        if (existingIndex >= 0) {
            val existing = accumulatedResults[existingIndex]
            val merged = existing.copy(
                streams = mergeStreams(existing.streams, result.streams)
            )
            accumulatedResults[existingIndex] = presentStreams(merged, debridSettings)
        } else {
            accumulatedResults.add(presentStreams(result, debridSettings))
        }
    }

    private fun presentStreams(result: AddonStreams, debridSettings: DebridSettings): AddonStreams {
        val presented = debridStreamPresentation.apply(
            groups = listOf(result),
            settings = debridSettings,
            includeBadgeMatches = false
        ).firstOrNull() ?: result
        return presented.copy(
            streams = presented.streams.sortedWith(PolishStreamRanking.comparator())
        )
    }

    private fun mergeStreams(existing: List<Stream>, incoming: List<Stream>): List<Stream> {
        val streamsByKey = LinkedHashMap<String, Stream>()
        existing.forEach { stream -> streamsByKey[stream.dedupKey()] = stream }
        incoming.forEach { stream -> streamsByKey[stream.dedupKey()] = stream }
        return streamsByKey.values.toList()
    }

    /**
     * Stream local plugin results - each scraper sends results individually
     */
    private fun String.canRunLocalPlugins(): Boolean {
        return startsWith("kitsu:", ignoreCase = true) ||
            startsWith("anilist:", ignoreCase = true) ||
            startsWith("mal:", ignoreCase = true)
    }

    private suspend fun streamLocalPlugins(
        pluginId: String,
        mediaType: String,
        pluginSource: String,
        season: Int?,
        episode: Int?,
        resultChannel: Channel<AddonStreams>
    ) {
        // Check if plugins are enabled
        if (!pluginManager.pluginsEnabled.first()) {
            Log.d(TAG, "Plugins are disabled")
            return
        }

        Log.d(TAG, "Streaming plugins for $pluginSource: $pluginId, type: $mediaType")

        try {
            val groupByRepository = pluginManager.groupStreamsByRepository.first()
            val repositoriesById = if (groupByRepository) {
                pluginManager.repositories.first().associateBy { it.id }
            } else {
                emptyMap()
            }

            // Collect streaming results from each scraper
            pluginManager.executeScrapersStreaming(
                tmdbId = pluginId,
                mediaType = mediaType,
                season = season,
                episode = episode
            ).collect { (scraper, results) ->
                if (results.isNotEmpty()) {
                    val addonName = scraper.pluginAddonName(groupByRepository, repositoriesById)
                    val addonStreams = AddonStreams(
                        addonName = addonName,
                        addonLogo = null,
                        streams = results.map { result -> result.toPluginStream(scraper, addonName) }
                    )
                    resultChannel.send(addonStreams)
                    Log.d(TAG, "Streamed ${results.size} results from ${scraper.name}")
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to stream plugins: ${e.message}", e)
        }
    }

    private fun ScraperInfo.pluginAddonName(
        groupByRepository: Boolean,
        repositoriesById: Map<String, PluginRepository>
    ): String {
        if (!groupByRepository) return name
        return repositoriesById[repositoryId]?.name?.takeIf { it.isNotBlank() } ?: name
    }

    private fun LocalScraperResult.toPluginStream(scraper: ScraperInfo, addonName: String): Stream {
        val baseTitle = title.takeIf { it.isNotBlank() }
        val baseName = name?.takeIf { it.isNotBlank() }
        val quality = quality?.takeIf { it.isNotBlank() }
        val qualityLabel = quality ?: context.getString(com.foxtv.app.R.string.stream_quality_unknown)
        val displayName = buildString {
            append(baseName ?: baseTitle ?: scraper.name)
            if (!toString().contains(qualityLabel)) {
                append(" - ").append(qualityLabel)
            }
        }.takeIf { it.isNotBlank() }
        val displayTitle = (baseTitle ?: baseName ?: scraper.name).takeIf { it.isNotBlank() }

        return Stream(
            name = displayName,
            title = displayTitle,
            url = url,
            addonName = addonName,
            addonLogo = null,
            description = buildDescription(this),
            behaviorHints = headers?.let { headers ->
                StreamBehaviorHints(
                    notWebReady = null,
                    bingeGroup = null,
                    countryWhitelist = null,
                    proxyHeaders = ProxyHeaders(request = headers, response = null)
                )
            },
            infoHash = infoHash,
            fileIdx = null,
            ytId = null,
            externalUrl = null,
            quality = quality,
            qualityValue = parseQualityValue(quality),
            subtitles = subtitles
        )
    }

    private fun Stream.dedupKey(): String =
        infoHash?.lowercase()?.let { hash -> "$hash:${fileIdx ?: ""}" }
            ?: clientResolve?.infoHash?.lowercase()?.let { hash -> "$hash:${clientResolve.fileIdx}" }
            ?: url
            ?: externalUrl
            ?: ytId
            ?: "${addonName}:${name}:${title}"

    /**
     * Build a description string from scraper result
     */
    private fun buildDescription(result: com.foxtv.app.domain.model.LocalScraperResult): String? {
        // Quality is shown in the stream name — only show size/language in description
        val parts = mutableListOf<String>()
        result.size?.let { parts.add(it) }
        result.language?.let { parts.add(it) }
        return if (parts.isNotEmpty()) parts.joinToString(" • ") else null
    }

    private fun parseQualityValue(quality: String?): Int {
        if (quality == null) return -1
        val lower = quality.lowercase()
        return when {
            lower.contains("4k") || lower.contains("2160") -> 2160
            lower.contains("1080") -> 1080
            lower.contains("800") -> 800
            lower.contains("720") -> 720
            lower.contains("480") -> 480
            lower.contains("360") -> 360
            else -> -1
        }
    }

    override suspend fun getStreamsFromAddon(
        addon: Addon,
        type: String,
        videoId: String
    ): NetworkResult<List<Stream>> {
        val cleanBaseUrl = addon.baseUrl.trimEnd('/')
        val queryStart = cleanBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) cleanBaseUrl.substring(0, queryStart).trimEnd('/') else cleanBaseUrl
        val baseQuery = if (queryStart >= 0) cleanBaseUrl.substring(queryStart) else ""
        val encodedType = encodePathSegment(type)
        val encodedVideoId = encodePathSegment(videoId)
        val streamUrl = "$basePath/stream/$encodedType/$encodedVideoId.json$baseQuery"
        Log.d(TAG, "Fetching streams type=$type videoId=$videoId url=$streamUrl")

        // Display info comes from the installed addon the caller already holds. Calling
        // addonRepository.fetchAddon() here caused an unconditional manifest GET ahead of every
        // queried addon's stream request: fetchAddon is the low-level fetch that does not consult
        // the cache, so this sidestepped the manifest-cache policy in AddonRepositoryImpl.
        val addonName = addon.displayName
        val addonLogo = addon.logo

        return when (val result = safeApiCall(context) { api.getStreams(streamUrl) }) {
            is NetworkResult.Success -> {
                val streams = result.data.streams?.map { 
                    it.toDomain(addonName, addonLogo) 
                } ?: emptyList()
                Log.d(TAG, "Streams success addon=$addonName count=${streams.size} url=$streamUrl")
                NetworkResult.Success(streams)
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Streams failed addon=$addonName code=${result.code} message=${result.message} url=$streamUrl"
                )
                result
            }
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    /**
     * Check if addon supports stream resource for the given type and video id.
     * Respects the resource-level idPrefixes declared in the addon manifest,
     * falling back to the top-level addon idPrefixes if the resource doesn't
     * declare its own.
     */
    private fun Addon.supportsStreamResource(type: String, videoId: String): Boolean {
        return resources.any { resource ->
            resource.name == "stream" &&
            (resource.types.isEmpty() || resource.types.contains(type)) &&
            run {
                val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() }
                    ?: idPrefixes.takeIf { it.isNotEmpty() }
                prefixes == null || prefixes.any { prefix -> videoId.startsWith(prefix) }
            }
        }
    }

    /**
     * Fetch meta for the given content and extract inline streams from the
     * matching video entry.  Returns an empty list when the addon doesn't
     * support meta or the video has no inline streams.
     */
    private suspend fun fetchInlineStreamsFromMeta(
        addon: Addon,
        type: String,
        videoId: String
    ): List<Stream> {
        // For inline streams the meta is fetched using the content-level ID
        // (everything before the video-specific suffix).  For "other" type
        // the videoId IS the content ID; for series it is contentId:S:E.
        // Video ID formats:
        //   tt1234567:1:5      → metaId = tt1234567
        //   mal:63375:1:5      → metaId = mal:63375
        //   kitsu:12345:2      → metaId = kitsu:12345
        // Strategy: drop up to 2 trailing numeric segments (season, episode)
        // but never reduce below 2 segments for prefixed IDs (mal:X, kitsu:X).
        val metaId = run {
            val parts = videoId.split(":")
            if (parts.size <= 1) return@run videoId
            // Count trailing numeric segments
            val trailingNumericCount = parts.reversed().takeWhile { it.toIntOrNull() != null }.size
            // Keep at least 2 segments for prefixed IDs (e.g. "mal:63375"),
            // or 1 segment for IMDB-style IDs (e.g. "tt1234567")
            val firstSegment = parts.first()
            val minSegments = if (firstSegment.startsWith("tt") || firstSegment.toIntOrNull() != null) 1 else 2
            val segmentsToDrop = trailingNumericCount.coerceAtMost((parts.size - minSegments).coerceAtLeast(0))
            if (segmentsToDrop > 0) {
                parts.dropLast(segmentsToDrop).joinToString(":")
            } else {
                videoId
            }
        }
        val cleanBaseUrl = addon.baseUrl.trimEnd('/')
        val queryStart = cleanBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) cleanBaseUrl.substring(0, queryStart).trimEnd('/') else cleanBaseUrl
        val baseQuery = if (queryStart >= 0) cleanBaseUrl.substring(queryStart) else ""
        val encodedType = encodePathSegment(type)
        val encodedMetaId = encodePathSegment(metaId)
        val metaUrl = "$basePath/meta/$encodedType/$encodedMetaId.json$baseQuery"
        Log.d(TAG, "Fetching inline streams via meta type=$type metaId=$metaId videoId=$videoId url=$metaUrl")
        return try {
            when (val result = safeApiCall(context) { api.getMeta(metaUrl) }) {
                is NetworkResult.Success -> {
                    val metaDto = result.data.meta ?: return emptyList()
                    val matchingVideo = metaDto.videos?.firstOrNull { it.id == videoId }
                    val streams = matchingVideo?.streams
                        ?.mapNotNull { it.toDomain(addon.displayName, addon.logo) }
                        ?: emptyList()
                    Log.d(TAG, "Inline streams from meta: addon=${addon.displayName} videoId=$videoId found=${streams.size}")
                    streams
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to fetch inline streams from meta for ${addon.displayName}: ${e.message}")
            emptyList()
        }
    }

    private fun buildMissingStreamFailure(addon: Addon): StreamAttemptFailure {
        return StreamAttemptFailure(
            addonName = addon.displayName,
            kind = StreamFailureKind.MISSING,
            detail = context.getString(com.foxtv.app.R.string.stream_error_detail_no_streams_for_id)
        )
    }

    private fun buildAddonFailure(addon: Addon, error: NetworkResult.Error): StreamAttemptFailure {
        if (error.code == 404 || error.message.equals("Not Found", ignoreCase = true)) {
            return buildMissingStreamFailure(addon)
        }
        val normalizedReason = when {
            error.message.contains("Unable to resolve host", ignoreCase = true) ->
                context.getString(com.foxtv.app.R.string.stream_error_detail_addon_unreachable)
            error.message.contains("Failed to connect", ignoreCase = true) ->
                context.getString(com.foxtv.app.R.string.stream_error_detail_addon_connection_failed)
            error.message.contains("timeout", ignoreCase = true) ->
                context.getString(com.foxtv.app.R.string.stream_error_detail_addon_timeout)
            error.message.contains("CLEARTEXT communication", ignoreCase = true) ->
                context.getString(com.foxtv.app.R.string.stream_error_detail_addon_cleartext_blocked)
            error.message.isBlank() ->
                context.getString(com.foxtv.app.R.string.stream_error_detail_addon_request_failed)
            else -> error.message.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }
        val httpSuffix = error.code?.let { " (HTTP $it)" } ?: ""
        return StreamAttemptFailure(
            addonName = addon.displayName,
            kind = StreamFailureKind.REQUEST_FAILED,
            detail = "$normalizedReason$httpSuffix"
        )
    }

    private fun buildAggregateFailureMessage(
        type: String,
        id: String,
        attemptedAddonNames: List<String>,
        failures: List<StreamAttemptFailure>
    ): String? {
        if (attemptedAddonNames.isEmpty()) {
            return context.getString(R.string.error_stream_no_supported_addon, type)
        }

        val triedAddons = attemptedAddonNames.joinToString(", ")
        val missingOnly = failures.isNotEmpty() && failures.all { it.kind == StreamFailureKind.MISSING }
        if (failures.isEmpty() || missingOnly) {
            return context.getString(R.string.error_stream_tried_none, triedAddons, id, type)
        }

        val issueSummary = failures
            .filter { it.kind == StreamFailureKind.REQUEST_FAILED }
            .distinctBy { it.addonName to it.detail }
            .take(3)
            .joinToString("; ") { "${it.addonName}: ${it.detail}" }

        return if (issueSummary.isBlank()) {
            context.getString(R.string.error_stream_tried_generic, triedAddons, id, type)
        } else {
            context.getString(R.string.error_stream_tried_issues, triedAddons, id, type, issueSummary)
        }
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

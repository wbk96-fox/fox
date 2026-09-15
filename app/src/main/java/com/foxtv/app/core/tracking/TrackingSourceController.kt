package com.foxtv.app.core.tracking

import com.foxtv.app.core.sync.StartupSyncService
import com.foxtv.app.core.sync.WatchedItemsSyncService
import com.foxtv.app.data.local.ContinueWatchingEnrichmentCache
import com.foxtv.app.data.local.TraktSettingsDataStore
import com.foxtv.app.data.local.WatchProgressPreferences
import com.foxtv.app.data.local.WatchProgressSource
import com.foxtv.app.data.local.WatchedItemsPreferences
import com.foxtv.app.data.local.WatchedSeriesStateHolder
import com.foxtv.app.data.repository.TraktProgressService
import com.foxtv.app.data.repository.isTraktCompatibleId
import com.foxtv.app.data.simkl.SimklSyncRepository
import com.foxtv.app.domain.model.LibrarySourceMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class TrackingSourceController @Inject constructor(
    private val settingsDataStore: TraktSettingsDataStore,
    private val traktProgressService: TraktProgressService,
    private val simklSyncRepository: SimklSyncRepository,
    private val startupSyncService: StartupSyncService,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val watchedItemsSyncService: WatchedItemsSyncService,
    private val watchedSeriesStateHolder: WatchedSeriesStateHolder,
    private val continueWatchingEnrichmentCache: ContinueWatchingEnrichmentCache
) {
    val watchProgressSource = settingsDataStore.watchProgressSource
    val librarySourceMode = settingsDataStore.librarySourceMode

    private val mutationMutex = Mutex()

    suspend fun selectWatchProgressSource(source: WatchProgressSource) {
        mutationMutex.withLock {
            if (settingsDataStore.watchProgressSource.first() == source) return
            applyWatchProgressSource(source)
        }
    }

    suspend fun selectLibrarySourceMode(mode: LibrarySourceMode) {
        mutationMutex.withLock {
            if (settingsDataStore.librarySourceMode.first() == mode) return
            applyLibrarySourceMode(mode)
        }
    }

    suspend fun reconcileConnectedProviders(
        connectedProviderIds: Set<TrackingProviderId>
    ): TrackingSourceSelection = mutationMutex.withLock {
        val requested = TrackingSourceSelection(
            watchProgressSource = settingsDataStore.watchProgressSource.first(),
            librarySourceMode = settingsDataStore.librarySourceMode.first()
        )
        val effective = effectiveTrackingSourceSelection(requested, connectedProviderIds)
        if (requested.watchProgressSource != effective.watchProgressSource) {
            applyWatchProgressSource(effective.watchProgressSource)
        }
        if (requested.librarySourceMode != effective.librarySourceMode) {
            applyLibrarySourceMode(effective.librarySourceMode)
        }
        effective
    }

    private suspend fun applyWatchProgressSource(source: WatchProgressSource) {
        settingsDataStore.setWatchProgressSource(source)
        continueWatchingEnrichmentCache.saveInProgressSnapshot(emptyList(), force = true)
        continueWatchingEnrichmentCache.saveNextUpSnapshot(emptyList(), force = true)
        when (source) {
            WatchProgressSource.TRAKT -> {
                watchProgressPreferences.clearAllPreservingNonTraktIds { contentId ->
                    !isTraktCompatibleId(contentId)
                }
                watchedItemsPreferences.clearAll()
                watchedSeriesStateHolder.update(emptySet())
                traktProgressService.refreshNow()
            }
            WatchProgressSource.SIMKL -> {
                watchedItemsPreferences.clearAll()
                watchedSeriesStateHolder.update(emptySet())
                simklSyncRepository.refresh(TrackingRefreshIntent.USER_INITIATED)
            }
        }
    }

    private suspend fun applyLibrarySourceMode(mode: LibrarySourceMode) {
        settingsDataStore.setLibrarySourceMode(mode)
        if (mode == LibrarySourceMode.SIMKL) {
            simklSyncRepository.refresh(TrackingRefreshIntent.USER_INITIATED)
        }
    }
}

package com.foxtv.app.data.simkl

import com.foxtv.app.core.profile.ProfileManager
import com.foxtv.app.core.tracking.TrackingHistoryItem
import com.foxtv.app.core.tracking.TrackingHistoryWriter
import com.foxtv.app.core.tracking.TrackingMediaReference
import com.foxtv.app.core.tracking.TrackingMutationResult
import com.foxtv.app.core.tracking.TrackingProviderId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimklTrackingHistoryWriter @Inject constructor(
    private val service: SimklMutationService,
    private val syncRepository: SimklSyncRepository,
    private val profileManager: ProfileManager
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.SIMKL

    override suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>
    ): TrackingMutationResult {
        if (profileId != profileManager.activeProfileId.value) return TrackingMutationResult(0)
        syncRepository.ensureLoaded()
        val snapshot = syncRepository.state.value.snapshot
        return service.addToHistory(
            items.map { item ->
                val enriched = snapshot.enrichMediaReference(item.media)
                item.copy(media = enriched.resolveAnimeEpisodeForSimkl())
            }
        )
    }

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>
    ): TrackingMutationResult {
        if (profileId != profileManager.activeProfileId.value) return TrackingMutationResult(0)
        syncRepository.ensureLoaded()
        val snapshot = syncRepository.state.value.snapshot
        return service.removeFromHistory(
            items.map { ref ->
                snapshot.enrichMediaReference(ref).resolveAnimeEpisodeForSimkl()
            }
        )
    }
}

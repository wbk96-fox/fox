package com.foxtv.app.core.tracking

import com.foxtv.app.domain.model.LibraryEntry
import com.foxtv.app.domain.model.LibraryEntryInput
import com.foxtv.app.domain.model.LibraryListTab
import com.foxtv.app.domain.model.ListMembershipChanges
import com.foxtv.app.domain.model.ListMembershipSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

interface TrackingLibraryProvider {
    val providerId: TrackingProviderId
    val isAuthenticated: Flow<Boolean>
    val isRefreshing: Flow<Boolean>
    val items: Flow<List<LibraryEntry>>
    val tabs: Flow<List<LibraryListTab>>

    fun recognizesListKey(key: String): Boolean
    fun observeMembership(itemId: String, itemType: String): Flow<Set<String>>
    fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean>
    suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot
    suspend fun membershipRemovalConfirmation(
        item: LibraryEntryInput,
        changes: ListMembershipChanges
    ): TrackingMembershipRemovalConfirmation? = null
    suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges,
        destructiveRemovalConfirmed: Boolean = false
    )
    suspend fun refresh(intent: TrackingRefreshIntent)
}

@Singleton
class TrackingLibraryProviderRegistry @Inject constructor(
    providers: Set<@JvmSuppressWildcards TrackingLibraryProvider>
) {
    private val providersById = providers.associateBy(TrackingLibraryProvider::providerId)

    init {
        require(providersById.size == providers.size)
    }

    fun providers(): List<TrackingLibraryProvider> =
        providersById.values.sortedBy { it.providerId.ordinal }

    fun provider(id: TrackingProviderId): TrackingLibraryProvider? = providersById[id]
}

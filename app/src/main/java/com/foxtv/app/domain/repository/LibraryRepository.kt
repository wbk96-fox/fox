package com.foxtv.app.domain.repository

import com.foxtv.app.domain.model.LibraryEntry
import com.foxtv.app.domain.model.LibraryEntryInput
import com.foxtv.app.domain.model.LibraryListTab
import com.foxtv.app.domain.model.LibrarySourceMode
import com.foxtv.app.domain.model.ListMembershipChanges
import com.foxtv.app.domain.model.ListMembershipSnapshot
import com.foxtv.app.domain.model.TraktListPrivacy
import com.foxtv.app.core.tracking.TrackingMembershipApplyResult
import com.foxtv.app.core.tracking.TrackingProviderId
import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    val sourceMode: Flow<LibrarySourceMode>
    val isSyncing: Flow<Boolean>
    val libraryItems: Flow<List<LibraryEntry>>
    val listTabs: Flow<List<LibraryListTab>>
    val membershipListTabs: Flow<List<LibraryListTab>>

    fun isInLibrary(itemId: String, itemType: String): Flow<Boolean>
    fun isInWatchlist(itemId: String, itemType: String): Flow<Boolean>

    suspend fun toggleDefault(
        item: LibraryEntryInput,
        confirmedRemovalProviders: Set<TrackingProviderId> = emptySet()
    ): TrackingMembershipApplyResult
    suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot
    suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges,
        confirmedRemovalProviders: Set<TrackingProviderId> = emptySet()
    ): TrackingMembershipApplyResult

    suspend fun createPersonalList(
        name: String,
        description: String?,
        privacy: TraktListPrivacy
    )

    suspend fun updatePersonalList(
        listId: String,
        name: String,
        description: String?,
        privacy: TraktListPrivacy
    )

    suspend fun deletePersonalList(listId: String)
    suspend fun reorderPersonalLists(orderedListIds: List<String>)
    suspend fun refreshNow()
}

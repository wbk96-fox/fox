package com.foxtv.app.core.sync.library

import com.foxtv.app.domain.model.LibraryDeltaApplyResult
import com.foxtv.app.domain.model.LibraryDeltaEvent
import com.foxtv.app.domain.model.LibrarySnapshotApplyResult
import com.foxtv.app.domain.model.LibrarySyncState
import com.foxtv.app.domain.model.SavedLibraryItem

interface LibrarySyncLocalStore {
    suspend fun getSyncState(profileId: Int): LibrarySyncState

    suspend fun applyRemoteSnapshot(
        profileId: Int,
        remoteItems: Collection<SavedLibraryItem>,
        cursorEventId: Long
    ): LibrarySnapshotApplyResult

    suspend fun applyRemoteDelta(
        profileId: Int,
        events: Collection<LibraryDeltaEvent>
    ): LibraryDeltaApplyResult

    suspend fun queueAllItemsForPush(profileId: Int): LibrarySyncState

    suspend fun acknowledgePush(
        profileId: Int,
        expectedMutationRevision: Long
    ): Boolean
}

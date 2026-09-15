package com.foxtv.app.ui.components.posteroptions

import androidx.compose.runtime.Immutable
import com.foxtv.app.domain.model.LibraryListTab
import com.foxtv.app.domain.model.LibrarySourceMode
import com.foxtv.app.domain.model.MetaPreview
import com.foxtv.app.core.tracking.TrackingMembershipRemovalConfirmation

@Immutable
data class PosterOptionsState(
    val target: MetaPreview? = null,
    /** The original item ID before canonicalization (e.g. "tmdb:123"). Used for optimistic badge updates. */
    val originalItemId: String? = null,
    val addonBaseUrl: String = "",
    val isInLibrary: Boolean = false,
    val isWatched: Boolean = false,
    val isLibraryPending: Boolean = false,
    val isWatchedPending: Boolean = false,
    val librarySourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val libraryListTabs: List<LibraryListTab> = emptyList(),
    val listPickerActive: Boolean = false,
    val listPickerTitle: String? = null,
    val listPickerContentType: String? = null,
    val listPickerMembership: Map<String, Boolean> = emptyMap(),
    val listPickerPending: Boolean = false,
    val listPickerError: String? = null,
    val removalConfirmations: List<TrackingMembershipRemovalConfirmation> = emptyList()
)

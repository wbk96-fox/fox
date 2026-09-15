package com.foxtv.app.ui.screens.collection

import com.foxtv.app.ui.theme.FoxTvTheme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.foxtv.app.domain.model.AddonCatalogCollectionSource
import com.foxtv.app.domain.model.CollectionFolder
import com.foxtv.app.domain.model.CollectionSource
import com.foxtv.app.domain.model.FolderViewMode
import com.foxtv.app.domain.model.PosterShape
import com.foxtv.app.domain.model.TmdbCollectionFilters
import com.foxtv.app.domain.model.TmdbCollectionMediaType
import com.foxtv.app.domain.model.TmdbCollectionSort
import com.foxtv.app.domain.model.TmdbCollectionSource
import com.foxtv.app.domain.model.TmdbCollectionSourceType
import com.foxtv.app.ui.components.LoadingIndicator
import com.foxtv.app.ui.components.FoxTvDialog
import com.foxtv.app.R
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CollectionEditorScreen(
    viewModel: CollectionEditorViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var lastFocusedFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    val folderFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    var folderToDelete by remember { mutableStateOf<CollectionFolder?>(null) }
    val deleteDialogFocusRequester = remember { FocusRequester() }

    LaunchedEffect(folderToDelete) {
        if (folderToDelete != null) {
            repeat(3) { withFrameNanos { } }
            try {
                deleteDialogFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(uiState.showFolderEditor) {
        if (!uiState.showFolderEditor && lastFocusedFolderId != null) {
            val targetId = lastFocusedFolderId!!
            repeat(3) { withFrameNanos { } }

            try {
                folderFocusRequesters[targetId]?.requestFocus()
            } catch (_: Exception) {}
        }
    }

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    if (uiState.showFolderEditor) {
        FolderEditorContent(
            viewModel = viewModel,
            uiState = uiState
        )
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = FoxTvTheme.spacing.xxxl),
        contentPadding = PaddingValues(start = FoxTvTheme.spacing.xxxl, end = FoxTvTheme.spacing.xxxl, bottom = FoxTvTheme.spacing.xxxl),
        verticalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.none)
    ) {
        item(key = "header") {
            Text(
                text = if (uiState.isNew) stringResource(R.string.collections_new) else stringResource(R.string.collections_editor_edit_collection),
                style = MaterialTheme.typography.headlineMedium,
                color = FoxTvTheme.colors.TextPrimary
            )
            Spacer(modifier = Modifier.height(FoxTvTheme.spacing.xl))
        }

        item(key = "title") {
            Text(
                text = stringResource(R.string.collections_editor_row_title),
                style = MaterialTheme.typography.labelLarge,
                color = FoxTvTheme.colors.TextSecondary
            )
            Spacer(modifier = Modifier.height(FoxTvTheme.spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FoxTvTextField(
                    value = uiState.title,
                    onValueChange = { viewModel.setTitle(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = stringResource(R.string.collections_editor_placeholder_name)
                )
                val canSaveCollection = uiState.title.isNotBlank() && uiState.folders.isNotEmpty()
                FoxTvButton(onClick = { viewModel.save { onBack() } }, enabled = canSaveCollection) {
                    Text(stringResource(R.string.collections_editor_save))
                }
            }
            Spacer(modifier = Modifier.height(FoxTvTheme.spacing.lg))
        }

        item(key = "backdrop") {
            Text(
                text = stringResource(R.string.collections_editor_backdrop),
                style = MaterialTheme.typography.labelLarge,
                color = FoxTvTheme.colors.TextSecondary
            )
            Spacer(modifier = Modifier.height(FoxTvTheme.spacing.sm))
            FoxTvTextField(
                value = uiState.backdropImageUrl,
                onValueChange = { viewModel.setBackdropImageUrl(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(R.string.collections_editor_placeholder_backdrop)
            )
            Spacer(modifier = Modifier.height(FoxTvTheme.spacing.lg))
        }

        item(key = "pin_to_top") {
            Card(
                onClick = { viewModel.setPinToTop(!uiState.pinToTop) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.colors(
                    containerColor = FoxTvTheme.colors.BackgroundCard,
                    focusedContainerColor = FoxTvTheme.colors.FocusBackground
                ),
                border = CardDefaults.border(
                    focusedBorder = Border(
                        border = FoxTvTheme.focusRing.border(FoxTvTheme.spacing.xxs),
                        shape = RoundedCornerShape(FoxTvTheme.radii.md)
                    )
                ),
                shape = CardDefaults.shape(RoundedCornerShape(FoxTvTheme.radii.md)),
                scale = CardDefaults.scale(focusedScale = 1.02f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.collections_editor_pin_above),
                            style = MaterialTheme.typography.titleMedium,
                            color = FoxTvTheme.colors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(FoxTvTheme.spacing.xs))
                        Text(
                            text = stringResource(R.string.collections_editor_pin_above_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = FoxTvTheme.colors.TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(FoxTvTheme.spacing.md))
                    Switch(
                        checked = uiState.pinToTop,
                        onCheckedChange = { viewModel.setPinToTop(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = FoxTvTheme.colors.Secondary,
                            checkedTrackColor = FoxTvTheme.colors.Secondary.copy(alpha = 0.3f),
                            uncheckedThumbColor = FoxTvTheme.colors.TextSecondary,
                            uncheckedTrackColor = FoxTvTheme.colors.BackgroundCard
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(FoxTvTheme.spacing.lg))
        }

        item(key = "focus_glow") {
            Card(
                onClick = { viewModel.setFocusGlowEnabled(!uiState.focusGlowEnabled) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.colors(
                    containerColor = FoxTvTheme.colors.BackgroundCard,
                    focusedContainerColor = FoxTvTheme.colors.FocusBackground
                ),
                border = CardDefaults.border(
                    focusedBorder = Border(
                        border = FoxTvTheme.focusRing.border(FoxTvTheme.spacing.xxs),
                        shape = RoundedCornerShape(FoxTvTheme.radii.md)
                    )
                ),
                shape = CardDefaults.shape(RoundedCornerShape(FoxTvTheme.radii.md)),
                scale = CardDefaults.scale(focusedScale = 1.02f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.collections_editor_focus_glow),
                            style = MaterialTheme.typography.titleMedium,
                            color = FoxTvTheme.colors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(FoxTvTheme.spacing.xs))
                        Text(
                            text = stringResource(R.string.collections_editor_focus_glow_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = FoxTvTheme.colors.TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(FoxTvTheme.spacing.md))
                    Switch(
                        checked = uiState.focusGlowEnabled,
                        onCheckedChange = { viewModel.setFocusGlowEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = FoxTvTheme.colors.Secondary,
                            checkedTrackColor = FoxTvTheme.colors.Secondary.copy(alpha = 0.3f),
                            uncheckedThumbColor = FoxTvTheme.colors.TextSecondary,
                            uncheckedTrackColor = FoxTvTheme.colors.BackgroundCard
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(FoxTvTheme.spacing.lg))
        }

        item(key = "view_mode") {
            Text(
                text = stringResource(R.string.collections_editor_view_mode),
                style = MaterialTheme.typography.labelLarge,
                color = FoxTvTheme.colors.TextSecondary
            )
            Spacer(modifier = Modifier.height(FoxTvTheme.spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.sm)
            ) {
                val viewModes = listOf(
                    FolderViewMode.TABBED_GRID to stringResource(R.string.collections_editor_view_mode_tabs),
                    FolderViewMode.ROWS to stringResource(R.string.collections_editor_view_mode_rows),
                    FolderViewMode.FOLLOW_LAYOUT to stringResource(R.string.collections_editor_view_mode_follow)
                )
                viewModes.forEach { (mode, label) ->
                    val isSelected = uiState.viewMode == mode
                    Button(
                        onClick = { viewModel.setViewMode(mode) },
                        colors = ButtonDefaults.colors(
                            containerColor = if (isSelected) FoxTvTheme.colors.Secondary.copy(alpha = 0.3f) else FoxTvTheme.colors.BackgroundCard,
                            contentColor = if (isSelected) FoxTvTheme.colors.Secondary else FoxTvTheme.colors.TextSecondary,
                            focusedContainerColor = FoxTvTheme.colors.FocusBackground,
                            focusedContentColor = FoxTvTheme.colors.Primary
                        ),
                        border = ButtonDefaults.border(
                            border = if (isSelected) Border(
                                border = BorderStroke(FoxTvTheme.spacing.xxs, FoxTvTheme.colors.Secondary),
                                shape = RoundedCornerShape(FoxTvTheme.radii.md)
                            ) else Border.None,
                            focusedBorder = Border(
                                border = FoxTvTheme.focusRing.border(FoxTvTheme.spacing.xxs),
                                shape = RoundedCornerShape(FoxTvTheme.radii.md)
                            )
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(FoxTvTheme.radii.md))
                    ) {
                        Text(label)
                    }
                }
            }
            Spacer(modifier = Modifier.height(FoxTvTheme.spacing.md))
        }

        if (uiState.viewMode == FolderViewMode.TABBED_GRID) {
            item(key = "show_all_tab") {
                Card(
                    onClick = { viewModel.setShowAllTab(!uiState.showAllTab) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(
                        containerColor = FoxTvTheme.colors.BackgroundCard,
                        focusedContainerColor = FoxTvTheme.colors.FocusBackground
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = FoxTvTheme.focusRing.border(FoxTvTheme.spacing.xxs),
                            shape = RoundedCornerShape(FoxTvTheme.radii.md)
                        )
                    ),
                    shape = CardDefaults.shape(RoundedCornerShape(FoxTvTheme.radii.md)),
                    scale = CardDefaults.scale(focusedScale = 1.02f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.collections_editor_show_all_tab),
                                style = MaterialTheme.typography.titleMedium,
                                color = FoxTvTheme.colors.TextPrimary
                            )
                            Spacer(modifier = Modifier.height(FoxTvTheme.spacing.xs))
                            Text(
                                text = stringResource(R.string.collections_editor_show_all_tab_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = FoxTvTheme.colors.TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(FoxTvTheme.spacing.md))
                        Switch(
                            checked = uiState.showAllTab,
                            onCheckedChange = { viewModel.setShowAllTab(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = FoxTvTheme.colors.Secondary,
                                checkedTrackColor = FoxTvTheme.colors.Secondary.copy(alpha = 0.3f),
                                uncheckedThumbColor = FoxTvTheme.colors.TextSecondary,
                                uncheckedTrackColor = FoxTvTheme.colors.BackgroundCard
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(FoxTvTheme.spacing.md))
            }
        }

        item(key = "folders_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.collections_editor_folders),
                    style = MaterialTheme.typography.titleMedium,
                    color = FoxTvTheme.colors.TextPrimary
                )
                Text(
                    text = stringResource(
                        if (uiState.folders.size == 1) R.string.collection_editor_folder_count_one
                        else R.string.collection_editor_folder_count_other,
                        uiState.folders.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxTvTheme.colors.TextTertiary
                )
            }
            Spacer(modifier = Modifier.height(FoxTvTheme.spacing.md))
        }

        itemsIndexed(
            items = uiState.folders,
            key = { _, folder -> folder.id }
        ) { index, folder ->
            val editFocusRequester = folderFocusRequesters.getOrPut(folder.id) { FocusRequester() }
            Box(modifier = Modifier.padding(start = FoxTvTheme.spacing.sm, end = FoxTvTheme.spacing.sm, bottom = FoxTvTheme.spacing.sm)) {
                FolderListItem(
                    folder = folder,
                    isFirst = index == 0,
                    isLast = index == uiState.folders.size - 1,
                    editFocusRequester = editFocusRequester,
                    onEdit = {
                        lastFocusedFolderId = folder.id
                        viewModel.editFolder(folder.id)
                    },
                    onDelete = { folderToDelete = folder },
                    onMoveUp = { viewModel.moveFolderUp(index) },
                    onMoveDown = { viewModel.moveFolderDown(index) }
                )
            }
        }

        item(key = "add_folder") {
            val addFolderFocusRequester = folderFocusRequesters.getOrPut("add_folder") { FocusRequester() }
            Box(modifier = Modifier.padding(start = FoxTvTheme.spacing.sm, end = FoxTvTheme.spacing.sm, top = FoxTvTheme.spacing.xs)) {
                FoxTvButton(
                    onClick = {
                        lastFocusedFolderId = "add_folder"
                        viewModel.addFolder()
                    },
                    modifier = Modifier.focusRequester(addFolderFocusRequester)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(R.string.cd_add))
                    Spacer(modifier = Modifier.width(FoxTvTheme.spacing.sm))
                    Text(stringResource(R.string.collections_editor_add_folder))
                }
            }
        }
    }

    folderToDelete?.let { folder ->
        FoxTvDialog(
            onDismiss = { folderToDelete = null },
            title = stringResource(R.string.collections_editor_delete_folder_title),
            subtitle = stringResource(R.string.collections_editor_delete_folder_subtitle, folder.title),
            suppressFirstKeyUp = false
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.md, Alignment.End)
            ) {
                FoxTvButton(
                    onClick = { folderToDelete = null },
                    modifier = Modifier.focusRequester(deleteDialogFocusRequester)
                ) {
                    Text(stringResource(R.string.collections_cancel))
                }
                FoxTvButton(
                    onClick = {
                        viewModel.removeFolder(folder.id)
                        folderToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.cd_delete))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FolderListItem(
    folder: CollectionFolder,
    isFirst: Boolean,
    isLast: Boolean,
    editFocusRequester: FocusRequester? = null,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(FoxTvTheme.radii.md),
        colors = SurfaceDefaults.colors(containerColor = FoxTvTheme.colors.BackgroundCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FoxTvTheme.spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = FoxTvTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val shapeLabel = stringResource(
                    when (folder.tileShape) {
                        com.foxtv.app.domain.model.PosterShape.POSTER -> R.string.collections_editor_shape_poster
                        com.foxtv.app.domain.model.PosterShape.LANDSCAPE -> R.string.collections_editor_shape_wide
                        com.foxtv.app.domain.model.PosterShape.SQUARE -> R.string.collections_editor_shape_square
                    }
                )
                Text(
                    text = "$shapeLabel - ${stringResource(R.string.collections_editor_source_count, folder.sources.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxTvTheme.colors.TextTertiary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.xxs)) {
                FoxTvButton(onClick = onMoveUp) {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.cd_move_up), tint = if (!isFirst) FoxTvTheme.colors.TextSecondary else FoxTvTheme.colors.TextTertiary)
                }
                FoxTvButton(onClick = onMoveDown) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.cd_move_down), tint = if (!isLast) FoxTvTheme.colors.TextSecondary else FoxTvTheme.colors.TextTertiary)
                }
                FoxTvButton(
                    onClick = onEdit,
                    modifier = if (editFocusRequester != null) Modifier.focusRequester(editFocusRequester) else Modifier
                ) {
                    Icon(Icons.Default.Edit, stringResource(R.string.cd_edit), tint = FoxTvTheme.colors.TextSecondary)
                }
                FoxTvButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, stringResource(R.string.cd_delete), tint = FoxTvTheme.colors.TextSecondary)
                }
            }
        }
    }
}

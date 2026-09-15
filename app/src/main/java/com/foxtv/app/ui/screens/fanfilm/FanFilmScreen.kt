package com.foxtv.app.ui.screens.fanfilm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.foxtv.app.R
import com.foxtv.app.core.fanfilm.FanFilmError
import com.foxtv.app.core.fanfilm.FanFilmListItem
import com.foxtv.app.ui.theme.FoxTvTheme

/**
 * The FanFilm browser: the addon's own catalogue, rendered as a FOX.TV screen.
 *
 * FanFilm is a directory-based Kodi plugin, so this is a folder browser rather than a
 * bespoke catalogue: whatever the plugin publishes (its menus, its searches, its
 * "recently added" rows) appears here, which is what makes the sidebar entry a real
 * entry point rather than a shortcut to a fixed list (AGENTS.md §34/§38).
 *
 * D-pad behaviour: the grid takes focus when a listing arrives, BACK walks up the
 * plugin's folder stack and only leaves the screen at the root, and a blocking plugin
 * dialog is overlaid by [FanFilmDialogHost] which grabs focus for as long as the
 * plugin is waiting.
 */
@Composable
fun FanFilmScreen(
    onNavigateToSettings: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FanFilmViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val dialog by viewModel.dialog.collectAsState()
    val gridState = rememberLazyGridState()
    val gridFocus = remember { FocusRequester() }

    // BACK walks the plugin's folder stack first; only the root hands the press back to
    // the host so the sidebar/system behaviour is unchanged elsewhere.
    BackHandler(enabled = dialog == null) {
        if (!viewModel.goBack()) onExit()
    }

    LaunchedEffect(state.items.isNotEmpty(), dialog == null) {
        if (state.items.isNotEmpty() && dialog == null) {
            runCatching { gridFocus.requestFocus() }
        }
    }

    LaunchedEffect(state.breadcrumbs.size) {
        // Entering a folder must start at the top rather than inheriting the parent's
        // scroll offset.
        gridState.scrollToItem(0)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = FoxTvTheme.spacing.xl,
                    vertical = FoxTvTheme.spacing.lg,
                ),
            verticalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.md),
        ) {
            Header(
                title = state.title.ifBlank { stringResource(R.string.fanfilm_title) },
                subtitle = state.startupSummary,
                onSettings = onNavigateToSettings,
            )

            state.progress?.let { progress ->
                ScanProgress(progress)
            }

            when {
                state.isStarting -> StatusMessage(stringResource(R.string.fanfilm_starting))

                state.error != null -> ErrorMessage(
                    error = state.error!!,
                    onRetry = viewModel::retry,
                )

                state.isLoading && state.items.isEmpty() ->
                    StatusMessage(stringResource(R.string.fanfilm_loading))

                state.isEmpty -> StatusMessage(stringResource(R.string.fanfilm_empty))

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(GRID_COLUMNS),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(gridFocus),
                    horizontalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.md),
                ) {
                    items(
                        items = state.items,
                        key = { item -> item.url.ifBlank { item.label } },
                    ) { item ->
                        FanFilmCard(item = item, onClick = { viewModel.onItemSelected(item) })
                    }
                }
            }
        }

        FanFilmDialogHost(
            request = dialog,
            onRespond = viewModel::onDialogResponse,
            onDismiss = viewModel::onDialogDismissed,
        )
    }

    state.notice?.let { notice ->
        LaunchedEffect(notice) {
            // Notifications are transient by contract; clearing after a beat keeps them
            // from sticking to an unrelated later screen state.
            kotlinx.coroutines.delay(NOTICE_VISIBLE_MS)
            viewModel.clearNotice()
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                ),
                color = FoxTvTheme.colors.TextPrimary,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = FoxTvTheme.colors.TextSecondary,
                )
            }
        }
        Card(onClick = onSettings) {
            Text(
                text = stringResource(R.string.fanfilm_settings_title),
                modifier = Modifier.padding(
                    horizontal = FoxTvTheme.spacing.md,
                    vertical = FoxTvTheme.spacing.sm,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun ScanProgress(progress: FanFilmViewModel.Progress) {
    Column(verticalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.xxs)) {
        Text(
            text = progress.message.ifBlank {
                stringResource(R.string.fanfilm_scanning, progress.percent)
            },
            style = MaterialTheme.typography.labelMedium,
            color = FoxTvTheme.colors.TextSecondary,
        )
        if (progress.providers.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.fanfilm_scanning_providers,
                    progress.providers.joinToString(", "),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = FoxTvTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LinearProgressIndicator(
            progress = { progress.percent.coerceIn(0, 100) / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        )
    }
}

@Composable
private fun StatusMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = FoxTvTheme.colors.TextSecondary,
        )
    }
}

@Composable
private fun ErrorMessage(error: FanFilmError, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.md),
        ) {
            Text(
                text = error.localizedMessage(),
                style = MaterialTheme.typography.bodyLarge,
                color = FoxTvTheme.colors.TextPrimary,
            )
            if (error.isRetryable) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Card(onClick = onRetry, modifier = Modifier.focusRequester(focus)) {
                    Text(
                        text = stringResource(R.string.action_retry),
                        modifier = Modifier.padding(
                            horizontal = FoxTvTheme.spacing.lg,
                            vertical = FoxTvTheme.spacing.sm,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun FanFilmCard(item: FanFilmListItem, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column {
            val artwork = item.poster ?: item.backdrop
            if (!artwork.isNullOrBlank()) {
                AsyncImage(
                    model = artwork,
                    contentDescription = item.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(POSTER_HEIGHT)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(POSTER_HEIGHT)
                        .clip(RoundedCornerShape(8.dp))
                        .background(FoxTvTheme.colors.BackgroundElevated),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (item.isFolder) "▸" else "▶",
                        style = MaterialTheme.typography.headlineMedium,
                        color = FoxTvTheme.colors.TextSecondary,
                    )
                }
            }
            Text(
                text = item.label,
                modifier = Modifier.padding(FoxTvTheme.spacing.sm),
                style = MaterialTheme.typography.labelMedium,
                color = FoxTvTheme.colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Map a typed error onto the string the user sees. */
@Composable
private fun FanFilmError.localizedMessage(): String = when (this) {
    is FanFilmError.Configuration ->
        stringResource(R.string.fanfilm_error_configuration, technicalDetail)
    is FanFilmError.Plugin -> stringResource(R.string.fanfilm_error_plugin, technicalDetail)
    is FanFilmError.NoSources -> stringResource(R.string.fanfilm_error_no_sources)
    is FanFilmError.Resolver -> stringResource(R.string.fanfilm_error_resolver, host)
    is FanFilmError.Network -> stringResource(R.string.fanfilm_error_network, technicalDetail)
    is FanFilmError.Drm -> stringResource(R.string.fanfilm_error_drm, scheme)
    is FanFilmError.Playback -> stringResource(R.string.fanfilm_error_playback, technicalDetail)
    is FanFilmError.Busy -> stringResource(R.string.fanfilm_error_busy)
    is FanFilmError.Update -> stringResource(R.string.fanfilm_error_update, technicalDetail)
    FanFilmError.Cancelled -> ""
}

private const val GRID_COLUMNS = 5
private val POSTER_HEIGHT = 200.dp
private const val NOTICE_VISIBLE_MS = 4_000L

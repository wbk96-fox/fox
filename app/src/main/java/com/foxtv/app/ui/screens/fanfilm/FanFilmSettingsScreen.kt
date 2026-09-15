package com.foxtv.app.ui.screens.fanfilm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.foxtv.app.R
import com.foxtv.app.core.fanfilm.FanFilmAddonUpdater
import com.foxtv.app.core.fanfilm.FanFilmSettingsRepository
import com.foxtv.app.ui.theme.FoxTvTheme

/**
 * FanFilm's own settings, rendered from the addon's `resources/settings.xml`.
 *
 * Categories become the left rail, groups become section headers, and each setting is
 * drawn from its declared `<control>` type. Nothing on this screen is decorative: a
 * toggle writes through to the addon's `settings.xml` and the running interpreter
 * before the switch moves, and `<dependency type="enable">` is honoured so a setting
 * the addon would ignore is shown disabled rather than pretending to work
 * (AGENTS.md §18/§53).
 *
 * Credentials (`*_password`, `*api_key*`, tokens, cookies) are masked and never logged.
 */
@Composable
fun FanFilmSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FanFilmSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val dialog by viewModel.dialog.collectAsState()
    var selectedCategory by rememberSaveable { mutableStateOf(0) }

    BackHandler(enabled = dialog == null) { onBack() }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(FoxTvTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.md),
        ) {
            Text(
                text = stringResource(R.string.fanfilm_settings_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = FoxTvTheme.colors.TextPrimary,
            )
            state.snapshot?.let { snapshot ->
                Text(
                    text = "${snapshot.addonId} ${snapshot.addonVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = FoxTvTheme.colors.TextSecondary,
                )
            }

            UpdatesSection(
                state = state.updates,
                onCheck = { viewModel.checkForUpdates(force = true) },
                onApply = viewModel::applyUpdate,
                onRollback = viewModel::rollbackUpdate,
            )

            when {
                state.isLoading -> Centered(stringResource(R.string.fanfilm_loading))

                state.snapshot == null -> Centered(
                    state.errorMessage ?: stringResource(R.string.fanfilm_settings_unavailable)
                )

                else -> {
                    val snapshot = state.snapshot!!
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.lg),
                    ) {
                        CategoryRail(
                            categories = snapshot.categories.map { it.label.ifBlank { it.id } },
                            selected = selectedCategory,
                            onSelect = { selectedCategory = it },
                        )
                        val category = snapshot.categories.getOrNull(selectedCategory)
                        SettingsList(
                            groups = category?.groups.orEmpty(),
                            snapshot = snapshot,
                            pending = state.pendingSettingId,
                            onToggle = viewModel::toggle,
                            onSelectOption = viewModel::setValue,
                            onEditText = viewModel::setValue,
                            onRunAction = viewModel::runAction,
                        )
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
}

/**
 * Addon updater surface.
 *
 * Renders staged-restart notices, available updates with an Update action, and a rollback
 * for anything staged. This is the runtime consumer that makes the updater a wired feature
 * rather than dead code: every row here maps to a real [FanFilmAddonUpdater] call.
 */
@Composable
private fun UpdatesSection(
    state: FanFilmSettingsViewModel.UpdateState,
    onCheck: () -> Unit,
    onApply: (FanFilmAddonUpdater.Available) -> Unit,
    onRollback: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.fanfilm_updates_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = FoxTvTheme.colors.TextSecondary,
            )
            Card(onClick = { if (!state.isChecking && state.busyAddonId == null) onCheck() }) {
                Text(
                    text = if (state.isChecking) {
                        stringResource(R.string.fanfilm_updates_checking)
                    } else {
                        stringResource(R.string.fanfilm_updates_check)
                    },
                    modifier = Modifier.padding(
                        horizontal = FoxTvTheme.spacing.md,
                        vertical = FoxTvTheme.spacing.sm,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = FoxTvTheme.colors.Primary,
                )
            }
        }

        state.pendingRestart.forEach { (addonId, version) ->
            Card(
                onClick = { if (state.busyAddonId == null) onRollback(addonId) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FoxTvTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(
                            R.string.fanfilm_updates_restart_required,
                            addonId,
                            version,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FoxTvTheme.colors.TextPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.fanfilm_updates_rollback),
                        style = MaterialTheme.typography.labelLarge,
                        color = FoxTvTheme.colors.Primary,
                    )
                }
            }
        }

        state.available.forEach { available ->
            val busy = state.busyAddonId == available.addonId
            Card(
                onClick = { if (state.busyAddonId == null) onApply(available) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FoxTvTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${available.addonId}  ${available.installedVersion} → ${available.remoteVersion}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FoxTvTheme.colors.TextPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (busy) {
                            stringResource(R.string.fanfilm_updates_updating)
                        } else {
                            stringResource(R.string.fanfilm_updates_update)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = FoxTvTheme.colors.Primary,
                    )
                }
            }
        }

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = FoxTvTheme.colors.TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = FoxTvTheme.colors.TextSecondary,
        )
    }
}

@Composable
private fun CategoryRail(
    categories: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.width(RAIL_WIDTH),
        state = rememberLazyListState(),
        verticalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.xs),
    ) {
        itemsIndexedCompat(categories) { index, label ->
            Card(
                onClick = { onSelect(index) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(FoxTvTheme.spacing.sm),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (index == selected) {
                        FoxTvTheme.colors.Primary
                    } else {
                        FoxTvTheme.colors.TextPrimary
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SettingsList(
    groups: List<FanFilmSettingsRepository.Group>,
    snapshot: FanFilmSettingsRepository.Snapshot,
    pending: String?,
    onToggle: (FanFilmSettingsRepository.Definition, Boolean) -> Unit,
    onSelectOption: (FanFilmSettingsRepository.Definition, String) -> Unit,
    onEditText: (FanFilmSettingsRepository.Definition, String) -> Unit,
    onRunAction: (FanFilmSettingsRepository.Definition) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.sm),
    ) {
        groups.forEach { group ->
            if (group.label.isNotBlank()) {
                item(key = "group:${group.id}") {
                    Text(
                        text = group.label,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = FoxTvTheme.colors.TextSecondary,
                        modifier = Modifier.padding(top = FoxTvTheme.spacing.sm),
                    )
                }
            }
            group.settings
                .filter { snapshot.isVisible(it.id) }
                .forEach { definition ->
                    item(key = definition.id) {
                        SettingRow(
                            definition = definition,
                            snapshot = snapshot,
                            isPending = pending == definition.id,
                            onToggle = onToggle,
                            onSelectOption = onSelectOption,
                            onEditText = onEditText,
                            onRunAction = onRunAction,
                        )
                    }
                }
        }
    }
}

@Composable
private fun SettingRow(
    definition: FanFilmSettingsRepository.Definition,
    snapshot: FanFilmSettingsRepository.Snapshot,
    isPending: Boolean,
    onToggle: (FanFilmSettingsRepository.Definition, Boolean) -> Unit,
    onSelectOption: (FanFilmSettingsRepository.Definition, String) -> Unit,
    onEditText: (FanFilmSettingsRepository.Definition, String) -> Unit,
    onRunAction: (FanFilmSettingsRepository.Definition) -> Unit,
) {
    val enabled = snapshot.isEnabled(definition.id) && !isPending
    val label = definition.label.ifBlank { definition.id }
    val current = snapshot.value(definition.id)

    Card(
        onClick = {
            if (!enabled) return@Card
            when (definition.type) {
                FanFilmSettingsRepository.Definition.Type.BOOLEAN ->
                    onToggle(definition, !snapshot.boolean(definition.id))

                FanFilmSettingsRepository.Definition.Type.ACTION -> onRunAction(definition)

                else -> if (definition.options.isNotEmpty()) {
                    // Cycle to the next declared option: on a remote this is one OK press
                    // instead of opening a picker for a two- or three-value list.
                    val index = definition.options.indexOfFirst { it.value == current }
                    val next = definition.options[(index + 1).mod(definition.options.size)]
                    onSelectOption(definition, next.value)
                } else {
                    onEditText(definition, current)
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FoxTvTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) {
                        FoxTvTheme.colors.TextPrimary
                    } else {
                        FoxTvTheme.colors.TextSecondary
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val summary = definition.summaryFor(current, snapshot)
                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = FoxTvTheme.colors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (definition.type == FanFilmSettingsRepository.Definition.Type.BOOLEAN) {
                Switch(
                    checked = snapshot.boolean(definition.id),
                    onCheckedChange = { if (enabled) onToggle(definition, it) },
                    enabled = enabled,
                )
            } else if (definition.type == FanFilmSettingsRepository.Definition.Type.ACTION) {
                Text(
                    text = stringResource(R.string.fanfilm_settings_action_run),
                    style = MaterialTheme.typography.labelLarge,
                    color = FoxTvTheme.colors.Primary,
                )
            }
        }
    }
}

/** Human-readable current value, with credentials masked. */
private fun FanFilmSettingsRepository.Definition.summaryFor(
    current: String,
    snapshot: FanFilmSettingsRepository.Snapshot,
): String {
    if (isAction) return help
    if (isSecret) return if (current.isBlank()) help else "••••••••"
    val optionLabel = options.firstOrNull { it.value == current }?.label
    val value = optionLabel ?: current
    return when {
        value.isBlank() -> help
        help.isBlank() -> value
        else -> "$value — $help"
    }
}

/**
 * `itemsIndexed` for a plain list inside `LazyColumn`.
 *
 * Declared locally so the rail does not need a key type; the category list is short and
 * stable, so positional keys are correct here.
 */
private inline fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedCompat(
    values: List<String>,
    crossinline content: @Composable (Int, String) -> Unit,
) {
    values.forEachIndexed { index, value ->
        item(key = "category:$index:$value") { content(index, value) }
    }
}

private val RAIL_WIDTH = 260.dp

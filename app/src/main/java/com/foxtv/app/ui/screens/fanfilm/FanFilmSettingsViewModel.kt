package com.foxtv.app.ui.screens.fanfilm

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtv.app.core.fanfilm.FanFilmAddonUpdater
import com.foxtv.app.core.fanfilm.FanFilmDialogCoordinator
import com.foxtv.app.core.fanfilm.FanFilmRuntime
import com.foxtv.app.core.fanfilm.FanFilmSettingsRepository
import com.foxtv.app.core.fanfilm.FanFilmStartupException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State holder for the FanFilm settings screen.
 *
 * Writes are optimistic only in appearance: the row is marked pending, the value is
 * written through to the addon, and the snapshot is refreshed from what the addon
 * reports afterwards. If the addon coerces or rejects a value the UI shows the real
 * result rather than the requested one.
 */
@HiltViewModel
class FanFilmSettingsViewModel @Inject constructor(
    private val settings: FanFilmSettingsRepository,
    private val runtime: FanFilmRuntime,
    private val dialogs: FanFilmDialogCoordinator,
    private val updater: FanFilmAddonUpdater,
) : ViewModel() {

    @Immutable
    data class UiState(
        val isLoading: Boolean = true,
        val snapshot: FanFilmSettingsRepository.Snapshot? = null,
        val pendingSettingId: String? = null,
        val errorMessage: String? = null,
        val updates: UpdateState = UpdateState(),
    )

    /**
     * Runtime state for the addon updater section.
     *
     * This is the UI surface that makes [FanFilmAddonUpdater] a wired feature: available
     * updates are checked on load, an update can be staged, a staged update reports that a
     * restart is required, and a broken update can be rolled back (AGENTS.md §25/§54/§86).
     */
    @Immutable
    data class UpdateState(
        val isChecking: Boolean = false,
        val available: List<FanFilmAddonUpdater.Available> = emptyList(),
        val busyAddonId: String? = null,
        val pendingRestart: Map<String, String> = emptyMap(),
        val message: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** An `action` setting can prompt; the dialog host renders it. */
    val dialog: StateFlow<FanFilmDialogCoordinator.Request?> = dialogs.visible

    init {
        reload()
        // Surface anything already staged from a previous session up front, then check the
        // network on the TTL the updater enforces.
        _uiState.value = _uiState.value.copy(
            updates = _uiState.value.updates.copy(
                pendingRestart = updater.pendingActivation(),
            ),
        )
        checkForUpdates(force = false)
    }

    /** Look for newer published addon versions; [force] bypasses the updater's TTL. */
    fun checkForUpdates(force: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                updates = _uiState.value.updates.copy(isChecking = true, message = null),
            )
            val result = updater.check(force = force)
            val updates = _uiState.value.updates
            _uiState.value = _uiState.value.copy(
                updates = result.fold(
                    onSuccess = { available ->
                        updates.copy(isChecking = false, available = available)
                    },
                    onFailure = { throwable ->
                        updates.copy(isChecking = false, message = throwable.userMessage())
                    },
                ),
            )
        }
    }

    /** Download, verify, stage and activate one update; takes effect after a restart. */
    fun applyUpdate(target: FanFilmAddonUpdater.Available) {
        if (_uiState.value.updates.busyAddonId != null) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                updates = _uiState.value.updates.copy(busyAddonId = target.addonId, message = null),
            )
            val outcome = updater.update(target)
            val updates = _uiState.value.updates
            _uiState.value = _uiState.value.copy(
                updates = when (outcome) {
                    is FanFilmAddonUpdater.Outcome.Staged -> updates.copy(
                        busyAddonId = null,
                        available = updates.available.filterNot { it.addonId == outcome.addonId },
                        pendingRestart = updater.pendingActivation(),
                        message = "${outcome.addonId} ${outcome.version} — restart to finish",
                    )

                    is FanFilmAddonUpdater.Outcome.UpToDate -> updates.copy(
                        busyAddonId = null,
                        available = updates.available.filterNot { it.addonId == target.addonId },
                    )

                    is FanFilmAddonUpdater.Outcome.Rejected -> updates.copy(
                        busyAddonId = null,
                        message = outcome.error.technicalDetail,
                    )

                    is FanFilmAddonUpdater.Outcome.RolledBack -> updates.copy(
                        busyAddonId = null,
                        pendingRestart = updater.pendingActivation(),
                        message = outcome.error.technicalDetail,
                    )
                },
            )
        }
    }

    /** Restore the backed-up copy of a staged addon; takes effect after a restart. */
    fun rollbackUpdate(addonId: String) {
        if (_uiState.value.updates.busyAddonId != null) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                updates = _uiState.value.updates.copy(busyAddonId = addonId, message = null),
            )
            val result = updater.rollback(addonId)
            val updates = _uiState.value.updates
            _uiState.value = _uiState.value.copy(
                updates = result.fold(
                    onSuccess = {
                        updates.copy(
                            busyAddonId = null,
                            pendingRestart = updater.pendingActivation(),
                            message = "$addonId rolled back — restart to finish",
                        )
                    },
                    onFailure = { throwable ->
                        updates.copy(busyAddonId = null, message = throwable.userMessage())
                    },
                ),
            )
        }
    }

    fun reload() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            settings.load().fold(
                onSuccess = { snapshot ->
                    _uiState.value = UiState(isLoading = false, snapshot = snapshot)
                },
                onFailure = { throwable ->
                    _uiState.value = UiState(
                        isLoading = false,
                        snapshot = null,
                        errorMessage = throwable.userMessage(),
                    )
                },
            )
        }
    }

    fun toggle(definition: FanFilmSettingsRepository.Definition, value: Boolean) {
        write(definition.id) { settings.writeBoolean(definition.id, value) }
    }

    fun setValue(definition: FanFilmSettingsRepository.Definition, value: String) {
        write(definition.id) { settings.write(definition.id, value) }
    }

    /**
     * Run an `action` setting through the plugin.
     *
     * Actions execute addon code (clear cache, sign in to a host), so they get their own
     * run id and can raise dialogs exactly like any other plugin invocation.
     */
    fun runAction(definition: FanFilmSettingsRepository.Definition) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(pendingSettingId = definition.id)
            val runId = runtime.newRunId()
            settings.runAction(definition, runId)
                .onFailure { throwable ->
                    _uiState.value = _uiState.value.copy(errorMessage = throwable.userMessage())
                }
            _uiState.value = _uiState.value.copy(pendingSettingId = null)
            // An action can change other settings (clearing an account clears its token),
            // so the whole snapshot is refreshed rather than one row.
            settings.load().onSuccess { snapshot ->
                _uiState.value = _uiState.value.copy(snapshot = snapshot)
            }
        }
    }

    fun onDialogResponse(id: Long, value: Any?) = dialogs.respond(id, value)

    fun onDialogDismissed(id: Long) = dialogs.dismiss(id)

    private fun write(settingId: String, action: suspend () -> Result<String>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(pendingSettingId = settingId, errorMessage = null)
            action().fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        snapshot = settings.snapshot.value,
                        pendingSettingId = null,
                    )
                },
                onFailure = { throwable ->
                    _uiState.value = _uiState.value.copy(
                        pendingSettingId = null,
                        errorMessage = throwable.userMessage(),
                    )
                },
            )
        }
    }

    private fun Throwable.userMessage(): String =
        (this as? FanFilmStartupException)?.error?.technicalDetail
            ?: message
            ?: this::class.java.simpleName
}

package com.foxtv.app.ui.screens.settings

import com.foxtv.app.ui.theme.FoxTvTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.foxtv.app.R
import com.foxtv.app.domain.model.ExperienceMode
import com.foxtv.app.ui.components.FoxTvDialog

@Composable
internal fun ExperienceModeConfirmationDialog(
    targetMode: ExperienceMode,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isEssential = targetMode == ExperienceMode.ESSENTIAL
    val confirmFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        confirmFocusRequester.requestFocus()
    }

    FoxTvDialog(
        onDismiss = onDismiss,
        title = stringResource(
            if (isEssential) {
                R.string.experience_mode_confirm_essential_title
            } else {
                R.string.experience_mode_confirm_advanced_title
            }
        ),
        subtitle = if (isEssential) {
            stringResource(R.string.experience_mode_confirm_essential_subtitle)
        } else {
            stringResource(R.string.experience_mode_confirm_advanced_subtitle)
        },
        suppressFirstKeyUp = false
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.md)
        ) {
            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(
                    containerColor = FoxTvTheme.colors.BackgroundCard,
                    contentColor = FoxTvTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(confirmFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = FoxTvTheme.colors.BackgroundCard,
                    contentColor = FoxTvTheme.colors.TextPrimary
                )
            ) {
                Text(
                    stringResource(
                        if (isEssential) {
                            R.string.experience_mode_switch_to_essential
                        } else {
                            R.string.experience_mode_switch_to_advanced
                        }
                    )
                )
            }
        }
    }
}

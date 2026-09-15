package com.foxtv.app.ui.screens.fanfilm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.foxtv.app.R
import com.foxtv.app.core.fanfilm.FanFilmDialogCoordinator
import com.foxtv.app.ui.theme.FoxTvTheme

/**
 * Renders the question FanFilm is currently blocking on.
 *
 * The Python worker thread is parked inside
 * [FanFilmDialogCoordinator.await] until [onRespond] or [onDismiss] is called, so this
 * composable is on the critical path of a provider scan. Two consequences shape it:
 *
 * * Every exit path answers. Back and the explicit cancel both call [onDismiss], which
 *   resolves the request as cancelled rather than leaving the plugin waiting for the
 *   full timeout.
 * * Focus is taken as soon as the dialog appears, because on a TV a dialog nobody can
 *   reach with a D-pad is the same as a hang.
 */
@Composable
fun FanFilmDialogHost(
    request: FanFilmDialogCoordinator.Request?,
    onRespond: (Long, Any?) -> Unit,
    onDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (request == null) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(FoxTvTheme.colors.BackgroundElevated)
                .padding(FoxTvTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.md),
        ) {
            if (request.heading.isNotBlank()) {
                Text(
                    text = request.heading,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = FoxTvTheme.colors.TextPrimary,
                )
            }
            if (request.message.isNotBlank()) {
                Text(
                    text = request.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FoxTvTheme.colors.TextSecondary,
                )
            }

            when (request.kind) {
                FanFilmDialogCoordinator.Kind.OK -> AcknowledgeButtons(request, onRespond)
                FanFilmDialogCoordinator.Kind.YES_NO -> YesNoButtons(request, onRespond, onDismiss)
                FanFilmDialogCoordinator.Kind.SELECT -> SelectList(request, onRespond, onDismiss)
                FanFilmDialogCoordinator.Kind.MULTI_SELECT ->
                    MultiSelectList(request, onRespond, onDismiss)
                FanFilmDialogCoordinator.Kind.INPUT,
                FanFilmDialogCoordinator.Kind.NUMERIC -> TextEntry(request, onRespond, onDismiss)
            }
        }
    }
}

@Composable
private fun AcknowledgeButtons(
    request: FanFilmDialogCoordinator.Request,
    onRespond: (Long, Any?) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(request.id) { focus.requestFocus() }
    Button(
        onClick = { onRespond(request.id, true) },
        modifier = Modifier.focusRequester(focus),
    ) {
        Text(text = stringResource(R.string.action_ok))
    }
}

@Composable
private fun YesNoButtons(
    request: FanFilmDialogCoordinator.Request,
    onRespond: (Long, Any?) -> Unit,
    onDismiss: (Long) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(request.id) { focus.requestFocus() }
    Row(horizontalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.md)) {
        Button(
            onClick = { onRespond(request.id, true) },
            modifier = Modifier.focusRequester(focus),
        ) {
            Text(
                text = request.positiveLabel.ifBlank { stringResource(R.string.action_yes) },
            )
        }
        Button(onClick = { onRespond(request.id, false) }) {
            Text(text = request.negativeLabel.ifBlank { stringResource(R.string.action_no) })
        }
        // Distinct from "No": Kodi treats a dismissal as "the user made no choice",
        // and several FanFilm flows branch on that.
        Button(onClick = { onDismiss(request.id) }) {
            Text(text = stringResource(R.string.action_cancel))
        }
    }
}

@Composable
private fun SelectList(
    request: FanFilmDialogCoordinator.Request,
    onRespond: (Long, Any?) -> Unit,
    onDismiss: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(request.id) {
        request.preselected.firstOrNull()?.let { listState.scrollToItem(it) }
        focus.requestFocus()
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.xs),
    ) {
        itemsIndexed(request.options, key = { index, label -> "$index:$label" }) { index, label ->
            Button(
                onClick = { onRespond(request.id, index) },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (index == request.preselected.firstOrNull()) {
                        Modifier.focusRequester(focus)
                    } else if (request.preselected.isEmpty() && index == 0) {
                        Modifier.focusRequester(focus)
                    } else {
                        Modifier
                    }),
            ) {
                Text(text = label, maxLines = 2)
            }
        }
        item(key = "__cancel") {
            Button(onClick = { onDismiss(request.id) }, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun MultiSelectList(
    request: FanFilmDialogCoordinator.Request,
    onRespond: (Long, Any?) -> Unit,
    onDismiss: (Long) -> Unit,
) {
    val selected = rememberSaveable(request.id) {
        mutableStateOf(request.preselected.toSet())
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(request.id) { focus.requestFocus() }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp),
        verticalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.xs),
    ) {
        itemsIndexed(request.options, key = { index, label -> "$index:$label" }) { index, label ->
            val isSelected = index in selected.value
            Button(
                onClick = {
                    selected.value = if (isSelected) {
                        selected.value - index
                    } else {
                        selected.value + index
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (index == 0) Modifier.focusRequester(focus) else Modifier),
            ) {
                Text(text = if (isSelected) "✓  $label" else label, maxLines = 2)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.md)) {
        Button(onClick = { onRespond(request.id, selected.value.sorted()) }) {
            Text(text = stringResource(R.string.action_ok))
        }
        Button(onClick = { onDismiss(request.id) }) {
            Text(text = stringResource(R.string.action_cancel))
        }
    }
}

@Composable
private fun TextEntry(
    request: FanFilmDialogCoordinator.Request,
    onRespond: (Long, Any?) -> Unit,
    onDismiss: (Long) -> Unit,
) {
    var text by rememberSaveable(request.id) { mutableStateOf(request.defaultValue) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(request.id) { focus.requestFocus() }

    val isPassword = request.inputType == FanFilmDialogCoordinator.InputType.PASSWORD
    val keyboardType = when (request.inputType) {
        FanFilmDialogCoordinator.InputType.NUMBER -> KeyboardType.Number
        FanFilmDialogCoordinator.InputType.PASSWORD -> KeyboardType.Password
        else -> KeyboardType.Text
    }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        visualTransformation = if (isPassword) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(FoxTvTheme.spacing.md)) {
        Button(onClick = { onRespond(request.id, text) }) {
            Text(text = stringResource(R.string.action_ok))
        }
        Button(onClick = { onDismiss(request.id) }) {
            Text(text = stringResource(R.string.action_cancel))
        }
    }
}

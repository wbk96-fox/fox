@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.foxtv.app.ui.screens.account

import com.foxtv.app.ui.theme.FoxTvTheme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

@Composable
internal fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val textFieldFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var isEditing by remember { mutableStateOf(false) }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            textFieldFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Surface(
        onClick = { isEditing = true },
        modifier = modifier,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FoxTvTheme.colors.BackgroundCard,
            focusedContainerColor = FoxTvTheme.colors.BackgroundCard
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(FoxTvTheme.spacing.hairline, FoxTvTheme.colors.Border),
                shape = RoundedCornerShape(FoxTvTheme.radii.md)
            ),
            focusedBorder = Border(
                border = FoxTvTheme.focusRing.border(FoxTvTheme.spacing.xxs),
                shape = RoundedCornerShape(FoxTvTheme.radii.md)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(FoxTvTheme.radii.md)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FoxTvTheme.spacing.lg, vertical = 14.dp)
                .focusRequester(textFieldFocusRequester)
                .onFocusChanged {
                    if (!it.isFocused && isEditing) {
                        isEditing = false
                        keyboardController?.hide()
                    }
                },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    onImeAction()
                    isEditing = false
                    keyboardController?.hide()
                },
                onNext = {
                    onImeAction()
                    isEditing = false
                    keyboardController?.hide()
                }
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = FoxTvTheme.colors.TextPrimary
            ),
            cursorBrush = SolidColor(if (isEditing) FoxTvTheme.colors.Secondary else Color.Transparent),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FoxTvTheme.colors.TextTertiary
                    )
                }
                innerTextField()
            }
        )
    }
}

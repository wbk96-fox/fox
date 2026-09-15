package com.foxtv.app.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import com.foxtv.app.ui.theme.FoxTvComponents

@Immutable
data class PosterCardStyle(
    val width: Dp = FoxTvComponents.tokens.posterCard.width,
    val height: Dp = FoxTvComponents.tokens.posterCard.height,
    val cornerRadius: Dp = FoxTvComponents.tokens.posterCard.cornerRadius,
    val focusedBorderWidth: Dp = FoxTvComponents.tokens.posterCard.focusedBorderWidth,
    val focusedScale: Float = FoxTvComponents.tokens.posterCard.focusedScale
) {
    val aspectRatio: Float
        get() = width.value / height.value
}

object PosterCardDefaults {
    val Style = PosterCardStyle()
}

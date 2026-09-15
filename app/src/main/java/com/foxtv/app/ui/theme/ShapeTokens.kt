package com.foxtv.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class FoxTvRadiusTokens(
    val none: Dp,
    val xxs: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
    val panel: Dp,
    val full: Dp
)

@Immutable
data class FoxTvShapeTokens(
    val posterCard: Shape,
    val backdropCard: Shape,
    val collectionCard: Shape,
    val button: Shape,
    val iconButton: Shape,
    val chip: Shape,
    val badge: Shape,
    val dialog: Shape,
    val sidePanel: Shape,
    val sidebar: Shape,
    val navItem: Shape,
    val progress: Shape,
    val slider: Shape,
    val field: Shape,
    val menu: Shape,
    val circle: Shape
)

object FoxTvRadii {
    val tokens = FoxTvRadiusTokens(
        none = 0.dp,
        xxs = 2.dp,
        xs = 4.dp,
        sm = 8.dp,
        md = 12.dp,
        lg = 14.dp,
        xl = 16.dp,
        xxl = 20.dp,
        panel = 28.dp,
        full = 999.dp
    )
}

object FoxTvShapes {
    val tokens = FoxTvShapeTokens(
        posterCard = RoundedCornerShape(FoxTvRadii.tokens.md),
        backdropCard = RoundedCornerShape(FoxTvRadii.tokens.xl),
        collectionCard = RoundedCornerShape(FoxTvRadii.tokens.xl),
        button = RoundedCornerShape(FoxTvRadii.tokens.md),
        iconButton = RoundedCornerShape(FoxTvRadii.tokens.md),
        chip = RoundedCornerShape(FoxTvRadii.tokens.full),
        badge = RoundedCornerShape(FoxTvRadii.tokens.xs),
        dialog = RoundedCornerShape(FoxTvRadii.tokens.xl),
        sidePanel = RoundedCornerShape(FoxTvRadii.tokens.xxl),
        sidebar = RoundedCornerShape(30.dp),
        navItem = RoundedCornerShape(FoxTvRadii.tokens.full),
        progress = RoundedCornerShape(FoxTvRadii.tokens.xxs),
        slider = RoundedCornerShape(FoxTvRadii.tokens.full),
        field = RoundedCornerShape(FoxTvRadii.tokens.md),
        menu = RoundedCornerShape(FoxTvRadii.tokens.lg),
        circle = CircleShape
    )
}

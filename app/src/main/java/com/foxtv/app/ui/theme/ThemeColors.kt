package com.foxtv.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.foxtv.app.domain.model.AppTheme

data class ThemeColorPalette(
    val secondary: Color = Color(0xFF38BDF8),
    val secondaryVariant: Color = Color(0xFF0284C7),
    val onSecondary: Color = FoxTvPrimitives.white,
    val onSecondaryVariant: Color = FoxTvPrimitives.white,
    val accentGradient: List<Color> = listOf(secondary),
    val focusRing: Color = Color(0xFF38BDF8),
    val focusRingGradient: List<Color> = listOf(focusRing),
    val focusBackground: Color = Color(0xFF1E3A60),
    val background: Color = FoxTvPrimitives.neutral950,
    val backgroundElevated: Color = FoxTvPrimitives.neutral900,
    val backgroundCard: Color = FoxTvPrimitives.neutral825,
    val surface: Color = FoxTvPrimitives.neutral875,
    val surfaceVariant: Color = FoxTvPrimitives.neutral800,
    val panel: Color = FoxTvPrimitives.neutral900,
    val overlay: Color = Color(0xD90B1320),
    val field: Color = FoxTvPrimitives.neutral850,
    val menu: Color = FoxTvPrimitives.neutral875,
    val modal: Color = FoxTvPrimitives.neutral900,
    val playerOverlay: Color = Color(0xCC0B1320)
)

object ThemeColors {
    val Crimson = ThemeColorPalette(
        secondary = FoxTvPrimitives.red500,
        secondaryVariant = FoxTvPrimitives.red600,
        focusRing = FoxTvPrimitives.red300,
        focusBackground = Color(0xFF3D1A1A),
        backgroundCard = Color(0xFF241A1A)
    )

    val Ocean = ThemeColorPalette(
        secondary = Color(0xFF38BDF8),
        secondaryVariant = Color(0xFF0284C7),
        focusRing = Color(0xFF7DD3FC),
        focusBackground = Color(0xFF1E3A60),
        background = Color(0xFF0B1320),
        backgroundElevated = Color(0xFF14233D),
        backgroundCard = Color(0xFF1F375C)
    )

    val Violet = ThemeColorPalette(
        secondary = FoxTvPrimitives.violet500,
        secondaryVariant = FoxTvPrimitives.violet700,
        focusRing = FoxTvPrimitives.violet300,
        focusBackground = Color(0xFF2D1A3D),
        background = Color(0xFF0B1320),
        backgroundElevated = Color(0xFF14233D),
        backgroundCard = Color(0xFF1F1A35)
    )

    val Emerald = ThemeColorPalette(
        secondary = FoxTvPrimitives.green500,
        secondaryVariant = FoxTvPrimitives.green700,
        focusRing = FoxTvPrimitives.green300,
        focusBackground = Color(0xFF1A3D25),
        backgroundCard = Color(0xFF1A2B20)
    )

    val Amber = ThemeColorPalette(
        secondary = FoxTvPrimitives.amber500,
        secondaryVariant = FoxTvPrimitives.amber700,
        focusRing = FoxTvPrimitives.amber300,
        focusBackground = Color(0xFF3D2D1A),
        background = Color(0xFF0B1320),
        backgroundElevated = Color(0xFF14233D),
        backgroundCard = Color(0xFF24201A)
    )

    val Rose = ThemeColorPalette(
        secondary = FoxTvPrimitives.rose500,
        secondaryVariant = FoxTvPrimitives.rose700,
        focusRing = FoxTvPrimitives.rose300,
        focusBackground = Color(0xFF3D1A2D),
        backgroundCard = Color(0xFF241A1F)
    )

    val White = ThemeColorPalette(
        secondary = Color(0xFF38BDF8),
        secondaryVariant = Color(0xFF0284C7),
        onSecondary = FoxTvPrimitives.white,
        onSecondaryVariant = FoxTvPrimitives.white,
        focusRing = Color(0xFF38BDF8),
        focusBackground = Color(0xFF1E3A60),
        background = Color(0xFF0B1320),
        backgroundElevated = Color(0xFF14233D),
        backgroundCard = Color(0xFF1F375C),
        surface = Color(0xFF182A47),
        surfaceVariant = Color(0xFF243E66),
        panel = Color(0xFF14233D),
        field = Color(0xFF1C3152),
        menu = Color(0xFF182A47),
        modal = Color(0xFF14233D)
    )

    fun getColorPalette(theme: AppTheme): ThemeColorPalette {
        return when (theme) {
            AppTheme.GOLD -> SupporterThemeColors.Gold
            AppTheme.JADE -> SupporterThemeColors.Jade
            AppTheme.ROSE_GOLD -> SupporterThemeColors.RoseGold
            AppTheme.ARCTIC_BLUE -> SupporterThemeColors.ArcticBlue
            AppTheme.GRAPHITE -> SupporterThemeColors.Graphite
            AppTheme.CRIMSON -> Crimson
            AppTheme.OCEAN -> Ocean
            AppTheme.VIOLET -> Violet
            AppTheme.EMERALD -> Emerald
            AppTheme.AMBER -> Amber
            AppTheme.ROSE -> Rose
            AppTheme.WHITE -> White
        }
    }
}

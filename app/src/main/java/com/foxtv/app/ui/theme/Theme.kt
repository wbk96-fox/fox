package com.foxtv.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.foxtv.app.domain.model.AppFont
import com.foxtv.app.domain.model.AppTheme
import com.foxtv.app.domain.model.SettingsUiStyle

data class FoxTvExtendedColors(
    val backgroundElevated: Color,
    val backgroundCard: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val focusRing: Color,
    val focusBackground: Color,
    val rating: Color
)

val LocalFoxTvColors = staticCompositionLocalOf {
    FoxTvColorScheme(ThemeColors.Ocean)
}

val LocalFoxTvExtendedColors = staticCompositionLocalOf {
    FoxTvExtendedColors(
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF242424),
        textSecondary = Color(0xFFB3B3B3),
        textTertiary = Color(0xFF808080),
        focusRing = ThemeColors.Ocean.focusRing,
        focusBackground = ThemeColors.Ocean.focusBackground,
        rating = Color(0xFFFFD700)
    )
}

val LocalFoxTvTextStyles = staticCompositionLocalOf { FoxTvTextStyles }

val LocalAppTheme = staticCompositionLocalOf { AppTheme.WHITE }

val LocalSettingsUiStyle = staticCompositionLocalOf { SettingsUiStyle.CLASSIC }

val LocalFoxTvFocusRingStyle = staticCompositionLocalOf {
    createFocusRingStyle(ThemeColors.Ocean)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FoxTvTheme(
    appTheme: AppTheme = AppTheme.WHITE,
    appFont: AppFont = AppFont.INTER,
    amoledMode: Boolean = false,
    amoledSurfacesMode: Boolean = false,
    settingsUiStyle: SettingsUiStyle = SettingsUiStyle.CLASSIC,
    content: @Composable () -> Unit
) {
    val palette = ThemeColors.getColorPalette(appTheme)
    val focusRingStyle = createFocusRingStyle(palette)
    val colorScheme = FoxTvColorScheme(
        palette = palette,
        amoledMode = amoledMode,
        amoledSurfacesMode = amoledSurfacesMode
    )
    val typography = buildFoxTvTypography(getFontFamily(appFont))
    val textStyles = buildFoxTvTextStyles(typography)

    val materialColorScheme = darkColorScheme(
        primary = colorScheme.Primary,
        onPrimary = colorScheme.OnPrimary,
        secondary = colorScheme.Secondary,
        onSecondary = colorScheme.OnSecondary,
        background = colorScheme.Background,
        surface = colorScheme.Surface,
        surfaceVariant = colorScheme.SurfaceVariant,
        onBackground = colorScheme.TextPrimary,
        onSurface = colorScheme.TextPrimary,
        onSurfaceVariant = colorScheme.TextSecondary,
        error = colorScheme.Error
    )

    val extendedColors = FoxTvExtendedColors(
        backgroundElevated = colorScheme.BackgroundElevated,
        backgroundCard = colorScheme.BackgroundCard,
        textSecondary = colorScheme.TextSecondary,
        textTertiary = colorScheme.TextTertiary,
        focusRing = colorScheme.FocusRing,
        focusBackground = colorScheme.FocusBackground,
        rating = colorScheme.Rating
    )

    CompositionLocalProvider(
        LocalFoxTvColors provides colorScheme,
        LocalFoxTvExtendedColors provides extendedColors,
        LocalFoxTvTextStyles provides textStyles,
        LocalAppTheme provides appTheme,
        LocalSettingsUiStyle provides settingsUiStyle,
        LocalFoxTvFocusRingStyle provides focusRingStyle
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = typography,
            content = content
        )
    }
}

object FoxTvTheme {
    val colors: FoxTvColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalFoxTvColors.current

    val extendedColors: FoxTvExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalFoxTvExtendedColors.current

    val textStyles: FoxTvTextStyleTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalFoxTvTextStyles.current

    val spacing: FoxTvSpacingTokens
        get() = FoxTvSpacing.tokens

    val radii: FoxTvRadiusTokens
        get() = FoxTvRadii.tokens

    val shapes: FoxTvShapeTokens
        get() = FoxTvShapes.tokens

    val sizes: FoxTvSizeTokens
        get() = FoxTvSizes.tokens

    val strokes: FoxTvStrokeTokens
        get() = FoxTvStrokes.tokens

    val elevations: FoxTvElevationTokens
        get() = FoxTvElevations.tokens

    val effects: FoxTvEffectTokens
        get() = FoxTvEffects.tokens

    val motion: FoxTvMotionTokens
        get() = FoxTvMotion.tokens

    val focus: FoxTvFocusTokens
        get() = FoxTvFocus.tokens

    val focusRing: FoxTvFocusRingStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalFoxTvFocusRingStyle.current

    val layout: FoxTvLayoutTokens
        get() = FoxTvLayout.tokens

    val media: FoxTvMediaTokens
        get() = FoxTvMedia.tokens

    val components: FoxTvComponentTokens
        get() = FoxTvComponents.tokens

    val currentTheme: AppTheme
        @Composable
        @ReadOnlyComposable
        get() = LocalAppTheme.current

    val settingsUiStyle: SettingsUiStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalSettingsUiStyle.current
}

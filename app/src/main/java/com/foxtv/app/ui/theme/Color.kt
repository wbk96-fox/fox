package com.foxtv.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

@Immutable
data class FoxTvSurfaceColors(
    val background: Color,
    val raised: Color,
    val card: Color,
    val default: Color,
    val variant: Color,
    val panel: Color,
    val overlay: Color,
    val field: Color,
    val menu: Color,
    val modal: Color,
    val playerOverlay: Color,
    val divider: Color
)

@Immutable
data class FoxTvTextColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val disabled: Color,
    val inverse: Color,
    val onAccent: Color,
    val onOverlay: Color,
    val metadata: Color
)

@Immutable
data class FoxTvFocusColors(
    val ring: Color,
    val background: Color,
    val content: Color,
    val accent: Color,
    val scrim: Color
)

@Immutable
data class FoxTvSelectionColors(
    val background: Color,
    val foreground: Color,
    val border: Color,
    val mutedBackground: Color,
    val mutedForeground: Color
)

@Immutable
data class FoxTvMediaColors(
    val heroScrim: Color,
    val imageScrim: Color,
    val posterFallback: Color,
    val videoControlsScrim: Color,
    val glassPanelTop: Color,
    val glassPanelMiddle: Color,
    val glassPanelBottom: Color,
    val glow: Color
)

@Immutable
data class FoxTvStatusColors(
    val rating: Color,
    val error: Color,
    val warning: Color,
    val success: Color,
    val info: Color,
    val watched: Color,
    val unwatched: Color,
    val cached: Color,
    val torrent: Color,
    val premium: Color
)

@Immutable
data class FoxTvDisabledColors(
    val container: Color,
    val content: Color,
    val border: Color,
    val overlay: Color
)

@Immutable
data class FoxTvSourceColors(
    val trakt: Color,
    val tmdb: Color,
    val imdb: Color,
    val mdblist: Color
)

@Immutable
data class FoxTvContrastPair(
    val foreground: Color,
    val background: Color
)

class FoxTvColorScheme(
    palette: ThemeColorPalette,
    amoledMode: Boolean = false,
    amoledSurfacesMode: Boolean = false
) {
    private val pureBlack = FoxTvPrimitives.black
    private val pureBlackSurfaces = amoledMode && amoledSurfacesMode

    val Background = if (amoledMode) pureBlack else palette.background
    val BackgroundElevated = if (pureBlackSurfaces) pureBlack else palette.backgroundElevated
    val BackgroundCard = if (pureBlackSurfaces) pureBlack else palette.backgroundCard
    val Surface = if (pureBlackSurfaces) pureBlack else palette.surface
    val SurfaceVariant = if (pureBlackSurfaces) pureBlack else palette.surfaceVariant
    val Panel = if (pureBlackSurfaces) pureBlack else palette.panel
    val Overlay = palette.overlay
    val Field = if (pureBlackSurfaces) pureBlack else palette.field
    val Menu = if (pureBlackSurfaces) pureBlack else palette.menu
    val Modal = if (pureBlackSurfaces) pureBlack else palette.modal
    val PlayerOverlay = palette.playerOverlay
    val Divider = FoxTvPrimitives.neutral750

    val Primary = FoxTvPrimitives.neutral500
    val PrimaryVariant = FoxTvPrimitives.neutral650
    val OnPrimary = FoxTvPrimitives.white
    val Secondary = palette.secondary
    val SecondaryVariant = palette.secondaryVariant
    val OnSecondary = palette.onSecondary
    val OnSecondaryVariant = palette.onSecondaryVariant

    val TextPrimary = FoxTvPrimitives.white
    val TextSecondary = FoxTvPrimitives.neutral400
    val TextTertiary = FoxTvPrimitives.neutral600
    val TextDisabled = FoxTvPrimitives.neutral700
    val TextInverse = FoxTvPrimitives.neutral925

    val FocusRing = palette.focusRing
    val FocusBackground = palette.focusBackground
    val FocusContent = FoxTvPrimitives.white
    val FocusScrim = FoxTvPrimitives.black.copy(alpha = 0.32f)

    val Rating = FoxTvPrimitives.rating
    val Error = FoxTvPrimitives.error
    val Warning = FoxTvPrimitives.warning
    val Success = FoxTvPrimitives.success
    val Info = FoxTvPrimitives.info
    val Watched = FoxTvPrimitives.success
    val Unwatched = FoxTvPrimitives.neutral600
    val Cached = FoxTvPrimitives.blue300
    val Torrent = FoxTvPrimitives.torrent
    val Premium = FoxTvPrimitives.premium

    val Border = FoxTvPrimitives.neutral750
    val BorderFocused = FocusRing
    val BorderMuted = FoxTvPrimitives.neutral750.copy(alpha = 0.58f)

    val Scrim = FoxTvPrimitives.black.copy(alpha = 0.62f)
    val ImageScrim = FoxTvPrimitives.black.copy(alpha = 0.58f)
    val VideoControlsScrim = FoxTvPrimitives.black.copy(alpha = 0.72f)
    val PosterFallback = BackgroundCard

    val DisabledContainer = SurfaceVariant.copy(alpha = 0.42f)
    val DisabledContent = TextDisabled
    val DisabledBorder = Border.copy(alpha = 0.48f)
    val DisabledOverlay = FoxTvPrimitives.black.copy(alpha = 0.42f)

    val surfaces = FoxTvSurfaceColors(
        background = Background,
        raised = BackgroundElevated,
        card = BackgroundCard,
        default = Surface,
        variant = SurfaceVariant,
        panel = Panel,
        overlay = Overlay,
        field = Field,
        menu = Menu,
        modal = Modal,
        playerOverlay = PlayerOverlay,
        divider = Divider
    )

    val text = FoxTvTextColors(
        primary = TextPrimary,
        secondary = TextSecondary,
        tertiary = TextTertiary,
        disabled = TextDisabled,
        inverse = TextInverse,
        onAccent = OnSecondary,
        onOverlay = FoxTvPrimitives.white,
        metadata = TextSecondary
    )

    val focus = FoxTvFocusColors(
        ring = FocusRing,
        background = FocusBackground,
        content = FocusContent,
        accent = Secondary,
        scrim = FocusScrim
    )

    val selection = FoxTvSelectionColors(
        background = Secondary,
        foreground = OnSecondary,
        border = SecondaryVariant,
        mutedBackground = FocusBackground,
        mutedForeground = TextPrimary
    )

    val media = FoxTvMediaColors(
        heroScrim = Scrim,
        imageScrim = ImageScrim,
        posterFallback = PosterFallback,
        videoControlsScrim = VideoControlsScrim,
        glassPanelTop = Color(0xD61E3A5F),
        glassPanelMiddle = Color(0xCC162D4A),
        glassPanelBottom = Color(0xC611233B),
        glow = FocusRing.copy(alpha = 0.32f)
    )

    val status = FoxTvStatusColors(
        rating = Rating,
        error = Error,
        warning = Warning,
        success = Success,
        info = Info,
        watched = Watched,
        unwatched = Unwatched,
        cached = Cached,
        torrent = Torrent,
        premium = Premium
    )

    val disabled = FoxTvDisabledColors(
        container = DisabledContainer,
        content = DisabledContent,
        border = DisabledBorder,
        overlay = DisabledOverlay
    )

    val source = FoxTvSourceColors(
        trakt = FoxTvPrimitives.trakt,
        tmdb = FoxTvPrimitives.tmdb,
        imdb = FoxTvPrimitives.imdb,
        mdblist = FoxTvPrimitives.mdblist
    )

    val contrastPairs = listOf(
        FoxTvContrastPair(TextPrimary, Background),
        FoxTvContrastPair(TextPrimary, BackgroundCard),
        FoxTvContrastPair(TextSecondary, Background),
        FoxTvContrastPair(OnSecondary, Secondary),
        FoxTvContrastPair(FocusContent, FocusBackground),
        FoxTvContrastPair(FoxTvPrimitives.white, PlayerOverlay)
    )
}

object FoxTvColors {
    val Background: Color
        @Composable
        @ReadOnlyComposable
        get() = FoxTvTheme.colors.Background

    val BackgroundElevated: Color
        @Composable
        @ReadOnlyComposable
        get() = FoxTvTheme.colors.BackgroundElevated

    val BackgroundCard: Color
        @Composable
        @ReadOnlyComposable
        get() = FoxTvTheme.colors.BackgroundCard

    val Surface: Color
        @Composable
        @ReadOnlyComposable
        get() = FoxTvTheme.colors.Surface

    val SurfaceVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = FoxTvTheme.colors.SurfaceVariant

    val Primary = FoxTvPrimitives.neutral500
    val PrimaryVariant = FoxTvPrimitives.neutral650
    val OnPrimary = FoxTvPrimitives.white
    val TextPrimary = FoxTvPrimitives.white
    val TextSecondary = FoxTvPrimitives.neutral400
    val TextTertiary = FoxTvPrimitives.neutral600
    val TextDisabled = FoxTvPrimitives.neutral700
    val Rating = FoxTvPrimitives.rating
    val Error = FoxTvPrimitives.error
    val Success = FoxTvPrimitives.success
    val Warning = FoxTvPrimitives.warning
    val Info = FoxTvPrimitives.info
    val Border = FoxTvPrimitives.neutral750

    val Secondary: Color
        @Composable
        @ReadOnlyComposable
        get() = FoxTvTheme.colors.Secondary

    val SecondaryVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = FoxTvTheme.colors.SecondaryVariant

    val OnSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = FoxTvTheme.colors.OnSecondary

    val OnSecondaryVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = FoxTvTheme.colors.OnSecondaryVariant

    val FocusRing: Color
        @Composable
        @ReadOnlyComposable
        get() = FoxTvTheme.colors.FocusRing

    val FocusBackground: Color
        @Composable
        @ReadOnlyComposable
        get() = FoxTvTheme.colors.FocusBackground

    val BorderFocused: Color
        @Composable
        @ReadOnlyComposable
        get() = FoxTvTheme.colors.BorderFocused
}

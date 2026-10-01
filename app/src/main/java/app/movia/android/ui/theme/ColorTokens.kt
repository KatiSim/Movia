package app.movia.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

internal object MoviaColorPolicy {
    var highContrast by mutableStateOf(false)
}

// MOVIA VISUAL BASELINE V1 — semantic color tokens.
val MoviaBackgroundPrimary: Color get() = Color(0xFF080B11)

val MoviaSurfacePrimary: Color get() = Color(0xFF0E131B)
val MoviaSurfaceSecondary: Color get() = Color(0xFF141A24)
val MoviaSurfaceElevated: Color get() = Color(0xFF1A2130)

val MoviaTextPrimary: Color get() = Color(0xFFF5F7FB)
val MoviaTextSecondary: Color get() = if (MoviaColorPolicy.highContrast) Color(0xFFE3E8F2) else Color(0xFFAEB6C5)
val MoviaTextTertiary: Color get() = if (MoviaColorPolicy.highContrast) Color(0xFFCDD5E5) else Color(0xFF8791A3)
val MoviaTextDisabled: Color get() = MoviaTextSecondary.copy(alpha = 0.38f)

val MoviaAccent: Color get() = Color(0xFFFFB73A)
val MoviaAccentPressed: Color get() = MoviaAccent

val MoviaSuccess: Color get() = Color(0xFF5BC58C)
val MoviaWarning: Color get() = MoviaAccent
val MoviaError: Color get() = Color(0xFFFF7272)
val MoviaInfo: Color get() = Color(0xFF6EA8FE)

val MoviaAlphaMaskOpaque: Color get() = Color.White
val MoviaBorderSubtle: Color get() = MoviaAlphaMaskOpaque.copy(alpha = if (MoviaColorPolicy.highContrast) 0.38f else 0.08f)
val MoviaBorderMedium: Color get() = MoviaAlphaMaskOpaque.copy(alpha = if (MoviaColorPolicy.highContrast) 0.48f else 0.12f)
val MoviaDividerSubtle: Color get() = MoviaBorderSubtle

val MoviaIconPrimary: Color get() = MoviaTextPrimary
val MoviaIconSecondary: Color get() = MoviaTextSecondary
val MoviaIconDisabled: Color get() = MoviaTextSecondary.copy(alpha = 0.38f)

val MoviaSelected: Color get() = MoviaAccent
val MoviaProgressActive: Color get() = MoviaAccent
val MoviaDisabledContent: Color get() = MoviaTextSecondary.copy(alpha = 0.38f)
val MoviaDisabledSurface: Color get() = MoviaAlphaMaskOpaque.copy(alpha = 0.05f)

val MoviaScrimSoft: Color get() = MoviaBackgroundPrimary.copy(alpha = 0.32f)
val MoviaScrimMedium: Color get() = MoviaBackgroundPrimary.copy(alpha = 0.56f)
val MoviaScrimStrong: Color get() = MoviaBackgroundPrimary.copy(alpha = 0.80f)
val MoviaOverlayDark: Color get() = MoviaScrimMedium

// Compatibility aliases preserve existing component structure while centralizing color ownership.
val MoviaBackgroundSecondary: Color get() = MoviaSurfacePrimary
val MoviaBrandAmber: Color get() = MoviaAccent
val MoviaPrimaryAccentHover: Color get() = MoviaAccentPressed
val MoviaSuccessRating: Color get() = MoviaAccent
val MoviaOnBrandAmber: Color get() = MoviaBackgroundPrimary
val MoviaDanger: Color get() = MoviaError
val MoviaBorderFocused: Color get() = MoviaAccent.copy(alpha = 0.40f)

val MoviaScrim40: Color get() = MoviaBackgroundPrimary.copy(alpha = 0.40f)
val MoviaScrim60: Color get() = MoviaBackgroundPrimary.copy(alpha = 0.60f)
val MoviaScrim70: Color get() = MoviaBackgroundPrimary.copy(alpha = 0.70f)
val MoviaShadow50: Color get() = MoviaBackgroundPrimary.copy(alpha = 0.50f)
val MoviaHighlight15: Color get() = MoviaTextPrimary.copy(alpha = 0.15f)

val MoviaHeroTextSecondary: Color get() = MoviaTextSecondary
val MoviaMetadataText: Color get() = Color(0xFFD9DDE6)
val MoviaArtworkScrimClear: Color get() = MoviaBackgroundPrimary.copy(alpha = 0f)
val MoviaArtworkScrimMid: Color get() = MoviaBackgroundPrimary.copy(alpha = 0.60f)
val MoviaArtworkScrimStrong: Color get() = MoviaBackgroundPrimary.copy(alpha = 0.95f)
val MoviaProgressTrack: Color get() = MoviaBorderMedium
val MoviaRatingBadgeBackground: Color get() = MoviaAccent.copy(alpha = 0.15f)
val MoviaPosterBadgeText: Color get() = MoviaOnBrandAmber

val MoviaHeroPlaceholderStart: Color get() = MoviaSurfaceSecondary
val MoviaHeroPlaceholderEnd: Color get() = MoviaBackgroundPrimary
val MoviaPosterPlaceholder: Color get() = MoviaSurfaceSecondary

// Existing Haze geometry/blur is unchanged; only the glass tint moves to the baseline palette.
val MoviaNavGlassSurface: Color get() = MoviaBackgroundPrimary.copy(alpha = 0.76f)
val MoviaNavTopBorder: Color get() = MoviaBorderSubtle
val MoviaGlowLuminescence: Color get() = MoviaAccent.copy(alpha = 0.12f)
val MoviaGlowLuminescenceClear: Color get() = MoviaAccent.copy(alpha = 0f)
val MoviaGlowLuminescenceOpaque: Color get() = MoviaAccent
val MoviaAccentGlow: Color get() = MoviaGlowLuminescence
val MoviaNavActiveGlow: Color get() = MoviaGlowLuminescence
val MoviaNavActiveGlowClear: Color get() = MoviaGlowLuminescenceClear
val MoviaHeroGlow: Color get() = MoviaGlowLuminescence
val MoviaHeroTextShadow: Color get() = MoviaScrim70
val MoviaPlayBackground: Color get() = MoviaSurfaceSecondary
val MoviaPlayShadow: Color get() = MoviaShadow50
val MoviaPlayHighlight: Color get() = MoviaHighlight15

val MoviaLibraryIconTile: Color get() = MoviaSurfaceSecondary
val MoviaLibraryIconPlay: Color get() = MoviaOnBrandAmber

// Existing logo gradient character is preserved; stops are centralized tokens.
val MoviaLogoGradientStart: Color get() = MoviaAccent
val MoviaLogoGradientSoftGold: Color get() = Color(0xFFFFC65B)
val MoviaLogoGradientPastelGold: Color get() = Color(0xFFFFD47D)
val MoviaLogoGradientLightBronze: Color get() = Color(0xFFFFDEA0)
val MoviaLogoGradientCream: Color get() = Color(0xFFFFE8BE)
val MoviaLogoGradientIvory: Color get() = Color(0xFFFFF0D8)
val MoviaLogoGradientMilk: Color get() = Color(0xFFFFF6E9)
val MoviaLogoGradientEnd: Color get() = MoviaTextPrimary

internal val MoviaDarkSurfaceCanvas: Color get() = MoviaBackgroundPrimary
internal val MoviaDarkSurfaceDeep: Color get() = MoviaSurfacePrimary
internal val MoviaDarkSurfaceCard: Color get() = MoviaSurfacePrimary
internal val MoviaDarkSurfaceSecondary: Color get() = MoviaSurfaceSecondary
internal val MoviaDarkSurfaceElevated: Color get() = MoviaSurfaceElevated
internal val MoviaDarkTextPrimary: Color get() = MoviaTextPrimary
internal val MoviaDarkTextSecondary: Color get() = MoviaTextSecondary
internal val MoviaDarkTextMuted: Color get() = MoviaTextTertiary
internal val MoviaDarkTextDisabled: Color get() = MoviaTextDisabled
internal val MoviaDarkAccentText: Color get() = MoviaAccent

internal val MoviaLightSurfaceCanvas: Color get() = MoviaDarkSurfaceCanvas
internal val MoviaLightSurfaceCard: Color get() = MoviaDarkSurfaceCard
internal val MoviaLightSurfaceElevated: Color get() = MoviaDarkSurfaceElevated
internal val MoviaLightTextPrimary: Color get() = MoviaDarkTextPrimary
internal val MoviaLightTextSecondary: Color get() = MoviaDarkTextSecondary
internal val MoviaLightTextMuted: Color get() = MoviaDarkTextMuted
internal val MoviaLightAccentText: Color get() = MoviaAccent

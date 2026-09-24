package app.movia.android.ui.theme

import androidx.compose.ui.graphics.Color

// MOVIA VISUAL BASELINE V1 — semantic color tokens.
val MoviaBackgroundPrimary = Color(0xFF080B11)

val MoviaSurfacePrimary = Color(0xFF0E131B)
val MoviaSurfaceSecondary = Color(0xFF141A24)
val MoviaSurfaceElevated = Color(0xFF1A2130)

val MoviaTextPrimary = Color(0xFFF5F7FB)
val MoviaTextSecondary = Color(0xFFAEB6C5)
val MoviaTextTertiary = Color(0xFF7E8798)
val MoviaTextDisabled = MoviaTextSecondary.copy(alpha = 0.38f)

val MoviaAccent = Color(0xFFFFB73A)
val MoviaAccentPressed = MoviaAccent

val MoviaSuccess = Color(0xFF5BC58C)
val MoviaWarning = MoviaAccent
val MoviaError = Color(0xFFFF7272)
val MoviaInfo = Color(0xFF6EA8FE)

val MoviaAlphaMaskOpaque = Color.White
val MoviaBorderSubtle = MoviaAlphaMaskOpaque.copy(alpha = 0.08f)
val MoviaBorderMedium = MoviaAlphaMaskOpaque.copy(alpha = 0.12f)
val MoviaDividerSubtle = MoviaBorderSubtle

val MoviaIconPrimary = MoviaTextPrimary
val MoviaIconSecondary = MoviaTextSecondary
val MoviaIconDisabled = MoviaTextSecondary.copy(alpha = 0.38f)

val MoviaSelected = MoviaAccent
val MoviaProgressActive = MoviaAccent
val MoviaDisabledContent = MoviaTextSecondary.copy(alpha = 0.38f)
val MoviaDisabledSurface = MoviaAlphaMaskOpaque.copy(alpha = 0.05f)

val MoviaScrimSoft = MoviaBackgroundPrimary.copy(alpha = 0.32f)
val MoviaScrimMedium = MoviaBackgroundPrimary.copy(alpha = 0.56f)
val MoviaScrimStrong = MoviaBackgroundPrimary.copy(alpha = 0.80f)
val MoviaOverlayDark = MoviaScrimMedium

// Compatibility aliases preserve existing component structure while centralizing color ownership.
val MoviaBackgroundSecondary = MoviaSurfacePrimary
val MoviaBrandAmber = MoviaAccent
val MoviaPrimaryAccentHover = MoviaAccentPressed
val MoviaSuccessRating = MoviaAccent
val MoviaOnBrandAmber = MoviaBackgroundPrimary
val MoviaDanger = MoviaError
val MoviaBorderFocused = MoviaAccent.copy(alpha = 0.40f)

val MoviaScrim40 = MoviaBackgroundPrimary.copy(alpha = 0.40f)
val MoviaScrim60 = MoviaBackgroundPrimary.copy(alpha = 0.60f)
val MoviaScrim70 = MoviaBackgroundPrimary.copy(alpha = 0.70f)
val MoviaShadow50 = MoviaBackgroundPrimary.copy(alpha = 0.50f)
val MoviaHighlight15 = MoviaTextPrimary.copy(alpha = 0.15f)

val MoviaHeroTextSecondary = MoviaTextSecondary
val MoviaMetadataText = MoviaTextSecondary
val MoviaArtworkScrimClear = MoviaBackgroundPrimary.copy(alpha = 0f)
val MoviaArtworkScrimMid = MoviaBackgroundPrimary.copy(alpha = 0.60f)
val MoviaArtworkScrimStrong = MoviaBackgroundPrimary.copy(alpha = 0.95f)
val MoviaProgressTrack = MoviaBorderMedium
val MoviaRatingBadgeBackground = MoviaAccent.copy(alpha = 0.15f)
val MoviaPosterBadgeText = MoviaOnBrandAmber

val MoviaHeroPlaceholderStart = MoviaSurfaceSecondary
val MoviaHeroPlaceholderEnd = MoviaBackgroundPrimary
val MoviaPosterPlaceholder = MoviaSurfaceSecondary

// Existing Haze geometry/blur is unchanged; only the glass tint moves to the baseline palette.
val MoviaNavGlassSurface = MoviaBackgroundPrimary.copy(alpha = 0.76f)
val MoviaNavTopBorder = MoviaBorderSubtle
val MoviaGlowLuminescence = MoviaAccent.copy(alpha = 0.12f)
val MoviaGlowLuminescenceClear = MoviaAccent.copy(alpha = 0f)
val MoviaGlowLuminescenceOpaque = MoviaAccent
val MoviaAccentGlow = MoviaGlowLuminescence
val MoviaNavActiveGlow = MoviaGlowLuminescence
val MoviaNavActiveGlowClear = MoviaGlowLuminescenceClear
val MoviaHeroGlow = MoviaGlowLuminescence
val MoviaHeroTextShadow = MoviaScrim70
val MoviaPlayBackground = MoviaSurfaceSecondary
val MoviaPlayShadow = MoviaShadow50
val MoviaPlayHighlight = MoviaHighlight15

val MoviaLibraryIconTile = MoviaSurfaceSecondary
val MoviaLibraryIconPlay = MoviaOnBrandAmber

// Existing logo gradient character is preserved; stops are centralized tokens.
val MoviaLogoGradientStart = MoviaAccent
val MoviaLogoGradientSoftGold = Color(0xFFFFC65B)
val MoviaLogoGradientPastelGold = Color(0xFFFFD47D)
val MoviaLogoGradientLightBronze = Color(0xFFFFDEA0)
val MoviaLogoGradientCream = Color(0xFFFFE8BE)
val MoviaLogoGradientIvory = Color(0xFFFFF0D8)
val MoviaLogoGradientMilk = Color(0xFFFFF6E9)
val MoviaLogoGradientEnd = MoviaTextPrimary

internal val MoviaDarkSurfaceCanvas = MoviaBackgroundPrimary
internal val MoviaDarkSurfaceDeep = MoviaSurfacePrimary
internal val MoviaDarkSurfaceCard = MoviaSurfacePrimary
internal val MoviaDarkSurfaceSecondary = MoviaSurfaceSecondary
internal val MoviaDarkSurfaceElevated = MoviaSurfaceElevated
internal val MoviaDarkTextPrimary = MoviaTextPrimary
internal val MoviaDarkTextSecondary = MoviaTextSecondary
internal val MoviaDarkTextMuted = MoviaTextTertiary
internal val MoviaDarkTextDisabled = MoviaTextDisabled
internal val MoviaDarkAccentText = MoviaAccent

internal val MoviaLightSurfaceCanvas = MoviaDarkSurfaceCanvas
internal val MoviaLightSurfaceCard = MoviaDarkSurfaceCard
internal val MoviaLightSurfaceElevated = MoviaDarkSurfaceElevated
internal val MoviaLightTextPrimary = MoviaDarkTextPrimary
internal val MoviaLightTextSecondary = MoviaDarkTextSecondary
internal val MoviaLightTextMuted = MoviaDarkTextMuted
internal val MoviaLightAccentText = MoviaAccent

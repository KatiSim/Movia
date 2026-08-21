package app.movia.android.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// MOVIA DESIGN TOKENS: DARK-FIRST GOLD / SLATE
// UI components consume MaterialTheme roles or these semantic tokens only.
// ============================================================================

val MoviaBrandAmber = Color(0xFFD4AF37)            // primary-accent
val MoviaPrimaryAccentHover = Color(0xFFB8912A)    // primary-accent-hover
val MoviaSuccessRating = MoviaBrandAmber
val MoviaOnBrandAmber = Color(0xFF0E1015)          // CTA text on gold

val MoviaGlowLuminescence = Color(0x47F2CF5F)     // rgba(242,207,95,.28)
val MoviaGlowLuminescenceClear = Color(0x00F2CF5F)
val MoviaGlowLuminescenceOpaque = Color(0xFFF2CF5F)
val MoviaAccentGlow = MoviaGlowLuminescence

val MoviaBorderSubtle = Color(0xFF2A2F3D)
val MoviaBorderFocused = Color(0x66D4AF37)         // accent 40%
val MoviaDividerSubtle = MoviaBorderSubtle

val MoviaScrim40 = Color(0x66000000)
val MoviaScrim60 = Color(0x99000000)
val MoviaScrim70 = Color(0xB3000000)
val MoviaShadow50 = Color(0x80000000)
val MoviaHighlight15 = Color(0x26FFFFFF)

val MoviaHeroTextSecondary = Color(0xFF8F96A3)
val MoviaMetadataText = Color(0xFF8F96A3)
val MoviaArtworkScrimClear = Color(0x000E1015)
val MoviaArtworkScrimMid = Color(0x990E1015)
val MoviaArtworkScrimStrong = Color(0xF20E1015)
val MoviaProgressTrack = Color(0xFF6B7280)
val MoviaRatingBadgeBackground = Color(0x26D4AF37) // accent 15%
val MoviaPosterBadgeText = MoviaOnBrandAmber

// Artwork placeholders: used only when no real poster/backdrop source is available.
val MoviaHeroPlaceholderStart = Color(0xFF1A1A1A)
val MoviaHeroPlaceholderEnd = Color(0xFF0D0D0D)
val MoviaPosterPlaceholder = Color(0xFF1A1A1A)

// Derived surfaces/effects stay inside the requested palette family.
val MoviaNavGlassSurface = Color(0xD1121212)       // proven 0.2.60 glass: rgba(18,18,18,.82)
val MoviaNavTopBorder = Color(0x14FFFFFF)           // restrained white bevel, ~8%
val MoviaNavActiveGlow = MoviaGlowLuminescence
val MoviaNavActiveGlowClear = MoviaGlowLuminescenceClear
val MoviaHeroGlow = MoviaGlowLuminescence
val MoviaHeroTextShadow = MoviaScrim70
val MoviaPlayBackground = Color(0xFF222631)
val MoviaPlayShadow = MoviaShadow50
val MoviaPlayHighlight = MoviaHighlight15

// Minimal Media Library icon remains flat, now using palette-native graphite.
val MoviaLibraryIconTile = Color(0xFF666A73)
val MoviaLibraryIconPlay = MoviaOnBrandAmber

// Preserve the requested premium logo gradient, recolored strictly into the new palette.
val MoviaLogoGradientStart = MoviaBrandAmber       // 0–10%
val MoviaLogoGradientSoftGold = Color(0xFFDCC165)   // 20%
val MoviaLogoGradientPastelGold = Color(0xFFE2C562) // 30%
val MoviaLogoGradientLightBronze = Color(0xFFE8D081)// 40%
val MoviaLogoGradientCream = Color(0xFFEFE2BA)      // 50%
val MoviaLogoGradientIvory = Color(0xFFF3EAD1)      // 60%
val MoviaLogoGradientMilk = Color(0xFFF8F4E6)       // 70%
val MoviaLogoGradientEnd = Color(0xFFFFFFFF)        // 80–100%

internal val MoviaDarkSurfaceCanvas = Color(0xFF0E1015)
internal val MoviaDarkSurfaceCard = Color(0xFF181B22)
internal val MoviaDarkSurfaceElevated = Color(0xFF222631)
internal val MoviaDarkTextPrimary = Color(0xFFFFFFFF)
internal val MoviaDarkTextSecondary = Color(0xFF8F96A3)
internal val MoviaDarkTextMuted = Color(0xFF6B7280)
internal val MoviaDarkAccentText = MoviaBrandAmber

// Runtime is dark-only. Dormant aliases deliberately stay in the same palette so
// no unrelated legacy color family survives in source or future accidental use.
internal val MoviaLightSurfaceCanvas = MoviaDarkSurfaceCanvas
internal val MoviaLightSurfaceCard = MoviaDarkSurfaceCard
internal val MoviaLightSurfaceElevated = MoviaDarkSurfaceElevated
internal val MoviaLightTextPrimary = MoviaDarkTextPrimary
internal val MoviaLightTextSecondary = MoviaDarkTextSecondary
internal val MoviaLightTextMuted = MoviaDarkTextMuted
internal val MoviaLightAccentText = MoviaBrandAmber

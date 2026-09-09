package app.movia.android.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// MOVIA CINEMATIC PALETTE — DARK-FIRST, NEUTRAL-COOL SURFACES / WARM GOLD LIGHT
// All screens consume MaterialTheme roles or semantic tokens from this file.
// Core palette is intentionally small so artwork remains the visual focus.
// ============================================================================

// Core surfaces from the approved Movia cinematic palette.
val MoviaSurfaceCanvas = Color(0xFF0E1015)         // primary background
val MoviaSurfaceCard = Color(0xFF191B22)           // card background
val MoviaSurfaceElevated = Color(0xFF22242C)       // raised controls / sheets
val MoviaBorderSubtle = Color(0xFF2A2F3D)          // border / separator
val MoviaDividerSubtle = MoviaBorderSubtle

// Warm light axis: brand gold -> neon gold -> warm ivory white.
val MoviaBrandAmber = Color(0xFFD4AF37)            // accent gold
val MoviaPrimaryAccentHover = Color(0xFFB8912A)    // darker pressed/hover gold
val MoviaNeonGold = Color(0xFFF2CF5F)              // luminous gold core
val MoviaTextPrimary = Color(0xFFF8F4E6)           // warm ivory primary text
val MoviaTextSecondary = Color(0xFFA0A6B2)         // secondary text
val MoviaTextMuted = Color(0xFF788191)             // muted metadata / inactive
val MoviaOnBrandAmber = MoviaSurfaceCanvas          // high-contrast content on gold
val MoviaSuccessRating = MoviaBrandAmber

// Focus and glow are derived only from approved golds.
val MoviaBorderFocused = Color(0x66D4AF37)         // accent gold 40%
val MoviaGlowLuminescence = Color(0x47F2CF5F)      // neon gold 28%
val MoviaGlowLuminescenceClear = Color(0x00F2CF5F)
val MoviaGlowLuminescenceOpaque = MoviaNeonGold
val MoviaAccentGlow = MoviaGlowLuminescence

// Cinematic overlays use the slate-black canvas rather than unrelated pure black.
val MoviaScrim40 = Color(0x660E1015)
val MoviaScrim60 = Color(0x990E1015)
val MoviaScrim70 = Color(0xB30E1015)
val MoviaShadow50 = Color(0x800E1015)
val MoviaHighlight15 = Color(0x26F8F4E6)           // warm-white highlight 15%

// Shared media semantics.
val MoviaHeroTextSecondary = MoviaTextSecondary
val MoviaMetadataText = MoviaTextSecondary
val MoviaArtworkScrimClear = Color(0x000E1015)
val MoviaArtworkScrimMid = Color(0x990E1015)
val MoviaArtworkScrimStrong = Color(0xF20E1015)
val MoviaProgressTrack = MoviaTextMuted
val MoviaRatingBadgeBackground = Color(0x26D4AF37) // accent gold 15%
val MoviaPosterBadgeText = MoviaOnBrandAmber

// Artwork placeholders remain in the same cool surface family.
val MoviaHeroPlaceholderStart = MoviaSurfaceElevated
val MoviaHeroPlaceholderEnd = MoviaSurfaceCanvas
val MoviaPosterPlaceholder = MoviaSurfaceCard

// Navigation/player glass and glow.
val MoviaNavGlassSurface = Color(0xD1191B22)        // card surface 82%
val MoviaNavTopBorder = Color(0x14F8F4E6)          // warm-white bevel ~8%
val MoviaNavActiveGlow = MoviaGlowLuminescence
val MoviaNavActiveGlowClear = MoviaGlowLuminescenceClear
val MoviaHeroGlow = MoviaGlowLuminescence
val MoviaHeroTextShadow = MoviaScrim70
val MoviaPlayBackground = MoviaSurfaceElevated
val MoviaPlayShadow = MoviaShadow50
val MoviaPlayHighlight = MoviaHighlight15

// Minimal Media Library icon uses palette-native muted slate.
val MoviaLibraryIconTile = MoviaTextMuted
val MoviaLibraryIconPlay = MoviaOnBrandAmber

// Premium logo gradient stays on the same warm 46–47° visual axis.
val MoviaLogoGradientStart = MoviaBrandAmber
val MoviaLogoGradientSoftGold = Color(0xFFDCC165)
val MoviaLogoGradientPastelGold = Color(0xFFE2C562)
val MoviaLogoGradientLightBronze = Color(0xFFE8D081)
val MoviaLogoGradientCream = Color(0xFFEFE2BA)
val MoviaLogoGradientIvory = Color(0xFFF3EAD1)
val MoviaLogoGradientMilk = MoviaTextPrimary
val MoviaLogoGradientEnd = MoviaTextPrimary

// Material color-scheme aliases. Runtime remains dark-first; dormant light aliases
// deliberately resolve to the same palette so no unrelated color family leaks in.
internal val MoviaDarkSurfaceCanvas = MoviaSurfaceCanvas
internal val MoviaDarkSurfaceCard = MoviaSurfaceCard
internal val MoviaDarkSurfaceElevated = MoviaSurfaceElevated
internal val MoviaDarkTextPrimary = MoviaTextPrimary
internal val MoviaDarkTextSecondary = MoviaTextSecondary
internal val MoviaDarkTextMuted = MoviaTextMuted
internal val MoviaDarkAccentText = MoviaBrandAmber

internal val MoviaLightSurfaceCanvas = MoviaSurfaceCanvas
internal val MoviaLightSurfaceCard = MoviaSurfaceCard
internal val MoviaLightSurfaceElevated = MoviaSurfaceElevated
internal val MoviaLightTextPrimary = MoviaTextPrimary
internal val MoviaLightTextSecondary = MoviaTextSecondary
internal val MoviaLightTextMuted = MoviaTextMuted
internal val MoviaLightAccentText = MoviaBrandAmber

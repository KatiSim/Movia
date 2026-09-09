package app.movia.android.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorTokensTest {
    @Test
    fun `approved cinematic palette is exact`() {
        assertEquals(0xFF0E1015.toInt(), MoviaSurfaceCanvas.toArgb())
        assertEquals(0xFF191B22.toInt(), MoviaSurfaceCard.toArgb())
        assertEquals(0xFF22242C.toInt(), MoviaSurfaceElevated.toArgb())
        assertEquals(0xFF2A2F3D.toInt(), MoviaBorderSubtle.toArgb())
        assertEquals(0xFFD4AF37.toInt(), MoviaBrandAmber.toArgb())
        assertEquals(0xFFF2CF5F.toInt(), MoviaNeonGold.toArgb())
        assertEquals(0xFFF8F4E6.toInt(), MoviaTextPrimary.toArgb())
        assertEquals(0xFFA0A6B2.toInt(), MoviaTextSecondary.toArgb())
        assertEquals(0xFF788191.toInt(), MoviaTextMuted.toArgb())
    }

    @Test
    fun `material aliases and prominent effects stay on palette`() {
        assertEquals(MoviaTextPrimary.toArgb(), MoviaDarkTextPrimary.toArgb())
        assertEquals(MoviaSurfaceCard.toArgb(), MoviaDarkSurfaceCard.toArgb())
        assertEquals(MoviaSurfaceElevated.toArgb(), MoviaDarkSurfaceElevated.toArgb())
        assertEquals(MoviaNeonGold.toArgb(), MoviaGlowLuminescenceOpaque.toArgb())
        assertEquals(MoviaTextPrimary.toArgb(), MoviaLogoGradientEnd.toArgb())
        assertEquals(MoviaTextMuted.toArgb(), MoviaLibraryIconTile.toArgb())
    }
}

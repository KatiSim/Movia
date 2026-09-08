package app.movia.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoviaBottomNavigationContractTest {
    private val source by lazy {
        java.io.File("src/main/java/app/movia/android/ui/MoviaApp.kt").readText()
    }

    @Test
    fun bottomBarOwnsOneContinuousSurface() {
        val start = source.indexOf("private fun MoviaBottomNavigation(")
        val end = source.indexOf("private fun MoviaBottomNavIcon(", start)
        assertTrue(start >= 0 && end > start)
        val block = source.substring(start, end)

        assertTrue(block.contains(".background(MoviaNavGlassSurface)"))
        assertTrue(block.contains("color = MoviaNavTopBorder"))
        assertTrue(block.contains("interactionSource = interactionSource"))
        assertTrue(block.contains("indication = null"))
        assertFalse(block.contains("NavigationBarItem("))
        assertFalse(block.contains("indicatorColor ="))
    }
}

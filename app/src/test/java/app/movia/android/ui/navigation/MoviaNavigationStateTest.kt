package app.movia.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class MoviaNavigationStateTest {
    @Test
    fun backPopsPlayerBeforeDetails() {
        val home = MoviaNavigationState()
        val details = home.push(MoviaRoute.Details(mediaId = "movie-42", title = "Arrival"))
        val player = details.push(MoviaRoute.Player)

        assertEquals(details, player.pop())
        assertEquals(home, details.pop())
    }

    @Test
    fun tabSelectionPreservesOverlayStateButExplicitNavigationClearsIt() {
        val details = MoviaNavigationState()
            .push(MoviaRoute.Details(mediaId = "movie-42", title = "Arrival"))

        val selected = details.selectTab(MoviaTopLevel.CATALOG)
        assertEquals(MoviaTopLevel.CATALOG, selected.selectedTab)
        assertEquals(details.backStack, selected.backStack)

        val navigated = selected.navigateToTab(MoviaTopLevel.LIBRARY)
        assertEquals(MoviaTopLevel.LIBRARY, navigated.selectedTab)
        assertEquals(emptyList<MoviaRoute>(), navigated.backStack)
    }
}

package app.movia.android.ui.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRetentionStateTest {
    @Test
    fun captureStoresExactRoutePagesAndPixelAnchor() {
        val state = CatalogRetentionState()

        state.capture(
            currentRequestKey = "SERIES|Drama|POPULAR",
            currentItemIds = "a|b|c|d",
            currentTotalCount = 412,
            currentHasMore = true,
            visibleItemIndex = 27,
            visibleItemScrollOffset = 143,
        )

        assertEquals("SERIES|Drama|POPULAR", state.requestKey)
        assertEquals("a|b|c|d", state.itemIds)
        assertEquals(412, state.totalCount)
        assertTrue(state.hasMore)
        assertEquals(27, state.firstVisibleItemIndex)
        assertEquals(143, state.firstVisibleItemScrollOffset)
    }

    @Test
    fun captureClampsInvalidNegativeAnchor() {
        val state = CatalogRetentionState()
        state.capture("all", "a", 1, false, -7, -90)

        assertEquals(0, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
        assertFalse(state.hasMore)
    }

    @Test
    fun catalogCardClickSynchronouslyCapturesBeforeOpeningDetails() {
        val source = java.io.File("src/main/java/app/movia/android/ui/catalog/CatalogScreen.kt").readText()
        val helperStart = source.indexOf("fun openDetailsPreservingCatalogPosition(item: MediaContent)")
        val helperEnd = source.indexOf("// Capture the exact grid position", helperStart)
        assertTrue(helperStart >= 0 && helperEnd > helperStart)
        val helper = source.substring(helperStart, helperEnd)

        assertTrue(helper.indexOf("retention.capture(") < helper.indexOf("onOpenDetails(item)"))
        assertTrue(helper.contains("gridState.firstVisibleItemIndex"))
        assertTrue(helper.contains("gridState.firstVisibleItemScrollOffset"))
        assertTrue(source.countOccurrences("openDetailsPreservingCatalogPosition(item)") >= 2)
    }

    @Test
    fun searchResultsRestoreRouteAnchorAfterReload() {
        val source = java.io.File("src/main/java/app/movia/android/ui/catalog/CatalogScreen.kt").readText()
        val searchStart = source.indexOf("if (searchQuery.isNotBlank())")
        val restore = source.indexOf("if (retention.requestKey == requestKey && results.isNotEmpty())", searchStart)
        val earlyReturn = source.indexOf("return@LaunchedEffect", searchStart)

        assertTrue(restore > searchStart)
        assertTrue(earlyReturn > restore)
    }

    @Test
    fun resetTriggerIsConsumedOnlyOnceAcrossCatalogRecomposition() {
        val state = CatalogRetentionState()

        assertTrue(state.shouldHandleReset(1))
        assertFalse(state.shouldHandleReset(1))
        assertFalse(state.shouldHandleReset(0))
        assertTrue(state.shouldHandleReset(2))
        assertFalse(state.shouldHandleReset(2))
    }

    @Test
    fun catalogGridStateIsHoistedAboveDetailsRoute() {
        val app = java.io.File("src/main/java/app/movia/android/ui/MoviaApp.kt").readText()
        val catalog = java.io.File("src/main/java/app/movia/android/ui/catalog/CatalogScreen.kt").readText()

        assertTrue(app.contains("val catalogGridState = rememberSaveable(saver = LazyGridState.Saver)"))
        assertTrue(app.contains("gridState = catalogGridState"))
        assertFalse(catalog.contains("val gridState = rememberSaveable(saver = LazyGridState.Saver)"))
        assertTrue(catalog.contains("retention.shouldHandleReset(resetTrigger)"))
    }
}

private fun String.countOccurrences(value: String): Int {
    if (value.isEmpty()) return 0
    var count = 0
    var start = 0
    while (true) {
        val index = indexOf(value, start)
        if (index < 0) return count
        count++
        start = index + value.length
    }
}

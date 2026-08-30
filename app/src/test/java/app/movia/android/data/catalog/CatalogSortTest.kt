package app.movia.android.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSortTest {
    private val items = DemoCatalogRepository.all()

    @Test
    fun ratingSortIsDescending() {
        val ratings = sortCatalog(items, CatalogSort.RATING).map { it.rating }
        assertEquals(ratings.sortedDescending(), ratings)
    }

    @Test
    fun newestAndOldestUseYearDirection() {
        val newest = sortCatalog(items, CatalogSort.NEWEST).map { it.year }
        val oldest = sortCatalog(items, CatalogSort.OLDEST).map { it.year }
        assertEquals(newest.sortedDescending(), newest)
        assertEquals(oldest.sorted(), oldest)
    }

    @Test
    fun titleSortIsAlphabetical() {
        val titles = sortCatalog(items, CatalogSort.TITLE).map { it.title.lowercase() }
        assertEquals(titles.sorted(), titles)
    }

    @Test
    fun resolutionFilterDoesNotPretendHdrIsResolution() {
        val result = filterCatalog(items, CatalogFilter(type = null, resolution = "4K"))
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.quality == "4K" })
    }
}

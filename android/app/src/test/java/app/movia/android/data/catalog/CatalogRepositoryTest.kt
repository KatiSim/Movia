package app.movia.android.data.catalog

import app.movia.android.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRepositoryTest {
    @Test
    fun searchMatchesTitleGenreCountryAndYear() {
        assertTrue(searchCatalogLocally(catalogTestItems, "орбита").any { it.title == "Нулевая орбита" })
        assertTrue(searchCatalogLocally(catalogTestItems, "фантастика").any { "Фантастика" in it.genres })
        assertTrue(searchCatalogLocally(catalogTestItems, "испания").all { it.country == "Испания" })
        assertTrue(searchCatalogLocally(catalogTestItems, "2026").all { it.year == 2026 })
    }

    @Test
    fun emptySearchReturnsNoResults() {
        assertTrue(searchCatalogLocally(catalogTestItems, "   ").isEmpty())
    }

    @Test
    fun combinedFilterRespectsRichQuickFilters() {
        val result = filterCatalog(
            items = catalogTestItems,
            filter = CatalogFilter(
                type = ContentType.MOVIE,
                genres = setOf("Комедия"),
                yearFrom = 2020,
                yearTo = 2026,
                minRating = 7.0,
                resolution = "1080p",
            ),
        )

        assertEquals(listOf("Город после дождя"), result.map { it.title })
        assertTrue(result.all { it.type == ContentType.MOVIE })
        assertTrue(result.all { "Комедия" in it.genres })
        assertTrue(result.all { it.year in 2020..2026 && it.rating >= 7.0 })
        assertTrue(result.all { it.quality == "1080p" })
    }

    @Test
    fun multipleGenresUseInclusiveOrSelection() {
        val result = filterCatalog(
            catalogTestItems,
            CatalogFilter(type = null, genres = setOf("Комедия", "Фантастика")),
        )

        assertTrue(result.isNotEmpty())
        assertTrue(result.all { item -> item.genres.any { it == "Комедия" || it == "Фантастика" } })
    }

    @Test
    fun exactYearAndRatingThresholdCompose() {
        val result = filterCatalog(
            catalogTestItems,
            CatalogFilter(type = null, yearFrom = 2026, yearTo = 2026, minRating = 8.0),
        )

        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.year == 2026 && it.rating >= 8.0 })
    }
}

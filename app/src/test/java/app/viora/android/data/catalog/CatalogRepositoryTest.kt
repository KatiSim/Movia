package app.viora.android.data.catalog

import app.viora.android.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRepositoryTest {
    @Test
    fun searchMatchesTitleGenreCountryAndYear() {
        assertTrue(DemoCatalogRepository.search("орбита").any { it.title == "Нулевая орбита" })
        assertTrue(DemoCatalogRepository.search("фантастика").any { "Фантастика" in it.genres })
        assertTrue(DemoCatalogRepository.search("испания").all { it.country == "Испания" })
        assertTrue(DemoCatalogRepository.search("2026").all { it.year == 2026 })
    }

    @Test
    fun emptySearchReturnsNoResults() {
        assertTrue(DemoCatalogRepository.search("   ").isEmpty())
    }

    @Test
    fun combinedFilterRespectsRichQuickFilters() {
        val result = filterCatalog(
            items = DemoCatalogRepository.all(),
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
            DemoCatalogRepository.all(),
            CatalogFilter(type = null, genres = setOf("Комедия", "Фантастика")),
        )

        assertTrue(result.isNotEmpty())
        assertTrue(result.all { item -> item.genres.any { it == "Комедия" || it == "Фантастика" } })
    }

    @Test
    fun exactYearAndRatingThresholdCompose() {
        val result = filterCatalog(
            DemoCatalogRepository.all(),
            CatalogFilter(type = null, yearFrom = 2026, yearTo = 2026, minRating = 8.0),
        )

        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.year == 2026 && it.rating >= 8.0 })
    }
}

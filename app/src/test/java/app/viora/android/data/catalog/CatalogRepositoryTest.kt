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
    fun combinedFilterRespectsAllActiveConstraints() {
        val result = filterCatalog(
            items = DemoCatalogRepository.all(),
            filter = CatalogFilter(
                type = ContentType.MOVIE,
                comedyOnly = true,
                recentOnly = true,
                highRatingOnly = true,
                hdOnly = true,
            ),
        )

        assertEquals(listOf("Город после дождя"), result.map { it.title })
        assertTrue(result.all { it.type == ContentType.MOVIE })
        assertTrue(result.all { "Комедия" in it.genres })
        assertTrue(result.all { it.year >= 2020 && it.rating >= 7.0 })
        assertTrue(result.all { it.quality == "1080p" || it.quality == "4K" })
    }
}

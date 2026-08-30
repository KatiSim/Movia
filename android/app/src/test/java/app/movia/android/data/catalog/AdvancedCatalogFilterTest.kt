package app.movia.android.data.catalog

import app.movia.android.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedCatalogFilterTest {
    @Test
    fun countryNewAndDurationConstraintsCompose() {
        val result = filterCatalog(
            DemoCatalogRepository.all(),
            CatalogFilter(
                type = ContentType.MOVIE,
                country = "Испания",
                newOnly = true,
                durationMode = "LONG",
            ),
        )
        assertEquals(listOf("Последний рейс"), result.map { it.title })
    }

    @Test
    fun audioAndSubtitleFiltersUseMetadata() {
        val result = filterCatalog(
            DemoCatalogRepository.all(),
            CatalogFilter(
                type = ContentType.MOVIE,
                audioLanguage = "Русский",
                subtitleLanguage = "Русский",
            ),
        )
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { "Русский" in it.audioLanguages && "Русский" in it.subtitleLanguages })
        assertTrue(result.none { it.title == "Точка возврата" })
    }
}

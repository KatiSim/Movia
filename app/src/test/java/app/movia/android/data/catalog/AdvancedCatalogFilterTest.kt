package app.movia.android.data.catalog

import app.movia.android.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedCatalogFilterTest {
    @Test
    fun countryNewAndDurationConstraintsCompose() {
        val result = filterCatalog(
            catalogTestItems,
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
            catalogTestItems,
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

    @Test fun languageCodesAndVisibleLabelsUseTheSameFilter() {
        val item=catalogTestItems.first().copy(type=ContentType.MOVIE,audioLanguages=setOf("ru"),subtitleLanguages=setOf("en"))
        assertEquals(listOf(item),filterCatalog(listOf(item),CatalogFilter(audioLanguage="Русский",subtitleLanguage="English")))
    }
    @Test fun fourKAnd2160pAreTheSameResolution() {
        val item=catalogTestItems.first().copy(type=ContentType.MOVIE,quality="2160p")
        assertEquals(listOf(item),filterCatalog(listOf(item),CatalogFilter(resolution="4K")))
    }
    @Test fun originalAudioDoesNotPromiseEnglish() {
        val item=catalogTestItems.first().copy(type=ContentType.MOVIE,audioLanguages=setOf("original"))
        assertTrue(filterCatalog(listOf(item),CatalogFilter(audioLanguage="English")).isEmpty())
    }

}

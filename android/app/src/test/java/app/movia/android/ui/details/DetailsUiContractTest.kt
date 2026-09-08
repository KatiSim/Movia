package app.movia.android.ui.details

import app.movia.android.domain.model.CatalogCategory
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsUiContractTest {
    private fun content(
        type: ContentType = ContentType.SERIES,
        category: CatalogCategory = CatalogCategory.TV_SERIES,
        rating: Double = 8.9,
        year: Int = 2008,
        country: String = "USA",
        genres: Set<String> = linkedSetOf("Drama", "Crime"),
        seasons: Int = 5,
        episodes: Int = 62,
        counts: List<Int> = listOf(7, 13, 13, 13, 16),
        duration: Int = 46,
    ) = MediaContent(
        id = "test",
        title = "Во все тяжкие",
        originalTitle = "Breaking Bad",
        type = type,
        category = category,
        year = year,
        rating = rating,
        genres = genres,
        country = country,
        quality = "1080p",
        synopsis = "",
        durationMinutes = duration,
        seasonsCount = seasons,
        episodesCount = episodes,
        seasonEpisodeCounts = counts,
        sourceUrl = "",
    )

    @Test
    fun seriesMetadataUsesPrimaryIdentityAndSecondaryStructureRows() {
        val rows = detailsMetadataRows(content(), isTv = false)

        assertEquals("8.9", rows.ratingText)
        assertEquals(listOf("2008", "США", "Драма"), rows.primary)
        assertEquals(listOf("5 сезонов", "62 серии", "46 мин/серия"), rows.secondary)
    }

    @Test
    fun movieMetadataKeepsRuntimeOutOfPrimaryIdentityRow() {
        val rows = detailsMetadataRows(
            content(
                type = ContentType.MOVIE,
                category = CatalogCategory.MOVIES,
                seasons = 0,
                episodes = 0,
                counts = emptyList(),
                duration = 148,
            ),
            isTv = false,
        )

        assertEquals(listOf("2008", "США", "Драма"), rows.primary)
        assertTrue(rows.secondary.contains("Фильм"))
        assertTrue(rows.secondary.contains("148 мин"))
    }

    @Test
    fun seasonPickerHasHandleOnlyDismissAndKeepsSelectedSeasonVisible() {
        val source = java.io.File("src/main/java/app/movia/android/ui/details/DetailsScreen.kt").readText()
        val start = source.indexOf("private fun SeasonEpisodesScreen(")
        val end = source.indexOf("private fun SeasonEpisodesButton(", start)
        assertTrue(start >= 0 && end > start)
        val block = source.substring(start, end)

        assertFalse(block.contains("Icons.AutoMirrored.Outlined.ArrowBack"))
        assertFalse(block.contains("nestedScroll(swipeDownBack)"))
        assertTrue(block.contains("detectVerticalDragGestures"))
        assertTrue(block.contains("seasonListState.animateScrollToItem"))
        assertTrue(block.contains("pagerState.animateScrollToPage"))
    }
    @Test
    fun detailsAndPersonNavigationUseStableMediaIdentity() {
        val details = java.io.File("src/main/java/app/movia/android/ui/details/DetailsScreen.kt").readText()
        val app = java.io.File("src/main/java/app/movia/android/ui/MoviaApp.kt").readText()

        assertTrue(details.contains("DemoCatalogRepository.findFullById(mediaId)"))
        assertTrue(app.contains("encodeDetailsRoute(item.id, item.title)"))
        assertTrue(details.contains("private fun PersonProjectsScreen("))
        assertTrue(details.contains("val creativeCreditTitle = if (isSeries) \"Создатели\" else \"Режиссёр\""))
        assertTrue(details.contains("clickable(onClick = onClick)"))
    }

}

package app.movia.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeNavigationTest {
    @Test
    fun nextEpisodeAdvancesWithinSeasonAndThenToNextSeason() {
        val first = MediaRef("provider:show", season = 1, episode = 8)

        assertEquals(MediaRef("provider:show", 1, 9), first.nextEpisode(listOf(9, 6)))
        assertEquals(
            MediaRef("provider:show", 2, 1),
            MediaRef("provider:show", 1, 9).nextEpisode(listOf(9, 6)),
        )
    }

    @Test
    fun previousEpisodeReturnsLastEpisodeOfPreviousSeason() {
        assertEquals(
            MediaRef("provider:show", 1, 9),
            MediaRef("provider:show", 2, 1).previousEpisode(listOf(9, 6)),
        )
        assertNull(MediaRef("provider:show", 1, 1).previousEpisode(listOf(9)))
    }

    @Test
    fun episodeNavigationDoesNotUseTheDisplayTitleAsIdentity() {
        val result = MediaRef("provider:show", 3, 4).nextEpisode(listOf(9, 6, 8))

        assertEquals("provider:show", result?.contentId)
        assertEquals(3, result?.season)
        assertEquals(5, result?.episode)
    }

    @Test fun unknownSeasonDoesNotInventTenEpisodes() {
        assertNull(MediaRef("show", 1, 1).nextEpisode(emptyList()))
        assertNull(MediaRef("show", 1, 1).previousEpisode(emptyList()))
    }
    @Test fun emptySeasonsAreSkippedInBothDirections() {
        assertEquals(MediaRef("show", 3, 1), MediaRef("show", 1, 2).nextEpisode(listOf(2, 0, 3)))
        assertEquals(MediaRef("show", 1, 2), MediaRef("show", 3, 1).previousEpisode(listOf(2, 0, 3)))
    }
    @Test fun outOfRangeEpisodeCannotAdvanceToAnotherContent() {
        assertNull(MediaRef("show", 1, 12).nextEpisode(listOf(2)))
        assertNull(MediaRef("show", 1, 12).previousEpisode(listOf(2)))
    }
}

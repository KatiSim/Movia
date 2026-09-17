package app.movia.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesPlaybackTest {
    private val counts = listOf(8, 8, 6)

    @Test
    fun nextEpisodeIncrementsWithinSeason() {
        assertEquals(
            "Нулевая орбита · S01E05 · Эпизод 5",
            nextEpisodeTitleForCounts("Нулевая орбита · S01E04 · Эпизод 4", counts),
        )
    }

    @Test
    fun nextEpisodeAdvancesToNextSeason() {
        assertEquals(
            "Нулевая орбита · S02E01 · Эпизод 1",
            nextEpisodeTitleForCounts("Нулевая орбита · S01E08 · Эпизод 8", counts),
        )
    }

    @Test
    fun finalEpisodeOfFinalSeasonHasNoNextEpisode() {
        assertNull(nextEpisodeTitleForCounts("Нулевая орбита · S03E06 · Эпизод 6", counts))
    }

    @Test
    fun legacyEpisodeTitlesRemainCompatible() {
        assertEquals(
            "Нулевая орбита · E05 · Эпизод 5",
            nextEpisodeTitleForCounts("Нулевая орбита · E04 · Эпизод 4", counts),
        )
    }

    @Test
    fun playbackBaseTitleHandlesSeasonAndLegacyFormats() {
        assertEquals("Нулевая орбита", playbackBaseTitle("Нулевая орбита · S02E03 · Эпизод 3"))
        assertEquals("Нулевая орбита", playbackBaseTitle("Нулевая орбита · E03 · Эпизод 3"))
    }
}

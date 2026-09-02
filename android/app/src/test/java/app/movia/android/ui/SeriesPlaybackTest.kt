package app.movia.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesPlaybackTest {
    @Test
    fun nextEpisodeIncrementsWithinSeason() {
        assertEquals(
            "Нулевая орбита · S01E05 · Эпизод 5",
            nextEpisodeTitle("Нулевая орбита · S01E04 · Эпизод 4"),
        )
    }

    @Test
    fun nextEpisodeAdvancesToNextSeason() {
        assertEquals(
            "Нулевая орбита · S02E01 · Эпизод 1",
            nextEpisodeTitle("Нулевая орбита · S01E08 · Эпизод 8"),
        )
    }

    @Test
    fun finalEpisodeOfFinalSeasonHasNoNextEpisode() {
        assertNull(nextEpisodeTitle("Нулевая орбита · S03E06 · Эпизод 6"))
    }

    @Test
    fun legacyEpisodeTitlesRemainCompatible() {
        assertEquals(
            "Нулевая орбита · E05 · Эпизод 5",
            nextEpisodeTitle("Нулевая орбита · E04 · Эпизод 4"),
        )
    }

    @Test
    fun playbackBaseTitleHandlesSeasonAndLegacyFormats() {
        assertEquals("Нулевая орбита", playbackBaseTitle("Нулевая орбита · S02E03 · Эпизод 3"))
        assertEquals("Нулевая орбита", playbackBaseTitle("Нулевая орбита · E03 · Эпизод 3"))
    }
}

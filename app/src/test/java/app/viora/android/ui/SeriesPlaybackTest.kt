package app.viora.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesPlaybackTest {
    @Test
    fun nextEpisodeIncrementsEpisodeNumber() {
        assertEquals(
            "Нулевая орбита · E05 · Эпизод 5",
            nextEpisodeTitle("Нулевая орбита · E04 · Эпизод 4"),
        )
    }

    @Test
    fun finalDemoEpisodeHasNoNextEpisode() {
        assertNull(nextEpisodeTitle("Нулевая орбита · E08 · Эпизод 8"))
    }
}

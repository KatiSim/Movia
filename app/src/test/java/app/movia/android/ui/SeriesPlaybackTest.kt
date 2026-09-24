package app.movia.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesPlaybackTest {
    @Test
    fun playbackBaseTitleHandlesSeasonAndLegacyFormats() {
        assertEquals("Нулевая орбита", playbackBaseTitle("Нулевая орбита · S02E03 · Эпизод 3"))
        assertEquals("Нулевая орбита", playbackBaseTitle("Нулевая орбита · E03 · Эпизод 3"))
    }
}

package app.movia.android.ui.player

import app.movia.android.domain.model.StreamOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamSettingsSelectionTest {
    private val streams = listOf(
        StreamOption(voice = "LostFilm", quality = "1080p", url = "https://a.example/lf-1080", source = "p"),
        StreamOption(voice = "Кубик в Кубе", quality = "720p", url = "https://a.example/kubik-720", source = "p"),
        StreamOption(voice = "Кубик в Кубе", quality = "1080p", url = "https://a.example/kubik-1080", source = "p"),
    )

    @Test
    fun qualityOptionsComeFirstAndAreSortedLowToHigh() {
        val mixed = streams + listOf(
            StreamOption(voice = "Studio", quality = "4K", url = "https://a.example/4k", source = "p"),
            StreamOption(voice = "Studio", quality = "360p", url = "https://a.example/360", source = "p"),
            StreamOption(voice = "Studio", quality = "240p", url = "https://a.example/240", source = "p"),
        )
        assertEquals(listOf("360p", "720p", "1080p", "4K"), StreamSettingsSelection.qualityOptions(mixed))
    }

    @Test
    fun voiceOptionsAreScopedToSelectedQuality() {
        assertEquals(listOf("Кубик в Кубе"), StreamSettingsSelection.voiceOptions(streams, "720p"))
        assertEquals(listOf("LostFilm", "Кубик в Кубе"), StreamSettingsSelection.voiceOptions(streams, "1080p"))
    }

    @Test
    fun exactVoiceAndQualitySelectsWholeStreamOption() {
        assertEquals(
            "https://a.example/kubik-1080",
            StreamSettingsSelection.select(streams, "Кубик в Кубе", "1080p")?.url,
        )
    }

    @Test
    fun missingVoiceFallsBackToRequestedQualityWithoutFabricatingTrackLabel() {
        assertEquals(
            "https://a.example/lf-1080",
            StreamSettingsSelection.select(streams, "Unknown studio", "1080p")?.url,
        )
        assertNull(StreamSettingsSelection.select(emptyList(), "LostFilm", "1080p"))
    }
}

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
    fun missingVoiceNeverSilentlyChangesExplicitVoice() {
        assertNull(StreamSettingsSelection.select(streams, "Unknown studio", "1080p"))
        assertNull(StreamSettingsSelection.select(emptyList(), "LostFilm", "1080p"))
    }
    @Test
    fun selectedQualityNeverFallsBackToVoicesFromAnotherResolution() {
        assertEquals(emptyList<String>(), StreamSettingsSelection.voiceOptions(streams, "480p"))
        assertNull(StreamSettingsSelection.select(streams, "LostFilm", "480p"))
    }

    @Test
    fun voiceOptionsPreferRussianAndHideTechnicalTrackLabels() {
        val variants = listOf(
            StreamOption(voice = "Укр. Дубльований", quality = "1080p", url = "https://x/uk", source = "p", language = "uk"),
            StreamOption(voice = "Original (с субтитрами)", quality = "1080p", url = "https://x/en", source = "p", language = "en"),
            StreamOption(voice = "LostFilm", quality = "1080p", url = "https://x/ru", source = "p", language = "ru"),
            StreamOption(voice = "delete", quality = "1080p", url = "https://x/delete", source = "p", language = "ru"),
            StreamOption(voice = "rus0", quality = "1080p", url = "https://x/raw", source = "p", language = "ru"),
        )

        assertEquals(
            listOf("LostFilm", "Укр. Дубльований"),
            StreamSettingsSelection.voiceOptions(variants, "1080p"),
        )
    }

    @Test
    fun qualityChangeKeepsVoiceOnlyWhenItExistsOtherwiseChoosesBestRussian() {
        val variants = listOf(
            StreamOption(voice = "HDRezka", quality = "1080p", url = "https://x/rezka1080", source = "p", language = "ru"),
            StreamOption(voice = "Укр. Дубльований", quality = "720p", url = "https://x/uk720", source = "p", language = "uk"),
            StreamOption(voice = "Дубляж", quality = "720p", url = "https://x/ru720", source = "p", language = "ru"),
        )

        assertEquals(
            "Дубляж",
            StreamSettingsSelection.bestVoiceForQuality(variants, "720p", preferredVoice = "HDRezka"),
        )
        assertEquals(
            "https://x/ru720",
            StreamSettingsSelection.select(variants, "Дубляж", "720p")?.url,
        )
    }

    @Test
    fun englishOnlyQualityIsNotOffered() {
        val variants = listOf(
            StreamOption(voice = "Original (English)", quality = "4K", url = "https://x/en4k", source = "p", language = "en"),
            StreamOption(voice = "LostFilm", quality = "1080p", url = "https://x/ru1080", source = "p", language = "ru"),
        )
        assertEquals(listOf("1080p"), StreamSettingsSelection.qualityOptions(variants))
        assertEquals(emptyList<String>(), StreamSettingsSelection.voiceOptions(variants, "4K"))
        assertNull(StreamSettingsSelection.select(variants, "Original (English)", "4K"))
    }

    @Test
    fun equivalentProviderQualityLabelsCollapseToOneCanonicalOption() {
        val variants = listOf(
            StreamOption(voice = "LostFilm", quality = "1080p", url = "https://x/a", source = "p", language = "ru"),
            StreamOption(voice = "HDRezka", quality = "FullHD 1080", url = "https://x/b", source = "p", language = "ru"),
            StreamOption(voice = "Дубляж", quality = "HD 720", url = "https://x/c", source = "p", language = "ru"),
        )
        assertEquals(listOf("720p", "1080p"), StreamSettingsSelection.qualityOptions(variants))
    }

    @Test
    fun technicalProviderVoicesAreNotSelectableEvenAsUnknown() {
        val variants = listOf(
            StreamOption(voice = "delete", quality = "360p", url = "https://x/delete", source = "p", language = "ru"),
            StreamOption(voice = "rus0", quality = "360p", url = "https://x/raw", source = "p", language = "ru"),
            StreamOption(voice = "Не указано", quality = "720p", url = "https://x/unknown", source = "p", language = "ru"),
        )
        assertEquals(emptyList<String>(), StreamSettingsSelection.voiceOptions(variants, "360p"))
        assertNull(StreamSettingsSelection.select(variants, "Не указано", "360p"))
        assertEquals(listOf("Не указано"), StreamSettingsSelection.voiceOptions(variants, "720p"))
    }

}

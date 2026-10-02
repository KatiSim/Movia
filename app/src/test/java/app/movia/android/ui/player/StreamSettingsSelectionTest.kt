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
        assertEquals(listOf("240p", "360p", "720p", "1080p", "4K"), StreamSettingsSelection.qualityOptions(mixed))
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

    @Test fun knownVoiceIsKeptWhenQualityIsUnavailableForIt() {
        assertEquals("LostFilm", StreamSettingsSelection.select(streams, "LostFilm", "720p")?.voice)
        assertEquals("1080p", StreamSettingsSelection.select(streams, "LostFilm", "720p")?.quality)
    }
    @Test fun adaptiveVoiceIsNotHiddenByAnotherVoicesFixedQuality() {
        val adaptive=StreamOption("Studio A","Auto",url="https://a.example/master.m3u8",transport="hls",audioTrackIndex=0)
        val fixed=StreamOption("Studio B","720p",url="https://b.example/movie.mp4")
        assertEquals(listOf("Studio A","Studio B"),StreamSettingsSelection.voiceOptions(listOf(adaptive,fixed),"720p"))
        assertEquals(adaptive,StreamSettingsSelection.select(listOf(fixed,adaptive),"Studio A","720p"))
    }
    @Test fun noCompatibleQualityDoesNotOfferUnrelatedFixedVoices() {
        assertEquals(emptyList<String>(),StreamSettingsSelection.voiceOptions(streams,"360p"))
    }
    @Test fun sourceQualityAliasesAreConsistentAcrossMenusAndSelection() {
        for((label,height) in listOf("FullHD" to 1080,"HD" to 720,"4K" to 2160,"1920x1080" to 1080,"576p" to 576,"240p" to 240)) {
            assertEquals(height,qualityHeight(label))
            val source=StreamOption("Studio",label,url="https://a.example/movie")
            assertEquals(source,StreamSettingsSelection.select(listOf(source),"Studio","${height}p"))
            assertEquals(listOf("Studio"),StreamSettingsSelection.voiceOptions(listOf(source),"${height}p"))
        }
    }
    @Test fun generatedMatricesKeepVoiceQualityPairsAcrossDifferentProviders() {
        val random=java.util.Random(20261002L)
        repeat(500) { item ->
            val rows=buildList {
                for(studio in 0 until 3+random.nextInt(8)) for(height in listOf(240,360,480,720,1080,2160))
                    if(random.nextBoolean()) add(StreamOption("Studio $studio","${height}p",url="https://p${studio%3}.example/$item/$studio/$height"))
            }
            for(row in rows) {
                val chosen=StreamSettingsSelection.select(rows,row.voice,row.quality)
                assertEquals(row,chosen)
                org.junit.Assert.assertTrue(StreamSettingsSelection.voiceOptions(rows,row.quality).contains(row.voice))
            }
        }
    }
}

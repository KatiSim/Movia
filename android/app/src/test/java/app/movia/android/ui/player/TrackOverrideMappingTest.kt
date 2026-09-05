package app.movia.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import app.movia.android.domain.playback.StreamCandidate
import org.junit.Test

class TrackOverrideMappingTest {
    @Test
    fun providerIndexWorksWhenAllRenditionsAreOneMedia3Group() {
        assertEquals(TrackOverrideLocation(0, 7), locateProviderTrackIndex(listOf(8), 7))
    }

    @Test
    fun providerIndexWorksWhenMedia3SplitsRenditionsIntoSingleTrackGroups() {
        assertEquals(TrackOverrideLocation(7, 0), locateProviderTrackIndex(List(8) { 1 }, 7))
    }

    @Test
    fun providerIndexStaysInsidePrimaryGroupBeforeDuplicateFailoverGroup() {
        assertEquals(TrackOverrideLocation(0, 5), locateProviderTrackIndex(listOf(8, 8), 5))
    }

    @Test
    fun outOfRangeProviderIndexIsRejected() {
        assertNull(locateProviderTrackIndex(listOf(2, 2), 4))
        assertNull(locateProviderTrackIndex(listOf(8), -1))
    }
    @Test
    fun sameMediaLocatorWithExplicitAudioIndexCanSwitchInPlace() {
        val current = StreamCandidate(
            stableStreamId = "dub", provider = "Collaps",
            url = "https://cdn.example/master.m3u8", voice = "Дубляж", quality = "1080p",
            transport = "hls", audioTrackIndex = 0, headers = mapOf("Referer" to "https://provider.example/"),
        )
        val lostFilm = current.copy(stableStreamId = "lostfilm", voice = "LostFilm", audioTrackIndex = 2)
        assertTrue(canSwitchTracksInPlace(current, lostFilm))
    }

    @Test
    fun changedLocatorOrRequestProfileRequiresNormalReprepare() {
        val current = StreamCandidate(
            stableStreamId = "a", provider = "Collaps",
            url = "https://cdn.example/master.m3u8", voice = "Дубляж", quality = "1080p",
            transport = "hls", audioTrackIndex = 0, headers = mapOf("Referer" to "https://provider.example/"),
        )
        assertFalse(canSwitchTracksInPlace(current, current.copy(url = "https://cdn.example/other.m3u8", audioTrackIndex = 1)))
        assertFalse(canSwitchTracksInPlace(current, current.copy(headers = mapOf("Referer" to "https://other.example/"), audioTrackIndex = 1)))
    }

}

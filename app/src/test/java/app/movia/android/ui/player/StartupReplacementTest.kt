package app.movia.android.ui.player

import app.movia.android.domain.playback.PlaybackRequest
import app.movia.android.domain.playback.StreamCandidate
import org.junit.Assert.*
import org.junit.Test

class StartupReplacementTest {
    private val request = PlaybackRequest(mediaId = "42", title = "Film", year = 2025)
    private val old = StreamCandidate("same", provider = "p", url = "https://cdn.example/old.mp4",
        catalogMediaId = "42", canonicalTitle = "Film", canonicalYear = 2025, voice = "Studio A", quality = "720p")
    private val fresh = old.copy(url = "https://cdn.example/new.mp4")
    @Test fun rotatedUrlWithSameStableIdIsEligible() {
        assertEquals(fresh, selectStartupReplacement(request, old, listOf(fresh)))
    }
    @Test fun unchangedLocatorDoesNotRestartPlayback() {
        assertNull(selectStartupReplacement(request, old, listOf(old)))
    }
    @Test fun anotherMovieOrEpisodeCannotReplaceTheRequest() {
        assertNull(selectStartupReplacement(request, old, listOf(fresh.copy(catalogMediaId = "other"))))
        assertNull(selectStartupReplacement(request, old, listOf(fresh.copy(seasonNumber = 1, episodeNumber = 2))))
    }
    @Test fun explicitVoiceQualityAndStreamArePreserved() {
        assertNull(selectStartupReplacement(request.copy(requestedVoice = "Studio B"), old, listOf(fresh)))
        assertNull(selectStartupReplacement(request.copy(requestedQuality = "1080p"), old, listOf(fresh)))
        assertNull(selectStartupReplacement(request.copy(requestedStreamId = "exact"), old, listOf(fresh)))
        assertEquals(fresh, selectStartupReplacement(request.copy(requestedVoice = "Studio A", requestedQuality = "720p", requestedStreamId = "same"), old, listOf(fresh)))
    }
    @Test fun embedsAndTorrentsAndProblematicSourcesAreNotFastHints() {
        assertNull(selectStartupReplacement(request, old, listOf(fresh.copy(transportMetadata = mapOf("legacy_web_player" to "true")))))
        assertNull(selectStartupReplacement(request, old, listOf(fresh.copy(transport = "torrent_p2p"))))
        assertNull(selectStartupReplacement(request, old, listOf(fresh.copy(isProblematic = true))))
    }
}

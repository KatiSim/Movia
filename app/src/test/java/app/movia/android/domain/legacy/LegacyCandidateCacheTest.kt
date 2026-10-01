package app.movia.android.domain.legacy

import app.movia.android.domain.playback.PlaybackRequest
import app.movia.android.domain.playback.StreamCandidate
import org.junit.Assert.*
import org.junit.Test

class LegacyCandidateCacheTest {
    private fun request(id: String = "159", episode: Int = 1) =
        PlaybackRequest(mediaId = id, title = "Во все тяжкие", year = 2008, seasonNumber = 1, episodeNumber = episode)
    private val candidate = StreamCandidate("one", provider = "provider", url = "https://cdn.example/movie.mp4")

    @Test fun repeatedPlaybackIgnoresAttemptAndSessionId() {
        val cache = LegacyCandidateCache()
        cache.put(request(), listOf(candidate))
        assertEquals(listOf(candidate), cache.get(request().copy(attempt = 3, generationId = 20)))
    }
    @Test fun episodesYearsAndTitlesCannotBorrowOtherCandidates() {
        val cache = LegacyCandidateCache()
        cache.put(request(), listOf(candidate))
        assertNull(cache.get(request(episode = 2)))
        assertNull(cache.get(request().copy(seasonNumber = 2)))
        assertNull(cache.get(request().copy(year = 2025)))
        assertNull(cache.get(request().copy(title = "Другой фильм")))
        assertNull(cache.get(request(id = "158")))
    }
    @Test fun expiredSignedUrlHintsAreNotReturned() {
        var now = 0L
        val cache = LegacyCandidateCache(ttlMs = 100, clock = { now })
        cache.put(request(), listOf(candidate))
        now = 99
        assertNotNull(cache.get(request()))
        now = 100
        assertNull(cache.get(request()))
    }
    @Test fun cacheSizeIsBoundedAndNewEmptySearchDoesNotEraseWorkingHint() {
        val cache = LegacyCandidateCache(maximumEntries = 2)
        cache.put(request("1"), listOf(candidate))
        cache.put(request("2"), listOf(candidate))
        cache.put(request("3"), listOf(candidate))
        assertNull(cache.get(request("1")))
        cache.put(request("3"), emptyList())
        assertNotNull(cache.get(request("3")))
    }
    @Test fun callerPlaylistChangesDoNotChangeCacheSnapshot() {
        val cache = LegacyCandidateCache()
        val playlist = mutableListOf(candidate)
        cache.put(request(), playlist)
        playlist.clear()
        assertEquals(listOf(candidate), cache.get(request()))
    }
}

package app.movia.android.domain.playback
import org.junit.Assert.*
import org.junit.Test
class NativeVariantFeedbackTest {
    private fun leaf() = StreamCandidate(stableStreamId="provider-item:v2:scope:leaf",
        provider="Test",url="https://cdn.example/movie.mp4",catalogMediaId="42")
    @Test fun cachedLeafDoesNotRequireDelayedDiscoveryMetadata() {
        val scope=nativeFeedbackScope(leaf())
        assertEquals("94064fa7d738003f77b56c1e69eef3b6f083f6b030004059b0ea170a9bcfd5cf",scope.profileHash)
        assertEquals(64,scope.locatorHash.length)
    }
    @Test fun unicodeHeadersAndIndexedTracksMatchBackendCanonicalProfile() {
        val scope=nativeFeedbackScope(leaf().copy(headers=mapOf("X-Label" to "Голос \"A\"",
            "Referer" to "https://provider.example"),userAgent="Test Agent",audioTrackIndex=0,
            videoTrackIndex=1,fileIndex=2,filePath="Фильм.mkv",transport="hls"))
        assertEquals("be4fd92acd00e34b0a682e9f93909528a4f9778e011e5fa1cc42455d806c0ff8",scope.profileHash)
    }
    @Test fun metadataAndQualityChangesCannotChangePreparedRequestFingerprint() {
        assertEquals(nativeFeedbackScope(leaf()),nativeFeedbackScope(leaf().copy(quality="480p",
            transportMetadata=mapOf("playback_decoded" to "true"))))
    }
    @Test fun locatorAndHeaderChangesRemainDistinct() {
        val before=nativeFeedbackScope(leaf())
        assertNotEquals(before.locatorHash,nativeFeedbackScope(leaf().copy(url="https://cdn.example/new.mp4")).locatorHash)
        assertNotEquals(before.profileHash,nativeFeedbackScope(leaf().copy(headers=mapOf("Referer" to "https://other.example"))).profileHash)
    }

    @Test fun scopedFeedbackAttachesSourceIdWithoutChangingIdentity() {
        val current = leaf().copy(quality="480p")
        val attached = current.withNativeFeedbackSourceId(leaf(),"src:decoded")
        assertEquals("src:decoded",attached.sourceId)
        assertEquals(current.stableStreamId,attached.stableStreamId)
        assertEquals(current.quality,attached.quality)
    }
    @Test fun staleReplyCannotAttachToRotatedUrlOrChangedHeaders() {
        val prepared = leaf()
        for (current in listOf(prepared.copy(url="https://cdn.example/new.mp4"),
            prepared.copy(headers=mapOf("Referer" to "https://new.example")))) {
            assertEquals(current,current.withNativeFeedbackSourceId(prepared,"src:old"))
        }
    }
    @Test fun feedbackCannotAttachToAnotherMovieOrEpisode() {
        val prepared=leaf().copy(seasonNumber=1,episodeNumber=2)
        for (current in listOf(prepared.copy(catalogMediaId="43"),prepared.copy(episodeNumber=3),
            prepared.copy(stableStreamId="provider-item:v2:other"))) {
            assertEquals(current,current.withNativeFeedbackSourceId(prepared,"src:old"))
        }
    }
    @Test fun malformedResponseDoesNotAttachSourceId() {
        assertEquals(leaf(),leaf().withNativeFeedbackSourceId(leaf(),"https://invalid.example/source"))
    }
}

package app.movia.android.data.download

import app.movia.android.domain.model.ContentType
import app.movia.android.domain.playback.PlaybackRequest
import app.movia.android.domain.playback.StreamCandidate
import org.junit.Assert.*
import org.junit.Test

class OfflineVariantSelectionTest {
    private val request = PlaybackRequest("42", "Fixture", ContentType.MOVIE, year=2020)
    private fun source(id: String="a",voice: String="Studio A",quality: String="360p") =
        StreamCandidate(id,provider="QA",url="https://media.example/x.mp4",voice=voice,quality=quality,
            catalogMediaId="42",canonicalTitle="Fixture",canonicalYear=2020)
    private fun failure(code: String, block: () -> Unit) {
        try { block();fail("Unavailable variant was silently substituted") }
        catch (error: OfflineSelectionException) { assertEquals(code,error.code) }
    }
    @Test fun missingExplicitSourceDoesNotDownloadAnotherSource() {
        failure("SOURCE_VARIANT_UNAVAILABLE") { selectOfflineCandidate(request.copy(requestedStreamId="missing"),listOf(source()),null,null,null) }
    }
    @Test fun unavailableExplicitVoiceDoesNotDownloadAnotherStudio() {
        failure("VOICE_UNAVAILABLE") { selectOfflineCandidate(request,listOf(source()),"Studio B","360p",null) }
    }
    @Test fun unavailableProgressiveQualityDoesNotDownloadLowerQuality() {
        failure("QUALITY_UNAVAILABLE") { selectOfflineCandidate(request,listOf(source()),"Studio A","720p",null) }
    }
    @Test fun adaptiveQualityStaysOnTheSelectedManifestAndHeaderProfile() {
        val adaptive=source().copy(url="https://media.example/master.m3u8",transport="hls",quality="Auto",
            headers=mapOf("Referer" to "https://provider.example/"),downloadUrl="https://download.example/x.mp4",
            downloadHeaders=mapOf("Referer" to "https://other.example/"))
        val selected=selectOfflineCandidate(request,listOf(adaptive),"Studio A","720p",null)
        assertEquals(adaptive.url,selected.url);assertEquals(adaptive.headers,selected.headers)
        assertNull(selected.downloadUrl);assertTrue(selected.downloadHeaders.isEmpty())
    }
    @Test fun capturedVoiceMayDifferFromProviderDefaultOnlyOnThePinnedSource() {
        val adaptive=source().copy(url="https://media.example/master.m3u8",transport="hls",quality="Auto",audioTrackIndex=0)
        val selected=selectOfflineCandidate(request.copy(requestedStreamId="a"),listOf(adaptive),"Studio B","720p",1)
        val captured=capturedDownloadSource(selected,"a","Studio B",1,"Studio B")
        assertEquals("Studio B",captured.voice);assertEquals(1,captured.audioTrackIndex)
        failure("VOICE_UNAVAILABLE") { selectOfflineCandidate(request,listOf(adaptive),"Studio B","720p",1) }
    }
    @Test fun implicitPreferencesMayChooseAnAvailableFallback() {
        assertEquals("a",selectOfflineCandidate(request.copy(requestedVoice="Missing",requestedQuality="720p"),listOf(source()),null,null,null).stableStreamId)
    }
    @Test fun pinnedIdFromAnotherFilmCannotBecomeAnOfflineDownload() {
        failure("SOURCE_VARIANT_UNAVAILABLE") { selectOfflineCandidate(request.copy(requestedStreamId="a"),listOf(source().copy(catalogMediaId="43")),null,null,null) }
    }
    @Test fun concreteQualityUsesOriginalHeadersAndIgnoresDownloadAliasHeaders() {
        val row=source().copy(headers=mapOf("Referer" to "https://provider.example/"),downloadUrl="https://other.example/x.mp4",
            downloadHeaders=mapOf("Referer" to "https://wrong.example/"))
        val selected=offlineRequestSource(row,"360p")
        assertEquals(row.url,selected.url);assertEquals(row.headers,selected.headers);assertNull(selected.downloadUrl)
    }
    @Test fun capturedAudioKeepsOriginalManifestEvenWhenQualityIsAutomatic() {
        val row=source().copy(audioTrackIndex=1,downloadUrl="https://other.example/x.mp4")
        assertEquals(row.url,offlineRequestSource(row,"Auto").url)
    }
    @Test fun unrestrictedAlternateLocatorDoesNotReceiveCookiesFromAnotherOrigin() {
        val row=source().copy(headers=mapOf("Cookie" to "original-fixture-cookie","Referer" to "https://provider.example/"),
            downloadUrl="https://other.example/x.mp4",downloadHeaders=mapOf("Origin" to "https://other.example"))
        val selected=offlineRequestSource(row,"Auto")
        assertEquals(row.downloadUrl,selected.url);assertFalse(selected.headers.keys.any { it.equals("Cookie",true) })
        assertEquals("https://other.example",selected.headers["Origin"])
        val same=offlineRequestSource(row.copy(downloadUrl="https://media.example/download.mp4",downloadHeaders=emptyMap()),"Auto")
        assertEquals("original-fixture-cookie",same.headers["Cookie"])
    }
}

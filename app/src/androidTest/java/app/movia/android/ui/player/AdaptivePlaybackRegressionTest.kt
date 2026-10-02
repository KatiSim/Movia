package app.movia.android.ui.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.movia.android.data.download.AdaptiveOfflineDownloader
import app.movia.android.data.download.DownloadScheduler
import app.movia.android.data.download.OfflineMediaStore
import app.movia.android.domain.model.MediaRef
import app.movia.android.domain.model.StreamOption
import app.movia.android.domain.playback.StreamCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/** Runs real Media3 decoders against deterministic local streams without changing user history. */
@RunWith(AndroidJUnit4::class)
class AdaptivePlaybackRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = "http://127.0.0.1:8897"
    private fun <T> main(block: () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(path: String): JSONObject {
        val c = URL(base + path).openConnection() as HttpURLConnection
        return try { c.connectTimeout=3000;c.readTimeout=3000; JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
    }
    private fun waitFor(timeout: Long = 20_000L, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime()+timeout
        while (SystemClock.elapsedRealtime()<end) { if (main(condition)) return; Thread.sleep(150) }
        assertTrue("Media3 condition timed out",main(condition))
    }
    @Test fun changingTheMediaUrlPreservesPausePositionAndSelectedAudio() {
        control("/__control?mode=online")
        val session=main { MoviaPlaybackRegistry.obtain(context) }
        val streams=listOf(
            StreamOption(voice="Studio A",quality="Auto",url="$base/master.m3u8?studio=a",source="Movia QA",streamId="qa:paused:a",audioTrackIndex=0),
            StreamOption(voice="Studio B",quality="Auto",url="$base/master.m3u8?studio=b",source="Movia QA",streamId="qa:paused:b",audioTrackIndex=1),
        )
        try {
            main { session.setFrameProbe(true);session.start("movia_qa_paused_switch","Movia QA pause",candidateStreamOptions=streams,preferredVoice="Studio A",recordHistory=false) }
            waitFor(30_000L) { session.probeFrames>2 && session.player.isPlaying }
            main { session.pausePlayback();session.seekTo(12_000L) }
            assertFalse(main { session.player.playWhenReady })
            main { session.switchToStream(streams[1]) }
            waitFor(30_000L) { session.player.playbackState==androidx.media3.common.Player.STATE_READY }
            assertEquals(streams[1].url,main { session.player.currentMediaItem!!.localConfiguration!!.uri.toString() })
            assertFalse("Source switch resumed paused playback",main { session.player.playWhenReady || session.player.isPlaying })
            val position=main { session.player.currentPosition };Thread.sleep(600L)
            assertTrue("Paused position moved",kotlin.math.abs(main { session.player.currentPosition }-position)<250L)
            assertTrue("Source switch lost requested position",kotlin.math.abs(position-12_000L)<1200L)
            val frames=main { session.probeFrames }
            main { session.playPlayback() }
            waitFor { session.probeFrames>frames+2 && session.player.isPlaying && session.player.audioFormat?.label=="Studio B" }
        } finally { main { session.stopAndClear();session.setFrameProbe(false) } }
    }
    @Test fun hlsQualityAndVoiceChangeActualTracksWhilePositionIsPreserved() {
        control("/__control?mode=online")
        val session = main { MoviaPlaybackRegistry.obtain(context) }
        val id = "movia_qa_online"
        val streams = listOf(0,1).map { i -> StreamOption(voice="Studio ${if(i==0) "A" else "B"}",quality="Auto",url="$base/master.m3u8",source="Movia QA",streamId="qa:$i",audioTrackIndex=i) }
        try {
            main { session.setFrameProbe(true);session.start(id,"Movia QA",candidateStreamOptions=streams,preferredVoice="Studio A",recordHistory=false) }
            waitFor(30_000L) { session.probeFrames>0L && session.choices.value.video.size>=2 && session.choices.value.audio.size>=2 }
            assertFalse(session.recordHistory)
            assertTrue(main { session.setPlaybackSpeed(1.5f) })
            assertEquals(1.5f,main { session.player.playbackParameters.speed },0.001f)
            assertFalse(main { session.setPlaybackSpeed(Float.NaN) })
            assertFalse(main { session.setPlaybackSpeed(0f) })
            assertEquals(1.5f,main { session.player.playbackParameters.speed },0.001f)
            main { session.setPlaybackSpeed(1f) }
            val position = main { session.player.currentPosition }
            assertTrue(main { session.selectVideoQuality("360p") })
            waitFor { session.player.videoFormat?.height == 360 }
            assertTrue(main { session.selectVoice("Studio B") })
            waitFor { session.player.audioFormat?.label == "Studio B" }
            assertTrue("Voice switch lost playback position",main { session.player.currentPosition }>=position-1200)
            assertTrue(main { session.selectVideoQuality("720p") })
            main { session.seekTo(12_000L) }
            waitFor { session.player.videoFormat?.height == 720 }
            assertEquals("Studio B",main { session.player.audioFormat?.label })
            main { session.pausePlayback() }
            val paused = main { session.player.currentPosition }
            Thread.sleep(700)
            assertTrue(kotlin.math.abs(main { session.player.currentPosition }-paused)<250)
            assertTrue(main { session.selectVideoQuality("Auto") })
            assertTrue(main { session.player.trackSelectionParameters.overrides.values.none { it.type==C.TRACK_TYPE_VIDEO } })
            assertFalse(main { session.selectVideoQuality("9999p") })
            assertFalse(main { session.selectVoice("Missing studio") })
        } finally { main { session.stopAndClear();session.setFrameProbe(false) } }
    }
    @Test fun stalledStartupHandsOverBeforeWatchdogAndPreservesPauseAndPosition() {
        control("/__control?mode=online")
        val session = main { MoviaPlaybackRegistry.obtain(context) }
        val ref = "movia_qa_handover"
        try {
            main {
                session.setFrameProbe(true)
                session.start(ref, "Movia QA handover", sourceUri = "$base/stall.mp4",
                    startPositionMs = 3_000L, recordHistory = false)
                session.pausePlayback()
            }
            val started = SystemClock.elapsedRealtime()
            runBlocking { withContext(Dispatchers.Main) {
                session.acceptDiscoveredCandidates(listOf(StreamCandidate("qa:fresh", provider = "Movia QA",
                    url = "$base/fixture.mp4", catalogMediaId = ref, canonicalTitle = "Movia QA handover",
                    voice = "Studio A", quality = "360p")))
            } }
            waitFor { session.player.playbackState == androidx.media3.common.Player.STATE_READY &&
                session.activeSourceUri == "$base/fixture.mp4" }
            assertTrue("Startup must not wait for the ten-second watchdog", SystemClock.elapsedRealtime() - started < 5_000L)
            assertFalse(main { session.player.playWhenReady })
            assertTrue(kotlin.math.abs(main { session.player.currentPosition } - 3_000L) < 500L)
            main { session.playPlayback() }
            waitFor { session.probeFrames >= 3 }
            val playingUri = session.activeSourceUri
            runBlocking { withContext(Dispatchers.Main) {
                session.acceptDiscoveredCandidates(listOf(StreamCandidate("qa:late", provider = "Movia QA",
                    url = "$base/fixture.mp4?late=1", catalogMediaId = ref, canonicalTitle = "Movia QA handover",
                    voice = "Studio A", quality = "360p")))
            } }
            Thread.sleep(1800)
            assertEquals("Late discovery must not interrupt playing media", playingUri, session.activeSourceUri)
        } finally { main { session.stopAndClear();session.setFrameProbe(false) } }
    }

    @Test fun hlsOfflineHasSelectedAudioAndQualityAndMakesZeroMediaRequests() {
        control("/__control?mode=online")
        val ref=MediaRef("movia_qa_offline",1,2)
        val session = main { MoviaPlaybackRegistry.obtain(context) }
        try {
            runBlocking { AdaptiveOfflineDownloader.download(context,ref,StreamCandidate("qa:offline",provider="Movia QA",url="$base/master.m3u8",voice="Studio B",quality="Auto",transport="hls",audioTrackIndex=1),"720p") }
            val request=OfflineMediaStore.request(context,ref)
            assertNotNull(request)
            assertTrue(request!!.streamKeys.isNotEmpty())
            control("/__control?mode=offline")
            main { session.setFrameProbe(true);session.start(ref.contentId,"Movia QA offline",seasonNumber=1,episodeNumber=2,recordHistory=false) }
            waitFor { session.probeFrames>0 && session.player.videoFormat?.height==720 }
            assertTrue(session.isOffline)
            assertEquals("Studio B",main { session.player.audioFormat?.label })
            assertEquals(0,control("/__stats").getInt("mediaRequests"))
            assertFalse("An active offline cache must not be deleted",DownloadScheduler.delete(context,ref,"Movia QA offline"))
            main { session.seekTo(12_000L);session.player.play() }
            waitFor { session.player.currentPosition>=12_000L && session.probeFrames>2 }
            assertEquals(0,control("/__stats").getInt("mediaRequests"))
            main { session.stopAndClear();session.setFrameProbe(false) }
            assertTrue(OfflineMediaStore.delete(context,ref))
            assertNull(OfflineMediaStore.request(context,ref))
        } finally { main { session.stopAndClear();session.setFrameProbe(false) };OfflineMediaStore.delete(context,ref);control("/__control?mode=online") }
    }
    @Test fun progressiveOfflineIsDecodedFromCacheWithoutNetwork() {
        control("/__control?mode=online")
        val ref=MediaRef("movia_qa_progressive")
        val session=main { MoviaPlaybackRegistry.obtain(context) }
        try {
            runBlocking { AdaptiveOfflineDownloader.download(context,ref,StreamCandidate("qa:mp4",provider="Movia QA",url="$base/fixture.mp4",voice="Studio A",quality="360p"),"360p") }
            assertNotNull(OfflineMediaStore.request(context,ref))
            control("/__control?mode=offline")
            main { session.setFrameProbe(true);session.start(ref.contentId,"Movia QA MP4",recordHistory=false) }
            waitFor { session.probeFrames>0 && session.player.videoFormat?.height==360 }
            assertEquals(0,control("/__stats").getInt("mediaRequests"))
        } finally { main { session.stopAndClear();session.setFrameProbe(false) };OfflineMediaStore.delete(context,ref);control("/__control?mode=online") }
    }
    @Test fun dashOfflineIsDecodedFromSegmentsWithoutNetwork() {
        control("/__control?mode=online")
        val ref=MediaRef("movia_qa_dash")
        val session=main { MoviaPlaybackRegistry.obtain(context) }
        try {
            runBlocking { AdaptiveOfflineDownloader.download(context,ref,StreamCandidate("qa:dash",provider="Movia QA",url="$base/dash/manifest.mpd",voice="Studio A",quality="360p",transport="dash"),"360p") }
            val request=OfflineMediaStore.request(context,ref)
            assertNotNull(request)
            assertTrue(request!!.streamKeys.isNotEmpty())
            control("/__control?mode=offline")
            main { session.setFrameProbe(true);session.start(ref.contentId,"Movia QA DASH",recordHistory=false) }
            waitFor { session.probeFrames>0 && session.player.videoFormat?.height==360 }
            assertTrue(session.isOffline)
            assertEquals(0,control("/__stats").getInt("mediaRequests"))
        } finally { main { session.stopAndClear();session.setFrameProbe(false) };OfflineMediaStore.delete(context,ref);control("/__control?mode=online") }
    }

}

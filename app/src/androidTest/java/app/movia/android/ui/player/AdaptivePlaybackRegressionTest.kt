package app.movia.android.ui.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.movia.android.data.download.AdaptiveOfflineDownloader
import app.movia.android.data.download.capturedDownloadSource
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
    @Test fun changingVoiceKeepsPreparedAdaptiveQualityAheadOfAnExactColdTorrent() {
        control("/__control?mode=online")
        val session=main { MoviaPlaybackRegistry.obtain(context) }
        val a=StreamOption(voice="Studio A",quality="Auto",url="$base/master.m3u8",source="Movia QA",streamId="qa:adaptive:a",audioTrackIndex=0)
        val b=a.copy(voice="Studio B",streamId="qa:adaptive:b",audioTrackIndex=1)
        val cold=b.copy(quality="360p",streamId="qa:cold:b",url="magnet:?xt=urn:btih:0123456789012345678901234567890123456789",transport="torrent_p2p",audioTrackIndex=null,seeders=999)
        try {
            main { session.setFrameProbe(true);session.start("movia_qa_prepared_voice","Movia QA",candidateStreamOptions=listOf(a,b,cold),preferredVoice="Studio A",recordHistory=false) }
            waitFor { session.probeFrames>2 && session.player.isPlaying }
            main { session.pausePlayback();session.seekTo(3_000L) }
            assertTrue(main { session.selectVideoQuality("360p") })
            assertTrue(main { session.selectVoice("Studio B") })
            assertEquals("$base/master.m3u8",session.activeSourceUri)
            assertFalse(main { session.player.playWhenReady })
            assertTrue(kotlin.math.abs(main { session.player.currentPosition }-3_000L)<1000)
            main { session.playPlayback() }
            val baseline=main { session.probeFrames }
            waitFor { session.probeFrames>baseline+2 && session.player.isPlaying && session.player.audioFormat?.label=="Studio B" && session.player.videoFormat?.height==360 }
            assertEquals("Studio B",main { session.player.audioFormat?.label })
            assertEquals(360,main { session.player.videoFormat?.height })
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

    @Test fun capturedAudioDropsStaleGroupAndAlternateDownloadWhilePreservingOfflineVariant() {
        control("/__control?mode=online")
        val ref=MediaRef("movia_qa_captured_offline")
        val session=main { MoviaPlaybackRegistry.obtain(context) }
        val row=StreamCandidate("qa:captured",provider="Movia QA",url="$base/master.m3u8",voice="Studio A",
            quality="Auto",transport="hls",audioTrackIndex=0,downloadUrl="$base/fixture.mp4",
            transportMetadata=mapOf("zona_audio_group_id" to "stale-group", "zona_audio_group_index" to "1", "movia_audio_label" to "Studio A"))
        val selected=capturedDownloadSource(row,row.stableStreamId,"Studio B",1,"Studio B")
        assertNull(selected.downloadUrl)
        assertFalse(selected.transportMetadata.containsKey("zona_audio_group_id"))
        assertFalse(selected.transportMetadata.containsKey("zona_audio_group_index"))
        try {
            runBlocking { AdaptiveOfflineDownloader.download(context,ref,selected,"720p") }
            control("/__control?mode=offline")
            main { session.setFrameProbe(true);session.start(ref.contentId,"Movia QA captured",recordHistory=false) }
            waitFor { session.probeFrames>2 && session.player.videoFormat?.height==720 && session.player.audioFormat?.label=="Studio B" }
            assertTrue(session.isOffline)
            assertEquals(0,control("/__stats").getInt("mediaRequests"))
            try { capturedDownloadSource(row,"qa:different", "Studio B",1,"Studio B");fail("Captured ordinal applied to a different source") }
            catch (expected: app.movia.android.data.download.OfflineSelectionException) { assertEquals("AUDIO_SELECTION_SOURCE_CHANGED",expected.code) }
        } finally { main { session.stopAndClear();session.setFrameProbe(false) };OfflineMediaStore.delete(context,ref);control("/__control?mode=online") }
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
    @Test fun selectingAutoThenTheSameProviderVoiceRestoresItsActualRendition() {
        control("/__control?mode=online")
        val session=main { MoviaPlaybackRegistry.obtain(context) }
        val streams=listOf(0,1).map { StreamOption("Studio ${if(it==0) "A" else "B"}","Auto",url="$base/master.m3u8",streamId="qa:auto:$it",audioTrackIndex=it) }
        try {
            main { session.setFrameProbe(true);session.start("movia_qa_auto_restore","Movia QA",candidateStreamOptions=streams,preferredVoice="Studio B",recordHistory=false) }
            waitFor { session.player.audioFormat?.label=="Studio B" && session.probeFrames>2 }
            assertTrue(main { session.selectVideoQuality("720p") })
            assertTrue(main { session.selectVoice("Auto") })
            waitFor { session.player.audioFormat?.label=="Studio A" }
            assertEquals("Studio A",main { session.state.value.activeStreamSelection?.activeVoice })
            assertTrue(main { session.selectVoice("Studio B") })
            waitFor { session.player.audioFormat?.label=="Studio B" && session.player.videoFormat?.height==720 }
            assertEquals("720p",main { session.state.value.activeStreamSelection?.requestedQuality })
            assertEquals(1,main { session.selectedDownloadAudio()?.first })
        } finally { main { session.stopAndClear();session.setFrameProbe(false) } }
    }
    @Test fun explicitUnavailableOfflineQualityIsRejectedBeforeAnyCompletedMarker() {
        control("/__control?mode=online")
        val ref=MediaRef("movia_qa_unavailable_download")
        try {
            try {
                runBlocking { AdaptiveOfflineDownloader.download(context,ref,StreamCandidate("qa:unavailable",provider="QA",url="$base/master.m3u8",transport="hls"),"1080p") }
                fail("A missing 1080p rendition was silently replaced by another quality")
            } catch(error: app.movia.android.data.download.OfflineSelectionException) { assertEquals("QUALITY_UNAVAILABLE",error.code) }
            assertNull(OfflineMediaStore.request(context,ref))
        } finally { OfflineMediaStore.delete(context,ref) }
    }
    @Test fun duplicateCodecGroupsKeepTheSameVoiceInOnlineAndOfflinePlayback() {
        control("/__control?mode=online")
        val ref=MediaRef("movia_qa_duplicate_codecs")
        val session=main { MoviaPlaybackRegistry.obtain(context) }
        val streams=listOf(0,1).map { StreamOption("Studio ${if(it==0) "A" else "B"}","Auto",url="$base/duplicate-master.m3u8",streamId="qa:codecs:$it",audioTrackIndex=it,transport="hls") }
        try {
            main { session.setFrameProbe(true);session.start(ref.contentId,"Movia QA codecs",candidateStreamOptions=streams,recordHistory=false) }
            waitFor { session.player.playbackState==androidx.media3.common.Player.STATE_READY && session.probeFrames>2 }
            val physical=main { session.player.currentTracks.groups.filter { it.type==C.TRACK_TYPE_AUDIO }.sumOf { it.length } }
            assertTrue("Fixture did not produce duplicate codec/group renditions: $physical",physical>=4)
            assertEquals(2,main { session.choices.value.audio.size })
            assertTrue(main { session.selectVoice("Studio B") })
            assertTrue(main { session.selectVideoQuality("720p") })
            waitFor { session.player.audioFormat?.label=="Studio B" && session.player.videoFormat?.height==720 }
            main { session.stopAndClear() }
            runBlocking { AdaptiveOfflineDownloader.download(context,ref,StreamCandidate.fromStreamOption(streams[1]),"720p") }
            assertEquals("Studio B",OfflineMediaStore.selection(context,ref)?.audioLabel)
            control("/__control?mode=offline")
            main { session.start(ref.contentId,"Movia QA codecs offline",recordHistory=false) }
            waitFor { session.probeFrames>2 && session.player.audioFormat?.label=="Studio B" && session.player.videoFormat?.height==720 }
            assertEquals(0,control("/__stats").getInt("mediaRequests"))
        } finally { main { session.stopAndClear();session.setFrameProbe(false) };OfflineMediaStore.delete(context,ref);control("/__control?mode=online") }
    }
    @Test fun unsupportedAudioDoesNotShiftProviderOrdinalsOrAppearAsASelectableVoice() {
        val formats=listOf("Studio A","Studio B").map { androidx.media3.common.Format.Builder().setLabel(it).setLanguage("ru").setSampleMimeType("audio/mp4a-latm").build() }
        val group=androidx.media3.common.Tracks.Group(androidx.media3.common.TrackGroup("qa:unsupported",*formats.toTypedArray()),false,
            intArrayOf(C.FORMAT_UNSUPPORTED_TYPE,C.FORMAT_HANDLED),booleanArrayOf(false,true))
        val tracks=androidx.media3.common.Tracks(listOf(group))
        val choices=playbackChoices(tracks,0)
        assertEquals(1,choices.audio.size);assertEquals(1,choices.audio.single().providerAudioIndex)
        assertEquals(setOf(1),choices.supportedAudioOrdinals)
        assertNull(providerTrackOverride(tracks,C.TRACK_TYPE_AUDIO,0))
        val override=providerTrackOverride(tracks,C.TRACK_TYPE_AUDIO,1)!!
        assertEquals("Studio B",override.mediaTrackGroup.getFormat(override.trackIndices.first()).label)
        val rows=listOf(0,1).map { StreamOption("Studio ${if(it==0) "A" else "B"}","Auto",url="$base/master.m3u8",audioTrackIndex=it) }
        assertEquals(listOf("Auto","Studio B"),voiceMenu(rows,choices,"Auto",rows[0]))
    }
    @Test fun persistedIdenticalAudioLabelsKeepTheSelectedLanguageThroughTheirOrdinal() {
        val formats=listOf("en","ru").map { androidx.media3.common.Format.Builder().setLabel("Studio").setLanguage(it).setSampleMimeType("audio/mp4a-latm").build() }
        val group=androidx.media3.common.Tracks.Group(androidx.media3.common.TrackGroup("qa:language",*formats.toTypedArray()),false,
            intArrayOf(C.FORMAT_HANDLED,C.FORMAT_HANDLED),booleanArrayOf(false,true))
        val tracks=androidx.media3.common.Tracks(listOf(group))
        val override=providerTrackOverride(tracks,C.TRACK_TYPE_AUDIO,1,mapOf("movia_audio_label" to "Studio"))!!
        assertEquals("ru",override.mediaTrackGroup.getFormat(override.trackIndices.first()).language)
        assertNull(providerTrackOverride(tracks,C.TRACK_TYPE_AUDIO,1,mapOf("movia_audio_label" to "Different Studio")))
    }
    @Test fun anUnverifiedProviderQualityIsNotOfferedAfterTheManifestHasBeenPrepared() {
        control("/__control?mode=online")
        val session=main { MoviaPlaybackRegistry.obtain(context) }
        val source=StreamOption("Studio A","480p",url="$base/master.m3u8",streamId="qa:claimed:480",audioTrackIndex=0,transport="hls")
        try {
            main { session.setFrameProbe(true);session.start("movia_qa_claimed_quality","Movia QA",candidateStreamOptions=listOf(source),recordHistory=false) }
            waitFor { session.probeFrames>2 && session.choices.value.video.size==2 }
            val menu=main { qualityMenu(listOf(source),session.choices.value,"Studio A",source) }
            assertFalse("Unverified 480p claim was exposed as a working rendition",menu.contains("480p"))
            assertTrue(menu.contains("360p"));assertTrue(menu.contains("720p"))
            val request=main { session.state.value.activeStreamSelection?.requestedQuality }
            assertFalse(main { session.selectVideoQuality("480p") })
            assertEquals(request,main { session.state.value.activeStreamSelection?.requestedQuality })
        } finally { main { session.stopAndClear();session.setFrameProbe(false) } }
    }
    @Test fun unknownProviderLabelDoesNotHideThePreparedManifestVoice() {
        control("/__control?mode=online")
        val session=main { MoviaPlaybackRegistry.obtain(context) }
        val stream=StreamOption("Не указано","Auto",url="$base/master.m3u8",streamId="qa:unknown",audioTrackIndex=0)
        try {
            main { session.setFrameProbe(true);session.start("movia_qa_unknown_voice","Movia QA",candidateStreamOptions=listOf(stream),recordHistory=false) }
            waitFor { session.probeFrames>2 && session.choices.value.audio.size==2 }
            val menu=main { voiceMenu(listOf(stream),session.choices.value,"Auto",stream) }
            assertTrue("Default manifest rendition was hidden by an unknown provider label",menu.contains("Studio A"))
            assertTrue(menu.contains("Studio B"))
            assertTrue(main { session.selectVoice("Studio B") })
            waitFor { session.player.audioFormat?.label=="Studio B" }
            assertEquals("Studio B",main { session.selectedDownloadAudio()?.second })
        } finally { main { session.stopAndClear();session.setFrameProbe(false) } }
    }
    @Test fun aDifferentFixedQualityCannotBeSilentlyAcceptedForTheRequestedVoice() {
        control("/__control?mode=online")
        val session=main { MoviaPlaybackRegistry.obtain(context) }
        val a=StreamOption("Studio A","360p",url="$base/fixture.mp4",streamId="qa:fixed:a")
        val b=StreamOption("Studio B","720p",url="$base/v720/index.m3u8",streamId="qa:fixed:b")
        try {
            main { session.setFrameProbe(true);session.start("movia_qa_fixed_pair","Movia QA",candidateStreamOptions=listOf(a,b),preferredVoice="Studio A",preferredQuality="360p",recordHistory=false) }
            waitFor { session.probeFrames>2 && session.player.videoFormat?.height==360 }
            val selection=main { session.state.value.activeStreamSelection }
            assertFalse("720p source accepted while 360p was explicitly requested",main { session.selectVoice("Studio B") })
            assertEquals(a.url,session.activeSourceUri)
            assertEquals(selection?.requestedVoice,main { session.state.value.activeStreamSelection?.requestedVoice })
            assertEquals("360p",main { session.state.value.activeStreamSelection?.requestedQuality })
            assertEquals(360,main { session.player.videoFormat?.height })
        } finally { main { session.stopAndClear();session.setFrameProbe(false) } }
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

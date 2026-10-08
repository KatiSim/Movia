from pathlib import Path
C=Path.home()/'.cache/movia-architecture-20261002';R=C/'repo';domain=R/'app/src/test/java/app/movia/android/domain/playback'
f=R/'app/src/main/java/app/movia/android/ui/player/PlaybackSession.kt';s=f.read_text();assert s.count('class DynamicHeaderDataSource(')==1;s=s.replace('class DynamicHeaderDataSource(','internal class DynamicHeaderDataSource(')
old='''                } else if (player.playbackState == Player.STATE_BUFFERING) {
                    startStallWatchdog(playbackGeneration)
                }
                publishSnapshot()'''
new='''                } else {
                    if (decoderFeedbackGate.shouldRecoverStartup(true) && watchdogJob?.isActive != true) {
                        activeCandidate?.let { startWatchdog(it, player.currentPosition.coerceAtLeast(0L), playbackGeneration) }
                    }
                    if (player.playbackState == Player.STATE_BUFFERING) startStallWatchdog(playbackGeneration)
                }
                publishSnapshot()'''
assert s.count(old)==1;s=s.replace(old,new);f.write_text(s)
(domain/'PlaybackDataSourceScopeTest.kt').write_text('''package app.movia.android.domain.playback
import org.junit.Assert.*
import org.junit.Test

class PlaybackDataSourceScopeTest {
    @Test fun lateSegmentCreationRetainsFirstMediaSourceProfileAfterSwitch() {
        var selected = StreamRequestProfile(userAgent="UA-A", headers=mapOf("Referer" to "A"))
        val first = PlaybackDataSourceScope(selected, null) { p -> p.userAgent + ":" + p.headersFor("https://example.test/late.ts")["Referer"] }
        selected = StreamRequestProfile(userAgent="UA-B", headers=mapOf("Referer" to "B"))
        val second = PlaybackDataSourceScope(selected, null) { p -> p.userAgent + ":" + p.headersFor("https://example.test/new.ts")["Referer"] }
        assertEquals("UA-B:B",second.create())
        assertEquals("UA-A:A",first.create())
        assertEquals("UA-A:A",first.create())
    }
    @Test fun mutableCallerHeadersDoNotChangeAnExistingPreparation() {
        val headers = mutableMapOf("Referer" to "original")
        val first = PlaybackDataSourceScope(StreamRequestProfile(headers=headers),null) { it.headersFor("https://example.test/x")["Referer"] }
        headers["Referer"]="later"
        assertEquals("original",first.create())
    }
    @Test fun offlinePreparationDoesNotAdoptLaterNetworkFactory() {
        var offline: (() -> String)? = { "offline-file-A" }
        val first = PlaybackDataSourceScope(StreamRequestProfile(), offline) { "network-A" }
        offline = null
        val second = PlaybackDataSourceScope(StreamRequestProfile(), offline) { "network-B" }
        assertEquals("network-B",second.create());assertEquals("offline-file-A",first.create())
    }
    @Test fun eachLazyRequestCreatesItsOwnDelegateWithTheBoundProfile() {
        var count=0
        val source=PlaybackDataSourceScope(StreamRequestProfile(userAgent="owned"),null) { Pair(++count,it.userAgent) }
        assertEquals(Pair(1,"owned"),source.create());assertEquals(Pair(2,"owned"),source.create())
    }
}
''')
(domain/'PlaybackLoadEvidenceTest.kt').write_text('''package app.movia.android.domain.playback
import org.junit.Assert.*
import org.junit.Test

class PlaybackLoadEvidenceTest {
    @Test fun boundedHistoryPreservesCountersAfterOldEventsAreDropped() {
        val e=PlaybackLoadEvidence(2)
        repeat(5) { e.record("OPEN","OTHER") }
        val s=e.snapshot();assertEquals(5L,s["opens"]);assertEquals(2,(s["events"] as List<*>).size)
    }
    @Test fun lateOldSourceErrorDoesNotEnterNewPreparationEvidence() {
        val old=PlaybackLoadEvidence();val fresh=PlaybackLoadEvidence()
        fresh.record("OPEN","MANIFEST_SUFFIX")
        old.record("ERROR","SEGMENT_SUFFIX",errorClass="InvalidResponseCodeException",httpStatus=403)
        assertEquals(0L,fresh.snapshot()["errors"]);assertEquals(1L,old.snapshot()["errors"])
    }
    @Test fun openAndBytesDoNotPublishDecoderQualityOrVerification() {
        val e=PlaybackLoadEvidence();e.record("OPEN","MANIFEST_SUFFIX");e.record("CLOSE","MANIFEST_SUFFIX",transferred=100)
        val s=e.snapshot();assertEquals(100L,s["closedRequestBytes"])
        assertFalse(s.containsKey("decodedPlayback"));assertFalse(s.containsKey("actualQuality"));assertFalse(s.containsKey("verificationStatus"))
    }
    @Test fun untrustedExceptionTextCannotBeStoredAndNegativeBytesAreClamped() {
        val e=PlaybackLoadEvidence();e.record("ERROR","OTHER",errorClass="https://secret.invalid/token",httpStatus=999)
        e.record("CLOSE","OTHER",elapsedMs=-1,transferred=-9)
        val s=e.snapshot();assertFalse(s.toString().contains("secret.invalid"));assertFalse(s.toString().contains("999"));assertEquals(0L,s["closedRequestBytes"])
    }
}
''')
p=domain/'DecoderFeedbackGateTest.kt';s=p.read_text();at=s.rfind('}');s=s[:at]+'''
    @Test fun playingWithoutRenderedFrameStillRequiresStartupRecovery() {
        val gate=DecoderFeedbackGate();gate.prepare(100)
        assertTrue(gate.shouldRecoverStartup(true));assertFalse(gate.hasRenderedFrame())
    }
    @Test fun pauseDoesNotCountAsFailedVideoStartup() {
        val gate=DecoderFeedbackGate();gate.prepare(100)
        assertFalse(gate.shouldRecoverStartup(false));assertTrue(gate.shouldRecoverStartup(true))
    }
    @Test fun renderedFrameSuppressesOnlyItsOwnPreparationTimeout() {
        val gate=DecoderFeedbackGate();gate.prepare(100);gate.onRenderedFrame()
        assertFalse(gate.shouldRecoverStartup(true));assertTrue(gate.hasRenderedFrame())
        gate.prepare(200);assertTrue(gate.shouldRecoverStartup(true));assertFalse(gate.hasRenderedFrame())
    }
    @Test fun strayFrameBeforeAnyPreparationDoesNotStartOrSatisfyAWatchdog() {
        val gate=DecoderFeedbackGate();gate.onRenderedFrame()
        assertFalse(gate.hasRenderedFrame());assertFalse(gate.shouldRecoverStartup(true))
    }
''' + s[at:];p.write_text(s)
p=R/'app/src/test/java/app/movia/android/ui/player/StreamSettingsSelectionTest.kt';s=p.read_text();at=s.rfind('}');s=s[:at]+'''
    @Test fun manualQualityAfterFallbackAdoptsPreparedLeafWithoutRebindingEpisode() {
        val r=app.movia.android.domain.playback.PlaybackRequest(mediaId="42",title="Series",seasonNumber=1,episodeNumber=2,requestedStreamId="failed-480",requestedVoice="Studio",requestedQuality="480p")
        val q=StreamSettingsSelection.withPreparedQuality(r,"240p","prepared-240")
        assertEquals("prepared-240",q.requestedStreamId);assertEquals("240p",q.requestedQuality)
        assertEquals(r.copy(requestedQuality="240p",requestedStreamId="prepared-240"),q)
    }
    @Test fun explicitAutoAfterFallbackDoesNotRetainOldFailedLeafPin() {
        val r=app.movia.android.domain.playback.PlaybackRequest(mediaId="42",title="Film",requestedStreamId="failed")
        val q=StreamSettingsSelection.withPreparedQuality(r,"Auto","playing")
        assertEquals("playing",q.requestedStreamId);assertEquals("Auto",q.requestedQuality)
    }
    @Test fun absentPreparedLeafDoesNotInventAnotherLogicalSource() {
        val r=app.movia.android.domain.playback.PlaybackRequest(mediaId="42",title="Film",requestedStreamId="known")
        assertEquals("known",StreamSettingsSelection.withPreparedQuality(r,"Auto",null).requestedStreamId)
    }
''' + s[at:];p.write_text(s)
# Keep this hour's build/install artifacts independent from the previous checkpoint.
for old,new in [('hour-2134-build.py','hour-0850-build.py'),('hour-2134-install.py','hour-0850-install.py')]:
 s=(C/old).read_text().replace('hour-2134','hour-0850');(C/new).write_text(s)
print('15 new regression tests and build/install scripts ready')

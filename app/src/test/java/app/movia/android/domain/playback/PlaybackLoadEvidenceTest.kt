package app.movia.android.domain.playback
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

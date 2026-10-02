package app.movia.android.ui.player
import org.junit.Assert.*
import org.junit.Test
class LogicalAudioTracksTest {
    @Test fun duplicateCodecsAndFailoverDoNotShiftStudioOrdinal() {
        val groups = listOf(
            listOf(AudioRenditionIdentity("rus0","ru"),AudioRenditionIdentity("rus0","ru")),
            listOf(AudioRenditionIdentity("rus1","ru"),AudioRenditionIdentity("rus1","ru")),
            listOf(AudioRenditionIdentity("rus2","ru"),AudioRenditionIdentity("rus2","ru")),
            listOf(AudioRenditionIdentity("rus0","ru")),
            listOf(AudioRenditionIdentity("rus1","ru")))
        val logical = logicalAudioTrackLocations(groups)
        assertEquals(3,logical.size)
        assertEquals(TrackOverrideLocation(2,0),logical[2])
        assertEquals(TrackOverrideLocation(1,0),logical[1])
    }
    @Test fun unlabelledSameLanguageTracksRemainDistinct() {
        assertEquals(2,logicalAudioTrackLocations(listOf(listOf(AudioRenditionIdentity(null,"ru"),AudioRenditionIdentity(null,"ru")))).size)
    }
    @Test fun supportedCodecWinsWithinTheSameLogicalVoice() {
        assertEquals(listOf(TrackOverrideLocation(0,1)),logicalAudioTrackLocations(listOf(listOf(
            AudioRenditionIdentity("Studio A","ru",supported=false),AudioRenditionIdentity("Studio A","ru",supported=true)))))
    }
    @Test fun languageAndRoleRemainPartOfLogicalIdentity() {
        assertEquals(3,logicalAudioTrackLocations(listOf(listOf(AudioRenditionIdentity("Original","en"),
            AudioRenditionIdentity("Original","ru"),AudioRenditionIdentity("Original","en",roleFlags=1)))).size)
    }
}

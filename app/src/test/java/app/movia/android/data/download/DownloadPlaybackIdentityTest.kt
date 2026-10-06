package app.movia.android.data.download

import app.movia.android.domain.model.MediaRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPlaybackIdentityTest {
    @Test fun idlePlayerCannotOwnDownloadAndDoesNotConstructInvalidMediaRef() {
        assertFalse(DownloadPlaybackIdentity.matches(MediaRef("158"), "", null, null))
        assertFalse(DownloadPlaybackIdentity.matches(null, "", null, null))
        assertFalse(DownloadPlaybackIdentity.matches(MediaRef("159", 1, 2), "159", 0, 2))
    }

    @Test fun onlySameMovieOwnsItsSelectedTracks() {
        assertTrue(DownloadPlaybackIdentity.matches(MediaRef("158"), "158", null, null))
        assertFalse(DownloadPlaybackIdentity.matches(MediaRef("226"), "621453", null, null))
        assertFalse(DownloadPlaybackIdentity.matches(null, "158", null, null))
    }

    @Test fun episodeIdentityCannotInheritAnotherEpisodeSelection() {
        val ref=MediaRef("159", 1, 2)
        assertTrue(DownloadPlaybackIdentity.matches(ref, "159", 1, 2))
        assertFalse(DownloadPlaybackIdentity.matches(ref, "159", 1, 1))
        assertFalse(DownloadPlaybackIdentity.matches(ref, "159", null, null))
    }
}

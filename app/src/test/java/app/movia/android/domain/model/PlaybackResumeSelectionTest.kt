package app.movia.android.domain.model

import org.junit.Assert.*
import org.junit.Test

class PlaybackResumeSelectionTest {
    private fun progress(ref: MediaRef?, position: Long = 120_000L, title: String = "Spider-Man") =
        PlaybackProgress(title = title, positionMs = position, durationMs = 7_000_000L,
            contentId = ref?.contentId, seasonNumber = ref?.season, episodeNumber = ref?.episode)

    @Test fun sameTitleDifferentMoviesCannotShareResumeAfterExactCacheMiss() {
        val movie2002 = MediaRef("226")
        val movie1977 = MediaRef("621453")
        assertNull(selectExactResumeProgress(movie1977,
            mapOf(movie2002.storageKey to progress(movie2002)), progress(movie2002)))
    }

    @Test fun sameFilmDifferentEpisodesCannotShareResume() {
        val first = MediaRef("159", 1, 1)
        val second = MediaRef("159", 1, 2)
        assertNull(selectExactResumeProgress(second,
            mapOf(first.storageKey to progress(first)), progress(first)))
    }

    @Test fun sameEpisodeDifferentSeasonsCannotShareResume() {
        val old = MediaRef("159", 1, 1)
        assertNull(selectExactResumeProgress(MediaRef("159", 2, 1),
            mapOf(old.storageKey to progress(old)), progress(old)))
    }

    @Test fun exactEpisodeRestoresPositionEvenIfDisplayTitleChanges() {
        val ref = MediaRef("159", 2, 3)
        val saved = progress(ref, title = "Localized episode title")
        assertEquals(saved, selectExactResumeProgress(ref, mapOf(ref.storageKey to saved)))
    }

    @Test fun exactLastProgressCanRecoverAnEmptyIndex() {
        val ref = MediaRef("42")
        assertEquals(120_000L, selectExactResumeProgress(ref, emptyMap(), progress(ref))?.positionMs)
    }

    @Test fun corruptIndexValueCannotOverrideCatalogIdentity() {
        val wanted = MediaRef("42")
        assertNull(selectExactResumeProgress(wanted,
            mapOf(wanted.storageKey to progress(MediaRef("43")))))
    }

    @Test fun identitylessLegacyRecordRequiresMigrationBeforeResume() {
        val ref = MediaRef("42")
        val legacy = progress(null)
        assertNull(selectExactResumeProgress(ref, mapOf(ref.storageKey to legacy), legacy))
    }

    @Test fun movieResumeDoesNotBorrowItsSeriesEpisode() {
        val episode = MediaRef("159", 1, 1)
        assertNull(selectExactResumeProgress(MediaRef("159"), emptyMap(), progress(episode)))
    }

    @Test fun storedLegacyTitleCannotAssignCatalogIdentity() {
        assertNull(storedProgressMediaRef("Spider-Man", null, "Spider-Man"))
    }
    @Test fun canonicalStoredKeyRestoresExactEpisodeWithoutColumnId() {
        val ref = MediaRef("159", 2, 3)
        assertEquals(ref, storedProgressMediaRef(ref.storageKey, null, "Renamed episode"))
    }
    @Test fun conflictingStoredIdentityIsRejected() {
        assertNull(storedProgressMediaRef(MediaRef("226").storageKey, "621453", "Spider-Man"))
    }
    @Test fun explicitStoredCatalogIdCanRestoreLegacyKey() {
        assertEquals(MediaRef("226"), storedProgressMediaRef("Spider-Man", "226", "Spider-Man"))
    }
}

package app.movia.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaRefTest {
    @Test
    fun episodeIdentityDoesNotDependOnLocalizedDisplayTitle() {
        val russian = MediaRef.from("provider:42", "Сериал · S02E05 · Эпизод 5")
        val english = MediaRef.from("provider:42", "Series · S02E05")
        val englishCaption = MediaRef.from("provider:42", "Series · S02E05 · Episode 5")

        assertEquals("provider:42", russian?.contentId)
        assertEquals(2, russian?.season)
        assertEquals(5, russian?.episode)
        assertEquals(russian?.storageKey, english?.storageKey)
        assertEquals(russian?.storageKey, englishCaption?.storageKey)
    }

    @Test
    fun invalidEpisodeNumbersRemainDistinctLegacyRows() {
        assertNull(MediaRef.from("provider:42", "Series · S00E01 · Episode 1"))
        assertNull(MediaRef.from("provider:42", "Series · S01E999999999999 · Episode 2"))
        assertNotEquals(
            MediaRef.storageKey("provider:42", "Series · S00E01 · Episode 1"),
            MediaRef.storageKey("provider:42", "Series · S00E02 · Episode 2"),
        )
    }

    @Test
    fun episodesAndDifferentContentIdsHaveDifferentKeys() {
        val firstEpisode = MediaRef.from("provider:42", "Series · S01E01")
        val secondEpisode = MediaRef.from("provider:42", "Series · S01E02")
        val otherTitle = MediaRef.from("provider:43", "Series · S01E01")

        assertNotEquals(firstEpisode?.storageKey, secondEpisode?.storageKey)
        assertNotEquals(firstEpisode?.storageKey, otherTitle?.storageKey)
    }

    @Test
    fun titlePlaceholderIsNotTreatedAsCanonicalContentId() {
        assertNull(MediaRef.from("Series · S01E01", "Series · S01E01"))
        assertEquals(
            MediaRef.legacyStorageKey("Series"),
            MediaRef.storageKey(null, "Series"),
        )
    }

    @Test
    fun canonicalStorageKeyRoundTripsIdsContainingSeparators() {
        val original = MediaRef("provider:part/42", season = 12, episode = 104)

        assertEquals(original, MediaRef.fromStorageKey(original.storageKey))
    }

    @Test
    fun legacyKeysAndMalformedCanonicalKeysAreNotDecoded() {
        assertNull(MediaRef.fromStorageKey(MediaRef.legacyStorageKey("Series · S01E01")))
        assertNull(MediaRef.fromStorageKey("media:12:short:s1:e2"))
        assertNull(MediaRef.fromStorageKey("media:0::s1:e2"))
    }
}

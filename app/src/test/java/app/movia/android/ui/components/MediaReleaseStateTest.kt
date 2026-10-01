package app.movia.android.ui.components

import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaReleaseStateTest {
    private val today = LocalDate.of(2026, 9, 29)

    @Test
    fun futureYearWithoutPremiereDateIsUpcoming() {
        assertEquals(
            MediaReleaseState.UPCOMING,
            moviaMediaReleaseState(media(year = 2027), today),
        )
    }

    @Test
    fun futureYearWinsOverCachedPlayableSourceWhenExactDateIsMissing() {
        assertEquals(
            MediaReleaseState.UPCOMING,
            moviaMediaReleaseState(
                media(year = 2028, playbackUrl = "https://example.invalid/trailer-or-source.mp4"),
                today,
            ),
        )
    }

    @Test
    fun pastYearWithoutPlayableSourceIsReleased() {
        assertEquals(
            MediaReleaseState.RELEASED,
            moviaMediaReleaseState(media(year = 2025), today),
        )
    }

    @Test
    fun exactFuturePremiereDateHasPriority() {
        assertEquals(
            MediaReleaseState.UPCOMING,
            moviaMediaReleaseState(
                media(year = 2026, premiereDate = "2026-12-18"),
                today,
            ),
        )
    }

    @Test
    fun exactPastPremiereDateHasPriority() {
        assertEquals(
            MediaReleaseState.RELEASED,
            moviaMediaReleaseState(
                media(year = 2027, premiereDate = "2026-01-15"),
                today,
            ),
        )
    }

    @Test
    fun currentYearWithoutDateOrPlayableSourceRemainsUnknown() {
        assertEquals(
            MediaReleaseState.UNKNOWN,
            moviaMediaReleaseState(media(year = 2026), today),
        )
    }

    @Test
    fun playableSourceEligibilityRequiresRealPlaybackLocator() {
        assertTrue(moviaHasPlayableSource(media(year = 2026, playbackUrl = "https://example.invalid/master.m3u8")))
        assertFalse(moviaHasPlayableSource(media(year = 2026)))
    }

    private fun media(
        year: Int,
        premiereDate: String? = null,
        playbackUrl: String? = null,
    ) = MediaContent(
        id = "test-$year",
        title = "Test $year",
        type = ContentType.MOVIE,
        year = year,
        rating = 0.0,
        genres = emptySet(),
        country = "",
        quality = "",
        durationMinutes = 0,
        premiereDate = premiereDate,
        playbackUrl = playbackUrl,
    )
}

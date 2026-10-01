package app.movia.android.ui.components

import app.movia.android.domain.model.MediaContent
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class MediaReleaseState { RELEASED, UPCOMING, UNKNOWN }

fun moviaMediaReleaseState(
    item: MediaContent,
    today: LocalDate = LocalDate.now(),
): MediaReleaseState {
    parseMoviaPremiereDate(item.premiereDate)?.let { premiere ->
        return if (premiere.isAfter(today)) {
            MediaReleaseState.UPCOMING
        } else {
            MediaReleaseState.RELEASED
        }
    }

    // Many catalog records have only a release year and no exact premiere date.
    // A year strictly after the current year is still sufficient evidence that
    // the title has not been released yet. Likewise, a past year is sufficient
    // to treat the title as released even when no playable source is cached.
    if (item.year > today.year) return MediaReleaseState.UPCOMING
    if (item.year in 1880 until today.year) return MediaReleaseState.RELEASED

    return if (moviaHasPlayableSource(item)) MediaReleaseState.RELEASED else MediaReleaseState.UNKNOWN
}

fun moviaHasPlayableSource(item: MediaContent): Boolean =
    !item.playbackUrl.isNullOrBlank() ||
        item.streams.any { stream ->
            stream.url.isNotBlank() ||
                !stream.downloadUrl.isNullOrBlank() ||
                !stream.infoHash.isNullOrBlank()
        }

private fun parseMoviaPremiereDate(raw: String?): LocalDate? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val isoPrefix = value.take(10)
    runCatching {
        LocalDate.parse(isoPrefix, DateTimeFormatter.ISO_LOCAL_DATE)
    }.getOrNull()?.let { return it }

    val knownFormats = listOf(
        DateTimeFormatter.ofPattern("dd.MM.uuuu"),
        DateTimeFormatter.ofPattern("uuuu/MM/dd"),
    )
    return knownFormats.firstNotNullOfOrNull { formatter ->
        runCatching { LocalDate.parse(value, formatter) }.getOrNull()
    }
}

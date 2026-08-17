package app.viora.android.data.catalog

import app.viora.android.domain.model.ContentType
import app.viora.android.domain.model.MediaContent

data class CatalogFilter(
    val type: ContentType? = ContentType.MOVIE,
    val genres: Set<String> = emptySet(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val minRating: Double? = null,
    val quality: String? = null,
    val country: String? = null,
    val durationMode: String = "ANY",
    val newOnly: Boolean = false,
    val maxAgeRating: Int? = null,
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
) {
    val activeCount: Int
        get() = listOf(
            genres.isNotEmpty(),
            yearFrom != null || yearTo != null,
            minRating != null,
            quality != null,
            country != null,
            durationMode != "ANY",
            newOnly,
            maxAgeRating != null,
            audioLanguage != null,
            subtitleLanguage != null,
        ).count { it }
}

fun filterCatalog(
    items: List<MediaContent>,
    filter: CatalogFilter,
): List<MediaContent> = items.filter { item ->
    val durationMatches = when (filter.durationMode) {
        "SHORT" -> item.durationMinutes in 1..100
        "LONG" -> item.durationMinutes >= 110
        else -> true
    }
    val genreMatches = filter.genres.isEmpty() || item.genres.any { it in filter.genres }
    val yearMatches =
        (filter.yearFrom == null || item.year >= filter.yearFrom) &&
            (filter.yearTo == null || item.year <= filter.yearTo)
    val qualityMatches = when (filter.quality) {
        null -> true
        "HDR" -> item.quality.contains("HDR", ignoreCase = true)
        else -> item.quality.equals(filter.quality, ignoreCase = true)
    }

    (filter.type == null || item.type == filter.type) &&
        genreMatches &&
        yearMatches &&
        (filter.minRating == null || item.rating >= filter.minRating) &&
        qualityMatches &&
        (filter.country == null || item.country == filter.country) &&
        durationMatches &&
        (!filter.newOnly || item.isNew) &&
        (filter.maxAgeRating == null || item.ageRating <= filter.maxAgeRating) &&
        (filter.audioLanguage == null || filter.audioLanguage in item.audioLanguages) &&
        (filter.subtitleLanguage == null || filter.subtitleLanguage in item.subtitleLanguages)
}

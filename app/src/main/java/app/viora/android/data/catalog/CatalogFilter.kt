package app.viora.android.data.catalog

import app.viora.android.domain.model.ContentType
import app.viora.android.domain.model.MediaContent

data class CatalogFilter(
    val type: ContentType = ContentType.MOVIE,
    val comedyOnly: Boolean = false,
    val recentOnly: Boolean = false,
    val highRatingOnly: Boolean = false,
    val hdOnly: Boolean = false,
    val country: String? = null,
    val durationMode: String = "ANY",
    val newOnly: Boolean = false,
    val maxAgeRating: Int? = null,
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
) {
    val advancedCount: Int
        get() = listOf(
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

    item.type == filter.type &&
        (!filter.comedyOnly || "Комедия" in item.genres) &&
        (!filter.recentOnly || item.year >= 2020) &&
        (!filter.highRatingOnly || item.rating >= 7.0) &&
        (!filter.hdOnly || item.quality in setOf("1080p", "4K")) &&
        (filter.country == null || item.country == filter.country) &&
        durationMatches &&
        (!filter.newOnly || item.isNew) &&
        (filter.maxAgeRating == null || item.ageRating <= filter.maxAgeRating) &&
        (filter.audioLanguage == null || filter.audioLanguage in item.audioLanguages) &&
        (filter.subtitleLanguage == null || filter.subtitleLanguage in item.subtitleLanguages)
}

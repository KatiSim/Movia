package app.movia.android.data.catalog

import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent

enum class CatalogSort(val label: String) {
    POPULAR("По популярности"),
    RATING("По рейтингу"),
    NEWEST("Сначала новые"),
    OLDEST("Сначала старые"),
    TITLE("А–Я"),
}

data class CatalogFilter(
    val type: ContentType? = ContentType.MOVIE,
    val genres: Set<String> = emptySet(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val minRating: Double? = null,
    val resolution: String? = null,
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
            resolution != null,
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
    val resolutionMatches = filter.resolution == null ||
        item.quality.equals(filter.resolution, ignoreCase = true)

    (filter.type == null || item.type == filter.type) &&
        genreMatches &&
        yearMatches &&
        (filter.minRating == null || item.rating >= filter.minRating) &&
        resolutionMatches &&
        (filter.country == null || item.country == filter.country) &&
        durationMatches &&
        (!filter.newOnly || item.isNew) &&
        (filter.maxAgeRating == null || item.ageRating <= filter.maxAgeRating) &&
        (filter.audioLanguage == null || filter.audioLanguage in item.audioLanguages) &&
        (filter.subtitleLanguage == null || filter.subtitleLanguage in item.subtitleLanguages)
}

fun sortCatalog(items: List<MediaContent>, sort: CatalogSort): List<MediaContent> = when (sort) {
    CatalogSort.POPULAR -> items.sortedWith(compareByDescending<MediaContent> { it.popularity }.thenByDescending { it.rating })
    CatalogSort.RATING -> items.sortedWith(compareByDescending<MediaContent> { it.rating }.thenByDescending { it.popularity })
    CatalogSort.NEWEST -> items.sortedWith(compareByDescending<MediaContent> { it.year }.thenByDescending { it.popularity })
    CatalogSort.OLDEST -> items.sortedWith(compareBy<MediaContent> { it.year }.thenByDescending { it.popularity })
    CatalogSort.TITLE -> items.sortedBy { it.title.lowercase() }
}

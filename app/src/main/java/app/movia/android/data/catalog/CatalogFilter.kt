package app.movia.android.data.catalog

import app.movia.android.domain.model.CatalogCategory
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent

enum class CatalogSort(val label: String) {
    POPULAR("По популярности"),
    RATING("По рейтингу"),
    NEWEST("По дате выхода"),
    OLDEST("Сначала старые"),
    CATEGORY("По типу и году"),
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

internal fun catalogLanguageKey(value: String): String = when(value.trim().lowercase().substringBefore('-')) {
    "ru", "rus", "русский", "русские", "russian" -> "ru"
    "en", "eng", "english", "английский", "английские" -> "en"
    else -> value.trim().lowercase()
}
internal fun catalogResolutionKey(value: String): String {
    val low=value.trim().lowercase()
    return when(low) {
        "4k", "uhd", "2160", "2160p" -> "2160p"
        "hd", "720" -> "720p"
        "fhd", "fullhd", "1080" -> "1080p"
        else -> low
    }
}

fun filterCatalog(
    items: List<MediaContent>,
    filter: CatalogFilter,
): List<MediaContent> = items.filter { item ->
    val durationMatches = when (filter.durationMode) {
        "SHORT" -> item.durationMinutes in 1..100
        "MEDIUM" -> item.durationMinutes in 101..109
        "LONG" -> item.durationMinutes >= 110
        else -> true
    }
    val genreMatches = filter.genres.isEmpty() || item.genres.any { it in filter.genres }
    val yearMatches =
        (filter.yearFrom == null || item.year >= filter.yearFrom) &&
            (filter.yearTo == null || item.year <= filter.yearTo)
    val resolutionMatches = filter.resolution == null ||
        (catalogResolutionKey(item.quality) == catalogResolutionKey(filter.resolution) || item.streams.any { catalogResolutionKey(it.quality) == catalogResolutionKey(filter.resolution) })

    (filter.type == null || item.type == filter.type) &&
        genreMatches &&
        yearMatches &&
        (filter.minRating == null || item.rating >= filter.minRating) &&
        resolutionMatches &&
        (filter.country == null || item.country.split(",").map(String::trim).any { it.equals(filter.country, ignoreCase = true) }) &&
        durationMatches &&
        (!filter.newOnly || item.year in (java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)-1)..java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)) &&
        (filter.maxAgeRating == null || (item.ageRating != null && item.ageRating <= filter.maxAgeRating)) &&
        (filter.audioLanguage == null || item.audioLanguages.any { catalogLanguageKey(it) == catalogLanguageKey(filter.audioLanguage) }) &&
        (filter.subtitleLanguage == null || item.subtitleLanguages.any { catalogLanguageKey(it) == catalogLanguageKey(filter.subtitleLanguage) })
}

private val contentTypeOrder = mapOf(
    ContentType.MOVIE to 0,
    ContentType.SERIES to 1,
    ContentType.TV to 2,
)

private val catalogCategoryOrder = mapOf(
    CatalogCategory.MOVIES to 0,
    CatalogCategory.TV_SERIES to 1,
    CatalogCategory.LIMITED_SERIES to 2,
    CatalogCategory.ANIMATION to 3,
    CatalogCategory.ANIME to 4,
    CatalogCategory.DRAMAS_ASIAN to 5,
    CatalogCategory.DOCUMENTARIES to 6,
    CatalogCategory.THEATER_MUSICALS to 7,
    CatalogCategory.STANDUP to 8,
    CatalogCategory.INTERACTIVE to 9,
)

fun sortCatalog(items: List<MediaContent>, sort: CatalogSort): List<MediaContent> = when (sort) {
    CatalogSort.POPULAR -> items.sortedWith(
        compareByDescending<MediaContent> { it.popularity }
            .thenByDescending { it.rating }
            .thenBy { it.title.lowercase() }
            .thenBy { it.id },
    )
    CatalogSort.RATING -> items.sortedWith(
        compareByDescending<MediaContent> { it.rating }
            .thenByDescending { it.popularity }
            .thenBy { it.title.lowercase() }
            .thenBy { it.id },
    )
    CatalogSort.NEWEST -> items.sortedWith(
        compareByDescending<MediaContent> { it.year }
            .thenByDescending { it.popularity }
            .thenBy { it.title.lowercase() }
            .thenBy { it.id },
    )
    CatalogSort.OLDEST -> items.sortedWith(
        compareBy<MediaContent> { it.year }
            .thenByDescending { it.popularity }
            .thenBy { it.title.lowercase() }
            .thenBy { it.id },
    )
    CatalogSort.CATEGORY -> items.sortedWith(
        compareBy<MediaContent> { contentTypeOrder[it.type] ?: Int.MAX_VALUE }
            .thenBy { catalogCategoryOrder[it.category] ?: Int.MAX_VALUE }
            .thenByDescending { it.year }
            .thenByDescending { it.rating }
            .thenBy { it.title.lowercase() }
            .thenBy { it.id },
    )
    CatalogSort.TITLE -> items.sortedWith(
        compareBy<MediaContent> { it.title.lowercase() }
            .thenBy { it.year }
            .thenBy { it.id },
    )
}

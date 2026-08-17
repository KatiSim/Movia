package app.viora.android.data.catalog

import app.viora.android.domain.model.ContentType
import app.viora.android.domain.model.MediaContent

data class CatalogFilter(
    val type: ContentType = ContentType.MOVIE,
    val comedyOnly: Boolean = false,
    val recentOnly: Boolean = false,
    val highRatingOnly: Boolean = false,
    val hdOnly: Boolean = false,
)

fun filterCatalog(
    items: List<MediaContent>,
    filter: CatalogFilter,
): List<MediaContent> = items.filter { item ->
    item.type == filter.type &&
        (!filter.comedyOnly || "Комедия" in item.genres) &&
        (!filter.recentOnly || item.year >= 2020) &&
        (!filter.highRatingOnly || item.rating >= 7.0) &&
        (!filter.hdOnly || item.quality in setOf("1080p", "4K"))
}

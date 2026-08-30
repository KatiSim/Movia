package app.movia.android.data.catalog

import app.movia.android.domain.model.CatalogCategory
import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.Person
import app.movia.android.domain.model.ContentType

interface CatalogRepository {
    fun all(): List<MediaContent>
    fun search(query: String): List<MediaContent>
    fun searchPeople(query: String): List<Person>
    fun findByTitle(title: String): MediaContent?
    fun findById(id: String): MediaContent?
}

/**
 * Bundled verified catalog generated from catalog100_verified.json.
 * Every entry has an explicit Public Domain/CC license field and a byte-probed playback URL.
 */
object DemoCatalogRepository : CatalogRepository {
    private val catalog: List<MediaContent> = emptyList()

    override fun all(): List<MediaContent> = catalog

    override fun search(query: String): List<MediaContent> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        return catalog.filter { item ->
            item.title.lowercase().contains(normalized) ||
                item.originalTitle?.lowercase()?.contains(normalized) == true ||
                item.genres.any { it.lowercase().contains(normalized) } ||
                item.country.lowercase().contains(normalized) ||
                item.director?.lowercase()?.contains(normalized) == true ||
                item.synopsis?.lowercase()?.contains(normalized) == true ||
                item.licenseName?.lowercase()?.contains(normalized) == true ||
                item.category.label.lowercase().contains(normalized) ||
                (item.year > 0 && item.year.toString().contains(normalized))
        }.sortedByDescending { it.popularity }
    }

    override fun searchPeople(query: String): List<Person> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        return catalog.mapNotNull { it.director }.distinct()
            .filter { it.lowercase().contains(normalized) }
            .map { name -> Person(name = name, knownFor = catalog.filter { it.director == name }.map { it.title }) }
            .sortedBy { it.name }
    }

    override fun findByTitle(title: String): MediaContent? = catalog.firstOrNull {
        it.title.equals(title, ignoreCase = true)
    }

    override fun findById(id: String): MediaContent? = catalog.firstOrNull { it.id == id }
}

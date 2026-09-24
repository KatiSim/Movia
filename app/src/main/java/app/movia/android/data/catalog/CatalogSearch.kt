package app.movia.android.data.catalog

import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.Person

private fun normalizedCatalogText(value: String?): String = value.orEmpty()
    .trim()
    .lowercase()
    .replace('ё', 'е')
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

/**
 * Small in-memory search used by the agent control plane.
 * It searches the already loaded catalog and never turns a metadata page into
 * a playback source.
 */
fun searchCatalogLocally(
    items: List<MediaContent>,
    query: String,
    limit: Int = 20,
): List<MediaContent> {
    val safeLimit = limit.coerceAtLeast(0)
    if (safeLimit == 0) return emptyList()
    val normalizedQuery = normalizedCatalogText(query)
    if (normalizedQuery.isBlank()) return items.take(safeLimit)

    val tokens = normalizedQuery.split(' ').filter { it.isNotBlank() }
    return items.asSequence()
        .map { item ->
            val title = normalizedCatalogText(item.title)
            val original = normalizedCatalogText(item.originalTitle)
            val metadata = normalizedCatalogText(
                buildString {
                    append(item.genres.joinToString(" "))
                    append(' ')
                    append(item.country)
                    append(' ')
                    append(item.year)
                    append(' ')
                    append(item.director.orEmpty())
                    append(' ')
                    append(item.cast.joinToString(" ") { person -> person.name })
                },
            )
            val titleSearchable = "$title $original"
            val searchable = "$titleSearchable $metadata"
            val exact = if (title == normalizedQuery || original == normalizedQuery) 100 else 0
            val prefixScore = if (title.startsWith(normalizedQuery) || original.startsWith(normalizedQuery)) 25 else 0
            val titleTokenScore = tokens.count { titleSearchable.contains(it) } * 4
            val metadataTokenScore = tokens.count { metadata.contains(it) }
            item to (exact + prefixScore + titleTokenScore + metadataTokenScore)
        }
        .filter { (_, score) -> score > 0 }
        .sortedWith(
            compareByDescending<Pair<MediaContent, Int>> { it.second }
                .thenBy { normalizedCatalogText(it.first.title) }
                .thenBy { it.first.id },
        )
        .map { it.first }
        .take(safeLimit)
        .toList()
}

/** Pure people lookup over an already loaded catalog; no Android/runtime calls. */
fun searchPeopleLocally(
    items: List<MediaContent>,
    query: String,
    limit: Int = 20,
): List<Person> {
    val safeLimit = limit.coerceAtLeast(0)
    val normalizedQuery = normalizedCatalogText(query)
    if (safeLimit == 0 || normalizedQuery.isBlank()) return emptyList()

    data class PersonAccumulator(
        val name: String,
        var photoUrl: String? = null,
        val roles: LinkedHashSet<String> = linkedSetOf(),
        val knownFor: LinkedHashSet<String> = linkedSetOf(),
    )

    val people = linkedMapOf<String, PersonAccumulator>()
    fun add(name: String?, photoUrl: String?, role: String?, title: String) {
        val cleanName = name?.trim().orEmpty()
        if (cleanName.isBlank()) return
        val normalizedName = normalizedCatalogText(cleanName)
        if (!normalizedName.contains(normalizedQuery)) return
        val accumulator = people.getOrPut(normalizedName) { PersonAccumulator(cleanName) }
        if (accumulator.photoUrl.isNullOrBlank() && !photoUrl.isNullOrBlank()) accumulator.photoUrl = photoUrl
        role?.takeIf { it.isNotBlank() }?.let(accumulator.roles::add)
        accumulator.knownFor.add(title)
    }

    items.forEach { item ->
        add(item.director, null, "Режиссёр", item.title)
        item.cast.forEach { person -> add(person.name, person.photoUrl, person.role ?: "Актёр", item.title) }
    }

    return people.values
        .sortedWith(compareByDescending<PersonAccumulator> { it.knownFor.size }.thenBy { normalizedCatalogText(it.name) })
        .take(safeLimit)
        .map { person ->
            Person(
                name = person.name,
                photoUrl = person.photoUrl,
                role = person.roles.joinToString(" / ").takeIf { it.isNotBlank() },
                knownFor = person.knownFor.toList(),
            )
        }
}

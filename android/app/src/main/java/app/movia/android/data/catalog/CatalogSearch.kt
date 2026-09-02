package app.movia.android.data.catalog

import app.movia.android.domain.model.MediaContent

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
            val searchable = "$title $original"
            val exact = if (title == normalizedQuery || original == normalizedQuery) 100 else 0
            val prefix = if (title.startsWith(normalizedQuery) || original.startsWith(normalizedQuery)) 25 else 0
            val tokenScore = tokens.count { searchable.contains(it) }
            item to (exact + prefix + tokenScore)
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

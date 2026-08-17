package app.viora.android.data.catalog

import app.viora.android.domain.model.MediaContent

data class RecommendationResult(
    val reason: String,
    val items: List<MediaContent>,
)

object RecommendationEngine {
    fun recommend(
        history: List<String>,
        repository: CatalogRepository = DemoCatalogRepository,
        limit: Int = 6,
    ): RecommendationResult {
        val watched = history.mapNotNull(repository::findByTitle)
        val watchedTitles = watched.mapTo(mutableSetOf()) { it.title }
        val topGenre = watched
            .flatMap { it.genres }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        val candidates = repository.all()
            .asSequence()
            .filterNot { it.title in watchedTitles }
            .let { sequence ->
                if (topGenre == null) sequence else sequence.filter { topGenre in it.genres }
            }
            .sortedByDescending { it.popularity }
            .take(limit)
            .toList()

        val fallback = if (candidates.isEmpty()) {
            repository.all()
                .filterNot { it.title in watchedTitles }
                .sortedByDescending { it.popularity }
                .take(limit)
        } else {
            candidates
        }

        return RecommendationResult(
            reason = if (topGenre == null) "Популярное для старта" else "Похожие по жанру: $topGenre",
            items = fallback,
        )
    }
}

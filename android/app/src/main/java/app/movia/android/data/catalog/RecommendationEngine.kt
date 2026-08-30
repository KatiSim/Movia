package app.movia.android.data.catalog

import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent

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
        val allItems = repository.all()
        if (allItems.isEmpty() || limit <= 0) {
            return RecommendationResult(reason = "Каталог пока пуст", items = emptyList())
        }

        val targetSize = limit.coerceAtMost(allItems.size)
        val watchedWithRecency = history.mapIndexedNotNull { index, title ->
            repository.findByTitle(title)?.let { item ->
                // DAO history is newest-first (openedAt DESC). Recent watches matter more.
                val recencyWeight = 1.0 / (1.0 + index * 0.35)
                item to recencyWeight
            }
        }

        if (watchedWithRecency.isEmpty()) {
            return RecommendationResult(
                reason = "Подборка для знакомства",
                items = coldStartRank(allItems, targetSize),
            )
        }

        val genreWeights = mutableMapOf<String, Double>()
        val actorWeights = mutableMapOf<String, Double>()
        val directorWeights = mutableMapOf<String, Double>()
        val typeWeights = mutableMapOf<ContentType, Double>()
        val countryWeights = mutableMapOf<String, Double>()
        val watchedTitles = linkedSetOf<String>()

        watchedWithRecency.forEach { (item, weight) ->
            watchedTitles += item.title
            item.genres.forEach { genre -> genreWeights.addWeight(genre, weight) }
            item.cast.forEach { actor -> actorWeights.addWeight(actor, weight) }
            item.director?.takeIf { it.isNotBlank() }?.let { directorWeights.addWeight(it, weight) }
            typeWeights.addWeight(item.type, weight)
            item.country.takeIf { it.isNotBlank() }?.let { countryWeights.addWeight(it, weight) }
        }

        val genreTotal = genreWeights.values.sum().coerceAtLeast(1.0)
        val actorTotal = actorWeights.values.sum().coerceAtLeast(1.0)
        val directorTotal = directorWeights.values.sum().coerceAtLeast(1.0)
        val typeTotal = typeWeights.values.sum().coerceAtLeast(1.0)
        val countryTotal = countryWeights.values.sum().coerceAtLeast(1.0)
        val maxPopularity = allItems.maxOfOrNull { it.popularity }?.coerceAtLeast(1) ?: 1

        fun personalizedScore(candidate: MediaContent): Double {
            val genreAffinity = candidate.genres.sumOf { genreWeights[it] ?: 0.0 } / genreTotal
            val actorAffinity = candidate.cast.sumOf { actorWeights[it] ?: 0.0 } / actorTotal
            val directorAffinity = candidate.director
                ?.let { directorWeights[it] ?: 0.0 }
                ?.div(directorTotal)
                ?: 0.0
            val typeAffinity = (typeWeights[candidate.type] ?: 0.0) / typeTotal
            val countryAffinity = (countryWeights[candidate.country] ?: 0.0) / countryTotal
            val ratingPrior = (candidate.rating / 10.0).coerceIn(0.0, 1.0)
            val popularityPrior = candidate.popularity.toDouble() / maxPopularity.toDouble()

            return genreAffinity * 0.35 +
                actorAffinity * 0.20 +
                directorAffinity * 0.15 +
                typeAffinity * 0.10 +
                countryAffinity * 0.05 +
                ratingPrior * 0.05 +
                popularityPrior * 0.10
        }

        val comparator = compareByDescending<MediaContent> { personalizedScore(it) }
            .thenByDescending { it.rating }
            .thenByDescending { it.popularity }
            .thenBy { it.title }

        // Unseen content is always preferred. If history covers too much of the small
        // catalog, fill the row with the best personalized watched items instead of
        // allowing the permanent "Для вас" section to disappear.
        val unseen = allItems.filterNot { it.title in watchedTitles }.sortedWith(comparator)
        val watchedFallback = allItems.filter { it.title in watchedTitles }.sortedWith(comparator)
        val selected = (unseen + watchedFallback).distinctBy { it.id }.take(targetSize)

        return RecommendationResult(
            reason = "На основе истории просмотра",
            items = selected,
        )
    }

    private fun coldStartRank(items: List<MediaContent>, limit: Int): List<MediaContent> {
        if (items.isEmpty() || limit <= 0) return emptyList()

        val maxPopularity = items.maxOfOrNull { it.popularity }?.coerceAtLeast(1) ?: 1
        val minYear = items.minOfOrNull { it.year } ?: 0
        val maxYear = items.maxOfOrNull { it.year } ?: minYear
        val yearSpan = (maxYear - minYear).coerceAtLeast(1)

        fun baseProbability(item: MediaContent): Double {
            val rating = (item.rating / 10.0).coerceIn(0.0, 1.0)
            val popularity = item.popularity.toDouble() / maxPopularity.toDouble()
            val freshness = ((item.year - minYear).toDouble() / yearSpan.toDouble()).coerceIn(0.0, 1.0)
            return rating * 0.40 + popularity * 0.35 + freshness * 0.15
        }

        val remaining = items.toMutableList()
        val selected = mutableListOf<MediaContent>()

        while (remaining.isNotEmpty() && selected.size < limit) {
            val seenGenres = selected.flatMapTo(mutableSetOf()) { it.genres }
            val seenTypes = selected.mapTo(mutableSetOf()) { it.type }

            val next = remaining.maxWithOrNull(
                compareBy<MediaContent> { candidate ->
                    val genreNovelty = if (candidate.genres.isEmpty()) {
                        0.0
                    } else {
                        candidate.genres.count { it !in seenGenres }.toDouble() / candidate.genres.size.toDouble()
                    }
                    val typeNovelty = if (candidate.type !in seenTypes) 1.0 else 0.0
                    val diversity = genreNovelty * 0.70 + typeNovelty * 0.30
                    baseProbability(candidate) + diversity * 0.10
                }.thenBy { it.rating }.thenBy { it.popularity },
            ) ?: break

            selected += next
            remaining -= next
        }

        return selected
    }

    private fun <K> MutableMap<K, Double>.addWeight(key: K, amount: Double) {
        this[key] = getOrDefault(key, 0.0) + amount
    }
}

package app.movia.android.data.catalog

import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent
import kotlin.math.roundToInt

data class RecommendationResult(
    val reason: String,
    val items: List<MediaContent>,
)

object RecommendationEngine {
    fun recommend(
        history: List<String>,
        repository: CatalogRepository = DemoCatalogRepository,
        limit: Int = 6,
        favorites: Set<String> = emptySet(),
    ): RecommendationResult {
        if (limit <= 0) {
            return RecommendationResult(reason = "Подборка отключена", items = emptyList())
        }

        val recentHistoryTitles = history
            .asSequence()
            .map(::baseTitle)
            .filter { it.isNotBlank() }
            .distinct()
            .take(5)
            .toList()

        val watchedItems = history
            .asSequence()
            .map(::baseTitle)
            .filter { it.isNotBlank() }
            .distinct()
            .mapNotNull(repository::findByTitle)
            .distinctBy { it.id }
            .toList()

        val favoriteTitles = favorites
            .asSequence()
            .map(::baseTitle)
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)
            .toList()

        val seedItems = (recentHistoryTitles + favoriteTitles)
            .distinct()
            .mapNotNull(repository::findByTitle)
            .distinctBy { it.id }

        val excludedIds = (watchedItems + seedItems)
            .mapTo(linkedSetOf()) { it.id }

        if (seedItems.isEmpty()) {
            return coldStart(repository, excludedIds, limit)
        }

        val genreWeights = mutableMapOf<String, Double>()
        val directorWeights = mutableMapOf<String, Double>()
        val typeWeights = mutableMapOf<ContentType, Double>()

        recentHistoryTitles.forEachIndexed { index, title ->
            repository.findByTitle(title)?.let { item ->
                val weight = 1.0 / (1.0 + index * 0.35)
                item.genres.forEach { genre -> genreWeights.addWeight(genre, weight) }
                item.director?.takeIf { it.isNotBlank() }?.let { director ->
                    directorWeights.addWeight(director, weight)
                }
                typeWeights.addWeight(item.type, weight)
            }
        }

        favoriteTitles.forEach { title ->
            repository.findByTitle(title)?.let { item ->
                val weight = 0.75
                item.genres.forEach { genre -> genreWeights.addWeight(genre, weight) }
                item.director?.takeIf { it.isNotBlank() }?.let { director ->
                    directorWeights.addWeight(director, weight)
                }
                typeWeights.addWeight(item.type, weight)
            }
        }

        val candidateLimit = (limit * 24).coerceIn(80, 400)
        var candidates = repository.getRecommendationCandidates(
            genres = genreWeights.keys,
            directors = directorWeights.keys,
            excludedIds = excludedIds,
            limit = candidateLimit,
        ).filter(::isUsable)

        if (candidates.isEmpty()) {
            candidates = repository.getRecommendationCandidates(
                genres = emptySet(),
                directors = emptySet(),
                excludedIds = excludedIds,
                limit = candidateLimit,
            ).filter(::isUsable)
        }
        if (candidates.isEmpty()) {
            return RecommendationResult(reason = "Для вас пока нет подходящих тайтлов", items = emptyList())
        }

        val genreTotal = genreWeights.values.sum().coerceAtLeast(1.0)
        val directorTotal = directorWeights.values.sum().coerceAtLeast(1.0)
        val typeTotal = typeWeights.values.sum().coerceAtLeast(1.0)
        val maxPopularity = candidates.maxOfOrNull { it.popularity }?.coerceAtLeast(1) ?: 1

        fun score(candidate: MediaContent): Double {
            val genreAffinity = candidate.genres
                .sumOf { genreWeights[it] ?: 0.0 }
                .div(genreTotal)
                .coerceIn(0.0, 1.0)
            val directorAffinity = candidate.director
                ?.let { (directorWeights[it] ?: 0.0) / directorTotal }
                ?.coerceIn(0.0, 1.0)
                ?: 0.0
            val typeAffinity = ((typeWeights[candidate.type] ?: 0.0) / typeTotal)
                .coerceIn(0.0, 1.0)
            val ratingPrior = (candidate.rating / 10.0).coerceIn(0.0, 1.0)
            val popularityPrior = (candidate.popularity.toDouble() / maxPopularity).coerceIn(0.0, 1.0)

            return genreAffinity * 0.50 +
                directorAffinity * 0.20 +
                typeAffinity * 0.10 +
                ratingPrior * 0.10 +
                popularityPrior * 0.10
        }

        val ranked = candidates
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<MediaContent> { score(it) }
                    .thenByDescending { it.rating }
                    .thenByDescending { it.popularity }
                    .thenBy { it.title },
            )

        return RecommendationResult(
            reason = "На основе истории и избранного",
            items = diversify(ranked, limit),
        )
    }

    private fun coldStart(
        repository: CatalogRepository,
        excludedIds: Set<String>,
        limit: Int,
    ): RecommendationResult {
        val candidateLimit = (limit * 24).coerceIn(80, 400)
        val allCandidates = repository.getRecommendationCandidates(
            genres = emptySet(),
            directors = emptySet(),
            excludedIds = excludedIds,
            limit = candidateLimit,
        ).filter(::isUsable)

        if (allCandidates.isEmpty()) {
            return RecommendationResult(reason = "Каталог пока пуст", items = emptyList())
        }

        val preferred = allCandidates.filter { it.rating >= 8.0 }
        val pool = if (preferred.size >= limit) preferred else {
            allCandidates.filter { it.rating >= 7.0 }.ifEmpty { allCandidates }
        }
        val maxPopularity = pool.maxOfOrNull { it.popularity }?.coerceAtLeast(1) ?: 1

        val ranked = pool.sortedWith(
            compareByDescending<MediaContent> {
                (it.rating / 10.0) * 0.65 +
                    (it.popularity.toDouble() / maxPopularity) * 0.25 +
                    (it.year.coerceAtLeast(0).toDouble() / 2100.0).coerceIn(0.0, 1.0) * 0.10
            }
                .thenByDescending { it.rating }
                .thenByDescending { it.year }
                .thenBy { it.title },
        )

        return RecommendationResult(
            reason = "Подборка для знакомства",
            items = diversify(ranked, limit),
        )
    }

    /**
     * Deterministic shelf diversification. It protects a neutral home shelf
     * from one narrow category taking over while still filling the shelf when
     * the catalog has too few alternatives.
     */
    fun diversifyShelf(
        items: List<MediaContent>,
        limit: Int,
        explicitCategory: Boolean = false,
    ): List<MediaContent> {
        if (limit <= 0) return emptyList()

        val unique = items
            .filter(::isUsable)
            .distinctBy { it.id }

        if (explicitCategory || unique.size <= limit) return unique.take(limit)

        val maxAsian = if (limit <= 3) 1 else (limit * 0.25).roundToInt().coerceAtLeast(1)
        val maxPerCategory = (limit * 0.40).roundToInt().coerceAtLeast(2)
        val categoryCounts = mutableMapOf<String, Int>()
        var asianCount = 0
        val selected = mutableListOf<MediaContent>()

        fun canAdd(item: MediaContent, allowAsianOverflow: Boolean = false): Boolean {
            val categoryCount = categoryCounts[item.category.name] ?: 0
            if (categoryCount >= maxPerCategory) return false
            if (!allowAsianOverflow && isAsianRelated(item) && asianCount >= maxAsian) return false
            return true
        }

        // First pass applies both limits and keeps the best-ranked order.
        unique.forEach { item ->
            if (selected.size < limit && canAdd(item)) {
                selected += item
                categoryCounts[item.category.name] = (categoryCounts[item.category.name] ?: 0) + 1
                if (isAsianRelated(item)) asianCount++
            }
        }

        // Fill from non-Asian alternatives before relaxing the category cap.
        unique.forEach { item ->
            if (selected.size >= limit || item.id in selected.map { it.id }) return@forEach
            if (!isAsianRelated(item)) {
                selected += item
                categoryCounts[item.category.name] = (categoryCounts[item.category.name] ?: 0) + 1
            }
        }

        // If the available catalog is narrow, do not leave empty slots.
        unique.forEach { item ->
            if (selected.size >= limit || item.id in selected.map { it.id }) return@forEach
            selected += item
        }

        return selected.take(limit)
    }

    /**
     * Blends the four home-feed pools, then applies one common diversity pass.
     * The quotas are 40% global trends, 30% personalized, 20% quality and 10%
     * exploration for the default eight-card shelf.
     */
    fun blendHomeShelf(
        global: List<MediaContent>,
        personalized: List<MediaContent>,
        quality: List<MediaContent>,
        exploration: List<MediaContent>,
        limit: Int = 8,
    ): List<MediaContent> {
        if (limit <= 0) return emptyList()
        val quotas = listOf(
            global to (limit * 0.40).roundToInt().coerceAtLeast(1),
            personalized to (limit * 0.30).roundToInt().coerceAtLeast(1),
            quality to (limit * 0.20).roundToInt().coerceAtLeast(1),
            exploration to (limit * 0.10).roundToInt().coerceAtLeast(1),
        )
        val selected = mutableListOf<MediaContent>()
        val used = mutableSetOf<String>()

        quotas.forEach { (pool, quota) ->
            pool.filter(::isUsable).distinctBy { it.id }.take(quota).forEach { item ->
                if (used.add(item.id)) selected += item
            }
        }

        val fallback = (global + personalized + quality + exploration)
            .filter(::isUsable)
            .distinctBy { it.id }
        selected += fallback.filterNot { it.id in used }
        return diversifyShelf(selected.distinctBy { it.id }, limit)
    }

    private fun isAsianRelated(item: MediaContent): Boolean {
        val asianCategory = item.category.name == "DRAMAS_ASIAN" || item.category.name == "ANIME"
        if (asianCategory) return true
        val searchable = buildString {
            append(item.title)
            append(' ')
            append(item.originalTitle.orEmpty())
            append(' ')
            append(item.country)
            append(' ')
            append(item.genres.joinToString(" "))
        }.lowercase()
        return listOf(
            "китай", "китайск", "коре", "япон", "инд", "таил",
            "тайван", "индонез", "вьетнам", "asia", "asian", "china",
            "korea", "japan", "india", "thai", "taiwan",
        ).any(searchable::contains)
    }

    private fun diversify(
        ranked: List<MediaContent>,
        limit: Int,
    ): List<MediaContent> = diversifyShelf(ranked, limit)

    private fun isUsable(item: MediaContent): Boolean =
        item.rating > 0.0 && !item.posterUrl.isNullOrBlank()

    private fun baseTitle(value: String): String =
        value.substringBefore(" · S").substringBefore(" · E").trim()

    private fun <K> MutableMap<K, Double>.addWeight(key: K, amount: Double) {
        this[key] = getOrDefault(key, 0.0) + amount
    }
}

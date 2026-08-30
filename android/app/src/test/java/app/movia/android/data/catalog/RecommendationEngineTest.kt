package app.movia.android.data.catalog

import app.movia.android.domain.model.CatalogCategory
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    @Test
    fun historyProducesPersonalizedRecommendationsWithoutPreferringWatched() {
        val seed = media("seed", "Основание", setOf("Фантастика", "Драма"), "Режиссёр А", ContentType.SERIES, 2024, 8.8)
        val match = media("match", "Сигнал", setOf("Фантастика"), "Режиссёр А", ContentType.SERIES, 2025, 8.2)
        val generic = media("generic", "Комната", setOf("Комедия"), "Режиссёр Б", ContentType.MOVIE, 2025, 9.0)
        val repository = FakeCatalogRepository(listOf(seed, match, generic))

        val result = RecommendationEngine.recommend(
            history = listOf("Основание"),
            repository = repository,
            limit = 1,
        )

        assertEquals("На основе истории и избранного", result.reason)
        assertEquals("Сигнал", result.items.single().title)
        assertTrue(result.items.none { it.id == seed.id })
    }

    @Test
    fun favoritesContributeToPersonalizedRecommendations() {
        val favorite = media("favorite", "Любимый фильм", setOf("Детектив"), "Режиссёр А", ContentType.MOVIE, 2020, 8.0)
        val match = media("match", "Новый детектив", setOf("Детектив"), "Режиссёр Б", ContentType.MOVIE, 2025, 8.1)
        val other = media("other", "Другая история", setOf("Комедия"), "Режиссёр В", ContentType.MOVIE, 2025, 9.5)
        val repository = FakeCatalogRepository(listOf(favorite, match, other))

        val result = RecommendationEngine.recommend(
            history = emptyList(),
            favorites = setOf("Любимый фильм"),
            repository = repository,
            limit = 1,
        )

        assertEquals("Новый детектив", result.items.single().title)
    }

    @Test
    fun emptyHistoryUsesProbabilityColdStartWithDiversity() {
        val items = listOf(
            media("1", "А", setOf("Фантастика"), null, ContentType.MOVIE, 2026, 8.8),
            media("2", "Б", setOf("Драма"), null, ContentType.SERIES, 2025, 8.7),
            media("3", "В", setOf("Комедия"), null, ContentType.MOVIE, 2024, 8.6),
            media("4", "Г", setOf("Триллер"), null, ContentType.SERIES, 2023, 8.5),
            media("5", "Д", setOf("Детектив"), null, ContentType.MOVIE, 2022, 8.4),
            media("6", "Е", setOf("Приключения"), null, ContentType.SERIES, 2021, 8.3),
            media("7", "Ж", setOf("Анимация"), null, ContentType.MOVIE, 2020, 8.2),
            media("8", "З", setOf("Ужасы"), null, ContentType.SERIES, 2019, 8.1),
        )

        val result = RecommendationEngine.recommend(
            history = emptyList(),
            repository = FakeCatalogRepository(items),
            limit = 8,
        )

        assertEquals("Подборка для знакомства", result.reason)
        assertEquals(8, result.items.size)
        assertTrue(result.items.map { it.type }.distinct().size >= 2)
        assertTrue(result.items.flatMap { it.genres }.distinct().size >= 4)
    }

    @Test
    fun watchedItemsAreExcludedEvenWhenHistoryIsLarge() {
        val watched = media("watched", "Просмотрено", setOf("Драма"), null, ContentType.MOVIE, 2020, 8.0)
        val unseen = media("unseen", "Не просмотрено", setOf("Драма"), null, ContentType.MOVIE, 2025, 8.0)
        val result = RecommendationEngine.recommend(
            history = listOf("Просмотрено"),
            repository = FakeCatalogRepository(listOf(watched, unseen)),
            limit = 1,
        )

        assertFalse(result.items.any { it.id == watched.id })
        assertEquals("Не просмотрено", result.items.single().title)
    }
}

private fun media(
    id: String,
    title: String,
    genres: Set<String>,
    director: String?,
    type: ContentType,
    year: Int,
    rating: Double,
): MediaContent = MediaContent(
    id = id,
    title = title,
    type = type,
    year = year,
    rating = rating,
    genres = genres,
    country = "Россия",
    quality = "1080p",
    durationMinutes = 100,
    category = if (type == ContentType.SERIES) CatalogCategory.TV_SERIES else CatalogCategory.MOVIES,
    director = director,
    cast = listOf(Person("Тестовый актёр")),
    popularity = 50,
    posterUrl = "test://$id",
)

private class FakeCatalogRepository(
    private val items: List<MediaContent>,
) : CatalogRepository {
    override fun getPaged(
        limit: Int,
        offset: Int,
        sort: CatalogSort,
        category: CatalogCategory?,
        filter: CatalogFilter?,
        query: String?,
    ): List<MediaContent> = items.drop(offset).take(limit)

    override fun getTotalCount(category: CatalogCategory?, filter: CatalogFilter?, query: String?): Int = items.size

    override fun getPopular(limit: Int): List<MediaContent> = items.take(limit)

    override fun getNew(limit: Int): List<MediaContent> = items.take(limit)

    override fun getRecommendationCandidates(
        genres: Set<String>,
        directors: Set<String>,
        excludedIds: Set<String>,
        limit: Int,
    ): List<MediaContent> = items.filter { item ->
        item.id !in excludedIds &&
            (genres.isEmpty() && directors.isEmpty() ||
                item.genres.any { it in genres } || item.director in directors)
    }.take(limit)

    override fun getSimilar(current: MediaContent, limit: Int): List<MediaContent> = items.filter { it.id != current.id }.take(limit)
    override fun getSequelsAndPrequels(movieId: String, limit: Int): List<MediaContent> = items.filter { it.id != movieId }.take(limit)

    override fun getAllGenres(): List<String> = items.flatMap { it.genres }.distinct()

    override fun search(query: String, limit: Int): List<MediaContent> = items.filter { it.title.contains(query, ignoreCase = true) }.take(limit)
    override fun searchFts(query: String, limit: Int): List<MediaContent> = search(query, limit)

    override fun searchPeople(query: String, limit: Int): List<Person> = emptyList()

    override fun findByTitle(title: String): MediaContent? = items.firstOrNull { it.title.equals(title, ignoreCase = true) }

    override fun findById(id: String): MediaContent? = items.firstOrNull { it.id == id }

    override fun findFullById(id: String): MediaContent? = findById(id)

    override fun findFullByTitle(title: String): MediaContent? = findByTitle(title)
}

package app.viora.android.data.catalog

import app.viora.android.domain.model.ContentType
import app.viora.android.domain.model.MediaContent
import app.viora.android.domain.model.Person

interface CatalogRepository {
    fun all(): List<MediaContent>
    fun search(query: String): List<MediaContent>
    fun searchPeople(query: String): List<Person>
    fun findByTitle(title: String): MediaContent?
}

object DemoCatalogRepository : CatalogRepository {
    private val catalog = listOf(
        MediaContent("world-border", "Граница миров", ContentType.SERIES, 2026, 8.1, setOf("Фантастика", "Драма"), "Испания", "1080p", 48, true, 95, originalTitle = "Border of Worlds", director = "Lucía Vega", cast = listOf("Marta Soler", "Diego Ríos")),
        MediaContent("quiet-signal", "Тихий сигнал", ContentType.MOVIE, 2025, 7.9, setOf("Триллер", "Драма"), "Франция", "1080p", 112, false, 91, ageRating = 18, audioLanguages = setOf("Original"), subtitleLanguages = setOf("Русский", "English")),
        MediaContent("last-flight", "Последний рейс", ContentType.MOVIE, 2026, 7.7, setOf("Триллер", "Приключения"), "Испания", "4K", 126, true, 88, originalTitle = "The Last Flight", director = "Álvaro Cruz", cast = listOf("Diego Ríos", "Nora Vidal")),
        MediaContent("north-wind", "Северный ветер", ContentType.SERIES, 2024, 8.0, setOf("Драма", "Детектив"), "Норвегия", "1080p", 52, false, 90),
        MediaContent("touch-light", "Касание света", ContentType.MOVIE, 2026, 7.6, setOf("Драма", "Мелодрама"), "Италия", "1080p", 108, true, 76),
        MediaContent("zero-orbit", "Нулевая орбита", ContentType.SERIES, 2026, 8.3, setOf("Фантастика", "Триллер"), "США", "4K", 46, true, 99, originalTitle = "Zero Orbit", director = "Elena Ward", cast = listOf("Maya Cole", "Jon Bell")),
        MediaContent("return-point", "Точка возврата", ContentType.MOVIE, 2026, 7.8, setOf("Фантастика", "Триллер"), "Германия", "1080p", 116, true, 84, ageRating = 16, audioLanguages = setOf("Original"), subtitleLanguages = setOf("English")),
        MediaContent("rain-city", "Город после дождя", ContentType.MOVIE, 2026, 8.0, setOf("Драма", "Комедия"), "Испания", "1080p", 102, true, 86, originalTitle = "Ciudad tras la lluvia", director = "Lucía Vega", cast = listOf("Marta Soler", "Irene Costa")),
        MediaContent("small-miracles", "Маленькие чудеса", ContentType.MOVIE, 2023, 7.4, setOf("Комедия", "Семейный"), "Испания", "720p", 94, false, 68, ageRating = 6, audioLanguages = setOf("Русский", "Original"), subtitleLanguages = setOf("Русский")),
        MediaContent("night-line", "Ночная линия", ContentType.SERIES, 2025, 7.5, setOf("Детектив", "Триллер"), "Великобритания", "1080p", 50, false, 79),
        MediaContent("viora-news", "Viora News", ContentType.TV, 2026, 7.2, setOf("Новости"), "Испания", "1080p", 0, false, 70),
        MediaContent("cinema-live", "Cinema Live", ContentType.TV, 2026, 7.9, setOf("Кино"), "Европа", "1080p", 0, false, 74),
    )

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
                item.cast.any { it.lowercase().contains(normalized) } ||
                item.year.toString().contains(normalized)
        }.sortedByDescending { it.popularity }
    }

    override fun searchPeople(query: String): List<Person> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        val credits = linkedSetOf<String>()
        catalog.forEach { item ->
            item.director?.let(credits::add)
            credits.addAll(item.cast)
        }
        return credits
            .filter { it.lowercase().contains(normalized) }
            .map { name ->
                Person(
                    name = name,
                    knownFor = catalog
                        .filter { it.director == name || name in it.cast }
                        .sortedByDescending { it.popularity }
                        .map { it.title },
                )
            }
            .sortedBy { it.name }
    }

    override fun findByTitle(title: String): MediaContent? = catalog.firstOrNull {
        it.title.equals(title, ignoreCase = true)
    }
}

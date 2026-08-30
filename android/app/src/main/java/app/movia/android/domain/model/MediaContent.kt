package app.movia.android.domain.model

enum class ContentType(val label: String) {
    MOVIE("Фильмы"),
    SERIES("Сериалы"),
    TV("ТВ"),
}

enum class CatalogCategory(val label: String) {
    MOVIES("Фильмы"),
    TV_SERIES("Сериалы"),
    LIMITED_SERIES("Мини-сериалы"),
    ANIMATION("Анимация"),
    ANIME("Аниме"),
    DRAMAS_ASIAN("Дорамы"),
    DOCUMENTARIES("Документальные"),
    THEATER_MUSICALS("Театр и мюзиклы"),
    STANDUP("Стендап"),
    INTERACTIVE("Интерактивное кино"),
}

data class Person(
    val name: String,
    val knownFor: List<String>,
)

data class MediaContent(
    val id: String,
    val title: String,
    val type: ContentType,
    val year: Int,
    val rating: Double,
    val genres: Set<String>,
    val country: String,
    val quality: String,
    val durationMinutes: Int,
    val isNew: Boolean = false,
    val popularity: Int = 0,
    val ageRating: Int = 16,
    val audioLanguages: Set<String> = setOf("Original"),
    val subtitleLanguages: Set<String> = emptySet(),
    val originalTitle: String? = null,
    val director: String? = null,
    val cast: List<String> = emptyList(),
    val synopsis: String? = null,
    val seasonEpisodeCounts: List<Int> = emptyList(),
    val relatedContentIds: List<String> = emptyList(),
    val sequelPrequelIds: List<String> = emptyList(),
    val imdbRating: Double? = null,
    val category: CatalogCategory = CatalogCategory.MOVIES,
    val sourceUrl: String? = null,
    val playbackUrl: String? = null,
    val posterUrl: String? = null,
    val licenseName: String? = null,
    val licenseUrl: String? = null,
)

package app.viora.android.domain.model

enum class ContentType(val label: String) {
    MOVIE("Фильмы"),
    SERIES("Сериалы"),
    TV("ТВ"),
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
    val audioLanguages: Set<String> = setOf("Русский", "Original"),
    val subtitleLanguages: Set<String> = setOf("Русский", "English"),
    val originalTitle: String? = null,
    val director: String? = null,
    val cast: List<String> = emptyList(),
)

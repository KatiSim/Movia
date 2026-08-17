package app.viora.android.domain.model

enum class ContentType(val label: String) {
    MOVIE("Фильмы"),
    SERIES("Сериалы"),
    TV("ТВ"),
}

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
)

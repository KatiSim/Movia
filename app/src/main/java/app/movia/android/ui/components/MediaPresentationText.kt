package app.movia.android.ui.components

import app.movia.android.domain.model.CatalogCategory
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent
import java.util.Locale

private val categoryGenreKeys = setOf(
    "фильмы", "сериалы", "тв", "tv", "movies", "series", "show", "shows",
)

private fun normalizedGenre(raw: String): String =
    raw.trim().lowercase(Locale.ROOT).replace('ё', 'е')

/**
 * One presentation vocabulary for genres across cards, details and search.
 * Catalog data may contain compound, duplicated or English labels; the UI exposes
 * one concise Russian label with a stable capitalized form.
 */
fun moviaLocalizedGenreLabel(raw: String): String {
    val key = normalizedGenre(raw)
    if (key.isBlank() || key in categoryGenreKeys) return ""

    return when {
        "боев" in key || "action" in key -> "Боевик"
        "приключ" in key || "adventure" in key -> "Приключения"
        "фантаст" in key || "science fiction" in key || "sci-fi" in key -> "Фантастика"
        "фэнтез" in key || "фентез" in key || "fantasy" in key -> "Фэнтези"
        "комед" in key || "comedy" in key -> "Комедия"
        "драм" in key || "drama" in key -> "Драма"
        "триллер" in key || "thriller" in key -> "Триллер"
        "криминал" in key || "crime" in key -> "Криминал"
        "детектив" in key || "mystery" in key -> "Детектив"
        "ужас" in key || "horror" in key -> "Ужасы"
        "мульт" in key || "animation" in key || "cartoon" in key -> "Мультфильм"
        "семейн" in key || "family" in key -> "Семейный"
        "мелодрам" in key || "romance" in key -> "Мелодрама"
        "вестерн" in key || "western" in key -> "Вестерн"
        "военн" in key || "war" in key -> "Военный"
        "истор" in key || "history" in key -> "История"
        "музык" in key || "music" in key -> "Музыка"
        "документ" in key || "documentary" in key -> "Документальный"
        "биограф" in key || "biograph" in key -> "Биография"
        "мюзикл" in key || "musical" in key -> "Мюзикл"
        "спорт" in key || "sport" in key -> "Спорт"
        "мыльн" in key || "soap" in key -> "Мыльная опера"
        "реалити" in key || "reality" in key -> "Реалити-шоу"
        "ток-шоу" in key || "talk show" in key -> "Ток-шоу"
        "новост" in key || "news" in key -> "Новости"
        else -> raw.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
                }
            }
    }
}

fun moviaPrimaryGenre(genres: Iterable<String>, excluding: String? = null): String? {
    val exclKey = excluding?.trim()?.lowercase(Locale.ROOT)
    val candidates = genres.asSequence()
        .map(::moviaLocalizedGenreLabel)
        .filter(String::isNotBlank)
        .distinct()
        .toList()

    if (exclKey.isNullOrBlank()) {
        return candidates.firstOrNull()
    }

    return candidates.firstOrNull {
        val k = it.lowercase(Locale.ROOT)
        k != exclKey && !k.contains(exclKey) && !exclKey.contains(k)
    }
}

fun moviaPrimaryGenre(item: MediaContent): String? {
    val typeLabel = moviaContentTypeLabel(item)
    return moviaPrimaryGenre(item.genres, excluding = typeLabel)
}

/** Explicit compact type label shared by every media card. */
fun moviaContentTypeLabel(item: MediaContent): String = when (item.category) {
    CatalogCategory.LIMITED_SERIES -> "Мини-сериал"
    CatalogCategory.ANIMATION -> "Мультфильм"
    CatalogCategory.ANIME -> "Аниме"
    CatalogCategory.DRAMAS_ASIAN -> "Дорама"
    CatalogCategory.DOCUMENTARIES -> "Документальный"
    CatalogCategory.THEATER_MUSICALS -> "Театр и мюзикл"
    CatalogCategory.STANDUP -> "Стендап"
    CatalogCategory.INTERACTIVE -> "Интерактивное кино"
    CatalogCategory.TV_SERIES -> "Сериал"
    CatalogCategory.MOVIES -> when (item.type) {
        ContentType.SERIES -> "Сериал"
        ContentType.TV -> "ТВ-шоу"
        ContentType.MOVIE -> "Фильм"
    }
}

fun moviaLocalizedGenreList(
    genres: Iterable<String>,
    limit: Int = 3,
): String = genres.asSequence()
    .map(::moviaLocalizedGenreLabel)
    .filter(String::isNotBlank)
    .distinct()
    .take(limit)
    .joinToString(" • ")

private val countryTranslations = mapOf(
    "united states of america" to "США",
    "united states" to "США",
    "usa" to "США",
    "us" to "США",
    "united kingdom" to "Великобритания",
    "great britain" to "Великобритания",
    "uk" to "Великобритания",
    "gb" to "Великобритания",
    "england" to "Великобритания",
    "france" to "Франция",
    "germany" to "Германия",
    "deutschland" to "Германия",
    "japan" to "Япония",
    "south korea" to "Южная Корея",
    "korea" to "Южная Корея",
    "canada" to "Канада",
    "italy" to "Италия",
    "italia" to "Италия",
    "spain" to "Испания",
    "españa" to "Испания",
    "russia" to "Россия",
    "russian federation" to "Россия",
    "australia" to "Австралия",
    "china" to "Китай",
    "india" to "Индия",
    "turkey" to "Турция",
    "türkiye" to "Турция",
    "sweden" to "Швеция",
    "norway" to "Норвегия",
    "denmark" to "Дания",
    "finland" to "Финляндия",
    "poland" to "Польша",
    "brazil" to "Бразилия",
    "mexico" to "Мексика",
    "argentina" to "Аргентина",
    "ireland" to "Ирландия",
    "new zealand" to "Новая Зеландия",
    "belgium" to "Бельгия",
    "netherlands" to "Нидерланды",
    "holland" to "Нидерланды",
    "switzerland" to "Швейцария",
    "austria" to "Австрия",
    "czech republic" to "Чехия",
    "czechia" to "Чехия",
    "hong kong" to "Гонконг",
    "taiwan" to "Тайвань",
    "thailand" to "Таиланд",
    "ukraine" to "Украина",
    "belarus" to "Беларусь",
    "kazakhstan" to "Казахстан",
    "philippines" to "Филиппины",
    "dominican republic" to "Доминикана",
    "colombia" to "Колумбия",
    "chile" to "Чили",
    "south africa" to "ЮАР",
    "egypt" to "Египет",
    "israel" to "Израиль",
    "greece" to "Греция",
    "portugal" to "Португалия",
    "hungary" to "Венгрия",
    "romania" to "Румыния",
    "bulgaria" to "Болгария",
    "iceland" to "Исландия",
    "serbia" to "Сербия",
    "croatia" to "Хорватия",
    "georgia" to "Грузия",
    "armenia" to "Армения",
    "azerbaijan" to "Азербайджан",
    "uzbekistan" to "Узбекистан",
    "singapore" to "Сингапур",
    "indonesia" to "Индонезия",
    "malaysia" to "Малайзия",
    "vietnam" to "Вьетнам",
    "iran" to "Иран",
    "saudi arabia" to "Саудовская Аравия",
    "united arab emirates" to "ОАЭ",
    "uae" to "ОАЭ",
    "morocco" to "Марокко",
)

/**
 * Universal Russian country localization dictionary for metadata and filters.
 */
fun moviaLocalizedCountry(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""

    if (trimmed.contains(",")) {
        return trimmed.split(",")
            .map { moviaLocalizedCountry(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
    }

    val key = trimmed.lowercase(Locale.ROOT)
    return countryTranslations[key] ?: trimmed.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
    }
}


private val moviaPlaybackEpisodeSuffix = Regex(
    """\s*·\s*S(\d{1,3})E(\d{1,4})(?:\s*·\s*(?:Эпизод|Серия)\s*\d+)?\s*$""",
    RegexOption.IGNORE_CASE,
)
private val moviaLegacyEpisodeSuffix = Regex(
    """\s*·\s*E(\d{1,4})(?:\s*·\s*(?:Эпизод|Серия)\s*\d+)?\s*$""",
    RegexOption.IGNORE_CASE,
)

/** User-facing title without internal playback SxxExx/Eyy suffixes. */
fun moviaDisplayTitle(raw: String): String {
    val cleaned = raw.trim().replace(Regex("\\s+"), " ")
    if (cleaned.isBlank()) return "Без названия"
    val withoutSeasonEpisode = moviaPlaybackEpisodeSuffix.replace(cleaned, "").trim()
    val withoutLegacyEpisode = moviaLegacyEpisodeSuffix.replace(withoutSeasonEpisode, "").trim()
    return withoutLegacyEpisode.ifBlank { "Без названия" }
}

/** Human-readable playback title while preserving episode context. */
fun moviaPlaybackDisplayTitle(raw: String): String {
    val cleaned = raw.trim().replace(Regex("\\s+"), " ")
    val match = moviaPlaybackEpisodeSuffix.find(cleaned)
    if (match != null) {
        val season = match.groupValues[1].toIntOrNull()
        val episode = match.groupValues[2].toIntOrNull()
        if (season != null && episode != null) {
            return "${moviaDisplayTitle(cleaned)} · Сезон $season · Серия $episode"
        }
    }
    val legacy = moviaLegacyEpisodeSuffix.find(cleaned)
    if (legacy != null) {
        val episode = legacy.groupValues[1].toIntOrNull()
        if (episode != null) return "${moviaDisplayTitle(cleaned)} · Серия $episode"
    }
    return moviaDisplayTitle(cleaned)
}

/** Unknown/invalid years are omitted instead of being replaced with a fabricated year. */
fun moviaYearLabel(year: Int): String? = year.takeIf { it in 1888..2100 }?.toString()

/** Ratings are shown only when they are finite values on the expected 0..10 scale. */
fun moviaRatingLabel(rating: Double): String? = rating
    .takeIf { it.isFinite() && it > 0.0 && it <= 10.0 }
    ?.let { String.format(Locale.US, "%.1f", it) }

fun moviaDurationLabel(minutes: Int, perEpisode: Boolean = false): String? {
    if (minutes <= 0) return null
    val hours = minutes / 60
    val rest = minutes % 60
    val base = when {
        hours <= 0 -> "$minutes мин"
        rest == 0 -> "$hours ч"
        else -> "$hours ч $rest мин"
    }
    return if (perEpisode) "$base/серия" else base
}

fun moviaRemainingMinutes(positionMs: Long, durationMs: Long): Int? {
    if (durationMs <= 0L || positionMs < 0L) return null
    val remaining = (durationMs - positionMs).coerceAtLeast(0L)
    return ((remaining + 59_999L) / 60_000L).toInt()
}

fun moviaRemainingLabel(positionMs: Long, durationMs: Long): String? =
    moviaRemainingMinutes(positionMs, durationMs)?.let { minutes ->
        if (minutes <= 0) "Просмотрено" else "Осталось $minutes мин"
    }

private fun moviaRussianCount(count: Int, one: String, few: String, many: String): String {
    val abs = kotlin.math.abs(count)
    val suffix = when {
        abs % 100 in 11..14 -> many
        abs % 10 == 1 -> one
        abs % 10 in 2..4 -> few
        else -> many
    }
    return "$count $suffix"
}

fun moviaSeasonCountLabel(count: Int): String? =
    count.takeIf { it > 0 }?.let { moviaRussianCount(it, "сезон", "сезона", "сезонов") }

fun moviaEpisodeCountLabel(count: Int): String? =
    count.takeIf { it > 0 }?.let { moviaRussianCount(it, "серия", "серии", "серий") }

fun moviaSeriesSeasonCount(item: MediaContent): Int? =
    item.seasonsCount.takeIf { it > 0 }
        ?: item.seasonEpisodeCounts.size.takeIf { it > 0 }

fun moviaSeriesEpisodeCount(item: MediaContent): Int? =
    item.episodesCount.takeIf { it > 0 }
        ?: item.seasonEpisodeCounts.takeIf { it.isNotEmpty() }?.sum()?.takeIf { it > 0 }

/** Compact, truthful facts for every ordinary poster card. */
fun moviaCardMetadataFacts(item: MediaContent): List<String> = buildList {
    moviaYearLabel(item.year)?.let(::add)
    moviaLocalizedCountry(item.country).takeIf { it.isNotBlank() }?.let(::add)
    (moviaPrimaryGenre(item) ?: moviaContentTypeLabel(item)).takeIf { it.isNotBlank() }?.let(::add)
}

/** Full media facts shared by Details and any future metadata surface. */
fun moviaDetailsMetadataFacts(item: MediaContent): List<String> = buildList {
    moviaYearLabel(item.year)?.let(::add)
    moviaLocalizedCountry(item.country).takeIf { it.isNotBlank() }?.let(::add)

    val isSeries = item.type == ContentType.SERIES ||
        item.category == CatalogCategory.TV_SERIES ||
        item.category == CatalogCategory.LIMITED_SERIES ||
        item.seasonEpisodeCounts.isNotEmpty() || item.seasonsCount > 0

    when {
        item.type == ContentType.TV -> {
            add("ТВ")
            add("Прямой эфир")
        }
        isSeries -> {
            moviaSeriesSeasonCount(item)?.let(::moviaSeasonCountLabel)?.let(::add)
            moviaSeriesEpisodeCount(item)?.let(::moviaEpisodeCountLabel)?.let(::add)
            moviaDurationLabel(item.durationMinutes, perEpisode = true)?.let(::add)
        }
        else -> {
            add(moviaContentTypeLabel(item))
            moviaDurationLabel(item.durationMinutes)?.let(::add)
        }
    }
}

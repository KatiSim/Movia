package app.movia.android.domain.model

internal fun inferStreamLanguage(rawLanguage: String?, voice: String?): String {
    val label = voice?.trim()?.lowercase().orEmpty()
    val strongSemantic = when {
        label.contains("укра") || label.contains("укр") || label.contains("ukr") || label.contains("dnipro") -> "uk"
        label.contains("original") || label.contains("оригинал") || label.contains("english") || label.contains("англ") -> "en"
        else -> null
    }

    val explicit = rawLanguage?.trim()?.lowercase().orEmpty()
    val normalizedRaw = when {
        explicit.isBlank() || explicit == "не указано" || explicit == "unknown" || explicit == "und" -> null
        explicit == "ua" || explicit.startsWith("uk") -> "uk"
        explicit.startsWith("en") -> "en"
        explicit.startsWith("ru") -> "ru"
        else -> explicit.substringBefore('-').trim().ifBlank { null }
    }

    // Strong non-RU semantic voice corrects contradictory/erroneous generic rawLanguage=ru
    if (normalizedRaw == "ru" && strongSemantic != null && strongSemantic != "ru") {
        return strongSemantic
    }

    // Explicit valid raw language wins over generic RU voice
    if (normalizedRaw != null) {
        return normalizedRaw
    }

    // If raw language is absent/unknown, infer from voice
    if (strongSemantic != null) return strongSemantic

    val genericRuSemantic = when {
        label.contains("дубляж") || label.contains("дублирован") ||
            label.contains("профессиональ") || label.contains("закадров") ||
            label.contains("lostfilm") || label.contains("кубик") ||
            label.contains("newstudio") || label.contains("red head") ||
            label.contains("hdrezka") -> "ru"
        else -> null
    }
    if (genericRuSemantic != null) return genericRuSemantic

    return "und"
}

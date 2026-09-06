package app.movia.android.domain.model

internal fun inferStreamLanguage(rawLanguage: String?, voice: String?): String {
    val label = voice?.trim()?.lowercase().orEmpty()
    val semantic = when {
        label.contains("укра") || label.contains("укр") || label.contains("ukr") || label.contains("dnipro") -> "uk"
        label.contains("english") || label.contains("англ") -> "en"
        else -> null
    }
    if (semantic != null) return semantic

    val explicit = rawLanguage?.trim()?.lowercase().orEmpty()
    if (explicit.isNotBlank()) {
        return when {
            explicit == "ua" || explicit.startsWith("uk") -> "uk"
            explicit.startsWith("en") -> "en"
            explicit.startsWith("ru") -> "ru"
            else -> explicit.substringBefore('-').ifBlank { explicit }
        }
    }
    return "ru"
}

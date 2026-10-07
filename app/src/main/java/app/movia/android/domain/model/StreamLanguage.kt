package app.movia.android.domain.model

import java.util.Locale

/** Provider studio labels and the word Original cannot establish an audio language. */
@Suppress("UNUSED_PARAMETER")
internal fun inferStreamLanguage(rawLanguage: String?, voice: String?): String {
    val explicit = rawLanguage?.trim()?.lowercase(Locale.ROOT).orEmpty()
    return when (explicit) {
        "ru", "russian", "русский" -> "ru"
        "en", "english", "английский" -> "en"
        "ua", "uk", "ukrainian", "украинский" -> "uk"
        "", "und", "unknown", "не указано", "auto", "null", "none" -> "und"
        else -> if (Regex("[a-z]{2,3}(?:[-_][a-z0-9]{2,8})*").matches(explicit))
            explicit.substringBefore('-').substringBefore('_') else "und"
    }
}

package app.movia.android.data.catalog

import java.text.Normalizer
import java.util.Locale

/**
 * UI boundary for user-facing catalog titles.  This does not participate in
 * canonical identity: IDs, media type, year and episode metadata remain the
 * identity fields.  It only prevents an unlocalized row from being rendered.
 */
object RussianDisplayTitlePolicy {
    private val cyrillic = Regex("[А-Яа-яЁё]")
    private val cjk = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]")

    fun isValid(value: String?, originalTitle: String? = null): Boolean {
        val title = clean(value)
        if (title.isBlank() || !cyrillic.containsMatchIn(title) || cjk.containsMatchIn(title)) {
            return false
        }
        val original = clean(originalTitle)
        return original.isBlank() || cyrillic.containsMatchIn(original) ||
            key(title) != key(original)
    }

    fun clean(value: String?): String = Normalizer.normalize(
        value.orEmpty(),
        Normalizer.Form.NFKC,
    ).trim().replace(Regex("\\s+"), " ")

    private fun key(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .filter { it.isLetterOrDigit() }
}

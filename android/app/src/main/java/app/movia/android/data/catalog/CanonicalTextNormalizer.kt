package app.movia.android.data.catalog

import java.text.Normalizer
import java.util.Locale

/** Must stay algorithmically aligned with media-parser/catalog_schema_v2.py. */
object CanonicalTextNormalizer {
    fun normalize(value: String?): String {
        val source = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        val output = StringBuilder(source.length)
        var pendingSpace = false
        for (char in source) {
            if (char.isLetterOrDigit()) {
                if (pendingSpace && output.isNotEmpty()) output.append(' ')
                output.append(if (char == 'ё') 'е' else char)
                pendingSpace = false
            } else {
                pendingSpace = true
            }
        }
        return output.toString().trim().replace(Regex("\\s+"), " ")
    }
}

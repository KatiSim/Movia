package app.movia.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamLanguageTest {
    @Test fun explicitLanguageWinsOverStudioAndOriginalLabels() {
        assertEquals("ja", inferStreamLanguage("ja", "Original"))
        assertEquals("ru", inferStreamLanguage("ru", "Original (English)"))
        assertEquals("en", inferStreamLanguage("en-US", "Дубляж"))
        assertEquals("uk", inferStreamLanguage("uk", "LostFilm"))
        assertEquals("es", inferStreamLanguage("es_ES", "HDRezka Studio"))
    }

    @Test fun studioAndOriginalLabelsNeverInventLanguage() {
        listOf("Original", "Оригинал (+субтитры)", "Дубляж", "LostFilm",
            "HDRezka Studio", "DniproFilm", "Профессиональный (МВО)").forEach {
            assertEquals(it, "und", inferStreamLanguage(null, it))
        }
    }

    @Test fun unknownAndMalformedMetadataStayUnknown() {
        listOf(null, "", "Не указано", "unknown", "und", "Auto", "Original",
            "invalid language").forEach {
            assertEquals("und", inferStreamLanguage(it, "Original (English)"))
        }
    }

    @Test fun explicitLanguageNamesAndAliasesNormalize() {
        assertEquals("uk", inferStreamLanguage("ua", null))
        assertEquals("ru", inferStreamLanguage("Russian", null))
        assertEquals("en", inferStreamLanguage("English", null))
        assertEquals("fr", inferStreamLanguage("fr-CA", null))
    }
}

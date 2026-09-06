package app.movia.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamLanguageTest {
    @Test fun explicitLanguageWins() {
        assertEquals("en", inferStreamLanguage("en-US", "Дубляж"))
        assertEquals("uk", inferStreamLanguage("uk", "Дубляж"))
    }

    @Test fun semanticVoiceOverridesContradictoryGenericLanguage() {
        assertEquals("en", inferStreamLanguage("ru", "Original (English)"))
        assertEquals("uk", inferStreamLanguage("ru", "Укр. Дубльований"))
        assertEquals(
            "en",
            inferStreamLanguage("ru", "Original (с субтитрами) collaps_tt4972582_Original%20%28English%29"),
        )
    }

    @Test fun languageIsInferredFromProviderVoiceWhenMissing() {
        assertEquals("en", inferStreamLanguage(null, "Original (English)"))
        assertEquals("uk", inferStreamLanguage(null, "Укр. Дубльований"))
        assertEquals("uk", inferStreamLanguage(null, "DniproFilm (укр)"))
        assertEquals("ru", inferStreamLanguage(null, "Дубляж"))
    }
}

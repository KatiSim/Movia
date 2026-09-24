package app.movia.android.data.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RussianDisplayTitlePolicyTest {
    @Test
    fun acceptsRussianTitleButRejectsOriginalAndCjk() {
        assertTrue(RussianDisplayTitlePolicy.isValid("Зверополис", "Zootopia"))
        assertFalse(RussianDisplayTitlePolicy.isValid("Zootopia", "Zootopia"))
        assertFalse(RussianDisplayTitlePolicy.isValid("劇場版 薬屋のひとりごと", "劇場版 薬屋のひとりごと"))
    }

    @Test
    fun allowsLatinAcronymInsideRussianTitle() {
        assertTrue(RussianDisplayTitlePolicy.isValid("Миссия: FIFA"))
    }
}

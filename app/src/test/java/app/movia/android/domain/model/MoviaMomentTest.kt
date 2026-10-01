package app.movia.android.domain.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class MoviaMomentTest {
    @Test
    fun requiredCalendarStatesResolveExactly() {
        val cases = linkedMapOf(
            "2026-01-01" to MoviaMoment.NEW_YEAR,
            "2026-01-20" to MoviaMoment.WINTER,
            "2026-02-14" to MoviaMoment.VALENTINE,
            "2026-04-15" to MoviaMoment.SPRING,
            "2026-07-15" to MoviaMoment.SUMMER,
            "2026-09-01" to MoviaMoment.SEPTEMBER_1,
            "2026-10-31" to MoviaMoment.HALLOWEEN,
            "2026-11-15" to MoviaMoment.DEFAULT,
            "2026-12-25" to MoviaMoment.NEW_YEAR,
        )
        cases.forEach { (date, expected) ->
            assertEquals(date, expected, resolveMoviaMoment(LocalDate.parse(date)))
        }
    }
}

package app.movia.android.domain.model

import java.time.LocalDate
import java.time.MonthDay

enum class MoviaMoment {
    DEFAULT,
    NEW_YEAR,
    WINTER,
    VALENTINE,
    SPRING,
    SUMMER,
    SEPTEMBER_1,
    HALLOWEEN,
}

private data class MoviaMomentWindow(
    val moment: MoviaMoment,
    val ranges: List<ClosedRange<MonthDay>>,
)

// Priority is intentional: first matching window wins. Keep all calendar policy here.
private val MoviaMomentWindows = listOf(
    MoviaMomentWindow(MoviaMoment.VALENTINE, listOf(MonthDay.of(2, 10)..MonthDay.of(2, 14))),
    MoviaMomentWindow(MoviaMoment.SEPTEMBER_1, listOf(MonthDay.of(8, 25)..MonthDay.of(9, 7))),
    MoviaMomentWindow(MoviaMoment.HALLOWEEN, listOf(MonthDay.of(10, 24)..MonthDay.of(10, 31))),
    MoviaMomentWindow(
        MoviaMoment.NEW_YEAR,
        listOf(
            MonthDay.of(12, 20)..MonthDay.of(12, 31),
            MonthDay.of(1, 1)..MonthDay.of(1, 8),
        ),
    ),
    MoviaMomentWindow(
        MoviaMoment.WINTER,
        listOf(
            MonthDay.of(1, 9)..MonthDay.of(2, 9),
            MonthDay.of(2, 15)..MonthDay.of(2, 29),
        ),
    ),
    MoviaMomentWindow(MoviaMoment.SPRING, listOf(MonthDay.of(3, 1)..MonthDay.of(5, 31))),
    MoviaMomentWindow(MoviaMoment.SUMMER, listOf(MonthDay.of(6, 1)..MonthDay.of(8, 24))),
)

fun resolveMoviaMoment(localDate: LocalDate): MoviaMoment {
    val day = MonthDay.from(localDate)
    return MoviaMomentWindows.firstOrNull { window ->
        window.ranges.any { range -> day >= range.start && day <= range.endInclusive }
    }?.moment ?: MoviaMoment.DEFAULT
}

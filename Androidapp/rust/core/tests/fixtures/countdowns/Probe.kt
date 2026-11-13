import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Verbatim decision block from CycleCareViewModel.updateCalculations (:491-508)
data class Row(val out: String)

fun countdowns(
    today: LocalDate,
    nextPeriodDate: LocalDate?,
    dayOfCycle: Int?,
    averageCycleLength: Int,
    lutealPhaseDays: Int,
    periodDuration: Int
): String {
    val cycleLength = averageCycleLength.coerceIn(21, 45)
    val daysUntilPeriod = nextPeriodDate?.let {
        ChronoUnit.DAYS.between(today, it).toInt().coerceAtLeast(0)
    } ?: 0
    val ovulationCycleDay = (cycleLength - lutealPhaseDays).coerceAtLeast(periodDuration + 2)
    val daysUntilOvu = dayOfCycle?.let { day ->
        if (day <= ovulationCycleDay) {
            ovulationCycleDay - day
        } else {
            cycleLength - day + ovulationCycleDay
        }
    } ?: 0
    return "$daysUntilPeriod|$ovulationCycleDay|$daysUntilOvu"
}

fun main() {
    val out = StringBuilder()
    val t = LocalDate.of(2026, 11, 3)
    // (nextPeriod, dayOfCycle, avgCycle, luteal, period)
    val cases = listOf(
        Quint(t.plusDays(10), 5, 28, 14, 5),
        Quint(t.plusDays(1), 28, 28, 14, 5),
        Quint(null, null, 28, 14, 5),
        Quint(t.plusDays(3), 15, 28, 14, 5),
        Quint(t.plusDays(3), 14, 28, 14, 5),
        Quint(t.plusDays(3), 16, 28, 14, 5),
        Quint(t.plusDays(20), 1, 20, 14, 5),   // avg clamped up to 21
        Quint(t.plusDays(20), 1, 50, 14, 5),   // avg clamped down to 45
        Quint(t.plusDays(4), 2, 28, 8, 3),     // RAW luteal 8 (below cycle_math clamp 10)
        Quint(t.plusDays(4), 2, 28, 20, 12),   // RAW luteal 20, period 12: ov floor period+2=14
        Quint(t.plusDays(4), 14, 28, 20, 12),  // day == ov
        Quint(t.plusDays(4), 15, 28, 20, 12),  // day > ov wrap
        Quint(t.plusDays(100), 28, 45, 16, 10),
        Quint(t.plusDays(1), 21, 21, 10, 2)
    )
    for (c in cases) out.append(countdowns(t, c.next, c.day, c.avg, c.lut, c.per)).append('\n')
    File("/tmp/countdownprobe/expected.txt").writeText(out.toString())
}
data class Quint(val next: LocalDate?, val day: Int?, val avg: Int, val lut: Int, val per: Int)

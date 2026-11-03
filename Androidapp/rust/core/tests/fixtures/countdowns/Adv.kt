import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

fun countdowns(today: LocalDate, nextPeriodDate: LocalDate?, dayOfCycle: Int?,
               averageCycleLength: Int, lutealPhaseDays: Int, periodDuration: Int): String {
    val cycleLength = averageCycleLength.coerceIn(21, 45)
    val daysUntilPeriod = nextPeriodDate?.let {
        ChronoUnit.DAYS.between(today, it).toInt().coerceAtLeast(0)
    } ?: 0
    val ovulationCycleDay = (cycleLength - lutealPhaseDays).coerceAtLeast(periodDuration + 2)
    val daysUntilOvu = dayOfCycle?.let { day ->
        if (day <= ovulationCycleDay) ovulationCycleDay - day
        else cycleLength - day + ovulationCycleDay
    } ?: 0
    return "$daysUntilPeriod|$ovulationCycleDay|$daysUntilOvu"
}

// Full updateCalculations anchor chain (:476-498), verbatim
fun chain(lastPeriodStartDate: String, approximatePeriodChoice: String?, cycleLengthOption: String?,
          averageCycleLength: Int, lutealPhaseDays: Int, periodDuration: Int,
          today: LocalDate, dayOfCycleIfAnchored: Int?): String {
    val dateChoice = approximatePeriodChoice?.trim()?.lowercase().orEmpty()
    val unknownDateChoice = dateChoice.isBlank() || dateChoice == "unknown" || dateChoice.contains("don't know") ||
        dateChoice.contains("don’t know") || dateChoice == "it varies"
    val clc = cycleLengthOption?.trim()?.lowercase().orEmpty()
    val unknownClc = clc.isBlank() || clc == "unknown" || clc.contains("don't know") ||
        clc.contains("don’t know") || clc.contains("changes")
    val hasCycleAnchor = lastPeriodStartDate.isNotBlank() && !unknownDateChoice && !unknownClc
    val approximateChoice = dateChoice.startsWith("approximate:") || dateChoice in setOf(
        "today", "a few days ago", "about a week ago", "about two weeks ago")
    val cycleLength = averageCycleLength.coerceIn(21, 45)
    val nextPeriodDate = if (hasCycleAnchor) {
        runCatching { LocalDate.parse(lastPeriodStartDate) }.getOrNull()?.let { lastStart ->
            var nextStart = lastStart
            while (!nextStart.isAfter(today)) nextStart = nextStart.plusDays(cycleLength.toLong())
            nextStart
        }
    } else null
    val cd = countdowns(today, nextPeriodDate, if (hasCycleAnchor) dayOfCycleIfAnchored else null,
        averageCycleLength, lutealPhaseDays, periodDuration)
    return "$hasCycleAnchor|$approximateChoice|${nextPeriodDate}|$cd"
}

fun main() {
    val out = StringBuilder()
    val t = LocalDate.of(2026, 11, 3)
    // Int-overflow family (malformed persisted/synced settings)
    out.append(countdowns(t, t.plusDays(5), 3, 28, Int.MIN_VALUE, 5)).append('\n')
    out.append(countdowns(t, t.plusDays(5), 3, 28, Int.MAX_VALUE, 5)).append('\n')
    out.append(countdowns(t, t.plusDays(5), 3, 28, 14, Int.MAX_VALUE)).append('\n')
    out.append(countdowns(t, t.plusDays(5), Int.MIN_VALUE, 28, 14, 5)).append('\n')
    out.append(countdowns(t, t.plusDays(5), Int.MAX_VALUE, 28, 14, 5)).append('\n')
    out.append(countdowns(t, LocalDate.of(99999999, 1, 1), 3, 28, 14, 5)).append('\n') // toInt() truncation
    out.append(countdowns(t, LocalDate.of(292000000, 1, 1), 3, 28, 14, 5)).append('\n') // ~2^31.4 days: wraps negative
    out.append("---chain---\n")
    out.append(chain("2026-10-20", "28 days ago", "28", 28, 14, 5, t, 14)).append('\n')
    out.append(chain("2026-10-20", "it varies", "28", 28, 14, 5, t, null)).append('\n')
    out.append(chain("2026-10-20", "approximate: 3 weeks", "28", 28, 14, 5, t, 20)).append('\n')
    out.append(chain("2026-10-20", "today", "I don't know", 28, 14, 5, t, null)).append('\n')
    out.append(chain("not-a-date", "28 days ago", "28", 28, 14, 5, t, null)).append('\n')
    out.append(chain("", "28 days ago", "28", 28, 14, 5, t, null)).append('\n')
    out.append(chain("2026-11-03", "a few days ago", "30", 21, 14, 5, t, 1)).append('\n') // nextStart must be AFTER today
    out.append(chain("2025-01-01", "about two weeks ago", "35", 35, 12, 4, t, 9)).append('\n')
    File("/tmp/countdownprobe/adv_expected.txt").writeText(out.toString())
}

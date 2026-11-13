import java.io.File

// Verbatim expressions from InsightsScreen.kt :134-153, :175-207, :225-234, :335-394
fun tiles(observed: Int, settingsAvg: Int, baselineAvg: Double, median: Int, variability: Int, totalLogged: Int): String {
    val label = if (observed == 0) "Setting" else if (observed == 1) "Interval" else "Average"
    val value = if (observed == 0) "${settingsAvg}d" else "${baselineAvg.toInt()}d"
    val med = if (observed == 0) "—" else "${median}d"
    val vari = if (observed < 2) "Need 2+" else "±${variability}d"
    val pts = "$totalLogged ${if (totalLogged == 1) "log" else "logs"}"
    return "$label|$value|$med|$vari|$pts"
}
fun visibleCount(showAll: Boolean, size: Int) = if (showAll) size else minOf(size, 1)
fun obsToggle(showAll: Boolean, size: Int) = if (showAll) "Show less" else "See all $size observations"
fun patToggle(showAll: Boolean, size: Int) = if (showAll) "Show fewer patterns" else "See all $size patterns"
fun tlToggle(showAll: Boolean, size: Int) = if (showAll) "Show less history" else "See all $size months"
fun tlEntry(cycleLengthDays: Int, periodBleedingDays: Int) =
    if (cycleLengthDays > 0) "${cycleLengthDays}d observed interval" else "$periodBleedingDays logged bleeding days"

fun main() {
    val out = StringBuilder()
    data class TC(val observed: Int, val settingsAvg: Int, val baselineAvg: Double, val median: Int, val variability: Int, val totalLogged: Int)
    val tileCases = listOf(
        TC(0, 28, 28.0, 0, 0, 0),
        TC(1, 28, 27.6, 27, 0, 1),
        TC(2, 28, 29.4, 29, 3, 2),
        TC(5, 30, 27.9, 28, 2, 1),
        TC(3, 21, 32.5, 33, 7, 40)
    )
    for (c in tileCases) {
        out.append(tiles(c.observed, c.settingsAvg, c.baselineAvg, c.median, c.variability, c.totalLogged)).append('\n')
    }
    out.append("---\n")
    for (sa in listOf(false, true)) for (sz in listOf(0, 1, 2, 5)) {
        out.append("$sa|$sz|${visibleCount(sa, sz)}|${obsToggle(sa, sz)}|${patToggle(sa, sz)}|${tlToggle(sa, sz)}\n")
    }
    out.append("---\n")
    out.append(tlEntry(27, 5)).append('\n')
    out.append(tlEntry(0, 5)).append('\n')
    out.append(tlEntry(0, 1)).append('\n')
    out.append(tlEntry(-1, 6)).append('\n')
    File("/tmp/insightsprobe/expected.txt").writeText(out.toString())
}

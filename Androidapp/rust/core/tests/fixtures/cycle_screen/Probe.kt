import java.io.File

// Verbatim from CycleScreen.kt :91, :158, :269-276
fun daysLeftChip(isPeriod: Boolean, averageCycleLength: Int, dayOfCycle: Int): Int =
    if (isPeriod) 0 else (averageCycleLength - dayOfCycle).coerceAtLeast(0)

fun selectedTitle(phaseTitle: String?, hasCycleAnchor: Boolean): String =
    phaseTitle ?: if (hasCycleAnchor) "No estimate for this date" else "Add a start date for estimates"

fun contentDesc(base: String, isPeriod: Boolean?, isOvulation: Boolean?, isFertile: Boolean?, hasLog: Boolean, isToday: Boolean): String {
    return buildString {
        append(base)
        if (isPeriod == true) append(", period estimate")
        else if (isOvulation == true) append(", ovulation estimate")
        else if (isFertile == true) append(", fertile window estimate")
        if (hasLog) append(", has a saved log")
        if (isToday) append(", today")
    }
}

fun main() {
    val out = StringBuilder()
    // chip: incl RAW-average quirk (avg 20 below clamp, day > avg)
    out.append(daysLeftChip(true, 28, 3)).append('\n')
    out.append(daysLeftChip(false, 28, 10)).append('\n')
    out.append(daysLeftChip(false, 28, 28)).append('\n')
    out.append(daysLeftChip(false, 20, 25)).append('\n')   // RAW 20, day 25 -> negative -> 0
    out.append(daysLeftChip(false, 20, 15)).append('\n')   // RAW 20 -> 5 (no 21 clamp!)
    out.append(daysLeftChip(false, 50, 45)).append('\n')   // RAW 50 -> 5
    out.append("---\n")
    out.append(selectedTitle("Follicular", true)).append('\n')
    out.append(selectedTitle(null, true)).append('\n')
    out.append(selectedTitle(null, false)).append('\n')
    out.append("---\n")
    out.append(contentDesc("5", null, null, null, false, false)).append('\n')
    out.append(contentDesc("6", true, true, true, true, true)).append('\n')
    out.append(contentDesc("7", false, true, true, false, false)).append('\n')
    out.append(contentDesc("8", false, false, true, true, false)).append('\n')
    out.append(contentDesc("9", false, false, false, false, true)).append('\n')
    File("/tmp/cyclescreenprobe/expected.txt").writeText(out.toString())
}

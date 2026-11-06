import java.io.File

// Verbatim from SomethingDifferentSheet.kt :189-195, :229-235, :70-81, :248, :292
fun main() {
    val out = StringBuilder()
    val chips = listOf(
        "Physical" to listOf("Cramps", "Headache", "Back pain", "Breast tenderness"),
        "Mood" to listOf("Anxious", "Irritable", "Tearful", "Low motivation"),
        "Energy & Sleep" to listOf("Heavy fatigue", "Brain fog", "Restless sleep"),
        "Pelvic" to listOf("Discharge change", "Spotting", "Deep pressure"),
        "Stomach" to listOf("Bloating", "Nausea", "Appetite surge")
    )
    for ((d, subs) in chips) out.append("$d=${subs.joinToString(",")}\n")
    out.append("---when---\n")
    for (d in listOf("Physical", "Mood", "Energy & Sleep", "Pelvic", "Stomach", "Unknown", "")) {
        val subOptions = when (d) {
            "Physical" -> listOf("Cramps", "Headache", "Back pain", "Breast tenderness")
            "Mood" -> listOf("Anxious", "Irritable", "Tearful", "Low motivation")
            "Energy & Sleep" -> listOf("Heavy fatigue", "Brain fog", "Restless sleep")
            "Pelvic" -> listOf("Discharge change", "Spotting", "Deep pressure")
            "Stomach" -> listOf("Bloating", "Nausea", "Appetite surge")
            else -> emptyList()
        }
        out.append("$d=${subOptions.joinToString(",")}\n")
    }
    out.append("---chips---\n")
    out.append(listOf("Cramps","Fatigue","Headache","Mood shift","Stomach pain","Bloating","Nausea","Restless sleep","Spotting","Just feel off").joinToString("|")).append('\n')
    out.append("---append---\n")
    for ((t, item) in listOf("" to "Cramps", "  " to "Nausea", "Headache" to "Bloating", "a, b" to "Spotting")) {
        out.append((if (t.isBlank()) item else "$t, $item")).append('\n')
    }
    out.append("---gates---\n")
    out.append((null as String?).let { it != null }.toString()).append('\n')
    out.append((listOf(1).isNotEmpty()).toString()).append('\n')
    File("/tmp/sheetprobe/expected.txt").writeText(out.toString())
}

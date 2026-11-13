import java.io.File

// Verbatim expressions from CareScreen.kt :165-167, :117, :206-209, :183, :303-317, :231
fun detail(t: List<String>) = t.filter { it.isNotBlank() }.distinct().joinToString("\n\n")
fun canSend(q: String, loading: Boolean) = q.trim().isNotEmpty() && !loading && q.length <= 1200
fun onSend(q: String, loading: Boolean): String {
    val question = q.trim()
    return if (question.isNotEmpty() && !loading && question.length <= 1200) question else "NO-SEND"
}
fun providerLine(name: String, model: String, eligible: Boolean, consent: Boolean, processors: List<String>, reason: String) =
    if (eligible && consent) "• $name ($model)" + if (processors.isNotEmpty()) " - via " + processors.joinToString(", ") else ""
    else "• $name - unavailable" + if (reason.isNotBlank()) ": $reason" else ""

fun main() {
    val out = StringBuilder()
    out.append(detail(listOf("a", "b", "c", "d", "e")).replace("\n", "\\n")).append('\n')
    out.append(detail(listOf("", "b", "", "d", "")).replace("\n", "\\n")).append('\n')
    out.append(detail(listOf("a", "b", "a", "b", "a")).replace("\n", "\\n")).append('\n')
    out.append(detail(listOf(" ", "  ", "x", "x", "\t")).replace("\n", "\\n")).append('\n')
    out.append(detail(listOf("", "", "", "", ""))).append('\n')
    out.append("---send---\n")
    out.append(canSend("hello", false)).append('\n')
    out.append(canSend("   ", false)).append('\n')
    out.append(canSend("hello", true)).append('\n')
    out.append(canSend("x".repeat(1200), false)).append('\n')
    out.append(canSend("x".repeat(1201), false)).append('\n')
    out.append(canSend(" " + "x".repeat(1199), false)).append('\n')  // raw 1200 ok
    out.append(canSend(" " + "x".repeat(1200), false)).append('\n')  // raw 1201 fails, trimmed 1200
    out.append(canSend("😀".repeat(600), false)).append('\n')        // 1200 UTF-16 units
    out.append(canSend("😀".repeat(601), false)).append('\n')        // 1202 units
    out.append("---onsend---\n")
    out.append(onSend("  hello  ", false)).append('\n')
    out.append(onSend("   ", false)).append('\n')
    out.append(onSend("hello", true)).append('\n')
    out.append(onSend(" " + "x".repeat(1200), false)).append('\n').append('\n')  // trims to 1200 -> SENDS
    out.append("---providers---\n")
    out.append(providerLine("Claude", "opus-4", true, true, listOf("Acme", "Sub Co"), "")).append('\n')
    out.append(providerLine("Claude", "opus-4", true, true, listOf(), "")).append('\n')
    out.append(providerLine("Gemini", "g-2", false, true, listOf(), "quota reached")).append('\n')
    out.append(providerLine("Gemini", "g-2", true, false, listOf(), "")).append('\n')
    out.append(providerLine("Grok", "x-1", false, false, listOf(), "  ")).append('\n')
    out.append("---style---\n")
    out.append(if (true) "• Botanical" else "Botanical").append('\n')
    out.append(if (false) "• Botanical" else "Botanical").append('\n')
    File("/tmp/careprobe/expected.txt").writeText(out.toString())
}

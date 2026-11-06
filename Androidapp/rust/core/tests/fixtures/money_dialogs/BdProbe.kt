import java.io.File
import java.math.BigDecimal

fun main() {
    val out = StringBuilder()
    val cases = listOf(
        "0.001", "30", "48.7655", "12.340", "0", "0.000000", "0.10",
        "100000000000000000000050",
        "1E+7", "0.0000001", "7E-9", "123E-8", "1.23E+5", "-4.500", "-0.0005", "999999999999999999999999.999"
    )
    for (c in cases) out.append("$c => ${BigDecimal(c)}").append('\n')
    // And the actual dialog line
    out.append("---\n")
    out.append("Need ${BigDecimal("30")} More Coins").append('\n')
    out.append("Need ${BigDecimal("0.001")} More Coins").append('\n')
    out.append("Need ${BigDecimal("48.7655")} More Coins").append('\n')
    File("/tmp/bdprobe/expected.txt").writeText(out.toString())
}

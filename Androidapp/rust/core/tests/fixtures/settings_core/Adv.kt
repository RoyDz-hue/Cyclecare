import java.io.File

private val skip = setOf("owner", "owner_id", "user_id", "revision", "server_revision", "hash", "payload_hash", "updated_at", "created_at", "device_id")

fun preview(payload: String): String = runCatching {
    val json = org.json.JSONObject(payload)
    val lines = mutableListOf<String>()
    val keys = json.keys()
    while (keys.hasNext() && lines.size < 12) {
        val key = keys.next()
        if (key in skip) continue
        val label = key.replace('_', ' ').replaceFirstChar { it.uppercase() }
        val value = json.opt(key)?.toString()?.replace('_', ' ')?.lowercase()?.take(120).orEmpty()
        if (value.isNotBlank() && value != "null") lines += "$label: $value"
    }
    lines.joinToString("\n").ifBlank { "(no readable details)" }.take(1000)
}.getOrElse { "(details can’t be shown safely)" }

fun main() {
    val out = StringBuilder()
    val cases = listOf(
        // org.json leniency family
        "{'mood':'HAPPY'}",                    // single quotes
        "{mood:\"HAPPY\"}",                    // unquoted key
        "{\"a\":1,\"a\":2}",                   // duplicate keys
        "{\"x\":0x10}",                        // hex number leniency
        "{\"x\":NaN}",                         // NaN literal
        "{\"x\":Infinity}",                    // Infinity literal
        "{\"x\":-Infinity}",
        "{\"x\":017}",                         // octal-looking
        "  {\"mood\":\"CALM\"}  ",             // surrounding whitespace
        "{\"k\":\"v\",}",                      // trailing comma
        "{mood:CALM}",                         // unquoted key AND unquoted value
        // special-letter case family
        "{\"greek_word\":\"ΟΔΟΣ\"}",           // final sigma in value
        "{\"ΟΔΟΣ\":\"x\"}",                    // final sigma in key (replaceFirstChar)
        "{\"dotted\":\"İSTANBUL\"}",           // dotted capital I
        "{\"ßeta_value\":\"STRASSE\"}",        // sharp-s key + value
        "{\"σ\":\"Σ\"}",                       // bare sigmas
        // numberToString family
        "{\"x\":1e10}",
        "{\"x\":10000000000}",
        "{\"x\":-0.0}",
        "{\"x\":0.30000000000000004}",
        "{\"x\":2147483648}",                  // > Int range -> Long
        "{\"x\":2147483647}",
        "{\"x\":1.0}",
        "{\"x\":1e-7}",
        "{\"x\":123456789012345678901234567890}" // beyond Long
    )
    for (p in cases) out.append(p.replace("\n", "\\n")).append(" => ").append(preview(p).replace("\n", "\\n")).append('\n')
    File("/tmp/settingsprobe/adv_expected.txt").writeText(out.toString())
}

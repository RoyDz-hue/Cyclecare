import java.io.File

private val conflictPreviewSkipKeys = setOf("owner", "owner_id", "user_id", "revision", "server_revision", "hash", "payload_hash", "updated_at", "created_at", "device_id")

fun boundedConflictPreview(payload: String): String = runCatching {
    val json = org.json.JSONObject(payload)
    val lines = mutableListOf<String>()
    val keys = json.keys()
    while (keys.hasNext() && lines.size < 12) {
        val key = keys.next()
        if (key in conflictPreviewSkipKeys) continue
        val label = key.replace('_', ' ').replaceFirstChar { it.uppercase() }
        val value = json.opt(key)?.toString()?.replace('_', ' ')?.lowercase()?.take(120).orEmpty()
        if (value.isNotBlank() && value != "null") lines += "$label: $value"
    }
    lines.joinToString("\n").ifBlank { "(no readable details)" }.take(1000)
}.getOrElse { "(details can’t be shown safely)" }

fun gates(recordId: String, localKind: String, localPayload: String?, remoteTombstone: Boolean, remoteRevision: Long, remotePayload: String?): String {
    val localMissing = localKind.equals("UPSERT", ignoreCase = true) && localPayload.isNullOrBlank()
    val keepDisabled = remoteTombstone || localMissing || localKind.equals("REMOTE", ignoreCase = true)
    val remoteValid = remoteRevision > 0L && (remoteTombstone || runCatching {
        val json = org.json.JSONObject(remotePayload ?: return@runCatching false)
        json.length() > 0
    }.getOrDefault(false))
    val label = if (recordId == "settings") "Cycle settings" else if (recordId == "profile") "Your profile" else "Saved day · $recordId"
    return "$localMissing|$keepDisabled|$remoteValid|$label"
}

fun cycleLengthIsSet(opt: String?): Boolean {
    val c = opt?.trim()?.lowercase().orEmpty()
    return c.isNotBlank() && c != "unknown" && !c.contains("don't know") && !c.contains("don’t know") && !c.contains("changes")
}

fun main() {
    val out = StringBuilder()
    val previews = listOf(
        """{"mood":"HAPPY","flow":"light_flow"}""",
        """{"owner":"x","user_id":"y"}""",
        """{"note":"NULL"}""",
        """{"note":null}""",
        """{"log":{"a":1}}""",
        "not json{",
        """{"k01":1,"k02":2,"k03":3,"k04":4,"k05":5,"k06":6,"k07":7,"k08":8,"k09":9,"k10":10,"k11":11,"k12":12,"k13":13,"k14":14}""",
        """{"story":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}""",
        """{"cramps":true,"pain":7,"temp":36.6}""",
        """{"sleep_quality":"VERY_GOOD"}""",
        """{"emoji":"😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀"}""",
        "{}",
        "{\"big\":\"" + "x".repeat(2000) + "\"}",
        """[1,2,3]""",
        "{" + (1..12).joinToString(",") { "\"k%02d\":\"".format(it) + "y".repeat(120) + "\"" } + "}"
    )
    for (p in previews) out.append(boundedConflictPreview(p).replace("\n", "\\n")).append('\n')
    out.append("---gates---\n")
    out.append(gates("settings", "UPSERT", """{"a":1}""", false, 5, """{"b":2}""")).append('\n')
    out.append(gates("profile", "UPSERT", null, false, 5, """{"b":2}""")).append('\n')
    out.append(gates("2026-11-01", "REMOTE", null, false, 5, """{"b":2}""")).append('\n')
    out.append(gates("2026-11-01", "upsert", "  ", true, 5, null)).append('\n')
    out.append(gates("2026-11-02", "LOCAL", "{}", false, 0, """{"b":2}""")).append('\n')
    out.append(gates("2026-11-03", "LOCAL", "{}", false, 5, "{}")).append('\n')
    out.append(gates("2026-11-04", "LOCAL", "{}", false, 5, "[1,2]")).append('\n')
    out.append(gates("2026-11-05", "LOCAL", "{}", false, 5, "junk{{")).append('\n')
    out.append(gates("2026-11-06", "REMOTE", null, false, 5, """{"b":2}""")).append('\n')
    out.append("---cycleLength---\n")
    for (o in listOf("28 days", "unknown", "I don't know", "I don’t know", "It changes", "  30 Days  ", "", null, "UNKNOWN", "my cycle changes a lot")) {
        out.append(cycleLengthIsSet(o)).append('\n')
    }
    out.append("---pinDraft---\n")
    for (r in listOf("12ab34", "12345", "١٢٣٤٥", "a", "", "𝟎𝟎𝟎𝟎", "12 3")) {
        out.append(r.filter { it.isDigit() }.take(4)).append('\n')
    }
    File("/tmp/settingsprobe/expected.txt").writeText(out.toString())
}

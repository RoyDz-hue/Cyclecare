package com.cyclecare.bridge

import com.cyclecare.app.data.model.ParsedLogInterpretation
import com.cyclecare.app.data.model.ParsedLogItem
import com.cyclecare.app.domain.parser.NaturalLanguageLogParser
import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject

object RustLogParserConfig { const val ENABLED = false }

/** Freeform daily-log parsing through the Rust core, with the exact production
 * NaturalLanguageLogParser as fallback. Pure interpretation preview: no writes, no health
 * record changes. Defense-parity shadow: the native interpretation is used only when it
 * EXACTLY equals the Kotlin parse of the same input; any difference and Kotlin wins.
 * Known bounded divergence (reported to reviewer): the fallback preview truncates at
 * 45 UTF-16 code units in Kotlin (can split a surrogate pair) vs 45 scalar values in Rust;
 * the shadow routes those inputs to Kotlin until the contract semantics are ruled on. */
object RustLogParser {
    fun parse(input: String): ParsedLogInterpretation {
        val kotlin = NaturalLanguageLogParser.parse(input)
        // Invalid UTF-16 (unpaired surrogates) cannot cross JNI faithfully: exact Kotlin result.
        if (hasUnpairedSurrogate(input)) return kotlin
        val native = nativeOrNull(input) ?: return kotlin
        // Full-interpretation equality: every ParsedLogInterpretation field (rawText, items,
        // followUpQuestion, flowLevelResult, energyResult, moodResult, symptomsResult,
        // painIntensityResult) and every ParsedLogItem field via data-class equals.
        return if (native == kotlin) native else kotlin
    }

    internal fun hasUnpairedSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return true
                i += 2
            } else if (c.isLowSurrogate()) {
                return true
            } else i++
        }
        return false
    }

    internal fun nativeOrNull(input: String): ParsedLogInterpretation? {
        val reply = CycleCareCoreBridge.call("parse_log_entry",
            JSONObject().put("text", input)) ?: return null
        val ok = reply.optJSONObject("ok") ?: return null
        return decode(ok)
    }

    internal fun decode(ok: JSONObject): ParsedLogInterpretation? = try {
        val rawText = ok.getString("rawText")
        val itemsArr = ok.getJSONArray("items")
        val items = mutableListOf<ParsedLogItem>()
        for (i in 0 until itemsArr.length()) {
            val o = itemsArr.getJSONObject(i)
            items.add(ParsedLogItem(
                category = o.getString("category"),
                label = o.getString("label"),
                detail = o.getString("detail"),
                iconEmoji = o.getString("iconEmoji")))
        }
        val symsArr = ok.getJSONArray("symptomsResult")
        val syms = mutableListOf<String>()
        for (i in 0 until symsArr.length()) {
            val s = symsArr.get(i)
            require(s is String)
            syms.add(s)
        }
        ParsedLogInterpretation(
            rawText = rawText,
            items = items,
            followUpQuestion = if (ok.isNull("followUpQuestion")) null else ok.getString("followUpQuestion"),
            flowLevelResult = if (ok.isNull("flowLevelResult")) null else ok.getString("flowLevelResult"),
            energyResult = if (ok.isNull("energyResult")) null else ok.getString("energyResult"),
            moodResult = if (ok.isNull("moodResult")) null else ok.getString("moodResult"),
            symptomsResult = syms,
            painIntensityResult = ok.getInt("painIntensityResult"))
    } catch (_: org.json.JSONException) { null } catch (_: IllegalArgumentException) { null }
}

package com.cyclecare.app.parity

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.domain.ai.*
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

fun main(args: Array<String>) {
    val out = File(args[0], "results_careai.tsv")
    out.writeText("")
    fun row(id: String, r: String) = out.appendText("$id\t$r\n")

    // serverSelectedSource
    row("s01", serverSelectedSource(JSONObject().put("provider", "cohere")))
    row("s02", serverSelectedSource(JSONObject()))
    row("s03", serverSelectedSource(JSONObject().put("provider", JSONObject.NULL).put("source", "legacy")))
    row("s04", serverSelectedSource(JSONObject().put("provider", "").put("source", "gemini")))

    // isRemoteCareResponse
    val cases = listOf("CycleCare Assistant", "CycleCare Assistant · cohere", "CycleCare AI",
        "CycleCare AI · x", "CycleCare Expert", "CycleCare Expert · y", "CycleCare AssistantX",
        "Curated care information", "cyclecare assistant", " CycleCare Assistant", "CycleCare Assistant ", "")
    cases.forEachIndexed { i, c -> row("r%02d".format(i + 1), "$c=>${isRemoteCareResponse(c)}") }

    // requestPayload
    row("p01", CareAiService.requestPayload("What does discharge mean?  ", false, null).toString())
    row("p02", CareAiService.requestPayload("q", true, "cycle summary here").toString())
    row("p03", CareAiService.requestPayload("q", true, "   ").toString())
    row("p04", CareAiService.requestPayload("q", false, "cycle summary here").toString())
    val hist = listOf(
        CareMemoryMessage("system", "sys"),
        CareMemoryMessage("user", "u1"), CareMemoryMessage("assistant", "a1"),
        CareMemoryMessage("user", "u2"), CareMemoryMessage("assistant", "a2"),
        CareMemoryMessage("user", "u3"), CareMemoryMessage("assistant", "a3"),
        CareMemoryMessage("user", "u4"), CareMemoryMessage("assistant", "x".repeat(700))
    )
    row("p05", CareAiService.requestPayload("q", false, null, hist).toString())
    val receipts = listOf(AiProcessorReceipt("COHERE", 2, "f".repeat(64)), AiProcessorReceipt("GEMINI", 1, "a".repeat(64)))
    row("p06", CareAiService.requestPayload("  " + "w".repeat(1300), true, "ctx", emptyList(), receipts).toString())

    // consult decision-tree paths (no network: stub client throws -> manifest failure)
    fun sig(r: CareResponse): String = JSONObject().put("summary", r.summary).put("whatText", r.whatText)
        .put("understandText", r.understandText).put("careText", r.careText).put("watchText", r.watchText)
        .put("escalateText", r.escalateText).put("safetyLevel", r.safetyAlert?.level?.name ?: "")
        .put("source", r.source).toString()
    val settings = CycleSettings()
    row("c01", sig(runBlocking { CareAiService.consult("I fainted and my bleeding is unusually heavy", null, settings) }))
    row("c02", sig(runBlocking { CareAiService.consult("Why am I so tired before my period?", null, settings) }))
    row("c03", sig(runBlocking { CareAiService.consult("zzq qqzz", null, settings) }))
    row("c04", sig(runBlocking { CareAiService.consult("zzq qqzz", null, settings, forceAi = true) }))
    val prefs = AuthPreferences()
    row("c05", sig(runBlocking { CareAiService.consult("zzq qqzz", null, settings, accessToken = "tok", aiSharingAllowed = true, authPreferences = prefs) }))
    row("c06", sig(runBlocking { CareAiService.consult("Why am I so tired before my period?", null, settings, accessToken = "tok", forceAi = true, aiSharingAllowed = true, authPreferences = prefs) }))
    val faintLog = CycleDayLog(dateString = "2026-11-01", symptomsCsv = "fainting", flowLevel = "UNUSUALLY_HEAVY")
    row("c07", sig(runBlocking { CareAiService.consult("just checking in", faintLog, settings) }))
    println("PROBE_DONE")
}

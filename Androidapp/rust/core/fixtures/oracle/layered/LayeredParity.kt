package com.cyclecare.app.parity

import com.cyclecare.app.data.economy.EconomyProfile
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.domain.ai.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal

fun main(args: Array<String>) {
    val out = File(args[0], "results_layered.tsv")
    out.writeText("")
    fun row(id: String, r: String) = out.appendText("$id\t$r\n")
    val settings = CycleSettings()
    val profile = EconomyProfile(userId = "o", coinBalance = BigDecimal("363"))
    fun sig(r: LayeredChatResult): String {
        val m = r.mainResponse
        return JSONObject().put("summary", m.summary).put("whatText", m.whatText)
            .put("understandText", m.understandText).put("careText", m.careText)
            .put("watchText", m.watchText).put("escalateText", m.escalateText)
            .put("safetyLevel", m.safetyAlert?.level?.name ?: "").put("source", m.source)
            .put("symptoms", r.detectedSymptoms.joinToString(",")).put("flow", r.detectedFlow ?: "")
            .put("energy", r.detectedEnergy ?: "").put("mood", r.detectedMood ?: "")
            .put("pain", r.painIntensity).put("coins", r.gentleProgressCoinsAwarded)
            .put("econ", r.economyIntent?.name ?: "").put("pay", r.paymentIntent?.name ?: "")
            .put("clarify", r.isClarificationNeeded).put("clarifyMsg", r.clarificationMessage ?: "")
            .put("choices", r.tiedDownChoices.joinToString(",") { it.id })
            .put("raw", r.rawQuery).toString()
    }
    fun proc(input: String, forceAi: Boolean = false, allowRemote: Boolean = false, token: String? = null): LayeredChatResult =
        runBlocking { LayeredChatOrchestrator.processRawChat(input, null, settings, profile,
            accessToken = token, forceAi = forceAi, allowRemoteAi = allowRemote) }
    row("l01", sig(proc("I fainted and my bleeding is unusually heavy")))
    row("l02", sig(proc("how many coins do I have?")))
    row("l03", sig(proc("show me the artifact store")))
    row("l04", sig(proc("can I transfer coins to a friend")))
    row("l05", sig(proc("help with mpesa top up")))
    row("l06", sig(proc("my mpesa payment failed and coins not received")))
    row("l07", sig(proc("my cramps are severe today")))
    row("l08", sig(proc("How can I ease period cramps?")))
    row("l09", sig(proc("zzq qqzz blorp")))
    row("l10", sig(proc("how many coins can I spend in the shop")))
    row("l11", sig(proc("why do I feel bloated before my period")))
    row("l12", sig(proc("zzq qqzz blorp", forceAi = true, allowRemote = true, token = "tok")))
    row("l13", sig(proc("zzq qqzz blorp", allowRemote = true, token = "tok")))
    println("PROBE_DONE")
}

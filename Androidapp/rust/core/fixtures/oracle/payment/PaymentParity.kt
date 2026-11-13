package com.cyclecare.app.parity

import com.cyclecare.app.data.economy.EconomyProfile
import com.cyclecare.app.data.economy.EconomyResult
import com.cyclecare.app.data.economy.SupabaseEconomyService
import com.cyclecare.app.data.payment.SupabasePaymentService
import com.cyclecare.app.domain.payment.PaymentPollResult
import com.cyclecare.app.domain.payment.PaymentUseCase
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

fun main(args: Array<String>) {
    val out = File(args[0], "results_payment.tsv")
    out.writeText("")
    fun row(id: String, r: String) = out.appendText("$id\t$r\n")
    fun sig(r: PaymentPollResult): String = when (r) {
        is PaymentPollResult.Success -> JSONObject().put("kind", "success").put("msg", r.message).put("userId", r.profile.userId).toString()
        is PaymentPollResult.Failed -> JSONObject().put("kind", "failed").put("status", r.status).put("msg", r.message).toString()
        is PaymentPollResult.Timeout -> JSONObject().put("kind", "timeout").put("msg", r.message).toString()
    }
    val pay = SupabasePaymentService()
    val econ = SupabaseEconomyService()
    fun prefs(): AuthPreferences = AuthPreferences()
    val profile = EconomyProfile(userId = "owner-1")
    fun uc(p: AuthPreferences, statuses: List<String>, refresh: (String) -> EconomyResult<EconomyProfile> = { EconomyResult.Success(EconomyProfile(userId = "owner-1")) }): PaymentUseCase {
        val it = statuses.iterator()
        return PaymentUseCase(pay, econ, p, checkStatus = { _ -> if (it.hasNext()) it.next() else "pending" }, refreshWallet = { uid -> refresh(uid) })
    }
    row("p01", sig(runBlocking { uc(prefs(), listOf("success")).pollPaymentStatus("ref", 30, profile) }))
    row("p02", sig(runBlocking { uc(prefs(), listOf("success"), { EconomyResult.Error("net down") }).pollPaymentStatus("ref", 30, profile) }))
    row("p03", sig(runBlocking { uc(prefs(), listOf("success"), { EconomyResult.Success(EconomyProfile(userId = "other")) }).pollPaymentStatus("ref", 30, profile) }))
    row("p04", sig(runBlocking { uc(prefs(), listOf("failed")).pollPaymentStatus("ref", 30, profile) }))
    row("p05", sig(runBlocking { uc(prefs(), listOf("refunded")).pollPaymentStatus("ref", 30, profile) }))
    row("p06", sig(runBlocking { uc(prefs(), listOf("auth_required")).pollPaymentStatus("ref", 30, profile) }))
    row("p07", sig(runBlocking { uc(prefs(), listOf("processing")).pollPaymentStatus("ref", 0, profile) }))
    row("p08", sig(runBlocking { uc(prefs(), listOf("pending", "pending", "success")).pollPaymentStatus("ref", 999, profile) }))
    val p9 = prefs()
    val uc9 = PaymentUseCase(pay, econ, p9, checkStatus = { _ -> p9.userId = "someone-else"; "pending" }, refreshWallet = { EconomyResult.Success(profile) })
    row("p09", sig(runBlocking { uc9.pollPaymentStatus("ref", 30, profile) }))
    row("p10", sig(runBlocking { uc(prefs(), listOf("success")).pollPaymentStatus("ref", 30, EconomyProfile(userId = "other")) }))
    val p11 = prefs(); p11.userId = null
    row("p11", sig(runBlocking { uc(p11, listOf("success")).pollPaymentStatus("ref", 30, profile) }))
    val p12 = prefs(); p12.isLocalOnlyMode = true
    row("p12", sig(runBlocking { uc(p12, listOf("success")).pollPaymentStatus("ref", 30, profile) }))
    row("p13", sig(runBlocking { uc(prefs(), listOf("success")).pollPaymentStatus("ref", -5, profile) }))
    row("p14", sig(runBlocking { uc(prefs(), listOf("reversed")).pollPaymentStatus("ref", 30, profile) }))
    row("p15", sig(runBlocking { uc(prefs(), listOf("cancelled")).pollPaymentStatus("ref", 30, profile) }))
    row("p16", sig(runBlocking { uc(prefs(), listOf("expired")).pollPaymentStatus("ref", 30, profile) }))
    println("PROBE_DONE delays=" + kotlinx.coroutines.recordedDelays.take(5))
}

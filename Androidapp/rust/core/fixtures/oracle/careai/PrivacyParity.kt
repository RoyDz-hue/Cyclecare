package com.cyclecare.app.parity

import com.cyclecare.app.domain.ai.PrivacyRpcClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.File

fun main(args: Array<String>) {
    val out = File(args[0], "results_privacy.tsv")
    out.writeText("")
    fun row(id: String, r: String) = out.appendText("$id\t$r\n")
    fun queue(codeOk: Boolean, body: String) {
        OkHttpClient.queuedResponses.addLast(Response(codeOk, ResponseBody(body.toByteArray(Charsets.UTF_8))))
    }
    val rpc = PrivacyRpcClient("https://probe.invalid/", "anon")
    // payload + url capture
    queue(true, """{"success":true}""")
    val u01 = runBlocking { rpc.updateConsents("tok", ai = true, cloud = false, image = true) }
    val req = OkHttpClient.recordedRequests.last()
    row("u01", "$u01|${req.url}|${req.body?.content}")
    queue(true, """{"success":"true"}""")
    row("u02", runBlocking { rpc.updateConsents("tok", true, true, true) }.toString())
    queue(true, """{}""")
    row("u03", runBlocking { rpc.updateConsents("tok", true, true, true) }.toString())
    queue(false, """{"success":true}""")
    row("u04", runBlocking { rpc.updateConsents("tok", true, true, true) }.toString())
    val before = OkHttpClient.recordedRequests.size
    row("u05", (runBlocking { rpc.updateConsents("  ", true, true, true) }.toString()) + "|calls=" + (OkHttpClient.recordedRequests.size - before))
    queue(true, " ".repeat(9000))
    row("u06", runBlocking { rpc.updateConsents("tok", true, true, true) }.toString())
    queue(true, """{"success":true,"health_data_deleted":true}""")
    row("d01", runBlocking { rpc.deleteCloudHealthCopy("tok") }.toString())
    queue(true, """{"success":true}""")
    row("d02", runBlocking { rpc.deleteCloudHealthCopy("tok") }.toString())
    queue(true, """{"success":true,"health_data_deleted":"true"}""")
    row("d03", runBlocking { rpc.deleteCloudHealthCopy("tok") }.toString())
    queue(true, """not json""")
    row("d04", runBlocking { rpc.deleteCloudHealthCopy("tok") }.toString())
    val beforeNull = OkHttpClient.recordedRequests.size
    row("d05", (runBlocking { rpc.deleteCloudHealthCopy(null) }.toString()) + "|calls=" + (OkHttpClient.recordedRequests.size - beforeNull))
    println("PROBE_DONE")
}

package com.cyclecare.app.lab

import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rust-execution lab self-check (LAB BUILD TYPE ONLY).
 *
 * Why this exists: in minified builds R8 strips every const-false Rust
 * adapter call site, so a production-shaped APK contains the native library
 * and the JNI bridge but NO live caller path - an ordinary-launch device
 * test would exercise nothing. This lab variant keeps one explicit caller
 * path (this object, kept via proguard-lab.pro) that performs the JNI
 * handshake and drives the JSON dispatcher directly with synthetic inputs,
 * so an instrumented test can prove Rust code actually executes under ART
 * in an R8-processed package.
 *
 * Hard boundaries:
 * - Read-only, pure contracts only: no accounts, no sign-in, no network,
 *   no local state, no Keystore, no user data. All inputs are synthetic.
 * - Lab-only: compiled only into the `lab` build type (applicationId
 *   com.cyclecare.app.lab, auto debug cert). Production and slim builds do
 *   not contain this class; nothing here enables a production Rust flag.
 * - A call() reply of null means the native library did not answer; the
 *   instrumented test fails, because "Kotlin fallback silently took over"
 *   is exactly what this lab exists to detect.
 */
object RustLabSelfCheck {

    data class ContractResult(
        val contract: String,
        val answered: Boolean,
        val detail: String,
    )

    /** Runs every lab contract assertion; returns one result per contract. */
    fun runAll(): List<ContractResult> = listOf(
        handshake(),
        completePin(),
        coinAmount(),
        baseline(),
        effectiveUserId(),
        startupGate(),
    )

    /** JSON summary for logcat/reporting. */
    fun runAllAsJson(): String {
        val arr = JSONArray()
        runAll().forEach { r ->
            arr.put(JSONObject()
                .put("contract", r.contract)
                .put("answered", r.answered)
                .put("detail", r.detail))
        }
        return JSONObject().put("results", arr).toString()
    }

    private fun handshake(): ContractResult {
        // call() internally performs ensureLoaded() -> nativeCoreHandshake()==42,
        // so a non-null reply is proof of load + handshake under ART.
        val reply = CycleCareCoreBridge.call("is_complete_pin", JSONObject().put("pin", "1234"))
        return ContractResult(
            "handshake_via_is_complete_pin",
            reply != null,
            if (reply != null) "native answered (load+handshake 42)" else "native UNAVAILABLE",
        )
    }

    private fun completePin(): ContractResult {
        val full = CycleCareCoreBridge.call("is_complete_pin", JSONObject().put("pin", "1234"))
        val short = CycleCareCoreBridge.call("is_complete_pin", JSONObject().put("pin", "12"))
        val ok = full?.optBoolean("ok") == true && short?.optBoolean("ok") == false
        return ContractResult("is_complete_pin", ok, "1234->true, 12->false (oracle: host pin tests)")
    }

    private fun coinAmount(): ContractResult {
        val accepted = CycleCareCoreBridge.call(
            "sanitize_coin_amount", JSONObject().put("raw", "12.5"))
        val rejected = CycleCareCoreBridge.call(
            "sanitize_coin_amount", JSONObject().put("raw", ".5"))
        val okA = accepted?.optJSONObject("ok")
        val okR = rejected?.optJSONObject("ok")
        val ok = okA != null && okA.getString("text") == "12.5" && okA.isNull("rejection") &&
            okR != null && okR.getString("text") == ".5" &&
            okR.getString("rejection") == "Start the amount with a digit, for example 0.5."
        return ContractResult("sanitize_coin_amount", ok, "12.5 accepted, .5 rejected with exact message")
    }

    private fun baseline(): ContractResult {
        // Fixture + oracle copied from the host-verified instrumented fixture
        // (PatternEngine Kotlin oracle: average 29.0, median 29, min 28, max 30,
        // variability 1, regular, 5 days, 2 intervals).
        val settings = JSONObject()
            .put("averageCycleLength", 28)
            .put("periodDuration", 5)
            .put("lutealPhaseDays", 14)
            .put("lastPeriodStartDate", "2026-10-05")
        val logs = JSONArray()
            .put(JSONObject().put("dateString", "2026-10-05").put("flowLevel", "MODERATE").put("symptomsCsv", ""))
            .put(JSONObject().put("dateString", "2026-10-06").put("flowLevel", "LIGHT").put("symptomsCsv", ""))
            .put(JSONObject().put("dateString", "2026-09-05").put("flowLevel", "MODERATE").put("symptomsCsv", ""))
            .put(JSONObject().put("dateString", "2026-08-08").put("flowLevel", "HEAVY").put("symptomsCsv", ""))
            .put(JSONObject().put("dateString", "2026-10-20").put("flowLevel", "").put("symptomsCsv", "cramps"))
        val ok = CycleCareCoreBridge.call("compute_baseline",
            JSONObject().put("settings", settings).put("logs", logs))?.optJSONObject("ok")
        val match = ok != null &&
            ok.getDouble("averageCycleLength") == 29.0 &&
            ok.getInt("medianCycleLength") == 29 &&
            ok.getInt("minCycleLength") == 28 &&
            ok.getInt("maxCycleLength") == 30 &&
            ok.getInt("variabilityDays") == 1 &&
            ok.getBoolean("isRegular") &&
            ok.getInt("totalLoggedDays") == 5 &&
            ok.getInt("observedCycleIntervals") == 2
        return ContractResult("compute_baseline", match, "5-log synthetic fixture, 8 exact values")
    }

    private fun effectiveUserId(): ContractResult {
        // Self-oracle: the test process computes the Kotlin oracle (UUID v3)
        // independently; the native reply must equal it.
        val deviceId = "lab-device-0001"
        val expected = java.util.UUID.nameUUIDFromBytes(deviceId.toByteArray()).toString()
        val reply = CycleCareCoreBridge.call("effective_user_id", JSONObject()
            .put("userId", JSONObject.NULL)
            .put("deviceId", deviceId))
        val actual = reply?.optString("ok")
        return ContractResult(
            "effective_user_id",
            actual == expected,
            "null userId -> UUIDv3($deviceId) = $expected; native=$actual",
        )
    }

    private fun startupGate(): ContractResult {
        // Oracle from rust/core/tests/startup_lifecycle_trace.rs
        // "gate_returning_complete" (matches executed JVM probes):
        // inputs (false,true,true,true,true,false,false,false,true) ->
        // auth=true cloudCheck=false locked=false onboarding=false fetch=true
        val ok = CycleCareCoreBridge.call("startup_gate", JSONObject()
            .put("local", false)
            .put("secure", true)
            .put("stored", true)
            .put("profile", true)
            .put("onboardingCompleted", true)
            .put("pinEnabled", false)
            .put("pinPresent", false)
            .put("fingerprintEnabled", false)
            .put("userIdPresent", true))?.optJSONObject("ok")
        val match = ok != null &&
            ok.optBoolean("hasCompletedInitialAuth") &&
            !ok.optBoolean("needsCloudProfileCheck") &&
            !ok.optBoolean("isLocked") &&
            !ok.optBoolean("showOnboarding") &&
            ok.optBoolean("fetchWallet")
        return ContractResult("startup_gate", match, "gate_returning_complete oracle from host trace test")
    }
}

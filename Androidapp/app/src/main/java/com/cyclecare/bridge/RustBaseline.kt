package com.cyclecare.bridge

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.domain.pattern.BaselineMetrics
import com.cyclecare.app.domain.pattern.PatternEngine
import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONArray
import org.json.JSONObject

object RustBaselineConfig { const val ENABLED = false }

/** Pure snapshot computation: does not read or write accounts, logs or settings.
 * Caller is synchronous, so native introduces no new suspended publication.
 * Existing updateCalculations caller/owner races remain separate hardening work.
 */
object RustBaseline {
    fun compute(settings: CycleSettings, logs: List<CycleDayLog>): BaselineMetrics =
        nativeOrNull(settings, logs) ?: PatternEngine.computeBaseline(settings, logs)

    fun nativeOrNull(settings: CycleSettings, logs: List<CycleDayLog>): BaselineMetrics? {
        // Kotlin LocalDate and chrono accept different textual date languages.
        // Unsupported/noncanonical inputs keep the exact production Kotlin path.
        fun canonical(s: String): Boolean = try {
            s.length == 10 && java.time.LocalDate.parse(s).toString() == s
        } catch (_: java.time.DateTimeException) { false }
        if (settings.lastPeriodStartDate.isNotEmpty() && !canonical(settings.lastPeriodStartDate)) return null
        if (logs.any { !canonical(it.dateString) }) return null
        val payload = JSONObject().put("settings", JSONObject()
            .put("averageCycleLength", settings.averageCycleLength)
            .put("periodDuration", settings.periodDuration)
            .put("lutealPhaseDays", settings.lutealPhaseDays)
            .put("lastPeriodStartDate", settings.lastPeriodStartDate))
            .put("logs", JSONArray().apply { logs.forEach { log ->
                put(JSONObject().put("dateString",log.dateString).put("flowLevel",log.flowLevel)
                    .put("symptomsCsv",log.symptomsCsv))
            } })
        val ok=CycleCareCoreBridge.call("compute_baseline",payload)?.optJSONObject("ok") ?: return null
        return decode(ok)
    }

    internal fun decode(ok: JSONObject): BaselineMetrics? = try {
        fun integer(key:String):Int {
            val n=ok.get(key)
            require(n is Int || n is Long)
            val l=(n as Number).toLong();require(l in Int.MIN_VALUE..Int.MAX_VALUE)
            return l.toInt()
        }
        val average=ok.get("averageCycleLength")
        require(average is Number && average.toDouble().isFinite())
        val regular=ok.get("isRegular");require(regular is Boolean)
        BaselineMetrics(average.toDouble(),integer("medianCycleLength"),integer("minCycleLength"),
            integer("maxCycleLength"),integer("variabilityDays"),regular,
            integer("totalLoggedDays"),integer("observedCycleIntervals"))
    } catch (_: org.json.JSONException) { null } catch (_: IllegalArgumentException) { null }
}

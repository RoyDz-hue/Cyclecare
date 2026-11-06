package com.cyclecare.bridge

import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.model.CyclePhase
import com.cyclecare.app.domain.pattern.PatternEngine
import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject

object RustCyclePhaseConfig { const val ENABLED = false }

/** Cycle phase for a date through the Rust core, with the exact
 * PatternEngine.calculatePhaseForDate result for the same inputs.
 * Pure snapshot computation: reads nothing, writes nothing.
 * With [RustCyclePhaseConfig.ENABLED] false this is the exact Kotlin function
 * with no JNI and no logging. When enabled, the native answer is validated and
 * compared against the production Kotlin result; any divergence keeps the exact
 * Kotlin answer (status-quo shadow). NOTE: no callsites are wired to this
 * adapter yet; PatternEngine callers still invoke Kotlin directly.
 */
object RustCyclePhase {
    fun forDate(date: java.time.LocalDate, settings: CycleSettings): CyclePhase {
        // Flag off: exact Kotlin, no JNI, no logging. Current state.
        if (!RustCyclePhaseConfig.ENABLED) return PatternEngine.calculatePhaseForDate(date, settings)
        val k = PatternEngine.calculatePhaseForDate(date, settings)
        val n = nativeOrNull(date, settings)
        // Status-quo shadow: a divergence keeps the exact Kotlin answer even if enabled.
        return if (n != null && n == k) n else k
    }

    internal fun nativeOrNull(date: java.time.LocalDate, settings: CycleSettings): CyclePhase? {
        // Kotlin LocalDate.parse and chrono accept different textual date languages.
        // Unsupported/noncanonical settings dates keep the exact production Kotlin path.
        fun canonical(s: String): Boolean = try {
            s.length == 10 && java.time.LocalDate.parse(s).toString() == s
        } catch (_: java.time.DateTimeException) { false }
        if (settings.lastPeriodStartDate.isNotEmpty() && !canonical(settings.lastPeriodStartDate)) return null
        val payload = JSONObject()
            .put("date", date.toString())
            .put("settings", JSONObject()
                .put("averageCycleLength", settings.averageCycleLength)
                .put("periodDuration", settings.periodDuration)
                .put("lutealPhaseDays", settings.lutealPhaseDays)
                .put("lastPeriodStartDate", settings.lastPeriodStartDate))
        val ok = CycleCareCoreBridge.call("calculate_phase_for_date", payload)?.optJSONObject("ok") ?: return null
        return decode(ok)
    }

    /** Strict decode: AOSP getString would coerce a non-string; require an actual
     * JSON string matching a real CyclePhase entry name. Anything else decodes to
     * null and the exact Kotlin path answers. */
    internal fun decode(ok: JSONObject): CyclePhase? = try {
        val p = ok.get("phase")
        require(p is String)
        CyclePhase.valueOf(p)
    } catch (_: org.json.JSONException) { null }
      catch (_: IllegalArgumentException) { null }
}

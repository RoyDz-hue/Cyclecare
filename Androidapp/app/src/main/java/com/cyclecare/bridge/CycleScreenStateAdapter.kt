// Cycle screen state path over the EXISTING transitional JNI bridge
// (com.cyclecare.app.rust.CycleCareCoreBridge on the integration branch):
// uses call() with its ensureLoaded + null-fallback contract - null means
// native unavailable/failed and the caller keeps the production Kotlin
// path. No direct nativeCall, no duplicate bridge class. ISOLATED SOURCE -
// compile-checked, not wired into the app.
package com.cyclecare.bridge

import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONException
import org.json.JSONObject

data class DayPredictionModel(
    val date: String,
    val dayOfCycle: Int,
    val phase: String, // Kotlin enum entry name, e.g. "LUTEAL"
    val phaseTitle: String,
    val phaseSubtitle: String,
    val phaseDescription: String,
    val phaseAdvice: String,
    val phaseColorHex: Long,
    val isPeriod: Boolean,
    val isFertile: Boolean,
    val isOvulation: Boolean,
    val fertilityConfidence: String,
    val pregnancyChance: String
)

data class CycleScreenStateModel(
    val hasCycleAnchor: Boolean,
    val cycleStartIsApproximate: Boolean,
    val daysUntilNextPeriod: Int,
    val nextPeriodDate: String?,
    val daysUntilOvulation: Int,
    val isPeriodActiveToday: Boolean,
    val todayPrediction: DayPredictionModel?,
    val selectedDatePrediction: DayPredictionModel?,
    val selectedDayOfCycleText: String,
    val daysUntilPeriodFromSelected: Int?,
    val selectedDayTitle: String,
    val disclaimer: String
)


/** The exact inputs a month-predictions map depends on. Value equality
 *  makes any settings/anchor/choices/today change observable to the
 *  host's remember() key, so a stable provider lambda can never serve
 *  stale predictions (source parity: getPredictionForDate reads
 *  _uiState.value on every call). */
data class CyclePredictionInputs(
    val lastPeriodStartDate: String,
    val averageCycleLength: Int,
    val periodDuration: Int,
    val lutealPhaseDays: Int,
    val dateChoice: String?,
    val cycleLengthChoice: String?,
    /** Typed date (source: uiState.currentDate): ISO validity is a
     *  compile-time property of the type, not a runtime parse gamble. */
    val today: java.time.LocalDate,
    /** The VM's OWN uiState.hasCycleAnchor (:483) - the anchor gate is
     *  taken from the single state source, never re-derived here. The
     *  native contract computes the same gate from the choices + anchor
     *  (corpus-locked parity); carried here so the Kotlin fallback and
     *  the cache key share exactly one truth. */
    val hasCycleAnchor: Boolean
)

object CycleScreenStateAdapter {

    /** null -> the native core is unavailable or the call failed; the
     *  caller MUST keep its existing Kotlin computation (bridge contract). */
    fun fetchState(
        lastPeriodStartDate: String,
        averageCycleLength: Int,
        periodDuration: Int,
        lutealPhaseDays: Int,
        dateChoice: String?,
        cycleLengthChoice: String?,
        today: String,
        selectedDate: String
    ): CycleScreenStateModel? {
        val payload = JSONObject()
            .put("settings", JSONObject()
                .put("lastPeriodStartDate", lastPeriodStartDate)
                .put("averageCycleLength", averageCycleLength)
                .put("periodDuration", periodDuration)
                .put("lutealPhaseDays", lutealPhaseDays))
            .put("dateChoice", dateChoice ?: JSONObject.NULL)
            .put("cycleLengthChoice", cycleLengthChoice ?: JSONObject.NULL)
            .put("today", today)
            .put("selectedDate", selectedDate)
        val ok = CycleCareCoreBridge.call("cycle_screen_state", payload)
            ?.optJSONObject("ok") ?: return null
        // Malformed native payload (missing key / wrong type -> org.json
        // get* throws JSONException) takes the SAME fallback as a null
        // call(): keep the production Kotlin path, never propagate.
        return try {
            parse(ok)
        } catch (e: org.json.JSONException) {
            null
        }
    }

    /**
     * Batch month-grid contract ("cycle_month_predictions"): ONE bridge
     * call per displayed month covering [first ..= last] (grid cells;
     * neighbor lookups are source-guarded inside the month), replacing per-cell JNI calls.
     * null -> native unavailable/failed/malformed: the caller MUST keep its
     * existing Kotlin computation (bridge contract), e.g. substitute its
     * own Kotlin-built map for that month.
     */
    private fun fetchMonthPredictions(
        lastPeriodStartDate: String,
        averageCycleLength: Int,
        periodDuration: Int,
        lutealPhaseDays: Int,
        dateChoice: String?,
        cycleLengthChoice: String?,
        today: String,
        month: String, // Kotlin YearMonth.toString(): "yyyy-MM"
        expectedHasCycleAnchor: Boolean
    ): Map<String, DayPredictionModel>? {
        val payload = JSONObject()
            .put("settings", JSONObject()
                .put("lastPeriodStartDate", lastPeriodStartDate)
                .put("averageCycleLength", averageCycleLength)
                .put("periodDuration", periodDuration)
                .put("lutealPhaseDays", lutealPhaseDays))
            .put("dateChoice", dateChoice ?: JSONObject.NULL)
            .put("cycleLengthChoice", cycleLengthChoice ?: JSONObject.NULL)
            .put("today", today)
            .put("month", month)
        val ok = CycleCareCoreBridge.call("cycle_month_predictions", payload)
            ?.optJSONObject("ok") ?: return null
        return decodeRequestedMonth(ok, expectedHasCycleAnchor, java.time.YearMonth.parse(month))
    }

    /** Response must correspond to the captured gate and exactly the requested
     * month. Native disagreement is unavailability, never a different UI gate. */
    internal fun decodeRequestedMonth(
        ok: JSONObject?, expectedHasCycleAnchor: Boolean, month: java.time.YearMonth
    ): Map<String, DayPredictionModel>? {
        if (ok == null) return null
        return try {
            if (reqBool(ok, "hasCycleAnchor") != expectedHasCycleAnchor) return null
            if (reqStr(ok, "month") != month.toString()) return null
            val map = parseMonth(ok)
            val entries = ok.getJSONArray("predictions")
            // Production Rust returns zero entries when the VM-equivalent
            // anchor gate is false. It must not send hidden predictions.
            if (!expectedHasCycleAnchor) return if (entries.length() == 0) emptyMap() else null
            if (entries.length() != month.lengthOfMonth()) return null
            val seen = HashSet<String>()
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val key = reqStr(entry, "date")
                val date = java.time.LocalDate.parse(key)
                if (java.time.YearMonth.from(date) != month || !seen.add(key)) return null
                val pred = map[key]
                if (pred != null && (pred.date != key || !expectedHasCycleAnchor)) return null
            }
            map
        } catch (_: JSONException) { null }
        catch (_: java.time.DateTimeException) { null }
    }

    /** Inputs-object overload: the values sent on the wire are exactly the
     *  values the host keys its cache on, so wire and freshness can never
     *  drift apart. null = native unavailable/failed/malformed; the
     *  fallback lives in exactly one place (CycleScreenRustMonthProvider). */
    fun fetchMonthPredictions(
        inputs: CyclePredictionInputs,
        month: String
    ): Map<String, DayPredictionModel>? = fetchMonthPredictions(
        inputs.lastPeriodStartDate,
        inputs.averageCycleLength,
        inputs.periodDuration,
        inputs.lutealPhaseDays,
        inputs.dateChoice,
        inputs.cycleLengthChoice,
        inputs.today.toString(),
        month,
        inputs.hasCycleAnchor
    )

    /** Visible for the JVM harness test of the month-map fallback. */
    internal fun decodeMonthForTest(ok: JSONObject?): Map<String, DayPredictionModel>? {
        if (ok == null) return null
        return try {
            parseMonth(ok)
        } catch (e: JSONException) {
            null
        }
    }

    // Strict month decode: any malformed entry rejects the WHOLE map (the
    // fallback must never mix native and Kotlin predictions in one grid).
    private fun parseMonth(ok: JSONObject): Map<String, DayPredictionModel> {
        reqBool(ok, "hasCycleAnchor")
        reqStr(ok, "month")
        val arr = ok.get("predictions")
        if (arr !is org.json.JSONArray) throw JSONException("strict array required for predictions")
        val map = LinkedHashMap<String, DayPredictionModel>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.get(i)
            if (e !is JSONObject) throw JSONException("prediction entry $i is not an object")
            val date = reqStr(e, "date")
            val p = optObjOrNull(e, "prediction")
            if (p != null) map[date] = parsePredObj(p) // null prediction = source getPredictionForDate null = key absent
        }
        return map
    }

    /** Visible for the JVM harness test of the malformed-payload fallback. */
    internal fun decodeForTest(ok: JSONObject?): CycleScreenStateModel? {
        if (ok == null) return null
        return try {
            parse(ok)
        } catch (e: org.json.JSONException) {
            null
        }
    }

    // STRICT wire validation: org.json getInt/getLong/getBoolean/getString
    // COERCE (numeric strings parse, doubles truncate). The bridge contract
    // claims malformed payloads fall back to the Kotlin path, so every field
    // is checked by RAW type first; any coercion-only value is rejected.
    private fun reqInt(o: JSONObject, key: String): Int {
        val v = o.get(key) // throws JSONException when missing
        if (v !is Int && v !is Long) throw JSONException("strict int required for $key, got ${v?.javaClass?.name}")
        val l = (v as Number).toLong()
        if (l < Int.MIN_VALUE || l > Int.MAX_VALUE) throw JSONException("int range exceeded for $key")
        return l.toInt()
    }

    private fun reqLong(o: JSONObject, key: String): Long {
        val v = o.get(key)
        if (v !is Int && v !is Long) throw JSONException("strict long required for $key, got ${v?.javaClass?.name}")
        return (v as Number).toLong()
    }

    private fun reqStr(o: JSONObject, key: String): String {
        val v = o.get(key)
        if (v !is String) throw JSONException("strict string required for $key, got ${v?.javaClass?.name}")
        return v
    }

    private fun reqBool(o: JSONObject, key: String): Boolean {
        val v = o.get(key)
        if (v !is Boolean) throw JSONException("strict boolean required for $key, got ${v?.javaClass?.name}")
        return v
    }

    private fun optIntOrNull(o: JSONObject, key: String): Int? {
        if (!o.has(key) || o.isNull(key)) return null
        return reqInt(o, key)
    }

    private fun optStrOrNull(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return reqStr(o, key)
    }

    private fun optObjOrNull(o: JSONObject, key: String): JSONObject? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.get(key)
        if (v !is JSONObject) throw JSONException("strict object required for $key, got ${v?.javaClass?.name}")
        return v
    }

    internal fun parse(o: JSONObject): CycleScreenStateModel = CycleScreenStateModel(
        hasCycleAnchor = reqBool(o, "hasCycleAnchor"),
        cycleStartIsApproximate = reqBool(o, "cycleStartIsApproximate"),
        daysUntilNextPeriod = reqInt(o, "daysUntilNextPeriod"),
        nextPeriodDate = optStrOrNull(o, "nextPeriodDate"),
        daysUntilOvulation = reqInt(o, "daysUntilOvulation"),
        isPeriodActiveToday = reqBool(o, "isPeriodActiveToday"),
        todayPrediction = parsePred(o, "todayPrediction"),
        selectedDatePrediction = parsePred(o, "selectedDatePrediction"),
        selectedDayOfCycleText = reqStr(o, "selectedDayOfCycleText"),
        daysUntilPeriodFromSelected = optIntOrNull(o, "daysUntilPeriodFromSelected"),
        selectedDayTitle = reqStr(o, "selectedDayTitle"),
        disclaimer = reqStr(o, "disclaimer")
    )

    private fun parsePred(o: JSONObject, key: String): DayPredictionModel? {
        val p = optObjOrNull(o, key) ?: return null
        return parsePredObj(p)
    }

    private fun parsePredObj(p: JSONObject): DayPredictionModel {
        return DayPredictionModel(
            date = reqStr(p, "date"),
            dayOfCycle = reqInt(p, "dayOfCycle"),
            phase = reqStr(p, "phase"),
            phaseTitle = reqStr(p, "phaseTitle"),
            phaseSubtitle = reqStr(p, "phaseSubtitle"),
            phaseDescription = reqStr(p, "phaseDescription"),
            phaseAdvice = reqStr(p, "phaseAdvice"),
            phaseColorHex = reqLong(p, "phaseColorHex"),
            isPeriod = reqBool(p, "isPeriod"),
            isFertile = reqBool(p, "isFertile"),
            isOvulation = reqBool(p, "isOvulation"),
            fertilityConfidence = reqStr(p, "fertilityConfidence"),
            pregnancyChance = reqStr(p, "pregnancyChance")
        )
    }
}

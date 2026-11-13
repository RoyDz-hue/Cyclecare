package com.cyclecare.app.rust

import android.util.Log
import com.cyclecare.app.ui.screens.isCompletePin as kotlinIsCompletePin
import org.json.JSONArray
import org.json.JSONObject

/**
 * Transitional JNI bridge to the Rust cyclecare-core cdylib (core-ffi crate,
 * JSON dispatcher). The Kotlin host stays authoritative: when the native
 * library is absent or a call fails, every contract falls back to the
 * production Kotlin implementation, so app behavior never depends on the
 * spike artifact.
 *
 * Native contract (rust/HOST-INTEGRATION.md):
 *   nativeCoreHandshake(): Int                 -> always 42
 *   nativeCall(contract, payloadJson): String  -> {"ok": ...} or {"error": ...}
 *
 * Identity-dependent contracts MUST go through [withIdentitySnapshot]: the
 * caller passes the owner + sessionGeneration captured before its async
 * boundary; a stale snapshot discards the native result and uses the Kotlin
 * fallback. This mirrors CycleCareViewModel's stillOwns re-checks.
 */
object CycleCareCoreBridge {

    private const val TAG = "CycleCareCoreBridge"
    private const val LIB_NAME = "cyclecare_core"

    enum class NativeState { NOT_ATTEMPTED, LOADED, UNAVAILABLE }

    @Volatile
    private var nativeState = NativeState.NOT_ATTEMPTED

    @Synchronized
    private fun ensureLoaded(): NativeState {
        if (nativeState != NativeState.NOT_ATTEMPTED) return nativeState
        nativeState = runCatching {
            System.loadLibrary(LIB_NAME)
            if (nativeCoreHandshake() == 42) NativeState.LOADED else NativeState.UNAVAILABLE
        }.getOrElse {
            Log.d(TAG, "native core unavailable, Kotlin fallback active: ${it.javaClass.simpleName}")
            NativeState.UNAVAILABLE
        }
        return nativeState
    }

    /** Nonsafety attention branch tags from the native attention_nonsafety_priority contract. */
    enum class NonsafetyBranch {
        SAFETY_PRIORITY, BASELINE_SHIFT_STRONG, BASELINE_SHIFT_NOTABLE, NONE,
        PERIOD_SLEEP_TAPER, FERTILE_WINDOW_NOTICE, PHASE_MENSTRUAL_START,
        NEEDS_CHECKIN, WELLBEING_CHECKIN
    }

    /** Strictly decoded native nonsafety decision; null means native unavailable or reply malformed. */
    data class NonsafetyDecision(val branch: NonsafetyBranch, val deviationIndex: Int?)

    /**
     * Two-pass nonsafety attention priority choice (native selection only - the Kotlin caller
     * keeps safety, deviation detection, evidence preparation, and all copy). Pass 1 sends
     * checkinResolved=false; on NEEDS_CHECKIN the caller resolves the original late current-date
     * evidence and calls again with checkinResolved=true. Returns null on native unavailability
     * or any malformed reply so the caller runs the verbatim Kotlin oracle.
     */
    fun attentionNonsafetyPriority(
        safetyUrgentOrSeek: Boolean,
        deviationSeverities: List<String>,
        hasPrediction: Boolean,
        dayOfCycle: Int,
        isPeriod: Boolean,
        isFertile: Boolean,
        daysUntilPeriod: Int,
        sleepAverage: Double?,
        checkinResolved: Boolean,
        daysSinceLastLog: Long?
    ): NonsafetyDecision? {
        if (sleepAverage != null && !sleepAverage.isFinite()) return null
        val severityArray = JSONArray()
        deviationSeverities.forEach { severityArray.put(it) }
        val payload = JSONObject()
            .put("safetyUrgentOrSeek", safetyUrgentOrSeek)
            .put("deviationSeverities", severityArray)
            .put("hasPrediction", hasPrediction)
            .put("dayOfCycle", dayOfCycle)
            .put("isPeriod", isPeriod)
            .put("isFertile", isFertile)
            .put("daysUntilPeriod", daysUntilPeriod)
            .put("sleepAverage", if (sleepAverage != null) sleepAverage else JSONObject.NULL)
            .put("checkinResolved", checkinResolved)
            .put("daysSinceLastLog", daysSinceLastLog ?: JSONObject.NULL)
        val reply = call("attention_nonsafety_priority", payload) ?: return null
        return decodeNonsafetyReply(reply, deviationSeverities, checkinResolved)
    }

    /**
     * Strict semantic decode bound to the actual evidence and stage: known tags only; stage
     * legality (pass 1 cannot yield WELLBEING_CHECKIN; pass 2 yields only WELLBEING_CHECKIN or
     * NONE); a baseline branch's index must equal the FIRST matching position in the caller's
     * actual ordered severities (first STRONG, resp. first SIGNIFICANT-or-NOTABLE) - not merely
     * be in range; any deviationIndex on a non-baseline branch is rejected whatever its type.
     */
    internal fun decodeNonsafetyReply(
        reply: JSONObject,
        deviationSeverities: List<String>,
        checkinResolved: Boolean
    ): NonsafetyDecision? {
        val ok = reply.optJSONObject("ok") ?: return null
        val branch = when (ok.opt("branch")) {
            "safety_priority" -> NonsafetyBranch.SAFETY_PRIORITY
            "baseline_shift_strong" -> NonsafetyBranch.BASELINE_SHIFT_STRONG
            "baseline_shift_notable" -> NonsafetyBranch.BASELINE_SHIFT_NOTABLE
            "none" -> NonsafetyBranch.NONE
            "period_sleep_taper" -> NonsafetyBranch.PERIOD_SLEEP_TAPER
            "fertile_window_notice" -> NonsafetyBranch.FERTILE_WINDOW_NOTICE
            "phase_menstrual_start" -> NonsafetyBranch.PHASE_MENSTRUAL_START
            "needs_checkin" -> NonsafetyBranch.NEEDS_CHECKIN
            "wellbeing_checkin" -> NonsafetyBranch.WELLBEING_CHECKIN
            else -> return null
        }
        // Stage legality: check-in outcomes are impossible before resolution; a resolved
        // second pass re-uses identical evidence, so only check-in outcomes remain legal.
        if (!checkinResolved && branch == NonsafetyBranch.WELLBEING_CHECKIN) return null
        if (checkinResolved && branch != NonsafetyBranch.WELLBEING_CHECKIN && branch != NonsafetyBranch.NONE) return null
        val rawIndex = ok.opt("deviationIndex")
        val isBaseline = branch == NonsafetyBranch.BASELINE_SHIFT_STRONG || branch == NonsafetyBranch.BASELINE_SHIFT_NOTABLE
        if (isBaseline) {
            if (rawIndex !is Int || rawIndex < 0 || rawIndex >= deviationSeverities.size) return null
            val expected = if (branch == NonsafetyBranch.BASELINE_SHIFT_STRONG) {
                deviationSeverities.indexOf("STRONG")
            } else {
                deviationSeverities.indexOfFirst { it == "SIGNIFICANT" || it == "NOTABLE" }
            }
            if (rawIndex != expected) return null
            return NonsafetyDecision(branch, rawIndex)
        }
        // Non-baseline branch: any deviationIndex (any type, including JSONObject.NULL) is extraneous.
        if (rawIndex != null) return null
        return NonsafetyDecision(branch, null)
    }

    /** Read-only doctor prompts. No notes, settings, owner or credentials cross JNI.
     * JVM keeps its exact trim/titlecase primitives; Rust owns the summary algorithm.
     * Lone UTF16 surrogates cannot round-trip Rust strings, so keep the Kotlin path.
     */
    internal fun doctorSummary(
        logs: List<com.cyclecare.app.data.local.CycleDayLog>,
        displayLabel: (String) -> String
    ): List<String>? = runCatching {
        fun wireSafe(s: String): Boolean {
            var i = 0
            while (i < s.length) {
                val c = s[i++]
                if (c.isHighSurrogate()) {
                    if (i == s.length || !s[i++].isLowSurrogate()) return false
                } else if (c.isLowSurrogate()) return false
            }
            return true
        }
        val entries = JSONArray()
        for (log in logs) {
            if (!wireSafe(log.dateString) || !wireSafe(log.flowLevel) || !wireSafe(log.symptomsCsv)) return null
            val symptoms = JSONArray()
            log.symptomsCsv.split(',').map(String::trim).filter(String::isNotBlank).forEach { key ->
                val label = displayLabel(key)
                if (!wireSafe(label)) return null
                symptoms.put(JSONObject().put("key", key).put("label", label))
            }
            entries.put(JSONObject().put("date", log.dateString).put("flow", log.flowLevel)
                .put("pain", log.painIntensity).put("symptoms", symptoms))
        }
        val reply = call("doctor_summary_v1", JSONObject().put("logs", entries)) ?: return null
        decodeDoctorSummaryReply(reply)
    }.getOrNull()

    internal fun decodeDoctorSummaryReply(reply: JSONObject): List<String>? {
        if (reply.keys().asSequence().toSet() != setOf("ok")) return null
        val ok = reply.opt("ok") as? JSONObject ?: return null
        if (ok.keys().asSequence().toSet() != setOf("version", "questions")) return null
        if (ok.opt("version") !is Int || ok.opt("version") != 1) return null
        val questions = ok.opt("questions") as? JSONArray ?: return null
        if (questions.length() > 5) return null
        val result = (0 until questions.length()).map { questions.opt(it) as? String ?: return null }
        if (result.any { it.isEmpty() } || result.distinct().size != result.size) return null
        return result
    }

    /** Local baseline evidence only. Full Java date semantics live in Rust; JVM
     * retains its uppercase flow primitive. Date grammar is ASCII, so malformed
     * UTF16 dates are invalid in both parsers, never user-facing text output.
     */
    internal fun fullBaseline(
        settings: com.cyclecare.app.data.local.CycleSettings,
        logs: List<com.cyclecare.app.data.local.CycleDayLog>
    ): com.cyclecare.app.domain.pattern.BaselineMetrics? = runCatching {
        // Avoid unpaired UTF16 serialization even though these dates would be invalid.
        if (logs.any { l -> l.dateString.any { it.isSurrogate() } }) return null
        val entries = JSONArray()
        logs.forEach { log -> entries.put(JSONObject().put("date", log.dateString)
            .put("bleeding", log.flowLevel.uppercase() in setOf("LIGHT", "MODERATE", "HEAVY", "UNUSUALLY_HEAVY"))) }
        val reply = call("baseline_full_v1", JSONObject().put("average", settings.averageCycleLength)
            .put("duration", settings.periodDuration).put("logs", entries)) ?: return null
        decodeFullBaseline(reply, logs.size)
    }.getOrNull()

    internal fun decodeFullBaseline(reply: JSONObject, logCount: Int): com.cyclecare.app.domain.pattern.BaselineMetrics? = runCatching {
        require(reply.keys().asSequence().toSet() == setOf("ok"))
        val ok = reply.opt("ok") as? JSONObject ?: return null
        require(ok.keys().asSequence().toSet() == setOf("version", "metrics"))
        require(ok.opt("version") is Int && ok.opt("version") == 1)
        val m = ok.opt("metrics") as? JSONObject ?: return null
        require(m.keys().asSequence().toSet() == setOf("averageCycleLength", "medianCycleLength", "minCycleLength",
            "maxCycleLength", "variabilityDays", "isRegular", "totalLoggedDays", "observedCycleIntervals"))
        fun int(k: String): Int = m.opt(k) as? Int ?: throw IllegalArgumentException(k)
        val raw = m.opt("averageCycleLength")
        require(raw is Number && raw.toDouble().isFinite())
        val regular = m.opt("isRegular") as? Boolean ?: return null
        val average = raw.toDouble(); val median=int("medianCycleLength"); val min=int("minCycleLength")
        val max=int("maxCycleLength"); val variability=int("variabilityDays"); val total=int("totalLoggedDays"); val intervals=int("observedCycleIntervals")
        require(total in 0..logCount && intervals >= 0 && intervals <= (total-1).coerceAtLeast(0))
        require(min in 1..120 && max in min..120 && median in min..max && average in min.toDouble()..max.toDouble())
        require(variability in 0..120)
        if (intervals == 0) require(min in 21..45 && min == max && median == min && average == min.toDouble() && variability == 0 && !regular)
        if (regular) require(intervals >= 2 && min >= 21 && max <= 45 && max-min <= 7)
        com.cyclecare.app.domain.pattern.BaselineMetrics(average,median,min,max,variability,regular,total,intervals)
    }.getOrNull()

    /** Synchronous phase snapshot, no identity, records or clock read. */
    internal fun fullPhase(
        date: java.time.LocalDate,
        settings: com.cyclecare.app.data.local.CycleSettings
    ): com.cyclecare.app.data.model.CyclePhase? = runCatching {
        if (settings.lastPeriodStartDate.any { it.isSurrogate() }) return null
        val reply=call("phase_full_v1",JSONObject().put("date",date.toString())
            .put("anchor",settings.lastPeriodStartDate).put("average",settings.averageCycleLength)
            .put("duration",settings.periodDuration).put("luteal",settings.lutealPhaseDays)) ?: return null
        decodeFullPhase(reply)
    }.getOrNull()

    internal fun decodeFullPhase(reply: JSONObject): com.cyclecare.app.data.model.CyclePhase? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"))
        val ok=reply.opt("ok") as? JSONObject ?: return null
        require(ok.keys().asSequence().toSet()==setOf("version","phase"))
        require(ok.opt("version") is Int && ok.opt("version")==1)
        val phase=ok.opt("phase") as? String ?: return null
        com.cyclecare.app.data.model.CyclePhase.valueOf(phase)
    }.getOrNull()

    /** Full observation assembly in Rust, JVM supplies exact Unicode label primitives.
     * No notes, credentials, owner or unrelated profile fields cross the local JNI boundary.
     */
    internal fun learnedObservations(
        settings: com.cyclecare.app.data.local.CycleSettings,
        logs: List<com.cyclecare.app.data.local.CycleDayLog>,
        profile: com.cyclecare.app.data.local.OnboardingProfile?,
        label: (String)->String
    ): List<com.cyclecare.app.data.model.LearnedObservation>? = runCatching {
        fun safe(s:String):Boolean {
            var i=0
            while (i<s.length) {val c=s[i++];if(c.isHighSurrogate()) {
                if(i==s.length || !s[i++].isLowSurrogate())return false
            } else if(c.isLowSurrogate())return false}
            return true
        }
        fun symptoms(csv:String):JSONArray? {
            if(!safe(csv))return null
            val out=JSONArray()
            for(key in csv.split(',').map(String::trim).filter(String::isNotBlank)) {
                val display=label(key);if(!safe(display))return null
                out.put(JSONObject().put("key",key).put("label",display))
            }
            return out
        }
        if(!safe(settings.lastPeriodStartDate))return null
        val setup=symptoms(profile?.typicalSymptomsCsv.orEmpty()) ?: return null
        val entries=JSONArray()
        for(log in logs) {
            if(!safe(log.dateString))return null
            entries.put(JSONObject().put("date",log.dateString).put("symptoms",symptoms(log.symptomsCsv) ?: return null))
        }
        val reply=call("learned_full_v1",JSONObject().put("completed",settings.isOnboardingCompleted || profile?.isCompleted==true)
            .put("average",settings.averageCycleLength).put("duration",settings.periodDuration).put("luteal",settings.lutealPhaseDays)
            .put("anchor",settings.lastPeriodStartDate).put("setup",setup).put("logs",entries)) ?: return null
        decodeLearnedObservations(reply)
    }.getOrNull()

    internal fun decodeLearnedObservations(reply:JSONObject):List<com.cyclecare.app.data.model.LearnedObservation>? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"))
        val ok=reply.opt("ok") as? JSONObject ?: return null
        require(ok.keys().asSequence().toSet()==setOf("version","observations"))
        require(ok.opt("version") is Int && ok.opt("version")==1)
        val items=ok.opt("observations") as? JSONArray ?: return null
        require(items.length() in 1..11)
        val result=(0 until items.length()).map { i ->
            val o=items.opt(i) as? JSONObject ?: return null
            require(o.keys().asSequence().toSet()==setOf("id","category","headline","description","confidence"))
            fun text(k:String):String {val s=o.opt(k) as? String ?: throw IllegalArgumentException(k);require(s.isNotEmpty());return s}
            com.cyclecare.app.data.model.LearnedObservation(text("id"),text("category"),text("headline"),text("description"),text("confidence"))
        }
        require(result.map {it.id}.distinct().size==result.size)
        result
    }.getOrNull()

    /** Raw dispatcher call; null on any failure (caller falls back to Kotlin). */
    fun call(contract: String, payload: JSONObject): JSONObject? {
        if (ensureLoaded() != NativeState.LOADED) return null
        return runCatching {
            val reply = JSONObject(nativeCall(contract, payload.toString()))
            if (reply.has("ok")) reply else null
        }.getOrNull()
    }

    /** Quick-unlock PIN shape (identity-free). Pure; no user data leaves the device. */
    fun isCompletePin(pin: String): Boolean {
        val ok = call("is_complete_pin", JSONObject().put("pin", pin))
        if (ok != null) return ok.optBoolean("ok", false)
        return kotlinIsCompletePin(pin)
    }

    /**
     * Native quick-unlock PIN verify (core pin_verify port). Returns
     * "current" / "legacy_migrate" / "rejected", or null when native is
     * unavailable so the caller keeps the production Kotlin chain. Pure:
     * the stored value never persists native-side; owner scoping stays in
     * the Kotlin read that supplied it.
     */
    fun verifyPin(stored: String?, candidate: String, deviceId: String): String? {
        val reply = call("verify_pin", JSONObject()
            .put("stored", stored ?: JSONObject.NULL)
            .put("candidate", candidate)
            .put("device_id", deviceId)) ?: return null
        val r = reply.optString("ok", "")
        return if (r == "current" || r == "legacy_migrate" || r == "rejected") r else null
    }

    /**
     * Native PBKDF2 PIN encode with a caller-supplied 16-byte salt (hex,
     * from SecureRandom). null -> caller uses the Kotlin encoder (which
     * fails closed if the JVM crypto is broken).
     */
    fun encodePin(pin: String, saltHex: String): String? {
        val reply = call("encode_pin", JSONObject()
            .put("pin", pin)
            .put("salt_hex", saltHex)) ?: return null
        val encoded = reply.optString("ok", "")
        return if (encoded.startsWith("pbkdf2:")) encoded else null
    }

    /**
     * Native period date-pair validation (core cycle_math port, probe-locked
     * ISO parse). null -> caller keeps the Kotlin comparison.
     */
    fun validatePeriodDates(start: java.time.LocalDate?, end: java.time.LocalDate?): Boolean? {
        val reply = call("validate_period_dates", JSONObject()
            .put("start", start?.toString() ?: JSONObject.NULL)
            .put("end", end?.toString() ?: JSONObject.NULL)) ?: return null
        // Strict typing: optBoolean would coerce a malformed/native-partial
        // reply into an authoritative false. "ok" must be a genuine Boolean
        // AND "supported" must be genuinely true - missing (pre-fix native
        // binaries), false, or non-Boolean all fall back to the Kotlin
        // comparison, which covers the full java.time domain
        // (+/-999999999 years).
        val ok = reply.opt("ok")
        if (ok !is Boolean) return null
        if (reply.opt("supported") != true) return null
        return ok
    }

    /**
     * Native day-prediction route answer. [prediction] null = the route's own
     * "no cycle anchor" answer (matches the Kotlin route's null); the whole
     * class returning null from the call below = native unavailable/malformed
     * and the caller keeps the Kotlin computation.
     */
    class NativeDayPrediction(val prediction: com.cyclecare.app.data.model.DayPrediction?)

    /**
     * Native day prediction (core cycle_math port of
     * CycleRepository.calculateDayPrediction). Strict decode: any coerced or
     * missing field falls back rather than shipping a guess.
     */
    fun calculateDayPrediction(
        date: java.time.LocalDate,
        settings: com.cyclecare.app.data.local.CycleSettings
    ): NativeDayPrediction? {
        val reply = call("calculate_day_prediction", JSONObject()
            .put("date", date.toString())
            .put("settings", JSONObject()
                .put("averageCycleLength", settings.averageCycleLength)
                .put("periodDuration", settings.periodDuration)
                .put("lutealPhaseDays", settings.lutealPhaseDays)
                .put("lastPeriodStartDate", settings.lastPeriodStartDate))) ?: return null
        if (!reply.has("ok")) return null
        if (reply.isNull("ok")) return NativeDayPrediction(null)
        val ok = reply.optJSONObject("ok") ?: return null
        val dayOfCycle = ok.opt("dayOfCycle")
        if (dayOfCycle !is Int) return null
        val phaseName = ok.opt("phase")
        if (phaseName !is String) return null
        val phase = runCatching { com.cyclecare.app.data.model.CyclePhase.valueOf(phaseName) }.getOrNull() ?: return null
        val isPeriod = ok.opt("isPeriod")
        if (isPeriod !is Boolean) return null
        val isFertile = ok.opt("isFertile")
        if (isFertile !is Boolean) return null
        val isOvulation = ok.opt("isOvulation")
        if (isOvulation !is Boolean) return null
        // Both strings are constants on both sides of the port; verify them
        // instead of trusting, so a contract drift falls back loudly.
        val confidence = ok.opt("fertilityConfidence")
        if (confidence != "Calendar Estimate") return null
        val chance = ok.opt("pregnancyChance")
        if (chance != "Not calculated from calendar dates") return null
        return NativeDayPrediction(com.cyclecare.app.data.model.DayPrediction(
            date = date,
            dayOfCycle = dayOfCycle,
            phase = phase,
            isPeriod = isPeriod,
            isFertile = isFertile,
            isOvulation = isOvulation,
            fertilityConfidence = confidence as String,
            pregnancyChance = chance as String
        ))
    }

    /**
     * Native safety triage (core safety port of SafetyEngine.evaluate).
     * null -> native unavailable/malformed: caller keeps the Kotlin engine.
     * Strict decode: every field must arrive with its real JSON type; a
     * coerced or unknown level falls back, never guesses a triage level.
     */
    fun evaluateSafety(log: com.cyclecare.app.data.local.CycleDayLog?): com.cyclecare.app.data.model.SafetyEvaluation? {
        val payload = JSONObject()
        if (log == null) {
            payload.put("log", JSONObject.NULL)
        } else {
            payload.put("log", JSONObject()
                .put("flowLevel", log.flowLevel)
                .put("symptomsCsv", log.symptomsCsv)
                .put("vaginalHealthCsv", log.vaginalHealthCsv)
                .put("urinaryHealthCsv", log.urinaryHealthCsv)
                .put("painIntensity", log.painIntensity))
        }
        val reply = call("evaluate_safety", payload) ?: return null
        val ok = reply.optJSONObject("ok") ?: return null
        val levelName = ok.opt("level")
        if (levelName !is String) return null
        val level = runCatching { com.cyclecare.app.data.model.SafetyLevel.valueOf(levelName) }.getOrNull() ?: return null
        val title = ok.opt("title")
        if (title !is String) return null
        val message = ok.opt("message")
        if (message !is String) return null
        val advice = ok.opt("actionAdvice")
        if (advice !is String) return null
        val redFlag = ok.opt("isRedFlag")
        if (redFlag !is Boolean) return null
        return com.cyclecare.app.data.model.SafetyEvaluation(level, title, message, advice, redFlag)
    }

    /** Anchor-slice scalars from the native cycle_screen_state port (the
     * updateCalculationsAdmitted anchor/countdown computation). */
    data class ScreenAnchorState(
        val hasCycleAnchor: Boolean,
        val cycleStartIsApproximate: Boolean,
        val daysUntilNextPeriod: Int,
        val daysUntilOvulation: Int
    )

    /**
     * Native cycle-screen anchor slice. null -> native unavailable/malformed:
     * caller keeps the Kotlin computation. Strict decode: the two Booleans
     * and two Ints must arrive with their real JSON types; the full
     * DayPrediction objects stay with the calculateDayPrediction route, this
     * carries only the anchor slice.
     */
    fun cycleScreenAnchorState(
        settings: com.cyclecare.app.data.local.CycleSettings,
        dateChoice: String?,
        cycleLengthChoice: String?,
        today: java.time.LocalDate,
        selectedDate: java.time.LocalDate
    ): ScreenAnchorState? {
        val reply = call("cycle_screen_state", JSONObject()
            .put("settings", JSONObject()
                .put("averageCycleLength", settings.averageCycleLength)
                .put("periodDuration", settings.periodDuration)
                .put("lutealPhaseDays", settings.lutealPhaseDays)
                .put("lastPeriodStartDate", settings.lastPeriodStartDate))
            .put("dateChoice", dateChoice ?: JSONObject.NULL)
            .put("cycleLengthChoice", cycleLengthChoice ?: JSONObject.NULL)
            .put("today", today.toString())
            .put("selectedDate", selectedDate.toString())) ?: return null
        val ok = reply.optJSONObject("ok") ?: return null
        val hasAnchor = ok.opt("hasCycleAnchor")
        if (hasAnchor !is Boolean) return null
        val approx = ok.opt("cycleStartIsApproximate")
        if (approx !is Boolean) return null
        val daysPeriod = ok.opt("daysUntilNextPeriod")
        if (daysPeriod !is Int) return null
        val daysOvulation = ok.opt("daysUntilOvulation")
        if (daysOvulation !is Int) return null
        return ScreenAnchorState(hasAnchor, approx, daysPeriod, daysOvulation)
    }

    /** Decision from the native flow-anchor route (core flow_anchor_decision
     * port of the saveDayLogBound anchor policy). */
    data class FlowAnchorDecision(val update: Boolean, val newAnchor: String?)

    /**
     * Native tracking-write anchor decision (core flow_anchor_decision port
     * of the saveDayLogBound policy). null -> native unavailable/malformed:
     * caller keeps the Kotlin policy. Strict decode: "update" must be a real
     * JSON Boolean; when it is true, "newAnchor" must be a real JSON String.
     */
    fun flowAnchorDecision(date: String, flowLevel: String, priorAnchor: String?): FlowAnchorDecision? {
        val payload = JSONObject()
            .put("date", date)
            .put("flowLevel", flowLevel)
            .put("priorAnchor", priorAnchor ?: JSONObject.NULL)
        val reply = call("flow_anchor_decision", payload) ?: return null
        return decodeFlowAnchorReply(reply, date)
    }

    /** Strict decode, split out for direct testing: "update" must be a real
     * JSON Boolean; when true, "newAnchor" must be a real JSON String
     * EXACTLY equal to the submitted date - the repository persists the
     * reply, so a drifted or wrong-typed echo is rejected (null) and the
     * caller falls back to the Kotlin policy instead of writing it. */
    internal fun decodeFlowAnchorReply(reply: JSONObject, date: String): FlowAnchorDecision? {
        val ok = reply.optJSONObject("ok") ?: return null
        val update = ok.opt("update")
        if (update !is Boolean) return null
        if (!update) return FlowAnchorDecision(false, null)
        val newAnchor = ok.opt("newAnchor")
        if (newAnchor !is String || newAnchor != date) return null
        return FlowAnchorDecision(true, newAnchor)
    }

    /** Reply of [syncProfileOnSettings]: [writeProfile] mirrors the Kotlin
     * `changed != null` guard; [cycleLengthOption] is the recomputed or
     * preserved option when [writeProfile] is true. */
    data class SettingsProfileSync(val writeProfile: Boolean, val cycleLengthOption: String?)

    /** Profile-sync decision inside CycleRepository.updateSettingsBound's
     * write transaction (and the dormant port host's twin expression).
     * null = native unavailable/malformed -> caller falls back to the
     * verbatim Kotlin expression. */
    fun syncProfileOnSettings(existingCycleLengthOption: String?, newAverage: Int, previousAverage: Int): SettingsProfileSync? {
        // Expected value of the ORIGINAL Kotlin expression, computed from the
        // submitted inputs: the repository persists the reply, so only a
        // reply exactly equal to it is admitted (same echo-binding as
        // flowAnchorDecision's newAnchor == date).
        val expectedOption = if (newAverage != previousAverage) "$newAverage days" else existingCycleLengthOption
        val payload = JSONObject()
            .put("hasProfile", existingCycleLengthOption != null)
            .put("existingOption", existingCycleLengthOption ?: JSONObject.NULL)
            .put("newAverage", newAverage)
            .put("previousAverage", previousAverage)
        val reply = call("sync_profile_on_settings", payload) ?: return null
        return decodeSyncProfileOnSettingsReply(reply, existingCycleLengthOption != null, expectedOption)
    }

    /** Strict decode, split out for direct testing: "writeProfile" must be a
     * real JSON Boolean EXACTLY equal to the submitted hasProfile; when true,
     * "cycleLengthOption" must be a real JSON String EXACTLY equal to the
     * original Kotlin expression's value ([expectedOption]: the existing
     * option verbatim when the average is unchanged, "$newAverage days" when
     * changed) - any other value is a drifted write and rejected; when false
     * the option must be JSON null. */
    internal fun decodeSyncProfileOnSettingsReply(reply: JSONObject, hasProfile: Boolean, expectedOption: String?): SettingsProfileSync? {
        val ok = reply.optJSONObject("ok") ?: return null
        val write = ok.opt("writeProfile")
        if (write !is Boolean || write != hasProfile) return null
        val opt = ok.opt("cycleLengthOption")
        if (write) {
            if (opt !is String || opt != expectedOption) return null
            return SettingsProfileSync(true, opt)
        }
        if (opt != JSONObject.NULL && opt != null) return null
        return SettingsProfileSync(false, null)
    }

    /**
     * Guard for future identity-dependent native contracts: runs [nativeCall]
     * only while the owner/session snapshot taken before the async boundary is
     * still current; otherwise returns [fallback]. No identity-dependent
     * contract is wired yet - first use must come with its own review.
     */
    inline fun <T> withIdentitySnapshot(
        owner: String?,
        sessionGeneration: Long,
        currentOwner: () -> String?,
        currentSessionGeneration: () -> Long,
        fallback: () -> T,
        nativeCall: () -> T,
    ): T {
        if (owner != currentOwner() || sessionGeneration != currentSessionGeneration()) return fallback()
        val result = nativeCall()
        return if (owner == currentOwner() && sessionGeneration == currentSessionGeneration()) result else fallback()
    }


    // ---- Lifecycle contracts (core-ffi v5 / core v14) --------------------
    // Isolated spike wiring: NOTHING in the production flow calls these yet.
    // Every function is a pure decision/parse over caller-supplied snapshots
    // and server-response bodies; none of them reads or writes local state,
    // accounts, or the wallet. Each returns null on native unavailability or
    // failure so the caller keeps the production Kotlin path.

    data class StartupGateInputs(
        val isLocalOnlyMode: Boolean,
        val isSecureStorageAvailable: Boolean,
        val storedInitialAuthCompleted: Boolean,
        val profilePresent: Boolean,
        val settingsOnboardingCompleted: Boolean,
        val isPinEnabled: Boolean,
        val pinPresent: Boolean,
        val isFingerprintEnabled: Boolean,
        val userIdPresent: Boolean,
    )

    data class StartupGate(
        val hasCompletedInitialAuth: Boolean,
        val needsCloudProfileCheck: Boolean,
        val isLocked: Boolean,
        val showOnboarding: Boolean,
        val fetchWallet: Boolean,
    )

    /** CycleCareViewModel.init startup gate. null -> use the Kotlin gate. */
    fun startupGate(i: StartupGateInputs): StartupGate? {
        val ok = call("startup_gate", JSONObject()
            .put("local", i.isLocalOnlyMode)
            .put("secure", i.isSecureStorageAvailable)
            .put("stored", i.storedInitialAuthCompleted)
            .put("profile", i.profilePresent)
            .put("onboardingCompleted", i.settingsOnboardingCompleted)
            .put("pinEnabled", i.isPinEnabled)
            .put("pinPresent", i.pinPresent)
            .put("fingerprintEnabled", i.isFingerprintEnabled)
            .put("userIdPresent", i.userIdPresent))?.optJSONObject("ok") ?: return null
        return StartupGate(
            hasCompletedInitialAuth = ok.optBoolean("hasCompletedInitialAuth"),
            needsCloudProfileCheck = ok.optBoolean("needsCloudProfileCheck"),
            isLocked = ok.optBoolean("isLocked"),
            showOnboarding = ok.optBoolean("showOnboarding"),
            fetchWallet = ok.optBoolean("fetchWallet"),
        )
    }

    /** AuthPreferences.effectiveUserId (userId, else UUID v3 over device id). */
    fun effectiveUserId(userId: String?, deviceId: String): String? {
        val ok = call("effective_user_id", JSONObject()
            .put("userId", userId ?: JSONObject.NULL)
            .put("deviceId", deviceId)) ?: return null
        return ok.optString("ok").takeIf { it.isNotEmpty() }
    }

    /** ensure-my-account-setup RPC response check. */
    fun ensureSetupOk(body: String): Boolean? {
        val ok = call("ensure_setup_ok", JSONObject().put("body", body)) ?: return null
        return if (ok.has("ok")) ok.optBoolean("ok") else null
    }

    /** hasRemoteProfile: TRUE/FALSE/UNKNOWN (server said nothing decidable) or null (native unavailable). */
    enum class RemoteProfileAnswer { TRUE, FALSE, UNKNOWN }

    fun hasRemoteProfile(httpOk: Boolean, body: String, owner: String): RemoteProfileAnswer? {
        val ok = call("has_remote_profile", JSONObject()
            .put("httpOk", httpOk)
            .put("body", body)
            .put("owner", owner)) ?: return null
        if (!ok.has("ok")) return null
        if (ok.isNull("ok")) return RemoteProfileAnswer.UNKNOWN
        return if (ok.optBoolean("ok")) RemoteProfileAnswer.TRUE else RemoteProfileAnswer.FALSE
    }

    /** fetchVerifiedProfile parse/merge. Raw ok-payload passthrough (16 fields). */
    fun verifiedProfileParse(owner: String, econBody: String, profBody: String, nowIso: String): JSONObject? {
        return call("verified_profile_parse", JSONObject()
            .put("owner", owner)
            .put("econBody", econBody)
            .put("profBody", profBody)
            .put("nowIso", nowIso))?.optJSONObject("ok")
    }

    /** reconcilePendingOfflineClaims decision core. Raw ok-payload passthrough (outcome + fields). */
    fun reconcileOfflineClaim(
        httpOk: Boolean,
        body: String,
        currentBalance: String,
        currentEarned: String,
        currentRank: String,
        pendingDate: String,
    ): JSONObject? {
        return call("reconcile_offline_claim", JSONObject()
            .put("httpOk", httpOk)
            .put("body", body)
            .put("balance", currentBalance)
            .put("earned", currentEarned)
            .put("rank", currentRank)
            .put("pendingDate", pendingDate))?.optJSONObject("ok")
    }

    private external fun nativeCoreHandshake(): Int
    private external fun nativeCall(contract: String, payloadJson: String): String
}

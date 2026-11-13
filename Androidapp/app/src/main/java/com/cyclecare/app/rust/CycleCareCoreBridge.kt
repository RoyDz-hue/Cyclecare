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
    internal interface FullAttentionChoice {val branch:NonsafetyBranch;val deviationIndex:Int?}
    private class BoundAttentionChoice(val decision:NonsafetyDecision,val until:Int,val day:Int,val sleep:Double?,val title:String,val message:String):FullAttentionChoice {
        override val branch get()=decision.branch
        override val deviationIndex get()=decision.deviationIndex
    }
    internal fun chooseFullAttention(
        safetyUrgentOrSeek: Boolean,
        deviations: List<com.cyclecare.app.domain.pattern.BaselineDeviation>,
        hasPrediction: Boolean,
        dayOfCycle: Int,
        isPeriod: Boolean,
        isFertile: Boolean,
        daysUntilPeriod: Int,
        sleepAverage: Double?,
        checkinResolved: Boolean,
        daysSinceLastLog: Long?
    ): FullAttentionChoice? {
        if (sleepAverage != null && !sleepAverage.isFinite()) return null
        val deviationSeverities=deviations.map {it.severity.name}
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
        val decision=decodeNonsafetyReply(reply,deviationSeverities,checkinResolved) ?: return null
        if(reply.keys().asSequence().toSet()!=setOf("ok"))return null
        val ok=reply.optJSONObject("ok") ?: return null
        if(ok.keys().asSequence().toSet()!=if(decision.deviationIndex==null)setOf("branch") else setOf("branch","deviationIndex"))return null
        val expected=when {
            safetyUrgentOrSeek -> NonsafetyBranch.SAFETY_PRIORITY
            "STRONG" in deviationSeverities -> NonsafetyBranch.BASELINE_SHIFT_STRONG
            deviationSeverities.any {it=="SIGNIFICANT" || it=="NOTABLE"} && !isPeriod -> NonsafetyBranch.BASELINE_SHIFT_NOTABLE
            !hasPrediction -> NonsafetyBranch.NONE
            daysUntilPeriod in 1..3 && sleepAverage!=null && sleepAverage<6.8 -> NonsafetyBranch.PERIOD_SLEEP_TAPER
            isFertile && !isPeriod -> NonsafetyBranch.FERTILE_WINDOW_NOTICE
            dayOfCycle==1 && isPeriod -> NonsafetyBranch.PHASE_MENSTRUAL_START
            !checkinResolved -> NonsafetyBranch.NEEDS_CHECKIN
            daysSinceLastLog!=null && daysSinceLastLog>=4 && !isPeriod -> NonsafetyBranch.WELLBEING_CHECKIN
            else -> NonsafetyBranch.NONE
        }
        if(decision.branch!=expected)return null
        val d=decision.deviationIndex?.let {deviations[it]}
        return BoundAttentionChoice(decision,daysUntilPeriod,dayOfCycle,sleepAverage,d?.title ?: "",d?.message ?: "")
    }


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
    /** Rust owns full-date pattern aggregation. JVM retains only its original
     * exp and text primitives so floating ties and Unicode stay bit-exact. */
    private fun completionText(s:String):String {require(attentionSafe(s));return s}
    internal fun completionProfilePayload(i:com.cyclecare.app.domain.onboarding.OnboardingCompletionMapping.Input):JSONObject? = runCatching {
        JSONObject().put("account",completionText(i.accountMode)).put("time",i.now).put("goals",JSONArray(i.submittedGoals.map(::completionText))).put("start",if(i.periodStartChoice=="UNKNOWN")""else i.lastPeriodDate.toString()).put("end",i.lastPeriodEndDate?.toString() ?: "").put("choice",completionText(i.periodStartChoice)).put("cycleOption",completionText(i.cycleLengthOption)).put("optionBlank",i.cycleLengthOption.isBlank()).put("cycle",i.cycleLength).put("duration",i.periodDuration).put("fertile",i.remindFertile).put("symptoms",JSONArray(i.submittedSymptoms.map(::completionText))).put("energy",completionText(i.typicalEnergy)).put("sleep",completionText(i.typicalSleep)).put("deep",i.hasCompletedDeepBaseline)
    }.getOrNull()
    internal fun fullCompletionProfile(i:com.cyclecare.app.domain.onboarding.OnboardingCompletionMapping.Input):com.cyclecare.app.data.local.OnboardingProfile? = runCatching {decodeCompletionProfile(call("onboarding_completion_profile_v1",completionProfilePayload(i) ?: return null) ?: return null)}.getOrNull()
    internal fun decodeCompletionProfile(reply:JSONObject):com.cyclecare.app.data.local.OnboardingProfile? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"));val ok=reply.opt("ok") as? JSONObject ?: return null;require(ok.keys().asSequence().toSet()==setOf("version","profile"));require(ok.opt("version") is Int&&ok.opt("version")==1);val p=ok.opt("profile") as? JSONObject ?: return null
        require(p.keys().asSequence().toSet()==setOf("id","isCompleted","completedAtTimestamp","isAgeVerified","isTermsAgreed","accountMode","selectedGoalsCsv","lastPeriodDateString","lastPeriodEndDateString","approximatePeriodChoice","cycleLengthOption","cycleLengthDays","periodDurationDays","remindFertileWindow","typicalSymptomsCsv","typicalEnergy","typicalSleepQuality","hasCompletedDeepBaseline"))
        fun int(k:String)=p.opt(k) as? Int ?: throw IllegalArgumentException(k)
        fun long(k:String):Long{val v=p.opt(k);require(v is Int||v is Long);return (v as Number).toLong()}
        fun bool(k:String)=p.opt(k) as? Boolean ?: throw IllegalArgumentException(k)
        fun text(k:String)=completionText(p.opt(k) as? String ?: throw IllegalArgumentException(k))
        require(int("id")==1&&bool("isCompleted")&&bool("isAgeVerified")&&bool("isTermsAgreed"))
        com.cyclecare.app.data.local.OnboardingProfile(id=int("id"),isCompleted=bool("isCompleted"),completedAtTimestamp=long("completedAtTimestamp"),isAgeVerified=bool("isAgeVerified"),isTermsAgreed=bool("isTermsAgreed"),accountMode=text("accountMode"),selectedGoalsCsv=text("selectedGoalsCsv"),lastPeriodDateString=text("lastPeriodDateString"),lastPeriodEndDateString=text("lastPeriodEndDateString"),approximatePeriodChoice=text("approximatePeriodChoice"),cycleLengthOption=text("cycleLengthOption"),cycleLengthDays=int("cycleLengthDays"),periodDurationDays=int("periodDurationDays"),remindFertileWindow=bool("remindFertileWindow"),typicalSymptomsCsv=text("typicalSymptomsCsv"),typicalEnergy=text("typicalEnergy"),typicalSleepQuality=text("typicalSleepQuality"),hasCompletedDeepBaseline=bool("hasCompletedDeepBaseline"))
    }.getOrNull()
    internal fun completionSettingsPayload(p:com.cyclecare.app.data.local.OnboardingProfile,lock:Boolean):JSONObject? = runCatching {JSONObject().put("profile",JSONObject().put("id",p.id).put("isCompleted",p.isCompleted).put("completedAtTimestamp",p.completedAtTimestamp).put("isAgeVerified",p.isAgeVerified).put("isTermsAgreed",p.isTermsAgreed).put("accountMode",completionText(p.accountMode)).put("selectedGoalsCsv",completionText(p.selectedGoalsCsv)).put("lastPeriodDateString",completionText(p.lastPeriodDateString)).put("lastPeriodEndDateString",completionText(p.lastPeriodEndDateString)).put("approximatePeriodChoice",completionText(p.approximatePeriodChoice)).put("cycleLengthOption",completionText(p.cycleLengthOption)).put("cycleLengthDays",p.cycleLengthDays).put("periodDurationDays",p.periodDurationDays).put("remindFertileWindow",p.remindFertileWindow).put("typicalSymptomsCsv",completionText(p.typicalSymptomsCsv)).put("typicalEnergy",completionText(p.typicalEnergy)).put("typicalSleepQuality",completionText(p.typicalSleepQuality)).put("hasCompletedDeepBaseline",p.hasCompletedDeepBaseline)).put("lock",lock)}.getOrNull()
    internal fun fullCompletionSettings(current:com.cyclecare.app.data.local.CycleSettings,p:com.cyclecare.app.data.local.OnboardingProfile,lock:Boolean):com.cyclecare.app.data.local.CycleSettings? = runCatching {decodeCompletionSettings(call("onboarding_completion_settings_v1",completionSettingsPayload(p,lock) ?: return null) ?: return null,current)}.getOrNull()
    internal fun decodeCompletionSettings(reply:JSONObject,current:com.cyclecare.app.data.local.CycleSettings):com.cyclecare.app.data.local.CycleSettings? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"));val ok=reply.opt("ok") as? JSONObject ?: return null;require(ok.keys().asSequence().toSet()==setOf("version","patch"));require(ok.opt("version") is Int&&ok.opt("version")==1);val p=ok.opt("patch") as? JSONObject ?: return null
        require(p.keys().asSequence().toSet()==setOf("isOnboardingCompleted","selectedGoalsCsv","lastPeriodStartDate","averageCycleLength","periodDuration","remindFertile","isAppLockEnabled"))
        fun int(k:String)=p.opt(k) as? Int ?: throw IllegalArgumentException(k)
        fun bool(k:String)=p.opt(k) as? Boolean ?: throw IllegalArgumentException(k)
        fun text(k:String)=completionText(p.opt(k) as? String ?: throw IllegalArgumentException(k))
        require(bool("isOnboardingCompleted"))
        current.copy(isOnboardingCompleted=true,selectedGoalsCsv=text("selectedGoalsCsv"),lastPeriodStartDate=text("lastPeriodStartDate"),averageCycleLength=int("averageCycleLength"),periodDuration=int("periodDuration"),remindFertile=bool("remindFertile"),isAppLockEnabled=bool("isAppLockEnabled"))
    }.getOrNull()

    internal fun onboardingDatePayload(s:com.cyclecare.app.ui.viewmodel.OnboardingUiState,e:com.cyclecare.app.ui.viewmodel.OnboardingDateReducer.Event,v:com.cyclecare.app.ui.viewmodel.OnboardingDateReducer.Evidence):JSONObject? = runCatching {
        fun text(t:String):String{require(attentionSafe(t));return t};fun date(d:java.time.LocalDate?):Any=d?.toEpochDay() ?: JSONObject.NULL
        val state=JSONObject().put("exact",date(s.selectedExactDate)).put("end",date(s.selectedPeriodEndDate)).put("chip",s.selectedApproximateChip?.let(::text) ?: JSONObject.NULL).put("duration",s.periodDurationDays).put("option",text(s.selectedPeriodDurationOption)).put("ongoing",s.isPeriodOngoing).put("datePicker",s.showDatePickerDialog).put("endPicker",s.showEndDatePickerDialog).put("confirmed",s.periodEndDateConfirmed).put("dialog",s.periodDateDialogMessage?.let(::text) ?: JSONObject.NULL)
        JSONObject().put("state",state).put("action",e.action.name).put("date",date(e.date)).put("text",text(e.text)).put("days",e.days).put("ongoing",e.ongoing).put("effectiveStart",date(v.start)).put("calculatedEnd",date(v.end)).put("today",date(v.today))
    }.getOrNull()
    internal fun fullOnboardingDate(s:com.cyclecare.app.ui.viewmodel.OnboardingUiState,e:com.cyclecare.app.ui.viewmodel.OnboardingDateReducer.Event,v:com.cyclecare.app.ui.viewmodel.OnboardingDateReducer.Evidence):com.cyclecare.app.ui.viewmodel.OnboardingUiState? = runCatching {
        decodeFullOnboardingDate(call("onboarding_date_full_v1",onboardingDatePayload(s,e,v) ?: return null) ?: return null,s,listOfNotNull(s.selectedExactDate,s.selectedPeriodEndDate,e.date,v.start,v.end,v.today))
    }.getOrNull()
    internal fun decodeFullOnboardingDate(reply:JSONObject,original:com.cyclecare.app.ui.viewmodel.OnboardingUiState,dates:List<java.time.LocalDate>):com.cyclecare.app.ui.viewmodel.OnboardingUiState? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"));val ok=reply.opt("ok") as? JSONObject ?: return null;require(ok.keys().asSequence().toSet()==setOf("version","state"));require(ok.opt("version") is Int&&ok.opt("version")==1);val s=ok.opt("state") as? JSONObject ?: return null
        require(s.keys().asSequence().toSet()==setOf("exact","end","chip","duration","option","ongoing","datePicker","endPicker","confirmed","dialog"))
        fun text(v:Any?):String{val t=v as? String ?: throw IllegalArgumentException();require(attentionSafe(t));return t}
        fun optional(k:String):String?=if(s.opt(k)===JSONObject.NULL)null else text(s.opt(k))
        fun bool(k:String)=s.opt(k) as? Boolean ?: throw IllegalArgumentException(k)
        fun date(k:String):java.time.LocalDate?{val v=s.opt(k);if(v===JSONObject.NULL)return null;require(v is Int||v is Long);return dates.firstOrNull{it.toEpochDay()==(v as Number).toLong()} ?: throw IllegalArgumentException("unbound date")}
        val duration=s.opt("duration") as? Int ?: return null
        original.copy(selectedExactDate=date("exact"),selectedPeriodEndDate=date("end"),selectedApproximateChip=optional("chip"),periodDurationDays=duration,selectedPeriodDurationOption=text(s.opt("option")),isPeriodOngoing=bool("ongoing"),showDatePickerDialog=bool("datePicker"),showEndDatePickerDialog=bool("endPicker"),periodEndDateConfirmed=bool("confirmed"),periodDateDialogMessage=optional("dialog"))
    }.getOrNull()

    private fun encodeOnboardingQuestions(questions:List<com.cyclecare.app.domain.onboarding.OnboardingQuestion>):JSONArray {
        fun text(v:String):String{require(attentionSafe(v));return v}
        return JSONArray(questions.map{JSONObject().put("id",text(it.id)).put("prompt",text(it.prompt)).put("options",JSONArray(it.options.map(::text))).put("multiSelect",it.multiSelect)})
    }
    private fun decodeOnboardingQuestions(value:Any?):List<com.cyclecare.app.domain.onboarding.OnboardingQuestion> {
        fun text(v:Any?):String{val s=v as? String ?: throw IllegalArgumentException();require(attentionSafe(s));return s}
        val a=value as? JSONArray ?: throw IllegalArgumentException()
        return (0 until a.length()).map {i->val q=a.opt(i) as? JSONObject ?: throw IllegalArgumentException();require(q.keys().asSequence().toSet()==setOf("id","prompt","options","multiSelect"));val opts=q.opt("options") as? JSONArray ?: throw IllegalArgumentException();val multi=q.opt("multiSelect") as? Boolean ?: throw IllegalArgumentException();com.cyclecare.app.domain.onboarding.OnboardingQuestion(text(q.opt("id")),text(q.opt("prompt")),(0 until opts.length()).map{text(opts.opt(it))},multi)}
    }
    internal fun fullOnboardingBank(step:Int,goals:Set<String>,prior:Map<String,Set<String>>):List<com.cyclecare.app.domain.onboarding.OnboardingQuestion>? = runCatching {
        val joined=goals.joinToString(" ").lowercase();val earlier=if(step==5)prior.values.flatten().joinToString(" ").lowercase()else ""
        require(attentionSafe(joined)&&attentionSafe(earlier))
        decodeFullOnboardingBank(call("onboarding_bank_full_v1",JSONObject().put("step",step).put("goals",joined).put("earlier",earlier)) ?: return null)
    }.getOrNull()
    internal fun decodeFullOnboardingBank(reply:JSONObject):List<com.cyclecare.app.domain.onboarding.OnboardingQuestion>? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"));val ok=reply.opt("ok") as? JSONObject ?: return null;require(ok.keys().asSequence().toSet()==setOf("version","questions"));require(ok.opt("version") is Int&&ok.opt("version")==1);decodeOnboardingQuestions(ok.opt("questions"))
    }.getOrNull()
    internal fun onboardingQuestionsPayload(s:com.cyclecare.app.ui.viewmodel.OnboardingUiState,step:Int,incoming:List<com.cyclecare.app.domain.onboarding.OnboardingQuestion>?,action:com.cyclecare.app.ui.viewmodel.OnboardingQuestionReducer.Action):JSONObject? = runCatching {
        val entries=JSONArray(s.dynamicQuestions.map{(key,value)->JSONObject().put("step",key).put("questions",encodeOnboardingQuestions(value))})
        JSONObject().put("entries",entries).put("step",step).put("incoming",incoming?.let(::encodeOnboardingQuestions) ?: JSONObject.NULL).put("action",action.name).put("loading",s.isLoadingDynamicQuestions)
    }.getOrNull()
    internal fun fullOnboardingQuestions(s:com.cyclecare.app.ui.viewmodel.OnboardingUiState,step:Int,incoming:List<com.cyclecare.app.domain.onboarding.OnboardingQuestion>?,action:com.cyclecare.app.ui.viewmodel.OnboardingQuestionReducer.Action):com.cyclecare.app.ui.viewmodel.OnboardingUiState? = runCatching {
        decodeFullOnboardingQuestions(call("onboarding_questions_state_v1",onboardingQuestionsPayload(s,step,incoming,action) ?: return null) ?: return null,s)
    }.getOrNull()
    internal fun decodeFullOnboardingQuestions(reply:JSONObject,original:com.cyclecare.app.ui.viewmodel.OnboardingUiState):com.cyclecare.app.ui.viewmodel.OnboardingUiState? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"));val ok=reply.opt("ok") as? JSONObject ?: return null;require(ok.keys().asSequence().toSet()==setOf("version","entries","loading"));require(ok.opt("version") is Int&&ok.opt("version")==1)
        val loading=ok.opt("loading") as? Boolean ?: return null;val a=ok.opt("entries") as? JSONArray ?: return null;val entries=linkedMapOf<Int,List<com.cyclecare.app.domain.onboarding.OnboardingQuestion>>()
        for(i in 0 until a.length()){val e=a.opt(i) as? JSONObject ?: return null;require(e.keys().asSequence().toSet()==setOf("step","questions"));val step=e.opt("step") as? Int ?: return null;require(!entries.containsKey(step));entries[step]=decodeOnboardingQuestions(e.opt("questions"))}
        original.copy(dynamicQuestions=entries,isLoadingDynamicQuestions=loading)
    }.getOrNull()

    internal fun onboardingStatePayload(s:com.cyclecare.app.ui.viewmodel.OnboardingUiState,e:com.cyclecare.app.ui.viewmodel.OnboardingStateReducer.Event):JSONObject? = runCatching {
        fun text(v:String):String {require(attentionSafe(v));return v}
        fun strings(v:Collection<String>)=JSONArray(v.map(::text))
        val answers=JSONArray(s.dynamicAnswers.map{(id,values)->JSONObject().put("id",text(id)).put("values",strings(values))})
        val state=JSONObject().put("step",s.step).put("total",s.totalSteps).put("goals",strings(s.selectedGoals)).put("answers",answers).put("symptoms",strings(s.selectedTypicalSymptoms)).put("energy",text(s.selectedTypicalEnergy)).put("sleep",text(s.selectedTypicalSleep)).put("cycleOption",text(s.selectedCycleOption)).put("cycleDays",s.cycleLengthDays)
            .put("notSure",s.isNotSureSelected).put("datePicker",s.showDatePickerDialog).put("endPicker",s.showEndDatePickerDialog).put("remind",s.remindFertileWindow ?: JSONObject.NULL).put("age",s.isAgeVerified).put("terms",s.isTermsAgreed).put("cloud",s.isCloudSyncAllowed).put("termsDialog",s.showTermsDialog).put("privacyDialog",s.showPrivacyDialog).put("deep",s.hasCompletedDeepBaseline).put("endConfirmed",s.periodEndDateConfirmed).put("dateDialog",s.periodDateDialogMessage?.let(::text) ?: JSONObject.NULL)
        JSONObject().put("state",state).put("event",JSONObject().put("action",e.action.name).put("text",text(e.text)).put("other",text(e.other)).put("flag",e.flag).put("days",e.days))
    }.getOrNull()
    internal fun fullOnboardingState(s:com.cyclecare.app.ui.viewmodel.OnboardingUiState,e:com.cyclecare.app.ui.viewmodel.OnboardingStateReducer.Event):com.cyclecare.app.ui.viewmodel.OnboardingUiState? = runCatching {
        val p=onboardingStatePayload(s,e) ?: return null
        decodeFullOnboardingState(call("onboarding_state_full_v1",p) ?: return null,s)
    }.getOrNull()
    internal fun decodeFullOnboardingState(reply:JSONObject,original:com.cyclecare.app.ui.viewmodel.OnboardingUiState):com.cyclecare.app.ui.viewmodel.OnboardingUiState? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"));val ok=reply.opt("ok") as? JSONObject ?: return null
        require(ok.keys().asSequence().toSet()==setOf("version","state"));require(ok.opt("version") is Int&&ok.opt("version")==1);val s=ok.opt("state") as? JSONObject ?: return null
        require(s.keys().asSequence().toSet()==setOf("step","total","goals","answers","symptoms","energy","sleep","cycleOption","cycleDays","notSure","datePicker","endPicker","remind","age","terms","cloud","termsDialog","privacyDialog","deep","endConfirmed","dateDialog"))
        fun text(v:Any?):String {val t=v as? String ?: throw IllegalArgumentException();require(attentionSafe(t));return t}
        fun bool(k:String)=s.opt(k) as? Boolean ?: throw IllegalArgumentException(k)
        fun int(k:String)=s.opt(k) as? Int ?: throw IllegalArgumentException(k)
        fun strings(a:Any?):Set<String> {val list=a as? JSONArray ?: throw IllegalArgumentException();val values=(0 until list.length()).map{text(list.opt(it))};require(values.distinct().size==values.size);return values.toSet()}
        val a=s.opt("answers") as? JSONArray ?: return null;val answers=linkedMapOf<String,Set<String>>()
        for(i in 0 until a.length()){val v=a.opt(i) as? JSONObject ?: return null;require(v.keys().asSequence().toSet()==setOf("id","values"));val id=text(v.opt("id"));require(!answers.containsKey(id));answers[id]=strings(v.opt("values"))}
        val remind=s.opt("remind");require(remind===JSONObject.NULL||remind is Boolean);val dialog=s.opt("dateDialog");val message=if(dialog===JSONObject.NULL)null else text(dialog)
        require(int("total")==original.totalSteps)
        original.copy(step=int("step"),selectedGoals=strings(s.opt("goals")),dynamicAnswers=answers,selectedTypicalSymptoms=strings(s.opt("symptoms")),selectedTypicalEnergy=text(s.opt("energy")),selectedTypicalSleep=text(s.opt("sleep")),selectedCycleOption=text(s.opt("cycleOption")),cycleLengthDays=int("cycleDays"),isNotSureSelected=bool("notSure"),showDatePickerDialog=bool("datePicker"),showEndDatePickerDialog=bool("endPicker"),remindFertileWindow=remind as? Boolean,isAgeVerified=bool("age"),isTermsAgreed=bool("terms"),isCloudSyncAllowed=bool("cloud"),showTermsDialog=bool("termsDialog"),showPrivacyDialog=bool("privacyDialog"),hasCompletedDeepBaseline=bool("deep"),periodEndDateConfirmed=bool("endConfirmed"),periodDateDialogMessage=message)
    }.getOrNull()

    internal data class PeriodEpisodeResult(val check:com.cyclecare.app.domain.health.PeriodDateConsistency.PeriodPairCheck,val storage:com.cyclecare.app.domain.health.PeriodEpisodeEntry.StorageDecision)
    internal fun fullPeriodEpisode(start:java.time.LocalDate?,end:java.time.LocalDate?,today:java.time.LocalDate,ongoing:Boolean,typical:Int):PeriodEpisodeResult? = runCatching {
        val p=JSONObject().put("start",start?.toEpochDay() ?: JSONObject.NULL).put("end",end?.toEpochDay() ?: JSONObject.NULL).put("today",today.toEpochDay()).put("ongoing",ongoing).put("typical",typical)
        decodeFullPeriodEpisode(call("period_episode_full_v1",p) ?: return null,end,typical)
    }.getOrNull()
    internal fun decodeFullPeriodEpisode(reply:JSONObject,end:java.time.LocalDate?,currentTypical:Int):PeriodEpisodeResult? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"));val ok=reply.opt("ok") as? JSONObject ?: return null
        require(ok.keys().asSequence().toSet()==setOf("version","valid","error","warning","duration","typical","confirm","keepEnd"));require(ok.opt("version") is Int&&ok.opt("version")==1)
        fun bool(k:String)=ok.opt(k) as? Boolean ?: throw IllegalArgumentException(k)
        fun int(k:String)=ok.opt(k) as? Int ?: throw IllegalArgumentException(k)
        fun optional(k:String):String? {val v=ok.opt(k);if(v===JSONObject.NULL)return null;return v as? String ?: throw IllegalArgumentException(k)}
        val valid=bool("valid");val error=optional("error");val warning=optional("warning");val duration=int("duration");val typical=int("typical");val confirm=bool("confirm");val keep=bool("keepEnd")
        require(keep==valid&&confirm==(warning!=null));require(if(valid)end!=null&&error==null&&typical==(if(confirm)currentTypical else duration) else error!=null&&warning==null&&duration==0&&typical==currentTypical&&!confirm)
        PeriodEpisodeResult(com.cyclecare.app.domain.health.PeriodDateConsistency.PeriodPairCheck(valid,error,warning,duration),com.cyclecare.app.domain.health.PeriodEpisodeEntry.StorageDecision(if(keep)end else null,typical,confirm,warning,error))
    }.getOrNull()

    internal fun careMergePayload(existing:com.cyclecare.app.data.local.CycleDayLog,parsed:com.cyclecare.app.data.model.ParsedLogInterpretation):JSONObject? = runCatching {
        fun text(s:String):String {require(attentionSafe(s));return s}
        fun optional(s:String?):Any = if(s==null)JSONObject.NULL else text(s)
        val tokens=(existing.symptomsCsv.split(",")+parsed.symptomsResult).map {it.trim()}.filter {it.isNotBlank()}
        JSONObject().put("flow",text(existing.flowLevel)).put("energy",text(existing.energyLevel)).put("mood",text(existing.moodLevel)).put("pain",existing.painIntensity)
            .put("newFlow",optional(parsed.flowLevelResult)).put("newEnergy",optional(parsed.energyResult)).put("newMood",optional(parsed.moodResult)).put("newPain",parsed.painIntensityResult).put("tokens",JSONArray(tokens.map(::text)))
    }.getOrNull()
    internal fun fullCareMerge(existing:com.cyclecare.app.data.local.CycleDayLog,parsed:com.cyclecare.app.data.model.ParsedLogInterpretation):com.cyclecare.app.data.local.CycleDayLog? = runCatching {
        val input=careMergePayload(existing,parsed) ?: return null
        decodeFullCareMerge(call("care_merge_full_v1",input) ?: return null,existing)
    }.getOrNull()
    internal fun decodeFullCareMerge(reply:JSONObject,existing:com.cyclecare.app.data.local.CycleDayLog):com.cyclecare.app.data.local.CycleDayLog? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"));val ok=reply.opt("ok") as? JSONObject ?: return null
        require(ok.keys().asSequence().toSet()==setOf("version","flow","energy","mood","pain","symptoms"));require(ok.opt("version") is Int && ok.opt("version")==1)
        fun text(key:String):String {val s=ok.opt(key) as? String ?: throw IllegalArgumentException(key);require(attentionSafe(s));return s}
        val pain=ok.opt("pain");require(pain is Int)
        existing.copy(flowLevel=text("flow"),energyLevel=text("energy"),moodLevel=text("mood"),symptomsCsv=text("symptoms"),painIntensity=pain as Int)
    }.getOrNull()

    internal fun fullCareContext(question:String,logs:List<com.cyclecare.app.data.local.CycleDayLog>,settings:com.cyclecare.app.data.local.CycleSettings,today:java.time.LocalDate):String? = runCatching {
        val input=careContextPayload(question,logs,settings,today) ?: return null
        decodeFullCareContext(call("care_context_full_v1",input) ?: return null)?.take(com.cyclecare.app.domain.ai.CareConversationPolicy.MAX_CONTEXT_LENGTH)
    }.getOrNull()
    internal fun careContextPayload(question:String,logs:List<com.cyclecare.app.data.local.CycleDayLog>,settings:com.cyclecare.app.data.local.CycleSettings,today:java.time.LocalDate):JSONObject? = runCatching {
        fun safe(s:String):String {require(attentionSafe(s));return s}
        fun selected(s:String):Any = if(s.isNotBlank())safe(s) else JSONObject.NULL
        val entries=JSONArray()
        for(l in logs)entries.put(JSONObject().put("date",safe(l.dateString)).put("flow",selected(l.flowLevel)).put("energy",selected(l.energyLevel)).put("mood",selected(l.moodLevel))
            .put("symptoms",if(l.symptomsCsv.isNotBlank())safe(l.symptomsCsv.take(180)) else JSONObject.NULL)
            .put("bits",java.lang.Integer.toHexString(l.sleepHours.toRawBits()).padStart(8,'0')).put("sleep",l.sleepHours.toString()).put("quality",selected(l.sleepQuality)).put("pain",l.painIntensity))
        val input=JSONObject().put("question",safe(question.lowercase())).put("anchor",selected(settings.lastPeriodStartDate)).put("length",settings.averageCycleLength).put("today",today.toString()).put("logs",entries)
        input
    }.getOrNull()
    internal fun decodeFullCareContext(reply:JSONObject):String? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"));val ok=reply.opt("ok") as? JSONObject ?: return null
        require(ok.keys().asSequence().toSet()==setOf("version","text"));require(ok.opt("version") is Int && ok.opt("version")==1)
        val text=ok.opt("text") as? String ?: return null
        require(attentionSafe(text) && text.startsWith("User-approved context. Self-reported observations, not diagnosis.\n") && text.endsWith("No free-text notes, account details, sexual activity or intimate-health records included."))
        text
    }.getOrNull()

    internal fun accountStart(valid:Boolean,state:com.cyclecare.app.domain.sync.LocalDataState,remote:Boolean?):com.cyclecare.app.domain.sync.AccountStartDestination? = runCatching {
        decodeAccountStart(call("account_start_v1",JSONObject().put("valid",valid).put("state",state.name).put("remote",remote ?: JSONObject.NULL)) ?: return null)
    }.getOrNull()
    internal fun decodeAccountStart(reply:JSONObject):com.cyclecare.app.domain.sync.AccountStartDestination? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"));val ok=reply.opt("ok") as? JSONObject ?: return null
        require(ok.keys().asSequence().toSet()==setOf("version","destination"));require(ok.opt("version") is Int && ok.opt("version")==1)
        val route=ok.opt("destination") as? String ?: return null
        com.cyclecare.app.domain.sync.AccountStartDestination.valueOf(route)
    }.getOrNull()
    internal data class ReconnectResult(val decision:com.cyclecare.app.domain.sync.ReconnectWorkPolicy.Decision,val connected:Boolean,val last:Long,val owner:String?)
    internal fun reconnectState(state:com.cyclecare.app.domain.sync.ReconnectWorkPolicy.State,trailing:Boolean,connected:Boolean,eligible:Boolean,owner:String?,now:Long):ReconnectResult? = runCatching {
        require(listOfNotNull(state.owner,owner).all(::attentionSafe))
        val pre=JSONObject().put("connected",state.wasConnected).put("last",state.lastRunAtMs).put("owner",state.owner ?: JSONObject.NULL)
        val input=JSONObject().put("state",pre).put("trailing",trailing).put("connected",connected).put("eligible",eligible).put("owner",owner ?: JSONObject.NULL).put("now",now)
        decodeReconnectState(call("reconnect_state_v1",input) ?: return null)
    }.getOrNull()
    internal fun decodeReconnectState(reply:JSONObject):ReconnectResult? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"));val ok=reply.opt("ok") as? JSONObject ?: return null
        require(ok.keys().asSequence().toSet()==setOf("version","kind","wait","state"));require(ok.opt("version") is Int && ok.opt("version")==1)
        fun long(o:JSONObject,key:String):Long {val value=o.opt(key);require(value is Int || value is Long);return (value as Number).toLong()}
        val state=ok.opt("state") as? JSONObject ?: return null
        require(state.keys().asSequence().toSet()==setOf("connected","last","owner"));val connected=state.opt("connected");require(connected is Boolean)
        val rawOwner=state.opt("owner");val owner=if(rawOwner==JSONObject.NULL)null else {require(rawOwner is String && attentionSafe(rawOwner));rawOwner as String}
        val wait=ok.opt("wait")
        val decision=when(ok.opt("kind")) {
            "RUN" -> {require(wait==JSONObject.NULL);com.cyclecare.app.domain.sync.ReconnectWorkPolicy.Decision.Run}
            "SKIP" -> {require(wait==JSONObject.NULL);com.cyclecare.app.domain.sync.ReconnectWorkPolicy.Decision.Skip}
            "THROTTLED" -> com.cyclecare.app.domain.sync.ReconnectWorkPolicy.Decision.Throttled(long(ok,"wait"))
            else -> return null
        }
        ReconnectResult(decision,connected as Boolean,long(state,"last"),owner)
    }.getOrNull()

    internal data class RecoveryDecision(val again:Boolean,val delayed:Boolean,val round:Int,val pending:Boolean,val inFlight:Boolean)
    internal fun recoveryAfterPass(round:Int,absolute:Int,max:Int,budget:Int,pending:Boolean,recoverable:Boolean):RecoveryDecision? = runCatching {
        val payload=JSONObject().put("round",round).put("absolute",absolute).put("max",max).put("budget",budget).put("pending",pending).put("recoverable",recoverable)
        val d=decodeRecoveryDecision(call("recovery_after_pass_v1",payload) ?: return null,round) ?: return null
        val expected=when {
            absolute>=budget -> RecoveryDecision(false,false,round,false,false)
            pending -> RecoveryDecision(true,false,0,false,true)
            recoverable && round<max -> RecoveryDecision(true,true,round,false,true)
            else -> RecoveryDecision(false,false,round,false,false)
        }
        d.takeIf {it==expected}
    }.getOrNull()
    internal fun decodeRecoveryDecision(reply:JSONObject,originalRound:Int):RecoveryDecision? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"));val ok=reply.opt("ok") as? JSONObject ?: return null
        require(ok.keys().asSequence().toSet()==setOf("version","action","round","pending","inFlight"))
        require(ok.opt("version") is Int && ok.opt("version")==1)
        val round=ok.opt("round");require(round is Int)
        require(ok.opt("pending") is Boolean && ok.opt("pending")==false)
        val active=ok.opt("inFlight");require(active is Boolean)
        when(ok.opt("action")) {
            "stop" -> {require(round==originalRound && active==false);RecoveryDecision(false,false,round as Int,false,false)}
            "prompt" -> {require(round==0 && active==true);RecoveryDecision(true,false,0,false,true)}
            "delayed" -> {require(round==originalRound && active==true);RecoveryDecision(true,true,round as Int,false,true)}
            else -> null
        }
    }.getOrNull()

    private fun attentionSafe(s:String):Boolean {var i=0;while(i<s.length) {val c=s[i++];if(c.isHighSurrogate()) {if(i==s.length || !s[i++].isLowSurrogate())return false} else if(c.isLowSurrogate())return false};return true}
    internal data class FullAttentionEvidence(val days:Int,val sleep:Double?)
    internal fun prepareFullAttention(average:Int,day:Int,logs:List<com.cyclecare.app.data.local.CycleDayLog>):FullAttentionEvidence? = runCatching {
        val entries=JSONArray()
        for(log in logs) {
            require(attentionSafe(log.dateString) && attentionSafe(log.sleepQuality))
            val quality=log.sleepQuality.uppercase();require(attentionSafe(quality))
            entries.put(JSONObject().put("date",log.dateString).put("bits",java.lang.Integer.toHexString(log.sleepHours.toRawBits()).padStart(8,'0')).put("quality",quality))
        }
        decodeFullAttentionEvidence(call("attention_full_prepare_v1",JSONObject().put("average",average).put("day",day).put("logs",entries)) ?: return null)?.also {require(it.days==(average-day).coerceAtLeast(0))}
    }.getOrNull()
    internal fun decodeFullAttentionEvidence(reply:JSONObject):FullAttentionEvidence? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"));val ok=reply.opt("ok") as? JSONObject ?: return null
        require(ok.keys().asSequence().toSet()==setOf("version","daysUntilPeriod","sleepBits"));require(ok.opt("version") is Int && ok.opt("version")==1)
        val days=ok.opt("daysUntilPeriod");require(days is Int && days>=0)
        val bits=ok.opt("sleepBits");val avg=if(bits==JSONObject.NULL)null else {
            require(bits is String && bits.length==16 && bits.all {it in '0'..'9' || it in 'a'..'f'})
            Double.fromBits(java.lang.Long.parseUnsignedLong(bits,16)).also {require(it.isFinite() && it>0)}
        }
        FullAttentionEvidence(days as Int,avg)
    }.getOrNull()
    internal fun renderFullAttention(choice:FullAttentionChoice):com.cyclecare.app.data.model.TodayAttentionItem? = runCatching {
        val bound=choice as? BoundAttentionChoice ?: return null
        val branch=bound.decision.branch.name.lowercase()
        val title=bound.title;val message=bound.message;val until=bound.until;val day=bound.day
        val sleep=if(bound.decision.branch==NonsafetyBranch.PERIOD_SLEEP_TAPER) "%.1f".format(bound.sleep ?: return null) else ""
        require(listOf(branch,title,message,sleep).all(::attentionSafe))
        val p=JSONObject().put("branch",branch).put("title",title).put("message",message).put("until",until).put("day",day).put("sleep",sleep)
        decodeFullAttentionItem(call("attention_full_render_v1",p) ?: return null,branch)
    }.getOrNull()
    internal fun decodeFullAttentionItem(reply:JSONObject,branch:String):com.cyclecare.app.data.model.TodayAttentionItem? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"));val ok=reply.opt("ok") as? JSONObject ?: return null
        require(ok.keys().asSequence().toSet()==setOf("version","item"));require(ok.opt("version") is Int && ok.opt("version")==1)
        val o=ok.opt("item") as? JSONObject ?: return null
        require(o.keys().asSequence().toSet()==setOf("id","category","headline","message","whyThisAppeared","careSuggestion","isUrgent"))
        require(o.opt("id")==branch && o.opt("isUrgent") is Boolean && o.opt("isUrgent")==false)
        require(branch in setOf("baseline_shift_strong","baseline_shift_notable","period_sleep_taper","fertile_window_notice","phase_menstrual_start","wellbeing_checkin"))
        fun text(k:String):String {val s=o.opt(k) as? String ?: throw IllegalArgumentException(k);require(s.isNotEmpty());return s}
        com.cyclecare.app.data.model.TodayAttentionItem(text("id"),text("category"),text("headline"),text("message"),text("whyThisAppeared"),text("careSuggestion"),false)
    }.getOrNull()

    internal fun fullDeviations(logs:List<com.cyclecare.app.data.local.CycleDayLog>,today:java.time.LocalDate):List<com.cyclecare.app.domain.pattern.BaselineDeviation>? = runCatching {
        fun safe(s:String):Boolean {var i=0;while(i<s.length) {val c=s[i++];if(c.isHighSurrogate()) {if(i==s.length || !s[i++].isLowSurrogate())return false} else if(c.isLowSurrogate())return false};return true}
        val entries=JSONArray()
        for(log in logs) {
            require(safe(log.dateString) && safe(log.symptomsCsv))
            val symptoms=JSONArray()
            for(key in log.symptomsCsv.split(',').map(String::trim).filter(String::isNotBlank)) {
                val label=key.replace('_',' ').replaceFirstChar {it.titlecase()};require(safe(label))
                symptoms.put(JSONObject().put("key",key).put("label",label))
            }
            entries.put(JSONObject().put("date",log.dateString).put("pain",log.painIntensity).put("symptoms",symptoms))
        }
        decodeFullDeviations(call("deviation_full_v1",JSONObject().put("today",today.toString()).put("logs",entries)) ?: return null)
    }.getOrNull()

    internal fun decodeFullDeviations(reply:JSONObject):List<com.cyclecare.app.domain.pattern.BaselineDeviation>? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"))
        val ok=reply.opt("ok") as? JSONObject ?: return null
        require(ok.keys().asSequence().toSet()==setOf("version","entries"));require(ok.opt("version") is Int && ok.opt("version")==1)
        val entries=ok.opt("entries") as? JSONArray ?: return null
        (0 until entries.length()).map {i ->
            val o=entries.opt(i) as? JSONObject ?: return null
            require(o.keys().asSequence().toSet()==setOf("factorKey","title","baselineBits","recentBits","deltaBits","severity","message"))
            fun text(k:String):String {val s=o.opt(k) as? String ?: throw IllegalArgumentException(k);require(s.isNotEmpty());return s}
            fun number(k:String):Double {val s=text(k);require(s.length==16 && s.all {it in '0'..'9' || it in 'a'..'f'});val v=Double.fromBits(java.lang.Long.parseUnsignedLong(s,16));require(v.isFinite() && v>=0.0);return v}
            val severity=com.cyclecare.app.domain.pattern.DeviationSeverity.valueOf(text("severity"))
            com.cyclecare.app.domain.pattern.BaselineDeviation(text("factorKey"),text("title"),number("baselineBits"),number("recentBits"),number("deltaBits"),severity,text("message"))
        }
    }.getOrNull()

    internal fun fullReport(settings:com.cyclecare.app.data.local.CycleSettings,logs:List<com.cyclecare.app.data.local.CycleDayLog>,profile:com.cyclecare.app.data.local.OnboardingProfile?,today:String,label:(String)->String):String? = runCatching {
        fun safe(s:String):Boolean {var i=0;while(i<s.length) {val c=s[i++];if(c.isHighSurrogate()) {if(i==s.length || !s[i++].isLowSurrogate())return false} else if(c.isLowSurrogate())return false};return true}
        require(safe(today) && safe(settings.lastPeriodStartDate))
        fun tag(key:String):JSONObject {val display=label(key);require(safe(key) && safe(display));return JSONObject().put("key",key).put("label",display)}
        val entries=JSONArray()
        for(log in logs) {
            require(safe(log.dateString) && safe(log.symptomsCsv) && safe(log.flowLevel) && safe(log.notes))
            val symptoms=JSONArray();log.symptomsCsv.split(',').map(String::trim).filter(String::isNotBlank).forEach {symptoms.put(tag(it))}
            entries.put(JSONObject().put("date",log.dateString).put("symptoms",symptoms).put("flow",log.flowLevel).put("flow_upper",tag(log.flowLevel.uppercase())).put("pain",log.painIntensity).put("note",log.notes.trim()))
        }
        val payload=JSONObject().put("today",today).put("average",settings.averageCycleLength).put("duration",settings.periodDuration)
            .put("anchor",settings.lastPeriodStartDate).put("anchor_blank",settings.lastPeriodStartDate.isBlank())
            .put("approximate",profile?.approximatePeriodChoice.orEmpty().startsWith("APPROXIMATE:",ignoreCase=true)).put("logs",entries)
        decodeFullReport(call("report_full_v1",payload) ?: return null)
    }.getOrNull()

    internal fun decodeFullReport(reply:JSONObject):String? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"))
        val ok=reply.opt("ok") as? JSONObject ?: return null
        require(ok.keys().asSequence().toSet()==setOf("version","text"));require(ok.opt("version") is Int && ok.opt("version")==1)
        val text=ok.opt("text") as? String ?: return null
        require(text.isNotEmpty());text
    }.getOrNull()

    internal fun fullExplain(prediction:com.cyclecare.app.data.model.DayPrediction?):com.cyclecare.app.data.model.ExplainMyBodyResult? {
        val p=prediction?.let {JSONObject().put("phase",it.phase.name).put("day",it.dayOfCycle)} ?: JSONObject.NULL
        return decodeFullExplain(call("explain_full_v1",JSONObject().put("prediction",p)) ?: return null)
    }

    internal fun decodeFullExplain(reply:JSONObject):com.cyclecare.app.data.model.ExplainMyBodyResult? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"))
        val ok=reply.opt("ok") as? JSONObject ?: return null
        require(ok.keys().asSequence().toSet()==setOf("version","result"));require(ok.opt("version") is Int && ok.opt("version")==1)
        val o=ok.opt("result") as? JSONObject ?: return null
        require(o.keys().asSequence().toSet()==setOf("summary","whatMightBeHappening","whatSupportsThat","whatElseCouldExplain","whatYouCanDo","whatToWatch","whenToGetChecked","safetyLevel"))
        fun text(k:String):String {val s=o.opt(k) as? String ?: throw IllegalArgumentException(k);require(s.isNotEmpty());return s}
        require(o.opt("safetyLevel")=="LOW")
        com.cyclecare.app.data.model.ExplainMyBodyResult(text("summary"),text("whatMightBeHappening"),text("whatSupportsThat"),text("whatElseCouldExplain"),text("whatYouCanDo"),text("whatToWatch"),text("whenToGetChecked"),com.cyclecare.app.data.model.SafetyLevel.LOW)
    }.getOrNull()

    internal fun fullTimeline(logs:List<com.cyclecare.app.data.local.CycleDayLog>,label:(String)->String):List<com.cyclecare.app.data.model.MonthlyTimelineEntry>? = runCatching {
        fun safe(s:String):Boolean {var i=0;while(i<s.length) {val c=s[i++];if(c.isHighSurrogate()) {if(i==s.length || !s[i++].isLowSurrogate())return false} else if(c.isLowSurrogate())return false};return true}
        val format=java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy")
        fun tag(key:String):JSONObject {val display=label(key);require(safe(key) && safe(display));return JSONObject().put("key",key).put("label",display)}
        val entries=JSONArray()
        for(log in logs) {
            require(safe(log.dateString) && safe(log.symptomsCsv) && safe(log.energyLevel) && safe(log.flowLevel))
            val parsed=runCatching {java.time.LocalDate.parse(log.dateString)}.getOrNull()
            val title=parsed?.let {java.time.YearMonth.from(it).atDay(1).format(format)} ?: ""
            require(safe(title))
            val symptoms=JSONArray();log.symptomsCsv.split(',').map(String::trim).filter(String::isNotBlank).forEach {symptoms.put(tag(it))}
            entries.put(JSONObject().put("date",log.dateString).put("month_title",title).put("energy",tag(log.energyLevel.uppercase()))
                .put("bleeding",log.flowLevel.uppercase() in setOf("LIGHT","MODERATE","HEAVY","UNUSUALLY_HEAVY")).put("symptoms",symptoms))
        }
        decodeFullTimeline(call("timeline_full_v1",JSONObject().put("logs",entries)) ?: return null,logs.size)
    }.getOrNull()

    internal fun decodeFullTimeline(reply:JSONObject,max:Int):List<com.cyclecare.app.data.model.MonthlyTimelineEntry>? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"))
        val ok=reply.opt("ok") as? JSONObject ?: return null
        require(ok.keys().asSequence().toSet()==setOf("version","entries"));require(ok.opt("version") is Int && ok.opt("version")==1)
        val entries=ok.opt("entries") as? JSONArray ?: return null;require(entries.length()<=max)
        (0 until entries.length()).map {i ->
            val o=entries.opt(i) as? JSONObject ?: return null
            require(o.keys().asSequence().toSet()==setOf("monthTitle","cycleLengthDays","periodBleedingDays","dominantEnergy","dominantSymptom","patternNoticed"))
            fun text(k:String):String {val s=o.opt(k) as? String ?: throw IllegalArgumentException(k);require(s.isNotEmpty());return s}
            require(o.opt("cycleLengthDays") is Int && o.opt("cycleLengthDays")==0)
            val bleed=o.opt("periodBleedingDays");require(bleed is Int && bleed in 0..max)
            require(o.opt("patternNoticed")==JSONObject.NULL)
            com.cyclecare.app.data.model.MonthlyTimelineEntry(text("monthTitle"),0,bleed as Int,text("dominantEnergy"),text("dominantSymptom"),null)
        }
    }.getOrNull()

    internal fun fullPatterns(
        settings: com.cyclecare.app.data.local.CycleSettings,
        logs: List<com.cyclecare.app.data.local.CycleDayLog>,
        today: java.time.LocalDate
    ): List<com.cyclecare.app.data.model.HealthPattern>? = runCatching {
        fun safe(s:String):Boolean {
            var i=0
            while(i<s.length) {val c=s[i++];if(c.isHighSurrogate()) {
                if(i==s.length || !s[i++].isLowSurrogate())return false
            } else if(c.isLowSurrogate())return false}
            return true
        }
        if(!safe(settings.lastPeriodStartDate))return null
        val entries=JSONArray()
        for(log in logs) {
            if(!safe(log.dateString) || !safe(log.symptomsCsv))return null
            val symptoms=JSONArray()
            for(key in log.symptomsCsv.split(',').map(String::trim).filter(String::isNotBlank)) {
                val label=key.replace('_',' ').replaceFirstChar {it.titlecase()}
                val lower=key.lowercase()
                if(!safe(label) || !safe(lower))return null
                symptoms.put(JSONObject().put("key",key).put("label",label).put("lower",lower))
            }
            entries.put(JSONObject().put("date",log.dateString).put("symptoms",symptoms))
        }
        val payload=JSONObject().put("today",today.toString()).put("anchor",settings.lastPeriodStartDate)
            .put("average",settings.averageCycleLength).put("duration",settings.periodDuration).put("luteal",settings.lutealPhaseDays)
            .put("logs",entries).put("weights",JSONArray())
        val plan=call("pattern_full_plan_v1",payload) ?: return null
        val offsets=decodePatternOffsets(plan,logs.size) ?: return null
        val weights=JSONArray()
        for(day in offsets) {
            val weight=kotlin.math.exp(-0.03 * day.toDouble())
            weights.put(JSONObject().put("day",day).put("bits",java.lang.Long.toHexString(weight.toRawBits()).padStart(16,'0')))
        }
        payload.put("weights",weights)
        decodeFullPatterns(call("pattern_full_v1",payload) ?: return null)
    }.getOrNull()

    internal fun decodePatternOffsets(reply:JSONObject,max:Int):List<Long>? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"))
        val ok=reply.opt("ok") as? JSONObject ?: return null
        require(ok.keys().asSequence().toSet()==setOf("version","days"))
        require(ok.opt("version") is Int && ok.opt("version")==1)
        val items=ok.opt("days") as? JSONArray ?: return null
        require(items.length()<=max)
        val days=(0 until items.length()).map {i ->
            val v=items.opt(i);require(v is Int || v is Long);val d=(v as Number).toLong()
            require(d in 0..730484999633L);d
        }
        require(days.zipWithNext().all {it.first<it.second})
        days
    }.getOrNull()

    internal fun decodeFullPatterns(reply:JSONObject):List<com.cyclecare.app.data.model.HealthPattern>? = runCatching {
        require(reply.keys().asSequence().toSet()==setOf("ok"))
        val ok=reply.opt("ok") as? JSONObject ?: return null
        require(ok.keys().asSequence().toSet()==setOf("version","patterns"))
        require(ok.opt("version") is Int && ok.opt("version")==1)
        val items=ok.opt("patterns") as? JSONArray ?: return null
        require(items.length()<=6)
        (0 until items.length()).map {i ->
            val o=items.opt(i) as? JSONObject ?: return null
            require(o.keys().asSequence().toSet()==setOf("id","title","supportingObservations","confidence","cyclePhase","explanation","actionableCare"))
            fun text(k:String):String {val s=o.opt(k) as? String ?: throw IllegalArgumentException(k);require(s.isNotEmpty());return s}
            require(o.opt("confidence") in setOf("High","Moderate","Emerging"))
            require(o.opt("cyclePhase") in setOf("Menstruation","Follicular Phase","Fertile & Ovulation","Luteal Phase"))
            com.cyclecare.app.data.model.HealthPattern(text("id"),text("title"),text("supportingObservations"),text("confidence"),text("cyclePhase"),text("explanation"),text("actionableCare"))
        }
    }.getOrNull()

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
    /* NEW IMPLEMENTATION (2026-11-13): native wiring of the existing core
     * community_transport parity port (TransportProbe JVM corpus) for
     * CommunityGuidesService + CommunityPulsePinger. Request shapes and
     * verdicts only - all I/O, credentials and timeouts stay Kotlin-side.
     * Every fn returns null on native unavailable/malformed so the caller
     * keeps its Kotlin expression, byte for byte. */
    private fun communityCall(op: String, payload: JSONObject): JSONObject? {
        payload.put("op", op)
        return call("community_transport_v1", payload)
    }

    internal fun communityPath(reply: JSONObject?): String? {
        val ok = reply?.optJSONObject("ok") ?: return null
        val version = ok.opt("version")
        if (version !is Int || version != 1) return null
        val path = ok.opt("path")
        if (path !is String || !path.startsWith("/")) return null
        return path
    }

    internal fun communityBody(reply: JSONObject?): String? {
        val ok = reply?.optJSONObject("ok") ?: return null
        val version = ok.opt("version")
        if (version !is Int || version != 1) return null
        val body = ok.opt("body")
        if (body !is String) return null
        return body
    }

    internal fun communityVerdict(reply: JSONObject?): Boolean? {
        val ok = reply?.optJSONObject("ok") ?: return null
        val version = ok.opt("version")
        if (version !is Int || version != 1) return null
        val verdict = ok.opt("verdict")
        if (verdict !is Boolean) return null
        return verdict
    }

    /** Reply of [communityCurrentUserId]: [userId] null means no signed-in
     * user; a null FUNCTION return means native unavailable/malformed. */
    data class CommunityUserId(val userId: String?)

    fun communityCurrentUserId(userId: String?): CommunityUserId? =
        decodeCommunityUserIdValue(communityCall("current_user_id", JSONObject().put("userId", userId ?: JSONObject.NULL)))

    /** Strict decode: "ok" a real object, "version" exactly 1, "value" either
     * JSON null (no signed-in user - the port's None) or a NONEMPTY string
     * (the port never emits an empty id: it maps blank to None, so an empty
     * string here is drift and is rejected, not coerced). */
    internal fun decodeCommunityUserIdValue(reply: JSONObject?): CommunityUserId? {
        val ok = reply?.optJSONObject("ok") ?: return null
        val version = ok.opt("version")
        if (version !is Int || version != 1) return null
        val value = ok.opt("value") ?: return null
        if (value === JSONObject.NULL) return CommunityUserId(null)
        if (value !is String || value.isEmpty()) return null
        return CommunityUserId(value)
    }

    fun communityFetchTopicsPath(): String? = communityPath(communityCall("fetch_topics_path", JSONObject()))

    fun communityFetchMessagesPath(topicId: String): String? =
        communityPath(communityCall("fetch_messages_path", JSONObject().put("topicId", topicId)))

    fun communityEnsureMembershipBody(userId: String, topicId: String): String? =
        communityBody(communityCall("ensure_membership_body", JSONObject().put("userId", userId).put("topicId", topicId)))

    fun communityMembershipSuccess(code: Int): Boolean? =
        communityVerdict(communityCall("membership_success", JSONObject().put("code", code)))

    fun communityPostContentAllowed(trimmed: String): Boolean? =
        communityVerdict(communityCall("post_content_allowed", JSONObject().put("trimmed", trimmed)))

    fun communityPostMessageBody(topicId: String, userId: String, senderAlias: String, contentTrimmed: String): String? =
        communityBody(communityCall("post_message_body", JSONObject().put("topicId", topicId).put("userId", userId).put("senderAlias", senderAlias).put("contentTrimmed", contentTrimmed)))

    fun communityFlagMessageBody(remoteMessageId: String): String? =
        communityBody(communityCall("flag_message_body", JSONObject().put("remoteMessageId", remoteMessageId)))

    fun communityFlagSuccess(code: Int, body: String): Boolean? =
        communityVerdict(communityCall("flag_success", JSONObject().put("code", code).put("body", body)))

    fun communityPulseBearer(accessToken: String?, anonKey: String): String? =
        decodeCommunityPulseBearerValue(communityCall("pulse_bearer", JSONObject().put("accessToken", accessToken ?: JSONObject.NULL).put("anonKey", anonKey)))

    /** Strict decode: "ok" a real object, "version" exactly 1, "value" a
     * NONEMPTY string (the port always returns a nonempty bearer - a blank
     * token maps to the anon key - so null, empty, or wrong-typed values
     * are drift and are rejected, not coerced). */
    internal fun decodeCommunityPulseBearerValue(reply: JSONObject?): String? {
        val ok = reply?.optJSONObject("ok") ?: return null
        val version = ok.opt("version")
        if (version !is Int || version != 1) return null
        val value = ok.opt("value")
        if (value !is String || value.isEmpty()) return null
        return value
    }

    fun communityPulsePath(): String? = communityPath(communityCall("pulse_path", JSONObject()))

    /** NEW IMPLEMENTATION (2026-11-13): native wiring of the existing core
     * session_token parity port for SupabaseSessionManager.tokenExpiry
     * (SessionTokenParity JVM locks t01-t08). 0 mirrors the source's
     * catch-all. null = native unavailable/malformed -> caller keeps the
     * Kotlin parse. */
    fun sessionTokenExpiry(token: String): Long? {
        val reply = call("session_token_expiry_v1", JSONObject().put("token", token)) ?: return null
        return decodeSessionTokenExpiry(reply)
    }

    /** Strict decode: "ok" a real object, "version" exactly 1, "expiry" a
     * genuine Int/Long >= 0 (0 mirrors the source catch-all; a negative or
     * coerced echo has no source shape). */
    internal fun decodeSessionTokenExpiry(reply: JSONObject): Long? {
        val ok = reply.optJSONObject("ok") ?: return null
        val version = ok.opt("version")
        if (version !is Int || version != 1) return null
        val expiry = ok.opt("expiry")
        if (expiry !is Int && expiry !is Long) return null
        val value = (expiry as Number).toLong()
        if (value < 0L) return null
        return value
    }

    /** NEW IMPLEMENTATION (2026-11-13): native wiring of the existing core
     * session_token parity port for
     * SupabaseSessionManager.validTokenForOwner (structural consistency
     * only; authenticity is the server's job). [nowSecs] is caller-supplied
     * so both paths judge the same instant. null = native
     * unavailable/malformed -> caller keeps the Kotlin check. */
    fun sessionTokenValidForOwner(token: String, owner: String, nowSecs: Long): Boolean? {
        val reply = call("session_token_valid_for_owner_v1", JSONObject()
            .put("token", token)
            .put("owner", owner)
            .put("nowSecs", nowSecs)) ?: return null
        return decodeSessionTokenValid(reply)
    }

    /** Strict decode: "ok" a real object, "version" exactly 1, "valid" a
     * genuine Boolean (optBoolean would coerce a malformed reply into an
     * authoritative auth verdict). */
    internal fun decodeSessionTokenValid(reply: JSONObject): Boolean? {
        val ok = reply.optJSONObject("ok") ?: return null
        val version = ok.opt("version")
        if (version !is Int || version != 1) return null
        val valid = ok.opt("valid")
        if (valid !is Boolean) return null
        return valid
    }

    /** Reply of [sessionRefreshShape]: SHAPE carries the extracted fields;
     * rotatedRefresh/userEmail null mean "keep the current value";
     * expiresAt/expiresIn are the RAW source fields (0 = absent, bounds
     * NOT applied natively). A null FUNCTION return means native
     * unavailable/malformed reply -> the caller keeps its Kotlin shape
     * handling, statement for statement. */
    data class SessionRefreshShape(
        val kind: Kind,
        val accessToken: String?,
        val rotatedRefresh: String?,
        val expiresAt: Long,
        val expiresIn: Long,
        val userEmail: String?,
        val anonymous: Boolean,
    ) { enum class Kind { SHAPE, REAUTH, UNAVAILABLE } }

    /** NEW IMPLEMENTATION (2026-11-13, reviewer-corrected): native wiring
     * of the NON-CLOCK shape segment of
     * SupabaseSessionManager.resolveAccessToken's refresh-response handling
     * (session_token::refresh_response_shape, corpus-locked, shared with
     * the full port - one parser, one implementation). Every clock-governed
     * decision (validTokenForOwner, the effective-refresh checks, the
     * reportedExpiry bounds) stays at the original Kotlin execution points
     * in the caller. No existing token crosses IN; the renewed access
     * token and any rotated refresh token cross OUT. */
    fun sessionRefreshShape(code: Int, body: String?, owner: String): SessionRefreshShape? {
        val reply = call("session_refresh_shape_v1", JSONObject()
            .put("code", code)
            .put("body", body ?: JSONObject.NULL)
            .put("owner", owner)) ?: return null
        return decodeSessionRefreshShape(reply)
    }

    /** Strict decode: "ok" a real object, "version" exactly 1, "verdict" an
     * exact kind string. A SHAPE reply must carry accessToken as a genuine
     * string (EMPTY INCLUDED - the source accepts it at this stage and the
     * original validTokenForOwner check rejects it at its own point),
     * rotatedRefresh null-or-string, userEmail null-or-NONEMPTY (the port
     * maps blank to the keep-old marker, so an empty email is drift),
     * expiresAt/expiresIn genuine integers (any value - negatives have a
     * source shape), and
     * anonymous a genuine Boolean. Anything coerced or defaulted is drift. */
    internal fun decodeSessionRefreshShape(reply: JSONObject): SessionRefreshShape? {
        val ok = reply.optJSONObject("ok") ?: return null
        val version = ok.opt("version")
        if (version !is Int || version != 1) return null
        return when (ok.opt("verdict")) {
            "reauth" -> SessionRefreshShape(SessionRefreshShape.Kind.REAUTH, null, null, 0L, 0L, null, false)
            "unavailable" -> SessionRefreshShape(SessionRefreshShape.Kind.UNAVAILABLE, null, null, 0L, 0L, null, false)
            "shape" -> {
                val access = ok.opt("accessToken")
                if (access !is String) return null
                val rotated = ok.opt("rotatedRefresh")
                if (rotated !== JSONObject.NULL && rotated !is String) return null
                val expiresAt = when (val e = ok.opt("expiresAt")) {
                    is Int -> e.toLong()
                    is Long -> e
                    else -> return null
                }
                val expiresIn = when (val e = ok.opt("expiresIn")) {
                    is Int -> e.toLong()
                    is Long -> e
                    else -> return null
                }
                val email = ok.opt("userEmail")
                if (email !== JSONObject.NULL && (email !is String || email.isEmpty())) return null
                val anonymous = ok.opt("anonymous")
                if (anonymous !is Boolean) return null
                SessionRefreshShape(
                    SessionRefreshShape.Kind.SHAPE, access,
                    if (rotated === JSONObject.NULL) null else rotated as String,
                    expiresAt, expiresIn,
                    if (email === JSONObject.NULL) null else email as String,
                    anonymous)
            }
            else -> null
        }
    }

    /** Decision of [authSignInResponse]. SUCCESS always carries user +
     * session; message is always present (the caller shows it verbatim). */
    enum class AuthDecisionKind { ERROR, VERIFY, SUCCESS }
    data class AuthUserFields(val id: String, val email: String?, val emailConfirmedAt: String?, val anonymous: Boolean)

    /** Raw session writes decided by the native interpretation. userId null
     * = KEEP the current value; userEmail/accessToken/refreshToken null =
     * SET null (the source's `.ifBlank { null }`); expiresAt null = the
     * caller computes now+expiresIn at its original commit-point clock
     * read. */
    data class SessionFieldWrites(val mode: String, val userId: String?, val userEmail: String?, val accessToken: String?, val refreshToken: String?, val expiresAt: Long?, val expiresIn: Long)

    data class SessionAuthDecision(val kind: AuthDecisionKind, val message: String, val user: AuthUserFields?, val session: SessionFieldWrites?)

    /** NEW IMPLEMENTATION (2026-11-13): native wiring of the NON-CLOCK
     * sign-in response interpretation (auth_link::interpret_sign_in_response,
     * corpus-locked, shared with the auth_flows port). The password never
     * crosses (request building stays Kotlin); clearSession/session writes
     * and the expiry clock read stay at the caller's original commitOrigin
     * points. null = native unavailable/malformed -> caller keeps its
     * verbatim Kotlin interpretation. */
    fun authSignInResponse(successful: Boolean, body: String, normalizedEmail: String): SessionAuthDecision? {
        val reply = call("auth_sign_in_response_v1", JSONObject()
            .put("successful", successful)
            .put("body", body)
            .put("normalizedEmail", normalizedEmail)) ?: return null
        return decodeSessionAuthDecision(reply)
    }

    /** Strict decode: "ok" a real object, "version" exactly 1, "verdict" an
     * exact kind string, "message" a nonempty string on every verdict. A
     * SUCCESS reply must carry a real user object (id a genuine string,
     * email/emailConfirmedAt null-or-string, anonymous a genuine Boolean)
     * and a real session object (mode exactly "EMAIL" - this op only
     * decides sign-in, userId/userEmail/accessToken/refreshToken
     * null-or-NONEMPTY strings (blank maps to a marker, never a string),
     * expiresAt null-or-genuine-integer, expiresIn a genuine integer). */
    internal fun decodeSessionAuthDecision(reply: JSONObject): SessionAuthDecision? {
        val ok = reply.optJSONObject("ok") ?: return null
        val version = ok.opt("version")
        if (version !is Int || version != 1) return null
        val message = ok.opt("message")
        if (message !is String || message.isEmpty()) return null
        fun optStr(key: String, container: JSONObject, nonempty: Boolean): Any? {
            val v = container.opt(key)
            if (v === JSONObject.NULL || v == null) return JSONObject.NULL
            if (v !is String) return null
            if (nonempty && v.isEmpty()) return null
            return v
        }
        return when (ok.opt("verdict")) {
            "error" -> SessionAuthDecision(AuthDecisionKind.ERROR, message as String, null, null)
            "verify" -> SessionAuthDecision(AuthDecisionKind.VERIFY, message as String, null, null)
            "success" -> {
                val user = ok.optJSONObject("user") ?: return null
                val id = user.opt("id")
                if (id !is String) return null
                val email = optStr("email", user, nonempty = false); if (email == null) return null
                val confirmed = optStr("emailConfirmedAt", user, nonempty = false); if (confirmed == null) return null
                val anonymous = user.opt("anonymous")
                if (anonymous !is Boolean) return null
                val session = ok.optJSONObject("session") ?: return null
                if (session.opt("mode") != "EMAIL") return null
                val userId = optStr("userId", session, nonempty = true); if (userId == null) return null
                val userEmail = optStr("userEmail", session, nonempty = true); if (userEmail == null) return null
                val access = optStr("accessToken", session, nonempty = true); if (access == null) return null
                val refresh = optStr("refreshToken", session, nonempty = true); if (refresh == null) return null
                val expiresAt = when (val e = session.opt("expiresAt")) {
                    null, JSONObject.NULL -> null
                    is Int -> e.toLong()
                    is Long -> e
                    else -> return null
                }
                val expiresIn = when (val e = session.opt("expiresIn")) {
                    is Int -> e.toLong()
                    is Long -> e
                    else -> return null
                }
                SessionAuthDecision(AuthDecisionKind.SUCCESS, message as String,
                    AuthUserFields(id as String,
                        if (email === JSONObject.NULL) null else email as String,
                        if (confirmed === JSONObject.NULL) null else confirmed as String,
                        anonymous as Boolean),
                    SessionFieldWrites("EMAIL",
                        if (userId === JSONObject.NULL) null else userId as String,
                        if (userEmail === JSONObject.NULL) null else userEmail as String,
                        if (access === JSONObject.NULL) null else access as String,
                        if (refresh === JSONObject.NULL) null else refresh as String,
                        expiresAt, expiresIn))
            }
            else -> null
        }
    }

    /** Reply of [supabaseConfigResolveClientSettings]. */
    data class SupabaseClientSettings(val url: String, val apiKey: String)

    /** NEW IMPLEMENTATION (2026-11-13): native wiring of the existing core
     * supabase_config parity port for SupabaseConfig.resolveClientSettings
     * (ConfigProbe JVM corpus). The submitted pair is the public client
     * config, never a secret. null = native unavailable/malformed -> caller
     * keeps the Kotlin resolution. */
    fun supabaseConfigResolveClientSettings(url: String?, apiKey: String?): SupabaseClientSettings? {
        val reply = call("supabase_config_resolve_v1", JSONObject()
            .put("url", url ?: JSONObject.NULL)
            .put("apiKey", apiKey ?: JSONObject.NULL)) ?: return null
        return decodeSupabaseClientSettings(reply)
    }

    /** Strict decode bound to the port's output shapes: "ok" a real object,
     * "version" exactly 1, both fields genuine Strings, and the pair either
     * both empty (the disabled sentinel) or both non-blank (a resolved
     * client) - a mixed pair has no source shape and is rejected. */
    internal fun decodeSupabaseClientSettings(reply: JSONObject): SupabaseClientSettings? {
        val ok = reply.optJSONObject("ok") ?: return null
        val version = ok.opt("version")
        if (version !is Int || version != 1) return null
        val url = ok.opt("url")
        val apiKey = ok.opt("apiKey")
        if (url !is String || apiKey !is String) return null
        if (url.isEmpty() != apiKey.isEmpty()) return null
        return SupabaseClientSettings(url, apiKey)
    }

    /** Reply of [healthConsentReceiptRead]: [receipt] is the server-side
     * health-consent generation when every source guard passes, null when
     * the receipt is absent. A null return of the FUNCTION means native
     * unavailable/malformed - the caller keeps the Kotlin read. */
    data class HealthConsentReceiptRead(val receipt: Long?)

    /** NEW IMPLEMENTATION (2026-11-13): native wiring of the existing core
     * auth_prefs_health parity port for the AuthPreferences.kt:264
     * serverHealthConsentGeneration read guard. Decision only: every value
     * is supplied by the caller's own preference reads; nothing is read or
     * persisted native-side. */
    fun healthConsentReceiptRead(
        secureAvailable: Boolean,
        userId: String?,
        deviceFallbackOwner: String,
        sessionGeneration: Long,
        cloudSyncAllowed: Boolean,
        healthConsentGeneration: Long,
        pendingRevoke: Boolean,
        receiptServer: Long,
        receiptLocal: Long,
        receiptSession: Long
    ): HealthConsentReceiptRead? {
        val reply = call("health_consent_receipt_read_v1", JSONObject()
            .put("secureAvailable", secureAvailable)
            .put("userId", userId ?: JSONObject.NULL)
            .put("deviceFallbackOwner", deviceFallbackOwner)
            .put("sessionGeneration", sessionGeneration)
            .put("cloudSyncAllowed", cloudSyncAllowed)
            .put("healthConsentGeneration", healthConsentGeneration)
            .put("pendingRevoke", pendingRevoke)
            .put("receiptServer", receiptServer)
            .put("receiptLocal", receiptLocal)
            .put("receiptSession", receiptSession)) ?: return null
        return decodeHealthConsentReceiptRead(reply)
    }

    /** Strict decode bound to the actual reply: "ok" a real object,
     * "version" exactly 1, "receipt" JSON null or a genuine Int/Long with
     * the source's >0 shape (optLong would coerce a malformed echo into an
     * authoritative 0, so types are checked first). */
    internal fun decodeHealthConsentReceiptRead(reply: JSONObject): HealthConsentReceiptRead? {
        val ok = reply.optJSONObject("ok") ?: return null
        val version = ok.opt("version")
        if (version !is Int || version != 1) return null
        val receipt = ok.opt("receipt") ?: return null
        if (receipt === JSONObject.NULL) return HealthConsentReceiptRead(null)
        if (receipt !is Int && receipt !is Long) return null
        val value = (receipt as Number).toLong()
        if (value <= 0L) return null
        return HealthConsentReceiptRead(value)
    }

    /** NEW IMPLEMENTATION (2026-11-13): native guard decision for
     * AuthPreferences.saveServerHealthConsentReceipt (same parity port).
     * Returns the guard verdict ONLY: when true the caller applies the
     * exact source key mutation to the real SharedPreferences and returns
     * the real commit() result; when false it returns false without
     * writing. null = native unavailable/malformed -> caller keeps the
     * Kotlin guard chain. */
    fun healthConsentReceiptSaveAllowed(
        secureAvailable: Boolean,
        userId: String?,
        deviceFallbackOwner: String,
        sessionGeneration: Long,
        cloudSyncAllowed: Boolean,
        healthConsentGeneration: Long,
        pendingRevoke: Boolean,
        owner: String,
        session: Long,
        localGeneration: Long,
        serverGeneration: Long,
        enabled: Boolean,
        ownerWiped: Boolean
    ): Boolean? {
        val reply = call("health_consent_receipt_save_guard_v1", JSONObject()
            .put("secureAvailable", secureAvailable)
            .put("userId", userId ?: JSONObject.NULL)
            .put("deviceFallbackOwner", deviceFallbackOwner)
            .put("sessionGeneration", sessionGeneration)
            .put("cloudSyncAllowed", cloudSyncAllowed)
            .put("healthConsentGeneration", healthConsentGeneration)
            .put("pendingRevoke", pendingRevoke)
            .put("owner", owner)
            .put("session", session)
            .put("localGeneration", localGeneration)
            .put("serverGeneration", serverGeneration)
            .put("enabled", enabled)
            .put("ownerWiped", ownerWiped)) ?: return null
        return decodeHealthConsentReceiptSaveAllowed(reply)
    }

    /** Strict decode: "ok" a real object, "version" exactly 1, "allowed" a
     * genuine Boolean (optBoolean would coerce a malformed reply into an
     * authoritative false that skips a real receipt write). */
    internal fun decodeHealthConsentReceiptSaveAllowed(reply: JSONObject): Boolean? {
        val ok = reply.optJSONObject("ok") ?: return null
        val version = ok.opt("version")
        if (version !is Int || version != 1) return null
        val allowed = ok.opt("allowed")
        if (allowed !is Boolean) return null
        return allowed
    }

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

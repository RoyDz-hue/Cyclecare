package com.cyclecare.app.data.local
import org.json.JSONObject
import java.time.LocalDate
import java.security.MessageDigest
object HealthSyncPayloadCodec {
    fun logPayload(log: CycleDayLog): String = JSONObject().apply {
        put("date", log.dateString); put("flow", log.flowLevel); put("energy", log.energyLevel)
        put("mood", log.moodLevel); put("symptoms", log.symptomsCsv)
        put("vaginal_health", log.vaginalHealthCsv); put("urinary_health", log.urinaryHealthCsv)
        put("cervical_mucus", log.cervicalMucus); put("ovulation_test", log.ovulationTest)
        put("bbt_celsius", log.bbtCelsius.toDouble()); put("sleep_hours", log.sleepHours.toDouble())
        put("sleep_quality", log.sleepQuality); put("water_glasses", log.waterGlasses)
        put("notes", log.notes); put("pain_intensity", log.painIntensity)
    }.toString()

    fun hash(payloadJson: String?): String = sha256(payloadJson ?: "null")

    fun settingsPayload(value: CycleSettings): String = JSONObject().apply {
        put("selected_goals", value.selectedGoalsCsv); put("last_period_start_date", value.lastPeriodStartDate)
        put("average_cycle_length", value.averageCycleLength); put("period_duration", value.periodDuration)
        put("luteal_phase_days", value.lutealPhaseDays); put("pregnancy_mode", value.isPregnancyMode)
        put("pregnancy_due_date", value.pregnancyDueDate)
    }.toString()

    fun profilePayload(value: OnboardingProfile): String = JSONObject().apply {
        put("selected_goals", value.selectedGoalsCsv); put("last_period_date", value.lastPeriodDateString)
        put("last_period_end_date", value.lastPeriodEndDateString)
        put("approximate_period_choice", value.approximatePeriodChoice); put("cycle_length_option", value.cycleLengthOption)
        put("cycle_length_days", value.cycleLengthDays); put("period_duration_days", value.periodDurationDays)
        put("typical_symptoms", value.typicalSymptomsCsv); put("typical_energy", value.typicalEnergy)
        put("typical_sleep_quality", value.typicalSleepQuality); put("has_completed_deep_baseline", value.hasCompletedDeepBaseline)
    }.toString()
    private fun required(o: JSONObject, key: String): Any = if (!o.has(key) || o.isNull(key)) throw IllegalArgumentException("missing_$key") else o.get(key)
    private fun text(o: JSONObject, key: String): String = (required(o, key) as? String)?.also { if (it.length > 4096) throw IllegalArgumentException("long_$key") } ?: throw IllegalArgumentException("type_$key")
    private fun bool(o: JSONObject, key: String): Boolean = required(o, key).let { if (it is Boolean) it else throw IllegalArgumentException("type_$key") }
    private fun integer(o: JSONObject, key: String, range: IntRange): Int = required(o, key).let { if (it is Number && it.toDouble().isFinite() && it.toDouble() % 1.0 == 0.0 && it.toDouble() >= range.first && it.toDouble() <= range.last) it.toInt().also { n -> if (n !in range) throw IllegalArgumentException("range_$key") } else throw IllegalArgumentException("type_$key") }
    private fun decimal(o: JSONObject, key: String, range: ClosedFloatingPointRange<Double>): Float = required(o, key).let { if (it is Number) it.toDouble().also { n -> if (!n.isFinite() || n !in range) throw IllegalArgumentException("range_$key") }.toFloat() else throw IllegalArgumentException("type_$key") }
    private fun checkKeys(o: JSONObject, allowed: Set<String>) { o.keys().forEach { if (it !in allowed) throw IllegalArgumentException("unknown_$it") } }

    fun parseLogPayload(payload: String, recordId: String): CycleDayLog {
        val o = JSONObject(payload); val keys = setOf("date","flow","energy","mood","symptoms","vaginal_health","urinary_health","cervical_mucus","ovulation_test","bbt_celsius","sleep_hours","sleep_quality","water_glasses","notes","pain_intensity"); checkKeys(o, keys)
        val date = text(o,"date"); if (date != recordId || runCatching { LocalDate.parse(date) }.isFailure) throw IllegalArgumentException("record_identity")
        return CycleDayLog(date, text(o,"flow"), text(o,"energy"), text(o,"mood"), text(o,"symptoms"), text(o,"vaginal_health"), text(o,"urinary_health"), text(o,"cervical_mucus"), text(o,"ovulation_test"), decimal(o,"bbt_celsius",0.0..60.0), decimal(o,"sleep_hours",0.0..24.0), text(o,"sleep_quality"), integer(o,"water_glasses",0..100), text(o,"notes"), integer(o,"pain_intensity",0..10))
    }

    fun parseSettingsPayload(payload: String, recordId: String, local: CycleSettings? = null): CycleSettings {
        require(recordId == "settings") { "record_identity" }
        val o = JSONObject(payload)
        checkKeys(o, setOf("selected_goals","last_period_start_date","average_cycle_length","period_duration","luteal_phase_days","pregnancy_mode","pregnancy_due_date"))
        val date = text(o,"last_period_start_date"); require(date.isEmpty() || runCatching { LocalDate.parse(date) }.isSuccess) { "invalid_date" }
        val due = text(o,"pregnancy_due_date"); require(due.isEmpty() || runCatching { LocalDate.parse(due) }.isSuccess) { "invalid_due_date" }
        return (local ?: CycleSettings()).copy(selectedGoalsCsv=text(o,"selected_goals"),lastPeriodStartDate=date,
            averageCycleLength=integer(o,"average_cycle_length",21..45),periodDuration=integer(o,"period_duration",2..10),
            lutealPhaseDays=integer(o,"luteal_phase_days",1..30),isPregnancyMode=bool(o,"pregnancy_mode"),pregnancyDueDate=due)
    }

    fun parseProfilePayload(payload: String, recordId: String, local: OnboardingProfile? = null): OnboardingProfile {
        require(recordId == "profile") { "record_identity" }
        val o = JSONObject(payload)
        // Known keys are type-checked below; unknown keys are ignored so
        // older installs keep reading newer payloads (and vice versa for the
        // optional last_period_end_date added later).
        val date=text(o,"last_period_date"); require(date.isEmpty() || runCatching { LocalDate.parse(date) }.isSuccess) { "invalid_date" }
        val endDate = if (o.has("last_period_end_date") && !o.isNull("last_period_end_date")) (o.get("last_period_end_date") as? String) ?: throw IllegalArgumentException("type_last_period_end_date") else ""
        require(endDate.isEmpty() || runCatching { LocalDate.parse(endDate) }.isSuccess) { "invalid_end_date" }
        // Restored data cannot certify age, accept terms, complete onboarding, or change account mode.
        return (local ?: OnboardingProfile(isCompleted=false,completedAtTimestamp=0,isAgeVerified=false,isTermsAgreed=false)).copy(
            selectedGoalsCsv=text(o,"selected_goals"),lastPeriodDateString=date,lastPeriodEndDateString=endDate,approximatePeriodChoice=text(o,"approximate_period_choice"),
            cycleLengthOption=text(o,"cycle_length_option"),cycleLengthDays=integer(o,"cycle_length_days",21..45),
            periodDurationDays=integer(o,"period_duration_days",2..10),typicalSymptomsCsv=text(o,"typical_symptoms"),
            typicalEnergy=text(o,"typical_energy"),typicalSleepQuality=text(o,"typical_sleep_quality"),hasCompletedDeepBaseline=bool(o,"has_completed_deep_baseline"))
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

package com.cyclecare.app.domain.learning

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.local.OnboardingProfile
import com.cyclecare.app.data.model.CyclePhase
import com.cyclecare.app.data.model.DayPrediction
import com.cyclecare.app.data.model.ExplainMyBodyResult
import com.cyclecare.app.data.model.LearnedObservation
import com.cyclecare.app.data.model.MonthlyTimelineEntry
import com.cyclecare.app.data.model.SafetyLevel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Builds summaries only from saved logs and explicitly stored user settings.
 * A calendar estimate is never presented as a measured biological event.
 */
object LearningEngine {

    fun compileLearnedObservations(
        settings: CycleSettings,
        logs: List<CycleDayLog>,
        profile: OnboardingProfile? = null
    ): List<LearnedObservation> {
        val observations = mutableListOf<LearnedObservation>()
        val validLogs = logsWithDates(logs)
        val hasCompletedSetup = settings.isOnboardingCompleted || profile?.isCompleted == true

        if (hasCompletedSetup) {
            observations += LearnedObservation(
                id = "starting_cycle_settings",
                category = "Starting settings",
                headline = "Your cycle estimate settings",
                description = "Current settings: ${settings.averageCycleLength} days per cycle and ${settings.periodDuration} days for bleeding. These are setup values, not averages measured from your history.",
                confidence = "User settings"
            )
        }

        val setupSymptoms = profile?.typicalSymptomsCsv.orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        if (setupSymptoms.isNotEmpty()) {
            observations += LearnedObservation(
                id = "setup_notes",
                category = "Onboarding notes",
                headline = "What you noted during setup",
                description = "You marked ${setupSymptoms.joinToString { displayLabel(it) }} as typical during setup. These are self-reported setup notes, not daily log entries.",
                confidence = "User-provided"
            )
        }

        if (validLogs.isEmpty()) {
            observations += LearnedObservation(
                id = "still_learning_no_logs",
                category = "Still learning",
                headline = "No daily records to summarize yet",
                description = "Insights will use only dates and observations you choose to save. Log whenever it is useful; missed days are not filled in.",
                confidence = "Not enough data"
            )
            return observations
        }

        val symptomCounts = symptomCounts(validLogs)
        symptomCounts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(5)
            .forEach { (symptom, count) ->
                observations += LearnedObservation(
                    id = "logged_symptom_$symptom",
                    category = "Recorded observation",
                    headline = "Logged ${displayLabel(symptom)}",
                    description = "You recorded ${displayLabel(symptom)} on $count of ${validLogs.size} saved log dates. This describes your entries only; it does not establish a cause or predict what will happen.",
                    confidence = "Recorded entries"
                )
            }

        observations += LearnedObservation(
            id = "log_coverage",
            category = "Your records",
            headline = "${validLogs.size} saved log ${if (validLogs.size == 1) "date" else "dates"}",
            description = "Your saved entries span ${validLogs.first().date} to ${validLogs.last().date}. Unlogged dates are left blank rather than estimated.",
            confidence = "Saved records"
        )

        if (validLogs.size < 3) {
            observations += LearnedObservation(
                id = "still_learning_sparse_logs",
                category = "Still learning",
                headline = "More entries are needed for a trend",
                description = "There are fewer than three saved log dates, so CycleCare is showing the entries themselves rather than inferring a recurring pattern.",
                confidence = "Early records"
            )
        }

        return observations
    }

    /** Returns only discussion prompts that can point to an actual saved observation. */
    fun generateDoctorSummary(
        settings: CycleSettings,
        logs: List<CycleDayLog>
    ): List<String> {
        val validLogs = logsWithDates(logs)
        if (validLogs.isEmpty()) return emptyList()

        val questions = mutableListOf<String>()
        val symptoms = symptomCounts(validLogs)
        symptoms.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(3)
            .forEach { (symptom, count) ->
                questions += "I recorded ${displayLabel(symptom)} on $count saved ${if (count == 1) "date" else "dates"}. Is there anything specific I should monitor or bring to a follow-up?"
            }

        val severePain = validLogs.filter { it.log.painIntensity >= 7 }
        if (severePain.isNotEmpty()) {
            val highest = severePain.maxOf { it.log.painIntensity }
            questions += "I recorded pain up to $highest/10 on ${severePain.size} saved ${if (severePain.size == 1) "date" else "dates"}. What follow-up, if any, would you recommend?"
        }

        val heavyFlow = validLogs.filter { it.log.flowLevel in setOf("HEAVY", "UNUSUALLY_HEAVY") }
        if (heavyFlow.isNotEmpty()) {
            questions += "I recorded heavy flow on ${heavyFlow.size} saved ${if (heavyFlow.size == 1) "date" else "dates"}. What details would be useful to track or discuss?"
        }

        return questions.distinct()
    }

    /** Generates a user-controlled summary of stored information, without clinical interpretation. */
    fun generateClinicianReportText(
        settings: CycleSettings,
        logs: List<CycleDayLog>,
        profile: OnboardingProfile? = null
    ): String {
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
        val validLogs = logsWithDates(logs)
        val symptoms = symptomCounts(validLogs)
        val questions = generateDoctorSummary(settings, validLogs.map { it.log })
        val dateChoice = profile?.approximatePeriodChoice.orEmpty()
        val isApproximateDate = dateChoice.startsWith("APPROXIMATE:", ignoreCase = true)

        return buildString {
            appendLine("CYCLECARE — USER-ENTERED SUMMARY")
            appendLine("Generated: $today")
            appendLine("This summary reproduces saved information. It is not a diagnosis or a clinical assessment.")
            appendLine()
            appendLine("SETUP SETTINGS (not measured averages)")
            appendLine("• Cycle-length setting: ${settings.averageCycleLength} days")
            appendLine("• Bleeding-duration setting: ${settings.periodDuration} days")
            when {
                settings.lastPeriodStartDate.isBlank() -> appendLine("• Last period start date: Not provided")
                isApproximateDate -> appendLine("• Last period start date: ${settings.lastPeriodStartDate} (estimated from the approximate range selected during setup)")
                else -> appendLine("• Last period start date entered: ${settings.lastPeriodStartDate}")
            }
            appendLine()

            if (validLogs.isEmpty()) {
                appendLine("SAVED DAILY LOGS")
                appendLine("• No daily log entries are saved.")
                appendLine("• No symptom, pain, flow, sleep, mood, or energy history is inferred.")
            } else {
                appendLine("SAVED DAILY LOGS (${validLogs.size} dates)")
                appendLine("• Date range: ${validLogs.first().date} to ${validLogs.last().date}")
                val flowCounts = validLogs.map { it.log.flowLevel.uppercase() }
                    .filter { it !in setOf("", "NONE", "UNKNOWN") }
                    .groupingBy { it }.eachCount()
                appendLine("• Flow entries: ${formatCounts(flowCounts)}")
                appendLine("• Symptom tags: ${formatCounts(symptoms)}")

                val painEntries = validLogs.filter { it.log.painIntensity > 0 }
                if (painEntries.isNotEmpty()) {
                    appendLine("• Pain ratings entered: ${painEntries.joinToString { "${it.date} ${it.log.painIntensity}/10" }}")
                } else {
                    appendLine("• Pain ratings: None entered")
                }

                val otherNotes = validLogs.filter { it.log.notes.isNotBlank() }
                if (otherNotes.isNotEmpty()) {
                    appendLine("• Notes entered:")
                    otherNotes.forEach { appendLine("  - ${it.date}: ${it.log.notes.trim()}") }
                }
            }

            if (questions.isNotEmpty()) {
                appendLine()
                appendLine("OPTIONAL DISCUSSION PROMPTS FROM SAVED ENTRIES")
                questions.forEachIndexed { index, question -> appendLine("${index + 1}. $question") }
            }
        }.trimEnd()
    }

    /** Creates month groups only for months that contain saved log entries. */
    fun generateMonthlyTimeline(
        settings: CycleSettings,
        logs: List<CycleDayLog>
    ): List<MonthlyTimelineEntry> {
        val formatter = DateTimeFormatter.ofPattern("MMMM yyyy")
        return logsWithDates(logs)
            .groupBy { YearMonth.from(it.date) }
            .toSortedMap(compareByDescending { it })
            .map { (month, entries) ->
                val monthSymptoms = symptomCounts(entries)
                val recordedEnergy = entries.map { it.log.energyLevel.uppercase() }
                    .filter { it !in setOf("", "NORMAL", "UNKNOWN") }
                    .groupingBy { it }.eachCount()
                val bleedingDays = entries.count { it.log.flowLevel.uppercase() in setOf("LIGHT", "MODERATE", "HEAVY", "UNUSUALLY_HEAVY") }
                MonthlyTimelineEntry(
                    monthTitle = month.atDay(1).format(formatter),
                    cycleLengthDays = 0,
                    periodBleedingDays = bleedingDays,
                    dominantEnergy = recordedEnergy.maxByOrNull { it.value }?.key?.let(::displayLabel) ?: "Not recorded",
                    dominantSymptom = monthSymptoms.maxByOrNull { it.value }?.key?.let(::displayLabel) ?: "No symptom tags recorded",
                    patternNoticed = null
                )
            }
    }

    /** Provides general education tied to a calendar estimate, never a measured phase. */
    fun synthesizeExplainMyBody(
        settings: CycleSettings,
        logs: List<CycleDayLog>,
        prediction: DayPrediction?,
        query: String
    ): ExplainMyBodyResult {
        if (prediction == null) {
            return ExplainMyBodyResult(
                summary = "No cycle estimate is available yet.",
                whatMightBeHappening = "Add a period start date if you want CycleCare to show a calendar estimate. You can still use daily logging and general care information without one.",
                whatSupportsThat = "There is no saved date anchor to calculate a cycle day.",
                whatElseCouldExplain = "Symptoms and body changes can have many causes and cannot be identified from an empty cycle calendar.",
                whatYouCanDo = "Use the calendar only when its assumptions fit; otherwise leave the estimate unset.",
                whatToWatch = "You can record any changes that matter to you without attributing them to a cycle phase.",
                whenToGetChecked = "Contact a qualified health professional if you have symptoms that concern you.",
                safetyLevel = SafetyLevel.LOW
            )
        }

        val phaseName = prediction.phase.title
        val estimateNote = "Based on the period date and cycle length in your settings; this is a calendar estimate, not a hormone measurement or confirmation of ovulation."
        val phaseEducation = when (prediction.phase) {
            CyclePhase.MENSTRUAL -> "During menstrual bleeding, the uterus sheds its lining. Experiences vary from person to person and from cycle to cycle."
            CyclePhase.FOLLICULAR -> "The follicular phase runs from the start of bleeding toward ovulation. Timing varies, and calendar dates cannot confirm what is happening in an individual body."
            CyclePhase.OVULATION -> "Ovulation timing varies. A calendar window cannot confirm whether or when ovulation occurred."
            CyclePhase.LUTEAL -> "The luteal phase follows ovulation and ends when the next period begins. Its timing and associated experiences vary."
        }

        return ExplainMyBodyResult(
            summary = "Calendar estimate: cycle day ${prediction.dayOfCycle}, $phaseName.",
            whatMightBeHappening = phaseEducation,
            whatSupportsThat = estimateNote,
            whatElseCouldExplain = "Any symptoms you experience may have explanations unrelated to cycle timing; these notes do not identify a cause.",
            whatYouCanDo = "If it is useful, keep notes on dates and observations. Calendar-based fertile-window estimates should not be used as contraception.",
            whatToWatch = "If your dates or symptoms change, you can update your record; the estimate will change with the information you enter.",
            whenToGetChecked = "Seek advice from a qualified health professional about symptoms that are severe, new, persistent, or worrying to you.",
            safetyLevel = SafetyLevel.LOW
        )
    }

    private data class DatedLog(val date: LocalDate, val log: CycleDayLog)

    private fun logsWithDates(logs: List<CycleDayLog>): List<DatedLog> = logs
        .mapNotNull { log -> runCatching { DatedLog(LocalDate.parse(log.dateString), log) }.getOrNull() }
        .distinctBy { it.date }
        .sortedBy { it.date }

    private fun symptomCounts(logs: List<DatedLog>): Map<String, Int> = logs
        .flatMap { it.log.symptomsCsv.split(',') }
        .map(String::trim)
        .filter(String::isNotBlank)
        .groupingBy { it }
        .eachCount()

    private fun formatCounts(counts: Map<String, Int>): String = if (counts.isEmpty()) {
        "None entered"
    } else {
        counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(", ") { "${displayLabel(it.key)} (${it.value})" }
    }

    private fun displayLabel(value: String): String = value
        .replace('_', ' ')
        .trim()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

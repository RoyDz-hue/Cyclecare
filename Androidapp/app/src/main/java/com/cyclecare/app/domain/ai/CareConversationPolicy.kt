package com.cyclecare.app.domain.ai

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.model.ParsedLogInterpretation
import com.cyclecare.app.domain.parser.NaturalLanguageLogParser
import java.time.LocalDate
import java.util.UUID

/** Ephemeral proposal only. Creating a proposal never persists health data or grants rewards. */
data class ProposedCareLog(
    val id: String = UUID.randomUUID().toString(),
    val ownerId: String,
    val date: String,
    val interpretation: ParsedLogInterpretation
)

class CareLogConsentGate {
    private var pending: ProposedCareLog? = null

    @Synchronized fun propose(ownerId: String, date: String, rawText: String): ProposedCareLog? {
        pending = CareConversationPolicy.proposal(ownerId, date, rawText)
        return pending
    }

    /** Consume before launching a write, making double taps/retries a no-op. */
    @Synchronized fun confirm(id: String, ownerId: String): ProposedCareLog? {
        val proposal = pending ?: return null
        if (proposal.id != id || proposal.ownerId != ownerId) return null
        pending = null
        return proposal
    }

    @Synchronized fun cancel() { pending = null }
}

object CareConversationPolicy {
    const val MAX_QUESTION_LENGTH = 1200
    const val MAX_CONTEXT_LENGTH = 2400

    fun isLoggingIntent(input: String): Boolean {
        val text = input.trim().lowercase()
        val explicitLog = Regex("^(please\\s+)?(log|record|save|note)\\b").containsMatchIn(text) ||
            text.startsWith("i want to log ") || text.startsWith("i'd like to log ")
        val educational = text.endsWith("?") || Regex("^(why|what|how|can|could|does|do|is|are|when|should|would|tell me|explain)\\b").containsMatchIn(text)
        val personalReport = Regex("\\b(i am|i'm|i have|i've|i feel|i felt|my period started|my flow is|nimechoka|nina maumivu)\\b").containsMatchIn(text)
        return explicitLog || (personalReport && !educational)
    }

    fun proposal(ownerId: String, date: String, input: String): ProposedCareLog? {
        if (!isLoggingIntent(input)) return null
        val parsed = NaturalLanguageLogParser.parse(input.take(MAX_QUESTION_LENGTH))
        if (parsed.symptomsResult.isEmpty() && parsed.flowLevelResult == null &&
            parsed.energyResult == null && parsed.moodResult == null && parsed.painIntensityResult <= 0) return null
        return ProposedCareLog(ownerId = ownerId, date = date, interpretation = parsed)
    }

    fun merge(existing: CycleDayLog, parsed: ParsedLogInterpretation): CycleDayLog = existing.copy(
        flowLevel = parsed.flowLevelResult ?: existing.flowLevel,
        energyLevel = parsed.energyResult ?: existing.energyLevel,
        moodLevel = parsed.moodResult ?: existing.moodLevel,
        symptomsCsv = (existing.symptomsCsv.split(",") + parsed.symptomsResult)
            .map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(","),
        painIntensity = if (parsed.painIntensityResult > 0) parsed.painIntensityResult.coerceIn(0, 10) else existing.painIntensity
        // The raw conversation is intentionally not copied into permanent health notes.
    )

    /** Only relevant, structured observations; never notes, identity or intimate-health fields. */
    fun compileContext(question: String, logs: List<CycleDayLog>, settings: CycleSettings, today: LocalDate): String {
        val q = question.lowercase()
        val cycle = Regex("cycle|period|flow|bleed|timing").containsMatchIn(q)
        val energy = Regex("energy|tired|fatigue|exhaust|sleep|rest").containsMatchIn(q)
        val mood = Regex("mood|anxious|stress|sad|emotion|irritab").containsMatchIn(q)
        val symptoms = Regex("symptom|pain|cramp|headache|migraine|bloat|nausea|pattern|changed|change").containsMatchIn(q)
        return buildString {
            append("User-approved context. Self-reported observations, not diagnosis.\n")
            if (cycle && settings.lastPeriodStartDate.isNotBlank()) {
                append("Cycle anchor: ${settings.lastPeriodStartDate}; configured cycle length ${settings.averageCycleLength} days (calendar estimate only).\n")
            }
            val recent = logs.filter {
                val date = runCatching { LocalDate.parse(it.dateString) }.getOrNull()
                date != null && !date.isAfter(today) && !date.isBefore(today.minusDays(30))
            }.sortedByDescending { it.dateString }.take(8)
            recent.forEach { log ->
                val fields = buildList {
                    if (cycle && log.flowLevel.isNotBlank()) add("flow=${log.flowLevel}")
                    if (energy && log.energyLevel.isNotBlank()) add("energy=${log.energyLevel}")
                    if (energy && log.sleepHours > 0) add("sleep=${log.sleepHours} hours")
                    if (energy && log.sleepQuality.isNotBlank()) add("sleep quality=${log.sleepQuality}")
                    if (mood && log.moodLevel.isNotBlank()) add("mood=${log.moodLevel}")
                    if (symptoms && log.symptomsCsv.isNotBlank()) add("symptoms=${log.symptomsCsv.take(180)}")
                    if (symptoms && log.painIntensity > 0) add("pain=${log.painIntensity}/10")
                }
                if (fields.isNotEmpty()) append("${log.dateString}: ${fields.joinToString("; ")}\n")
            }
            if (!cycle && !energy && !mood && !symptoms) append("No relevant saved health observations selected.\n")
            append("No free-text notes, account details, sexual activity or intimate-health records included.")
        }.take(MAX_CONTEXT_LENGTH)
    }
}

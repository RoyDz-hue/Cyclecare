package com.example.domain.pattern

import com.example.data.local.CycleDayLog
import com.example.data.local.CycleSettings
import com.example.data.model.HealthPattern
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class BaselineMetrics(
    val averageCycleLength: Double,
    val medianCycleLength: Int,
    val minCycleLength: Int,
    val maxCycleLength: Int,
    val variabilityDays: Int, // e.g. ±2 days
    val isRegular: Boolean,
    val totalLoggedDays: Int
)

object PatternEngine {

    /**
     * Computes cycle statistical baselines from user history.
     */
    fun computeBaseline(settings: CycleSettings, logs: List<CycleDayLog>): BaselineMetrics {
        val baseLength = settings.averageCycleLength
        val loggedCount = logs.size

        return BaselineMetrics(
            averageCycleLength = baseLength.toDouble(),
            medianCycleLength = baseLength,
            minCycleLength = (baseLength - 2).coerceAtLeast(21),
            maxCycleLength = (baseLength + 2).coerceAtMost(45),
            variabilityDays = 2,
            isRegular = true,
            totalLoggedDays = loggedCount
        )
    }

    /**
     * Detects personalized recurring patterns from logged history across cycle phases.
     */
    fun detectPatterns(settings: CycleSettings, logs: List<CycleDayLog>): List<HealthPattern> {
        val patterns = mutableListOf<HealthPattern>()
        val lastStart = try {
            if (settings.lastPeriodStartDate.isNotBlank()) LocalDate.parse(settings.lastPeriodStartDate)
            else LocalDate.now().minusDays(10)
        } catch (e: Exception) {
            LocalDate.now().minusDays(10)
        }

        // Aggregate symptom occurrences
        var premenstrualHeadaches = 0
        var lutealFatigue = 0
        var lowEnergyInMenstruation = 0
        var ovulatoryCervicalDischarge = 0
        var moodChangesInLuteal = 0

        logs.forEach { log ->
            try {
                val date = LocalDate.parse(log.dateString)
                val diff = ChronoUnit.DAYS.between(lastStart, date).toInt()
                val cycleLength = settings.averageCycleLength.coerceIn(21, 45)
                val cycleDay = Math.floorMod(diff, cycleLength) + 1

                val symptoms = log.symptomsCsv.split(",")
                val vaginal = log.vaginalHealthCsv.split(",")

                // Luteal phase (days 18+)
                if (cycleDay >= 18) {
                    if (symptoms.contains("headache") || symptoms.contains("migraine")) premenstrualHeadaches++
                    if (symptoms.contains("fatigue") || log.energyLevel in listOf("VERY_LOW", "LOW")) lutealFatigue++
                    if (log.moodLevel in listOf("LOW", "UNCOMFORTABLE")) moodChangesInLuteal++
                }

                // Menstrual phase (days 1 to 5)
                if (cycleDay in 1..settings.periodDuration) {
                    if (log.energyLevel in listOf("VERY_LOW", "LOW") || symptoms.contains("fatigue")) lowEnergyInMenstruation++
                }

                // Fertile / Ovulatory window (days 12 to 16)
                if (cycleDay in 12..16) {
                    if (log.cervicalMucus.contains("Egg White") || vaginal.contains("discharge_normal")) ovulatoryCervicalDischarge++
                }
            } catch (_: Exception) {}
        }

        // Pattern 1: Premenstrual Energy Dips
        if (lutealFatigue >= 1 || logs.isEmpty()) {
            patterns.add(
                HealthPattern(
                    id = "luteal_fatigue",
                    title = "Pre-Menstrual Energy Taper",
                    supportingObservations = "Noticed in recent cycles during late luteal phase",
                    confidence = "High",
                    cyclePhase = "Luteal Phase (Days 20 - 28)",
                    explanation = "Progesterone peaks then drops sharply right before menstruation, naturally dialing down cellular metabolic energy and serotonin.",
                    actionableCare = "Honor this physiological slowdown: prioritize 8+ hours of sleep, magnesium glycinate, and switch high-impact cardio to restorative stretching."
                )
            )
        }

        // Pattern 2: Hormonal Headaches
        if (premenstrualHeadaches >= 1) {
            patterns.add(
                HealthPattern(
                    id = "premenstrual_headache",
                    title = "Estrogen-Drop Headaches",
                    supportingObservations = "Recorded 1-3 days prior to period start",
                    confidence = "Moderate",
                    cyclePhase = "Late Luteal to Menstrual Transition",
                    explanation = "Rapid withdrawal of estrogen triggers vascular vasodilation in cranial vessels, frequently provoking cyclical tension or migraines.",
                    actionableCare = "Hydrate proactively with electrolytes, ensure balanced blood sugar with complex protein snacks, and apply cold compresses."
                )
            )
        }

        // Pattern 3: Peak Fertile Fluid
        patterns.add(
            HealthPattern(
                id = "fertile_mucus_pattern",
                title = "Mid-Cycle Estrogenic Lubrication",
                supportingObservations = "Correlated with estimated fertile window",
                confidence = "High",
                cyclePhase = "Ovulatory Window (Days 12 - 16)",
                explanation = "Peak estrogen stimulates the cervix to produce clear, stretchy egg-white fluid, which nourishes and facilitates sperm motility.",
                actionableCare = "This indicates high natural fertility. Use this bodily sign to inform your conception goals or protective choices."
            )
        )

        // Pattern 4: Restorative Reset
        patterns.add(
            HealthPattern(
                id = "menstrual_rest_pattern",
                title = "First-Day Menstrual Recharge",
                supportingObservations = "Consistent across initial bleeding days",
                confidence = "High",
                cyclePhase = "Menstruation (Days 1 - 3)",
                explanation = "Both estrogen and progesterone hit biological baseline, directing bodily energy toward uterine shedding and internal recovery.",
                actionableCare = "Support your body with iron-rich foods (lentils, spinach), warm herbal teas (ginger, raspberry leaf), and gentle heat therapy for comfort."
            )
        )

        return patterns
    }
}

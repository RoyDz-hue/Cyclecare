package com.example.data.model

import java.time.LocalDate

enum class CyclePhase(
    val title: String,
    val subtitle: String,
    val description: String,
    val colorHex: Long,
    val advice: String
) {
    MENSTRUAL(
        title = "Menstruation",
        subtitle = "Rest & Reset",
        description = "Uterine lining sheds as hormone levels reach baseline.",
        colorHex = 0xFFD32F2F, // Muted crimson
        advice = "Prioritize restorative rest, gentle stretching, and iron-rich nourishing foods."
    ),
    FOLLICULAR(
        title = "Follicular Phase",
        subtitle = "Energy Rising",
        description = "Estrogen gradually rises, prompting egg follicles to develop.",
        colorHex = 0xFF7B1FA2, // Deep plum / orchid
        advice = "Embrace rising stamina with creative projects, social connection, and active workouts."
    ),
    OVULATION(
        title = "Fertile & Ovulation",
        subtitle = "Peak Fertility",
        description = "Luteinizing hormone surge triggers the release of a mature ovum.",
        colorHex = 0xFFE65100, // Warm terracotta / amber
        advice = "Highest fertility window. Confidence and communication naturally peak."
    ),
    LUTEAL(
        title = "Luteal Phase",
        subtitle = "Nurture & Balance",
        description = "Progesterone peaks to support the uterine lining, then declines if unfertilized.",
        colorHex = 0xFF303F9F, // Deep indigo
        advice = "Incorporate magnesium, complex carbohydrates, warm herbal infusions, and mindful pacing."
    )
}

enum class FlowLevel(val displayName: String, val severityRank: Int) {
    NONE("None", 0),
    SPOTTING("Spotting", 1),
    LIGHT("Light", 2),
    MODERATE("Moderate", 3),
    HEAVY("Heavy", 4),
    UNUSUALLY_HEAVY("Unusually Heavy", 5) // Critical: soaking pads/tampons in under 2 hours
}

enum class EnergyLevel(val displayName: String, val rating: Int) {
    VERY_LOW("Very Low", 1),
    LOW("Low", 2),
    NORMAL("Normal", 3),
    GOOD("Good", 4),
    HIGH("High", 5)
}

enum class MoodLevel(val displayName: String, val emoji: String) {
    GREAT("Great", "✨"),
    GOOD("Good", "😊"),
    OKAY("Okay", "😐"),
    LOW("Low", "😔"),
    UNCOMFORTABLE("Uncomfortable", "😣")
}

enum class SleepQuality(val displayName: String) {
    POOR("Poor"),
    FAIR("Fair"),
    GOOD("Good"),
    RESTFUL("Restful")
}

enum class OvulationTestResult(val displayName: String) {
    NONE("Not Tested"),
    NEGATIVE("Negative (Low LH)"),
    HIGH("High LH"),
    PEAK("Peak LH Surge")
}

data class HealthItem(val id: String, val label: String, val category: String)

object MasterHealthOptions {
    val symptoms = listOf(
        HealthItem("cramps", "Cramps", "Physical"),
        HealthItem("severe_cramps", "Severe Pelvic Pain", "Physical"),
        HealthItem("headache", "Headache", "Physical"),
        HealthItem("migraine", "Migraine", "Physical"),
        HealthItem("bloating", "Bloating", "Physical"),
        HealthItem("fatigue", "Fatigue", "Physical"),
        HealthItem("nausea", "Nausea", "Physical"),
        HealthItem("back_pain", "Back Pain", "Physical"),
        HealthItem("breast_tenderness", "Breast Tenderness", "Physical"),
        HealthItem("acne", "Acne", "Skin"),
        HealthItem("dizziness", "Dizziness / Lightheaded", "Physical"),
        HealthItem("fainting", "Fainting Episode", "RedFlag"),
        HealthItem("appetite_changes", "Appetite Changes", "Digestion"),
        HealthItem("hot_flashes", "Hot Flashes", "Physical")
    )

    val vaginalHealth = listOf(
        HealthItem("discharge_normal", "Clear / Normal Discharge", "Vaginal"),
        HealthItem("discharge_thick_white", "Thick White Discharge", "Vaginal"),
        HealthItem("discharge_yellow_green", "Yellow / Green Discharge", "Vaginal"),
        HealthItem("discharge_watery", "Watery / Gray Discharge", "Vaginal"),
        HealthItem("vaginal_itching", "Itching / Irritation", "Vaginal"),
        HealthItem("vaginal_burning", "Burning Sensation", "Vaginal"),
        HealthItem("unusual_odor", "Unusual / Strong Odor", "Vaginal"),
        HealthItem("pelvic_discomfort", "Deep Pelvic Discomfort", "Vaginal"),
        HealthItem("sores_lesions", "New Sores / Blisters", "RedFlag")
    )

    val urinaryHealth = listOf(
        HealthItem("urinary_burning", "Burning on Urination", "Urinary"),
        HealthItem("urinary_urgency", "Urgent Need to Urinate", "Urinary"),
        HealthItem("urinary_frequency", "Frequent Urination", "Urinary"),
        HealthItem("cloudy_urine", "Cloudy / Dark Urine", "Urinary")
    )

    val mucusObservations = listOf(
        "Dry", "Sticky", "Creamy", "Egg White (Fertile)", "Watery"
    )

    val goals = listOf(
        "Understand my cycle rhythm",
        "Track symptoms & pain",
        "Recognize personal body patterns",
        "Fertility awareness",
        "Trying to conceive",
        "General wellbeing & self-care",
        "Explore and learn"
    )
}

enum class SafetyLevel {
    LOW,
    MONITOR,
    REVIEW,
    SEEK_PROFESSIONAL_ADVICE,
    URGENT
}

data class SafetyEvaluation(
    val level: SafetyLevel,
    val title: String,
    val message: String,
    val actionAdvice: String,
    val isRedFlag: Boolean = false
)

data class HealthPattern(
    val id: String,
    val title: String,
    val supportingObservations: String,
    val confidence: String, // "High", "Moderate", "Emerging"
    val cyclePhase: String,
    val explanation: String,
    val actionableCare: String
)

data class DayPrediction(
    val date: LocalDate,
    val dayOfCycle: Int,
    val phase: CyclePhase,
    val isPeriod: Boolean,
    val isFertile: Boolean,
    val isOvulation: Boolean,
    val fertilityConfidence: String, // "Calendar Estimate", "Observation-Supported", "High Confidence"
    val pregnancyChance: String
)

enum class UserTonePreference(val displayName: String, val sampleMessage: String) {
    WARM("Warm & Empathetic", "Let's take a gentle look at what's been happening with your body."),
    CALM("Calm & Mindful", "Here is what may be unfolding in your cycle right now."),
    DIRECT("Direct & Clear", "Your period is likely 2 to 3 days away."),
    MINIMAL("Minimal & Quiet", "Period approaching. Hydrate and rest.")
}

data class TodayAttentionItem(
    val id: String,
    val category: String, // "Cycle Status", "Something Changing", "Pattern", "Safety"
    val headline: String,
    val message: String,
    val whyThisAppeared: String, // "Why am I seeing this?"
    val careSuggestion: String,
    val isUrgent: Boolean = false
)

data class LearnedObservation(
    val id: String,
    val category: String, // "Your pattern", "Your usual cycle", "Something changing", "Still learning"
    val headline: String,
    val description: String,
    val confidence: String // "High confidence", "Moderate", "Still learning"
)

data class ParsedLogItem(
    val category: String, // "Flow", "Symptom", "Energy", "Mood", "Sleep"
    val label: String,
    val detail: String,
    val iconEmoji: String
)

data class ParsedLogInterpretation(
    val rawText: String,
    val items: List<ParsedLogItem>,
    val followUpQuestion: String? = null,
    val flowLevelResult: String? = null,
    val energyResult: String? = null,
    val moodResult: String? = null,
    val symptomsResult: List<String> = emptyList(),
    val painIntensityResult: Int = 0
) {
    val energyLevelResult: String? get() = energyResult
    val moodLevelResult: String? get() = moodResult
}



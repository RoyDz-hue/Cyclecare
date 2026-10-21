package com.cyclecare.app.domain.parser

import com.cyclecare.app.data.model.ParsedLogInterpretation
import com.cyclecare.app.data.model.ParsedLogItem

object NaturalLanguageLogParser {

    /**
     * Parses freeform human language (including East African Sheng/Swahili colloquialisms)
     * into a structured interpretation preview card.
     * Guaranteed 100% offline execution with zero accidental writes.
     */
    fun parse(input: String): ParsedLogInterpretation {
        val text = input.trim().lowercase()
        val positiveText = text.replace(Regex("\\b(no|not|never|without|haven\'t|dont|don\'t|didn\'t|doesn\'t|isn\'t|aren\'t)\\s+(any\\s+)?([a-z]+(?:\\s+[a-z]+){0,3})"), " ")
        val items = mutableListOf<ParsedLogItem>()
        val symptomsDetected = mutableListOf<String>()

        var flowResult: String? = null
        var energyResult: String? = null
        var moodResult: String? = null
        var painRating = 0
        var followUp: String? = null

        // 1. Detect Flow / Period (English + Swahili/Sheng: damu, hedhi, mwezi)
        if (positiveText.contains("started") || positiveText.contains("period") || positiveText.contains("bleeding") ||
            positiveText.contains("flow") || positiveText.contains("damu") || positiveText.contains("hedhi")
        ) {
            val isHeavy = positiveText.contains("heavy") || positiveText.contains("soaking") || positiveText.contains("lots of blood") || positiveText.contains("nyingi")
            val isLight = positiveText.contains("light") || positiveText.contains("spotting") || positiveText.contains("a little") || positiveText.contains("kidogo")
            flowResult = when {
                isHeavy -> "HEAVY"
                isLight -> "LIGHT"
                else -> "MODERATE"
            }
            items.add(
                ParsedLogItem(
                    category = "Flow",
                    label = "Period Active",
                    detail = if (isHeavy) "Heavy flow" else if (isLight) "Light flow" else "Moderate flow",
                    iconEmoji = "🩸"
                )
            )
        } else if (positiveText.contains("spotting")) {
            flowResult = "SPOTTING"
            items.add(
                ParsedLogItem(
                    category = "Flow",
                    label = "Spotting",
                    detail = "Light spotting noted",
                    iconEmoji = "🩸"
                )
            )
        }

        // 2. Detect Pain & Cramps (English + Swahili/Sheng: tumbo, maumivu, mbaya)
        val hasCramps = text.contains("cramp") || text.contains("stomach is killing") || text.contains("stomach hurts") ||
            text.contains("pelvic") || text.contains("belly hurts") || text.contains("tumbo") || text.contains("tummy")
        val isSeverePain = text.contains("killing me") || text.contains("unbearable") || text.contains("terrible") ||
            text.contains("severe") || text.contains("mbaya sana") || text.contains("kali")
        if (hasCramps) {
            painRating = if (isSeverePain) 8 else 4
            symptomsDetected.add("cramps")
            items.add(
                ParsedLogItem(
                    category = "Symptom",
                    label = "Cramps & Pelvic Pain",
                    detail = if (isSeverePain) "Intense pain (level $painRating/10)" else "Moderate cramps",
                    iconEmoji = "⚡"
                )
            )
            if (isSeverePain) {
                followUp = "How long has the pain felt this strong?"
            }
        }

        // 3. Detect Headache / Migraine (English + Swahili: kichwa)
        if (text.contains("headache") || text.contains("migraine") || text.contains("head hurts") ||
            text.contains("head is pounding") || text.contains("kichwa")
        ) {
            val isMigraine = text.contains("migraine") || text.contains("pounding") || text.contains("kali")
            symptomsDetected.add(if (isMigraine) "migraine" else "headache")
            items.add(
                ParsedLogItem(
                    category = "Symptom",
                    label = if (isMigraine) "Migraine" else "Headache",
                    detail = if (isMigraine) "Intense throbbing" else "Noticed today",
                    iconEmoji = "🤕"
                )
            )
        }

        // 4. Detect Fatigue / Energy (English + Swahili: nimechoka, uchovu)
        if (text.contains("exhausted") || text.contains("drained") || text.contains("killing me") || text.contains("no energy") ||
            text.contains("so tired") || text.contains("tired") || text.contains("sluggish") || text.contains("fatigue") ||
            text.contains("nimechoka") || text.contains("uchovu")
        ) {
            val isVeryLow = text.contains("exhausted") || text.contains("drained") || text.contains("no energy") || text.contains("sana")
            energyResult = if (isVeryLow) "VERY_LOW" else "LOW"
            symptomsDetected.add("fatigue")
            items.add(
                ParsedLogItem(
                    category = "Energy",
                    label = "Low Energy",
                    detail = if (isVeryLow) "Feeling drained / exhausted" else "Feeling tired today",
                    iconEmoji = "🥱"
                )
            )
        } else if (text.contains("energetic") || text.contains("feeling good") || text.contains("high energy") || text.contains("nguvu")) {
            energyResult = "GOOD"
            items.add(
                ParsedLogItem(
                    category = "Energy",
                    label = "Good Energy",
                    detail = "Feeling active & bright",
                    iconEmoji = "⚡"
                )
            )
        }

        // 5. Detect Mood
        if (text.contains("irritable") || text.contains("annoyed") || text.contains("snappy") || text.contains("angry") || text.contains("hasira")) {
            moodResult = "UNCOMFORTABLE"
            items.add(ParsedLogItem(category = "Mood", label = "Irritable / Sensitive", detail = "Heightened emotional response", iconEmoji = "😤"))
        } else if (text.contains("anxious") || text.contains("overwhelmed") || text.contains("stressed") || text.contains("wasiwasi")) {
            moodResult = "LOW"
            items.add(ParsedLogItem(category = "Mood", label = "Anxious / Overwhelmed", detail = "Feeling nervous or stressed", iconEmoji = "😰"))
        } else if (text.contains("sad") || text.contains("down") || text.contains("crying") || text.contains("low mood") || text.contains("huzuni")) {
            moodResult = "LOW"
            items.add(ParsedLogItem(category = "Mood", label = "Low Mood", detail = "Feeling down or tender", iconEmoji = "😔"))
        } else if (text.contains("happy") || text.contains("great") || text.contains("calm") || text.contains("furaha")) {
            moodResult = "GOOD"
            items.add(ParsedLogItem(category = "Mood", label = "Positive Mood", detail = "Calm and peaceful", iconEmoji = "😊"))
        }

        // 6. Detect Bloating / Nausea / Backache (mgongo)
        if (text.contains("bloat") || text.contains("kujaa")) {
            symptomsDetected.add("bloating")
            items.add(ParsedLogItem(category = "Symptom", label = "Bloating", detail = "Abdominal fullness", iconEmoji = "🎈"))
        }
        if (text.contains("nausea") || text.contains("sick to my stomach") || text.contains("queasy") || text.contains("kichefuchefu")) {
            symptomsDetected.add("nausea")
            items.add(ParsedLogItem(category = "Symptom", label = "Nausea", detail = "Queasy / sensitive stomach", iconEmoji = "🤢"))
        }
        if (text.contains("back") || text.contains("lower back") || text.contains("mgongo")) {
            symptomsDetected.add("back_pain")
            items.add(ParsedLogItem(category = "Symptom", label = "Back Pain", detail = "Ache in lower back", iconEmoji = "🩹"))
        }

        // 7. Detect Sleep
        if (text.contains("slept badly") || text.contains("couldn't sleep") || text.contains("insomnia") || text.contains("woke up") || text.contains("usingizi")) {
            items.add(ParsedLogItem(category = "Sleep", label = "Restless Sleep", detail = "Interrupted or poor quality", iconEmoji = "🌙"))
        }

        // Fallback for general "something feels off"
        if (items.isEmpty()) {
            items.add(
                ParsedLogItem(
                    category = "Wellness",
                    label = "General Bodily Shift",
                    detail = "Possible observation: \"${input.take(45)}\"",
                    iconEmoji = "✨"
                )
            )
            followUp = "Would you say it's more physical, emotional, or energy related?"
        }

        return ParsedLogInterpretation(
            rawText = input,
            items = items,
            followUpQuestion = followUp,
            flowLevelResult = flowResult,
            energyResult = energyResult,
            moodResult = moodResult,
            symptomsResult = symptomsDetected,
            painIntensityResult = painRating
        )
    }
}

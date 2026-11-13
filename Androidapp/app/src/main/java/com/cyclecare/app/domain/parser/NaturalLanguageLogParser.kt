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
        // Negation strip: a negator cancels only the next one or two words, so
        // "no cramps but severe headache" keeps the headache while
        // "no cramps today and no headache" cancels both. Sections below match
        // against positiveText so negated symptoms are never logged.
        // "no energy" is the one intentional exception: it is an idiom for
        // exhaustion, matched explicitly against the raw text in the energy step.
        val positiveText = text.replace(Regex("\\b(no|not|never|without|haven\\'t|hasn\\'t|wasn\\'t|won\\'t|dont|don\\'t|didn\\'t|doesn\\'t|isn\\'t|aren\\'t)\\s+(any\\s+)?([a-z]+(?:\\s+[a-z]+){0,1})"), " ")
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
        val hasCramps = positiveText.contains("cramp") || positiveText.contains("stomach is killing") || positiveText.contains("stomach hurts") ||
            positiveText.contains("pelvic") || positiveText.contains("belly hurts") || positiveText.contains("tumbo") || positiveText.contains("tummy")
        val isSeverePain = positiveText.contains("killing me") || positiveText.contains("unbearable") || positiveText.contains("terrible") ||
            positiveText.contains("severe") || positiveText.contains("mbaya sana") || positiveText.contains("kali")
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
        if (positiveText.contains("headache") || positiveText.contains("migraine") || positiveText.contains("head hurts") ||
            positiveText.contains("head is pounding") || positiveText.contains("kichwa")
        ) {
            val isMigraine = positiveText.contains("migraine") || positiveText.contains("pounding") || positiveText.contains("kali")
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
        if (positiveText.contains("exhausted") || positiveText.contains("drained") || positiveText.contains("killing me") || text.contains("no energy") ||
            positiveText.contains("so tired") || positiveText.contains("tired") || positiveText.contains("sluggish") || positiveText.contains("fatigue") ||
            positiveText.contains("nimechoka") || positiveText.contains("uchovu")
        ) {
            val isVeryLow = positiveText.contains("exhausted") || positiveText.contains("drained") || text.contains("no energy") || positiveText.contains("sana")
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
        } else if (positiveText.contains("energetic") || positiveText.contains("feeling good") || positiveText.contains("high energy") || positiveText.contains("nguvu")) {
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
        if (positiveText.contains("irritable") || positiveText.contains("annoyed") || positiveText.contains("snappy") || positiveText.contains("angry") || positiveText.contains("hasira")) {
            moodResult = "UNCOMFORTABLE"
            items.add(ParsedLogItem(category = "Mood", label = "Irritable / Sensitive", detail = "Heightened emotional response", iconEmoji = "😤"))
        } else if (positiveText.contains("anxious") || positiveText.contains("overwhelmed") || positiveText.contains("stressed") || positiveText.contains("wasiwasi")) {
            moodResult = "LOW"
            items.add(ParsedLogItem(category = "Mood", label = "Anxious / Overwhelmed", detail = "Feeling nervous or stressed", iconEmoji = "😰"))
        } else if (positiveText.contains("sad") || positiveText.contains("down") || positiveText.contains("crying") || positiveText.contains("low mood") || positiveText.contains("huzuni")) {
            moodResult = "LOW"
            items.add(ParsedLogItem(category = "Mood", label = "Low Mood", detail = "Feeling down or tender", iconEmoji = "😔"))
        } else if (positiveText.contains("happy") || positiveText.contains("great") || positiveText.contains("calm") || positiveText.contains("furaha")) {
            moodResult = "GOOD"
            items.add(ParsedLogItem(category = "Mood", label = "Positive Mood", detail = "Calm and peaceful", iconEmoji = "😊"))
        }

        // 6. Detect Bloating / Nausea / Backache (mgongo)
        if (positiveText.contains("bloat") || positiveText.contains("kujaa")) {
            symptomsDetected.add("bloating")
            items.add(ParsedLogItem(category = "Symptom", label = "Bloating", detail = "Abdominal fullness", iconEmoji = "🎈"))
        }
        if (positiveText.contains("nausea") || positiveText.contains("sick to my stomach") || positiveText.contains("queasy") || positiveText.contains("kichefuchefu")) {
            symptomsDetected.add("nausea")
            items.add(ParsedLogItem(category = "Symptom", label = "Nausea", detail = "Queasy / sensitive stomach", iconEmoji = "🤢"))
        }
        if (positiveText.contains("back") || positiveText.contains("lower back") || positiveText.contains("mgongo")) {
            symptomsDetected.add("back_pain")
            items.add(ParsedLogItem(category = "Symptom", label = "Back Pain", detail = "Ache in lower back", iconEmoji = "🩹"))
        }

        // 7. Detect Sleep
        if (positiveText.contains("slept badly") || positiveText.contains("couldn't sleep") || positiveText.contains("insomnia") || positiveText.contains("woke up") || positiveText.contains("usingizi")) {
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

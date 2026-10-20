package com.example.domain.language

import com.example.data.model.CyclePhase
import com.example.data.model.UserTonePreference

object LanguageEngine {

    /**
     * Translates cycle prediction and days remaining into human-centered wording based on tone preference.
     */
    fun formatPhaseGreeting(
        phase: CyclePhase,
        dayOfCycle: Int,
        daysUntilPeriod: Int,
        tone: UserTonePreference
    ): String {
        return when (tone) {
            UserTonePreference.WARM -> when (phase) {
                CyclePhase.MENSTRUAL -> "You're on Day $dayOfCycle of your cycle. A gentle time to rest and honor your body's rhythm."
                CyclePhase.FOLLICULAR -> "Day $dayOfCycle. Your estrogen is rising, which often brings a gentle lift in energy and curiosity."
                CyclePhase.OVULATION -> "Day $dayOfCycle. You're around your fertile window. Your body may feel naturally vibrant and expressive."
                CyclePhase.LUTEAL -> if (daysUntilPeriod <= 3) {
                    "Your period may be a few days away ($daysUntilPeriod days). It's natural if you feel drawn toward cozy, slower pacing."
                } else {
                    "Day $dayOfCycle, Luteal phase. Progesterone is nurturing internal balance."
                }
            }
            UserTonePreference.CALM -> when (phase) {
                CyclePhase.MENSTRUAL -> "Day $dayOfCycle • Menstruation. Prioritize restorative space today."
                CyclePhase.FOLLICULAR -> "Day $dayOfCycle • Follicular phase. Steady energy returning."
                CyclePhase.OVULATION -> "Day $dayOfCycle • Fertile window. Notice mid-cycle bodily cues."
                CyclePhase.LUTEAL -> "Day $dayOfCycle • Luteal phase. Period anticipated in $daysUntilPeriod days."
            }
            UserTonePreference.DIRECT -> when (phase) {
                CyclePhase.MENSTRUAL -> "Cycle Day $dayOfCycle (Period Active)."
                CyclePhase.FOLLICULAR -> "Cycle Day $dayOfCycle (Follicular Phase)."
                CyclePhase.OVULATION -> "Cycle Day $dayOfCycle (Fertile / Ovulation Window)."
                CyclePhase.LUTEAL -> "Cycle Day $dayOfCycle • Next period estimated in $daysUntilPeriod days."
            }
            UserTonePreference.MINIMAL -> when (phase) {
                CyclePhase.MENSTRUAL -> "Day $dayOfCycle • Period."
                CyclePhase.FOLLICULAR -> "Day $dayOfCycle • Follicular."
                CyclePhase.OVULATION -> "Day $dayOfCycle • Fertile."
                CyclePhase.LUTEAL -> "Day $dayOfCycle • In $daysUntilPeriod days."
            }
        }
    }

    /**
     * Converts technical confidence scores into honest, intuitive phrasing.
     */
    fun translateConfidence(sampleCycles: Int, varianceDays: Int): String {
        return when {
            sampleCycles >= 3 && varianceDays <= 2 -> "We have a clear picture of your rhythm"
            sampleCycles >= 2 -> "We have a reasonable initial picture"
            else -> "We're still getting to know your pattern"
        }
    }

    /**
     * Gentle continuity message for returning users (zero guilt, zero streak pressure).
     */
    fun gentleContinuityWelcome(): String {
        return "Welcome back. We picked up right where you left off. How has your body been feeling?"
    }
}

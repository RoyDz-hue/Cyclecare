package com.cyclecare.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cycle_day_logs")
data class CycleDayLog(
    @PrimaryKey
    val dateString: String, // Format: YYYY-MM-DD
    val flowLevel: String = "", // Empty means not reported; FlowLevel enum name when selected
    val energyLevel: String = "", // Empty means not reported; EnergyLevel enum name when selected
    val moodLevel: String = "", // Empty means not reported; MoodLevel enum name when selected
    val symptomsCsv: String = "", // Comma-separated symptom ids
    val vaginalHealthCsv: String = "", // Comma-separated vaginal health ids
    val urinaryHealthCsv: String = "", // Comma-separated urinary symptom ids
    val cervicalMucus: String = "",
    val ovulationTest: String = "NONE", // OvulationTestResult enum name
    val bbtCelsius: Float = 0.0f,
    val sleepHours: Float = 0.0f, // Zero means not reported
    val sleepQuality: String = "", // Empty means not reported; SleepQuality enum name when selected
    val waterGlasses: Int = 0,
    val notes: String = "",
    val painIntensity: Int = 0 // 0 to 10 scale; zero is not reported
) {
    fun hasRecordedObservations(): Boolean =
        flowLevel.isNotBlank() ||
            energyLevel.isNotBlank() ||
            moodLevel.isNotBlank() ||
            symptomsCsv.isNotBlank() ||
            vaginalHealthCsv.isNotBlank() ||
            urinaryHealthCsv.isNotBlank() ||
            cervicalMucus.isNotBlank() ||
            ovulationTest !in setOf("", "NONE") ||
            bbtCelsius > 0f ||
            sleepHours > 0f ||
            sleepQuality.isNotBlank() ||
            waterGlasses > 0 ||
            notes.isNotBlank() ||
            painIntensity > 0
}

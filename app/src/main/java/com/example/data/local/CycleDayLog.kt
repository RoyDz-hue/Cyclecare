package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cycle_day_logs")
data class CycleDayLog(
    @PrimaryKey
    val dateString: String, // Format: YYYY-MM-DD
    val flowLevel: String = "NONE", // FlowLevel enum name
    val energyLevel: String = "NORMAL", // EnergyLevel enum name
    val moodLevel: String = "GOOD", // MoodLevel enum name
    val symptomsCsv: String = "", // Comma-separated symptom ids
    val vaginalHealthCsv: String = "", // Comma-separated vaginal health ids
    val urinaryHealthCsv: String = "", // Comma-separated urinary symptom ids
    val cervicalMucus: String = "",
    val ovulationTest: String = "NONE", // OvulationTestResult enum name
    val bbtCelsius: Float = 0.0f,
    val sleepHours: Float = 7.5f,
    val sleepQuality: String = "GOOD",
    val waterGlasses: Int = 0,
    val notes: String = "",
    val painIntensity: Int = 0 // 0 to 10 scale
)

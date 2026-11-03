package com.cyclecare.app.lab

/** Only String leaves this lab-only assertion facade. The production route,
 * adapter, models and original Kotlin oracle are resolved in ONE app R8 graph.
 * Success is empty; failures are fixed labels, never fixture dates/settings.
 */
object RustCalendarLabProof {
    @JvmStatic fun runOracleChecks(): String = try {
        checkOracleChecks()
        ""
    } catch (_: AssertionError) { "Calendar oracle assertion failed" }
      catch (_: IllegalStateException) { "Calendar native answer or parity failed" }

    /** Canonical eight-field values, deterministic sorted date order. Never
     * logged or exposed: only synthetic data and internal equality checking. */
    internal fun canonicalDays(days: Map<String, com.cyclecare.app.data.model.DayPrediction>): String {
        val rows = org.json.JSONArray()
        for ((key, p) in days.toSortedMap()) {
            check(key == p.date.toString()) { "Prediction date key mismatch" }
            rows.put(org.json.JSONArray().put(p.date.toString()).put(p.dayOfCycle).put(p.phase.name)
                .put(p.isPeriod).put(p.isFertile).put(p.isOvulation)
                .put(p.fertilityConfidence).put(p.pregnancyChance))
        }
        return org.json.JSONObject().put("count", days.size).put("days", rows).toString()
    }

    private fun checkOracleChecks() {
        var compared = 0
        var anchored = 0
        var noAnchor = 0
        var predictionCount = 0
        val settings = listOf(
            RustCalendarLabActivity.fixture().settings,
            RustCalendarLabActivity.fixture().settings.copy(lastPeriodStartDate = "2023-12-29", averageCycleLength = 21, periodDuration = 2),
            RustCalendarLabActivity.fixture().settings.copy(lastPeriodStartDate = "2024-02-29", averageCycleLength = 45, periodDuration = 10),
            RustCalendarLabActivity.fixture().settings.copy(lastPeriodStartDate = "")
        )
        for (setting in settings) for (month in listOf("2023-12", "2024-01", "2024-02", "2024-03")) for (choices in listOf(
            "EXACT" to "28 days", "A few days ago" to "30 days", "It varies / I don't know" to "I don’t know")) {
            val dateChoice = choices.first.trim().lowercase()
            val cycleLengthChoice = choices.second.trim().lowercase()
            val unknownDateChoice = dateChoice.isBlank() || dateChoice == "unknown" || dateChoice.contains("don't know") ||
                dateChoice.contains("don’t know") || dateChoice == "it varies"
            val unknownCycleLengthChoice = cycleLengthChoice.isBlank() || cycleLengthChoice == "unknown" ||
                cycleLengthChoice.contains("don't know") || cycleLengthChoice.contains("don’t know") || cycleLengthChoice.contains("changes")
            val hasAnchor = setting.lastPeriodStartDate.isNotBlank() && !unknownDateChoice && !unknownCycleLengthChoice
            val inputs = com.cyclecare.bridge.CyclePredictionInputs(setting.lastPeriodStartDate,
                setting.averageCycleLength, setting.periodDuration, setting.lutealPhaseDays,
                choices.first, choices.second, java.time.LocalDate.of(2024,2,14), hasAnchor)
            val result = com.cyclecare.bridge.CycleScreenStateAdapter.fetchMonthPredictions(inputs, month)
            check(result != null) { "Native answer required, no fallback" }
            val decoded = result!!.mapValues { (_, p) -> com.cyclecare.app.data.model.DayPrediction(
                java.time.LocalDate.parse(p.date), p.dayOfCycle,
                com.cyclecare.app.data.model.CyclePhase.valueOf(p.phase), p.isPeriod, p.isFertile,
                p.isOvulation, p.fertilityConfidence, p.pregnancyChance) }
            val ym = java.time.YearMonth.parse(month)
            val expected = (if (hasAnchor) (1..ym.lengthOfMonth()).toList() else emptyList()).mapNotNull { day ->
                val date = ym.atDay(day)
                CalendarKotlinOracle.calculateDayPrediction(date,setting)?.let { date.toString() to it }
            }.toMap()
            // Native null never reaches here; successful empty response is
            // valid ONLY for the independently computed no-anchor case.
            if (hasAnchor) {
                check(decoded.size == ym.lengthOfMonth()) { "Missing native prediction days" }
                anchored += 1
            } else {
                check(decoded.isEmpty() && expected.isEmpty()) { "No-anchor response contains predictions" }
                noAnchor += 1
            }
            check(canonicalDays(expected) == canonicalDays(decoded)) { "Canonical eight-field predictor mismatch" }
            compared += 1
            predictionCount += decoded.size
        }
        check(compared == 48 && anchored == 24 && noAnchor == 24 && predictionCount == 732) {
            "Oracle case/count coverage changed"
        }
    }
}

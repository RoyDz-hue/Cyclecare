package com.cyclecare.app.fixtures

// Cross-language parity fixture runner. Runs the PRODUCTION Kotlin functions
// over the shared TSV corpus and emits results_<corpus>.tsv next to it.
//
// Placement: Androidapp/app/src/test/java/com/cyclecare/app/fixtures/
// (same Gradle module, so `internal` functions are visible).
//
// Run from the repo root (host JVM unit tests, no device needed):
//   ./gradlew :app:testDebugUnitTest \
//     --tests "com.cyclecare.app.fixtures.CrossLanguageFixtureRunner" \
//     -Dfixture.dir=<absolute path to fixtures/oracle>
//
// Output lines: case_id TAB out1 TAB out2 ... matching the Rust comparator's
// expectations per corpus. No JSON dependency: pure TSV.

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.notification.explicitPriorityOrNormal
import com.cyclecare.app.data.notification.parseIsoTimestamp
import com.cyclecare.app.domain.safety.SafetyEngine
import com.cyclecare.app.ui.screens.isCompletePin
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CrossLanguageFixtureRunner {

    private fun dir(): File {
        val d = System.getProperty("fixture.dir")
        require(!d.isNullOrBlank()) { "pass -Dfixture.dir=<corpus directory>" }
        return File(d)
    }

    private fun decode(token: String): String? = when (token) {
        "<NULL>" -> null
        "<EMPTY>" -> ""
        "<SPACES>" -> "   "
        else -> token
    }

    private data class SafetyCols(
        val hasLog: Boolean,
        val flow: String,
        val symptoms: String,
        val vaginal: String,
        val urinary: String,
        val pain: Int
    )

    private fun safetyCols(c: List<String>): SafetyCols = SafetyCols(
        hasLog = c[0] == "PRESENT",
        flow = c[1], symptoms = c[2], vaginal = c[3], urinary = c[4],
        pain = c[5].toInt()
    )

    private fun SafetyCols.toLog(): CycleDayLog? = if (!hasLog) null else CycleDayLog(
        dateString = "2026-01-01",
        flowLevel = flow,
        symptomsCsv = symptoms,
        vaginalHealthCsv = vaginal,
        urinaryHealthCsv = urinary,
        painIntensity = pain
    )

    @Test
    fun generateAll() {
        val d = dir()

        // isCompletePin -> bool
        run("corpus_pin.tsv", "results_pin.tsv") { c ->
            listOf(isCompletePin(decode(c[0])).toString())
        }

        // parseIsoTimestamp -> epoch millis
        run("corpus_notifications.tsv", "results_notifications.tsv") { c ->
            listOf(parseIsoTimestamp(decode(c[0])).toString())
        }

        // explicitPriorityOrNormal -> level string
        run("corpus_priority.tsv", "results_priority.tsv") { c ->
            listOf(explicitPriorityOrNormal(decode(c[0])))
        }

        // SafetyEngine.evaluate -> level, title, message, advice, isRedFlag
        run("corpus_safety.tsv", "results_safety.tsv") { c ->
            val e = SafetyEngine.evaluate(safetyCols(c).toLog())
            listOf(e.level.name, e.title, e.message, e.actionAdvice, e.isRedFlag.toString())
        }

        // SafetyEngine.evaluateInput -> same shape
        run("corpus_safety_input.tsv", "results_safety_input.tsv") { c ->
            val e = SafetyEngine.evaluateInput(decode(c[0]) ?: "", safetyCols(c.drop(1)).toLog())
            listOf(e.level.name, e.title, e.message, e.actionAdvice, e.isRedFlag.toString())
        }
    }

    private fun run(corpus: String, out: String, eval: (List<String>) -> List<String>) {
        val lines = File(dir(), corpus).readLines().filter { it.isNotBlank() }
        val sb = StringBuilder()
        for (line in lines) {
            val cols = line.split('\t')
            val caseId = cols[0]
            val fields = eval(cols.drop(1))
            sb.append(caseId)
            fields.forEach { sb.append('\t').append(it.replace('\t', ' ')) }
            sb.append('\n')
        }
        File(dir(), out).writeText(sb.toString())
        assertTrue(sb.isNotEmpty())
    }
}

// ---------------------------------------------------------------------------
// cycle_math corpora (added v8). Notes for the operator:
// - calculateDayPrediction lives on CycleRepository (needs a Room database);
//   construct with an in-memory Room db or Robolectric, and build
//   CycleSettings from the corpus columns.
// - PeriodDateConsistency is domain/health/PeriodDateConsistency.kt; call its
//   duration classification/message members directly.
// Output columns cycle_math: day_of_cycle, phase, is_period, is_fertile,
//   is_ovulation, fertility_confidence, pregnancy_chance (or single "NULL").
// Output columns verdict: verdict name, ongoing message, ended message.
// These blocks are appended as separate @Test methods so the notification /
// safety runners stay stable.
// ---------------------------------------------------------------------------

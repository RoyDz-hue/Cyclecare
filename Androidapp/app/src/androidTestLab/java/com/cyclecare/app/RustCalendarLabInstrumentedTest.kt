package com.cyclecare.app

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyclecare.app.lab.RustCalendarLabActivity
import com.cyclecare.bridge.acceptFor
import com.cyclecare.app.lab.CalendarKotlinOracle
import com.cyclecare.app.lab.RustCalendarLabActivity.Companion.fixture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import android.graphics.Bitmap

/** Separate explicit class filter required. No MainActivity/accounts/database. */
@RunWith(AndroidJUnit4::class)
class RustCalendarLabInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!check() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue("Synthetic native calendar state did not become ready", check())
    }

    private fun find(node: AccessibilityNodeInfo?, description: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString()?.let { it == description || it.startsWith(description + ",") } == true) return node
        for (i in 0 until node.childCount) {
            val hit = find(node.getChild(i), description)
            if (hit != null) return hit
        }
        return null
    }

    private fun assertIsolatedProcess() {
        val context = instrumentation.targetContext
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(android.Manifest.permission.INTERNET))
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PROVIDERS or android.content.pm.PackageManager.GET_SERVICES or
                android.content.pm.PackageManager.GET_RECEIVERS)
        assertFalse(info.providers.orEmpty().any { it.name == "androidx.startup.InitializationProvider" })
        assertFalse(info.services.orEmpty().any { it.name.startsWith("androidx.work.") })
        assertFalse(info.receivers.orEmpty().any { it.name.startsWith("androidx.work.") })
        val databaseDir = java.io.File(context.applicationInfo.dataDir, "databases")
        assertFalse("Lab process must not create real health or WorkManager databases",
            databaseDir.list().orEmpty().any { it.startsWith("cycle_care_database") ||
                it.startsWith("cycle_care_owner_") || it.startsWith("androidx.work.workdb") })
    }

    @Test fun nativeMonthMatchesExistingKotlinPredictorForSyntheticEdges() {
        val settings = listOf(
            fixture().settings,
            fixture().settings.copy(lastPeriodStartDate = "2023-12-29", averageCycleLength = 21, periodDuration = 2),
            fixture().settings.copy(lastPeriodStartDate = "2024-02-29", averageCycleLength = 45, periodDuration = 10),
            fixture().settings.copy(lastPeriodStartDate = "")
        )
        for (setting in settings) for (month in listOf("2023-12", "2024-01", "2024-02", "2024-03")) for (choices in listOf(
            "EXACT" to "28 days", "A few days ago" to "30 days", "It varies" to "I don’t know")) {
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
            assertNotNull("Native answer required, no fallback", result)
            val decoded = result!!.mapValues { (_, p) -> com.cyclecare.app.data.model.DayPrediction(
                java.time.LocalDate.parse(p.date), p.dayOfCycle,
                com.cyclecare.app.data.model.CyclePhase.valueOf(p.phase), p.isPeriod, p.isFertile,
                p.isOvulation, p.fertilityConfidence, p.pregnancyChance) }
            val ym = java.time.YearMonth.parse(month)
            val expected = (if (hasAnchor) (1..ym.lengthOfMonth()).toList() else emptyList()).mapNotNull { day ->
                val date = ym.atDay(day)
                CalendarKotlinOracle.calculateDayPrediction(date,setting)?.let { date.toString() to it }
            }.toMap()
            assertEquals("Existing Kotlin predictor differs from native month",expected,decoded)
        }
    }

    @Test fun existingCalendarRendersNativeAndRejectsSupersededFixture() {
        val context = instrumentation.targetContext
        assertEquals("com.cyclecare.app.lab", context.packageName)
        assertIsolatedProcess()
        val intent = Intent(context, RustCalendarLabActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as RustCalendarLabActivity
        try {
            await { activity.nativeStatus == "native" }
            val initial = activity.acceptedResult
            assertNotNull(initial)
            assertTrue("Kotlin fallback must not mask native failure", initial!!.nativeAnswered)
            assertTrue("Month native compute must be off main thread", initial.computedOffMain)
            assertTrue("Timing must be monotonic and positive", initial.elapsedNanos > 0L)
            assertEquals(29, initial.predictions.size)
            for ((date, prediction) in initial.predictions) {
                assertEquals("Existing Kotlin predictor differs from native", 
                    CalendarKotlinOracle.calculateDayPrediction(java.time.LocalDate.parse(date), fixture().settings), prediction)
            }
            // Assert actual accessibility nodes from the existing calendar, not color alone.
            await { find(instrumentation.uiAutomation.rootInActiveWindow, "Next month") != null }
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull(screenshot)
            val file = File(context.getExternalFilesDir(null), "synthetic-calendar-native.png")
            FileOutputStream(file).use { screenshot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(find(instrumentation.uiAutomation.rootInActiveWindow, "Next month")!!
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await { activity.acceptedResult?.request?.month?.monthValue == 3 && activity.nativeStatus == "native" }
            assertEquals(31, activity.acceptedResult!!.predictions.size)
            // Date selection uses the existing calendar's spoken label.
            val first = find(instrumentation.uiAutomation.rootInActiveWindow, "March 1")
            assertNotNull("Calendar date must expose a non-color accessibility cue", first)
            assertTrue(first!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await { activity.selectedFixtureDate == java.time.LocalDate.of(2024,3,1) }
            val march = activity.acceptedResult!!
            instrumentation.runOnMainSync { activity.supersedeFixture() }
            await { activity.acceptedResult?.request?.epoch == 2L && activity.nativeStatus == "native" }
            assertNotEquals(initial.request, activity.acceptedResult!!.request)
            assertEquals(march.request.inputs, activity.acceptedResult!!.request.inputs)
            assertEquals(march.request.month, activity.acceptedResult!!.request.month)
            assertEquals(march.request.owner, activity.acceptedResult!!.request.owner)
            assertNull(march.acceptFor(activity.acceptedResult!!.request))
            assertIsolatedProcess()
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}

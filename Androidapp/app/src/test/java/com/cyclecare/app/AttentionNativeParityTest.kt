package com.cyclecare.app

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.model.CyclePhase
import com.cyclecare.app.data.model.DayPrediction
import com.cyclecare.app.data.model.SafetyEvaluation
import com.cyclecare.app.data.model.SafetyLevel
import com.cyclecare.app.data.model.TodayAttentionItem
import com.cyclecare.app.domain.attention.AttentionEngine
import com.cyclecare.app.rust.CycleCareCoreBridge
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host JNI parity for the AttentionEngine nonsafety two-pass selector slice:
 * the native Rust select_nonsafety_priority port makes ONLY the non-safety
 * priority choice through the real libcyclecare_core.so dispatcher, while
 * safety, deviation detection, evidence preparation and all copy remain the
 * original Kotlin. Every scenario asserts FULL TodayAttentionItem equality
 * between the native-first wrapper and the verbatim Kotlin oracle, plus
 * native-branch proof (the bridge actually returned a decoded native
 * decision, not a fallback) and strict-decode witnesses (authoritative NONE
 * distinct from unavailable/malformed). Skips cleanly without the host .so.
 * Host parity is host proof only, not Android device proof.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttentionNativeParityTest {

    private val today: LocalDate = LocalDate.now()

    private fun nativeActive() {
        assumeTrue(
            "host libcyclecare_core.so not loadable; JNI parity test skipped",
            CycleCareCoreBridge.attentionNonsafetyPriority(
                false, emptyList(), false, 0, false, false, 0, null, false, null
            ) != null
        )
    }

    private fun settings() = CycleSettings(
        isOnboardingCompleted = true,
        lastPeriodStartDate = today.minusDays(10).toString(),
        averageCycleLength = 28,
        periodDuration = 5,
        lutealPhaseDays = 14
    )

    private fun prediction(day: Int, period: Boolean = false, fertile: Boolean = false) = DayPrediction(
        date = today,
        dayOfCycle = day,
        phase = CyclePhase.MENSTRUAL,
        isPeriod = period,
        isFertile = fertile,
        isOvulation = false,
        fertilityConfidence = "Calendar Estimate",
        pregnancyChance = "Low"
    )

    private val safe = SafetyEvaluation(SafetyLevel.LOW, "t", "m", "a")
    private val urgent = SafetyEvaluation(SafetyLevel.URGENT, "Urgent title", "Urgent message", "Seek care now")

    private fun log(daysAgo: Long, pain: Int = 0, symptoms: String = "", sleepHours: Float = 0f, sleepQuality: String = "") =
        CycleDayLog(
            dateString = today.minusDays(daysAgo).toString(),
            painIntensity = pain,
            symptomsCsv = symptoms,
            sleepHours = sleepHours,
            sleepQuality = sleepQuality
        )

    /** Logs producing a STRONG pain deviation (hist avg 2 vs recent avg 6). */
    private fun strongPainLogs(): List<CycleDayLog> =
        (100..107).map { log(it.toLong(), pain = 2) } + (1..8).map { log(it.toLong(), pain = 6) }

    /** Logs producing a NOTABLE symptom deviation (cramps 0.3 -> 0.5, ratio 1.67x). */
    private fun notableSymptomLogs(): List<CycleDayLog> =
        (100..109).map { log(it.toLong(), symptoms = if (it % 10 < 3) "cramps" else "") } +
            (1..10).map { log(it.toLong(), symptoms = if (it <= 5) "cramps" else "") }

    private fun oracle(
        pred: DayPrediction?,
        logs: List<CycleDayLog>,
        safety: SafetyEvaluation = safe
    ): TodayAttentionItem? = AttentionEngine.evaluateTodayPriority(pred, safety, settings(), logs)

    private fun nativeFirst(
        pred: DayPrediction?,
        logs: List<CycleDayLog>,
        safety: SafetyEvaluation = safe,
        daySupplier: () -> LocalDate = { LocalDate.now() }
    ): TodayAttentionItem? = AttentionEngine.evaluateTodayPriorityNonsafetyNativeFirst(pred, safety, settings(), logs, daySupplier)

    private fun assertParity(
        pred: DayPrediction?,
        logs: List<CycleDayLog>,
        safety: SafetyEvaluation = safe,
        daySupplier: () -> LocalDate = { LocalDate.now() }
    ): TodayAttentionItem? {
        val expected = oracle(pred, logs, safety)
        val actual = nativeFirst(pred, logs, safety, daySupplier)
        assertEquals("native-first must equal the verbatim oracle", expected, actual)
        return actual
    }

    // ---------- full-item parity across the branch/boundary matrix ----------

    @Test fun safetyUrgentIdenticalImmediateKotlinReturn() {
        nativeActive()
        val item = assertParity(prediction(5), strongPainLogs(), urgent)
        assertNotNull(item)
        assertEquals("safety_priority", item!!.id)
        assertTrue(item.isUrgent)
    }

    @Test fun strongDeviationParity() {
        nativeActive()
        val item = assertParity(prediction(20), strongPainLogs())
        assertEquals("baseline_shift_strong", item!!.id)
        assertEquals("Noticeable change: Pelvic Comfort Shift", item.headline)
    }

    @Test fun strongDeviationWinsEvenOnPeriod() {
        nativeActive()
        val item = assertParity(prediction(2, period = true), strongPainLogs())
        assertEquals("baseline_shift_strong", item!!.id)
    }

    @Test fun notableDeviationParity() {
        nativeActive()
        val item = assertParity(prediction(20), notableSymptomLogs())
        assertEquals("baseline_shift_notable", item!!.id)
        assertEquals("A subtle shift in your rhythm", item.headline)
    }

    @Test fun notableDeviationSuppressedOnPeriod() {
        nativeActive()
        assertNull(assertParity(prediction(3, period = true), notableSymptomLogs()))
    }

    @Test fun notableDeviationFiresWithoutPrediction() {
        nativeActive()
        val item = assertParity(null, notableSymptomLogs())
        assertEquals("baseline_shift_notable", item!!.id)
    }

    @Test fun strongDeviationIndexZeroWithMixedSeverities() {
        nativeActive()
        // strong symptom (cramps 0.1 -> 0.3, 3.0x) + notable pain (2 -> 4, diff 2.0):
        // sorted [STRONG, NOTABLE], first strong at index 0.
        val logs = (100..109).map { log(it.toLong(), pain = 2, symptoms = if (it == 100) "cramps" else "") } +
            (1..10).map { log(it.toLong(), pain = 4, symptoms = if (it <= 3) "cramps" else "") }
        val item = assertParity(prediction(20), logs)
        assertEquals("baseline_shift_strong", item!!.id)
        assertEquals("Noticeable change: Cramps Frequency Shift", item.headline)
    }

    @Test fun sleepTaperParityAndFormatting() {
        nativeActive()
        val logs = listOf(
            log(1, sleepHours = 6.5f, sleepQuality = "POOR"),
            log(2, sleepHours = 6.25f, sleepQuality = "POOR")
        )
        // avg = 6.375 -> "%.1f" -> 6.4
        val item = assertParity(prediction(25), logs)
        assertEquals("period_sleep_taper", item!!.id)
        assertEquals("Calendar estimate: period in about 3 days", item.headline)
        assertTrue(item.message.contains("6.4"))
    }

    @Test fun sleepTaperTake3BeforeFilter() {
        nativeActive()
        val logs = listOf(
            log(1, sleepHours = 7.5f, sleepQuality = "GOOD"), // newest: dropped by filter
            log(2, sleepHours = 6.0f, sleepQuality = "POOR"),
            log(3, sleepHours = 6.0f, sleepQuality = "POOR"),
            log(4, sleepHours = 5.0f, sleepQuality = "POOR")  // outside take(3); filter-first would avg 5.67
        )
        val item = assertParity(prediction(26), logs)
        assertEquals("period_sleep_taper", item!!.id)
        assertTrue("take3-before-filter average 6.0 expected", item.message.contains("6.0"))
    }

    @Test fun sleepTaperBoundaries() {
        nativeActive()
        // avg exactly 6.8 is NOT < 6.8
        assertNull(assertParity(prediction(25), listOf(log(1, sleepHours = 6.8f, sleepQuality = "POOR"))))
        // daysUntilPeriod 0 not in 1..3
        assertNull(assertParity(prediction(28), listOf(log(1, sleepHours = 6.0f, sleepQuality = "POOR"))))
        // daysUntilPeriod 4 not in 1..3
        assertNull(assertParity(prediction(24), listOf(log(1, sleepHours = 6.0f, sleepQuality = "POOR"))))
        // default sleep excluded entirely -> null average -> no taper
        assertNull(assertParity(prediction(25), listOf(log(1, sleepHours = 7.5f, sleepQuality = "GOOD"))))
        // boundary 1 day fires
        assertEquals("period_sleep_taper", assertParity(prediction(27), listOf(log(1, sleepHours = 6.0f, sleepQuality = "POOR")))!!.id)
    }

    @Test fun sleepQualityCaseInsensitiveDefaultExclusion() {
        nativeActive()
        // "good" uppercases to GOOD -> excluded; hours equal default within 0.01 -> excluded too
        assertNull(assertParity(prediction(25), listOf(log(1, sleepHours = 7.5f, sleepQuality = "good"))))
        // empty quality with exactly-default hours -> excluded
        assertNull(assertParity(prediction(25), listOf(log(1, sleepHours = 7.5f, sleepQuality = ""))))
        // quality POOR alone (default hours) still counts as non-default evidence
        val item = assertParity(prediction(25), listOf(log(1, sleepHours = 7.5f, sleepQuality = "POOR")))
        assertNull("avg 7.5 is not < 6.8", item)
    }

    @Test fun fertileWindowParity() {
        nativeActive()
        val item = assertParity(prediction(12, fertile = true), emptyList())
        assertEquals("fertile_window_notice", item!!.id)
        assertTrue(item.whyThisAppeared.contains("day 12"))
    }

    @Test fun fertileWindowSuppressedOnPeriod() {
        nativeActive()
        assertNull(assertParity(prediction(2, period = true, fertile = true), emptyList()))
    }

    @Test fun menstrualStartParity() {
        nativeActive()
        val item = assertParity(prediction(1, period = true), emptyList())
        assertEquals("phase_menstrual_start", item!!.id)
        assertEquals("Cycle Reset", item.category)
    }

    @Test fun checkinFiresAtFourDays() {
        nativeActive()
        val fixedNow = LocalDate.now()
        val logs = listOf(log(5))
        val item = assertParity(prediction(20), logs, daySupplier = { fixedNow })
        assertEquals("wellbeing_checkin", item!!.id)
    }

    @Test fun checkinBoundaryThreeDaysIsNull() {
        nativeActive()
        val fixedNow = LocalDate.now()
        assertNull(assertParity(prediction(20), listOf(log(3)), daySupplier = { fixedNow }))
    }

    @Test fun checkinSuppressedOnPeriod() {
        nativeActive()
        val fixedNow = LocalDate.now()
        assertNull(assertParity(prediction(2, period = true), listOf(log(5)), daySupplier = { fixedNow }))
    }

    @Test fun noLogsResolvesToNoneWithoutReadingClock() {
        nativeActive()
        // No parseable logs -> lastLogDate null -> the wrapper must NOT call daySupplier.
        val expected = oracle(prediction(20), emptyList())
        val actual = nativeFirst(prediction(20), emptyList()) {
            fail("daySupplier must not be sampled when lastLogDate is null"); LocalDate.now()
        }
        assertEquals(expected, actual)
        assertNull(actual)
    }

    @Test fun nullPredictionNoDeviationsIsNull() {
        nativeActive()
        assertNull(assertParity(null, emptyList()))
    }

    // ---------- native-branch proof: decisions genuinely decoded from Rust ----------

    @Test fun nativeContractBranchMatrix() {
        nativeActive()
        fun call(
            sevs: List<String> = emptyList(),
            hasPrediction: Boolean = true,
            day: Int = 10,
            period: Boolean = false,
            fertile: Boolean = false,
            daysUntil: Int = 18,
            sleep: Double? = null,
            resolved: Boolean = false,
            daysSince: Long? = null
        ) = CycleCareCoreBridge.attentionNonsafetyPriority(
            false, sevs, hasPrediction, day, period, fertile, daysUntil, sleep, resolved, daysSince
        )

        assertEquals(CycleCareCoreBridge.NonsafetyBranch.NONE,
            call(hasPrediction = false)!!.branch)
        assertEquals(CycleCareCoreBridge.NonsafetyBranch.SAFETY_PRIORITY,
            CycleCareCoreBridge.attentionNonsafetyPriority(true, emptyList(), false, 0, false, false, 0, null, false, null)!!.branch)
        // Original index preserved: STRONG sits at position 1 in the caller's array.
        assertEquals(CycleCareCoreBridge.NonsafetyDecision(CycleCareCoreBridge.NonsafetyBranch.BASELINE_SHIFT_STRONG, 1),
            call(sevs = listOf("NOTABLE", "STRONG")))
        assertEquals(CycleCareCoreBridge.NonsafetyDecision(CycleCareCoreBridge.NonsafetyBranch.BASELINE_SHIFT_NOTABLE, 0),
            call(sevs = listOf("SIGNIFICANT")))
        assertEquals(CycleCareCoreBridge.NonsafetyBranch.NEEDS_CHECKIN,
            call(sevs = listOf("NOTABLE"), period = true)!!.branch)
        assertEquals(CycleCareCoreBridge.NonsafetyBranch.PERIOD_SLEEP_TAPER,
            call(daysUntil = 2, sleep = 6.5)!!.branch)
        assertEquals(CycleCareCoreBridge.NonsafetyBranch.NEEDS_CHECKIN,
            call(daysUntil = 2, sleep = 6.8)!!.branch)
        assertEquals(CycleCareCoreBridge.NonsafetyBranch.FERTILE_WINDOW_NOTICE,
            call(day = 12, fertile = true)!!.branch)
        assertEquals(CycleCareCoreBridge.NonsafetyBranch.PHASE_MENSTRUAL_START,
            call(day = 1, period = true)!!.branch)
        assertEquals(CycleCareCoreBridge.NonsafetyBranch.WELLBEING_CHECKIN,
            call(resolved = true, daysSince = 4)!!.branch)
        assertEquals(CycleCareCoreBridge.NonsafetyBranch.NONE,
            call(resolved = true, daysSince = 3)!!.branch)
        assertEquals(CycleCareCoreBridge.NonsafetyBranch.NONE,
            call(resolved = true, daysSince = null)!!.branch)
        assertEquals(CycleCareCoreBridge.NonsafetyBranch.NONE,
            call(resolved = true, daysSince = 5, period = true)!!.branch)
    }

    // ---------- strict decode witnesses: NONE distinct from unavailable ----------

    @Test fun authoritativeNoneDecodesDistinctFromUnavailable() {
        val none = CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"none"}}"""), emptyList(), checkinResolved = false)
        assertEquals(CycleCareCoreBridge.NonsafetyDecision(CycleCareCoreBridge.NonsafetyBranch.NONE, null), none)
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"error":"boom"}"""), emptyList(), checkinResolved = false))
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"bogus"}}"""), emptyList(), checkinResolved = false))
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{}"""), emptyList(), checkinResolved = false))
    }

    @Test fun deviationIndexBoundToActualFirstMatchingSeverity() {
        // Valid: first STRONG is at index 1 in the caller's actual array.
        assertEquals(
            CycleCareCoreBridge.NonsafetyDecision(CycleCareCoreBridge.NonsafetyBranch.BASELINE_SHIFT_STRONG, 1),
            CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"baseline_shift_strong","deviationIndex":1}}"""), listOf("NOTABLE", "STRONG"), checkinResolved = false)
        )
        // Wrong severity at the claimed index: index 0 is NOTABLE, not STRONG.
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"baseline_shift_strong","deviationIndex":0}}"""), listOf("NOTABLE", "STRONG"), checkinResolved = false))
        // Notable branch must name the first SIGNIFICANT-or-NOTABLE, not a STRONG candidate.
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"baseline_shift_notable","deviationIndex":0}}"""), listOf("STRONG"), checkinResolved = false))
        // Wrong order: first notable is at 0, not 1.
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"baseline_shift_notable","deviationIndex":1}}"""), listOf("NOTABLE", "NOTABLE"), checkinResolved = false))
        // Valid notable after strong candidates.
        assertEquals(
            CycleCareCoreBridge.NonsafetyDecision(CycleCareCoreBridge.NonsafetyBranch.BASELINE_SHIFT_NOTABLE, 1),
            CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"baseline_shift_notable","deviationIndex":1}}"""), listOf("STRONG", "SIGNIFICANT"), checkinResolved = false)
        )
        // Missing, out-of-range, negative, wrong type.
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"baseline_shift_strong"}}"""), listOf("STRONG"), checkinResolved = false))
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"baseline_shift_strong","deviationIndex":1}}"""), listOf("STRONG"), checkinResolved = false))
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"baseline_shift_notable","deviationIndex":-1}}"""), listOf("NOTABLE"), checkinResolved = false))
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"baseline_shift_strong","deviationIndex":"0"}}"""), listOf("STRONG"), checkinResolved = false))
    }

    @Test fun extraneousIndexRejectedOnNonBaselineBranches() {
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"none","deviationIndex":0}}"""), listOf("STRONG"), checkinResolved = false))
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"period_sleep_taper","deviationIndex":0}}"""), listOf("STRONG"), checkinResolved = false))
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"fertile_window_notice","deviationIndex":"0"}}"""), emptyList(), checkinResolved = false))
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"needs_checkin","deviationIndex":null}}"""), emptyList(), checkinResolved = false))
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"wellbeing_checkin","deviationIndex":0}}"""), emptyList(), checkinResolved = true))
        // Absence remains fine.
        assertEquals(
            CycleCareCoreBridge.NonsafetyDecision(CycleCareCoreBridge.NonsafetyBranch.PHASE_MENSTRUAL_START, null),
            CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"phase_menstrual_start"}}"""), emptyList(), checkinResolved = false)
        )
    }

    @Test fun stageLegalityEnforced() {
        // Pass 1 must never yield a resolved-checkin outcome.
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"wellbeing_checkin"}}"""), emptyList(), checkinResolved = false))
        // Pass 2 re-uses identical evidence: only check-in outcomes remain legal.
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"baseline_shift_strong","deviationIndex":0}}"""), listOf("STRONG"), checkinResolved = true))
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"period_sleep_taper"}}"""), emptyList(), checkinResolved = true))
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"needs_checkin"}}"""), emptyList(), checkinResolved = true))
        assertNull(CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"safety_priority"}}"""), emptyList(), checkinResolved = true))
        // Legal pass-2 outcomes.
        assertEquals(
            CycleCareCoreBridge.NonsafetyDecision(CycleCareCoreBridge.NonsafetyBranch.WELLBEING_CHECKIN, null),
            CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"wellbeing_checkin"}}"""), emptyList(), checkinResolved = true)
        )
        assertEquals(
            CycleCareCoreBridge.NonsafetyDecision(CycleCareCoreBridge.NonsafetyBranch.NONE, null),
            CycleCareCoreBridge.decodeNonsafetyReply(JSONObject("""{"ok":{"branch":"none"}}"""), emptyList(), checkinResolved = true)
        )
    }
}

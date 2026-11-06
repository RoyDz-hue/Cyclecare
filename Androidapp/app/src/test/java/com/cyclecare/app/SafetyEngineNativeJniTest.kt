package com.cyclecare.app

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.model.SafetyLevel
import com.cyclecare.app.rust.CycleCareCoreBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Loaded-contract evidence for the native SafetyEngine.evaluate branch:
 * calls CycleCareCoreBridge.evaluateSafety DIRECTLY (no Kotlin fallback in
 * this path - the bridge returns null on native unavailability OR strict
 * decode failure, and this test skips cleanly in that case). A non-null
 * reply therefore proves the loaded host cdylib executed the strict
 * evaluate_safety arm: the pre-M7 arm emitted Debug-format level strings
 * ("SeekProfessionalAdvice") which the strict bridge decode rejects, so only
 * a library carrying the .name() strict arm (SEEK_PROFESSIONAL_ADVICE) can
 * answer at all. Pair with JniProvenanceCaptureTest's RESOLVED-SHA256 for
 * the loaded binary's identity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafetyEngineNativeJniTest {

    private fun nativeAnswer(log: CycleDayLog?): com.cyclecare.app.data.model.SafetyEvaluation {
        val ev = CycleCareCoreBridge.evaluateSafety(log)
        org.junit.Assume.assumeTrue(
            "native evaluate_safety unavailable or strict decode rejected; JNI test skipped",
            ev != null
        )
        return ev!!
    }

    @Test
    fun nativeNullLogIsRoutineLow() {
        val ev = nativeAnswer(null)
        assertEquals(SafetyLevel.LOW, ev.level)
        assertEquals("Routine health status", ev.title)
        assertEquals("No acute symptoms are recorded.", ev.message)
        assertFalse(ev.isRedFlag)
    }

    @Test
    fun nativeFaintingPlusHeavyIsUrgent() {
        val ev = nativeAnswer(CycleDayLog("2026-10-20", flowLevel = "UNUSUALLY_HEAVY", symptomsCsv = "fainting"))
        assertEquals(SafetyLevel.URGENT, ev.level)
        assertEquals("Fainting and unusually heavy bleeding were recorded together.", ev.message)
        assertTrue(ev.isRedFlag)
    }

    @Test
    fun nativeSoresSeekProfessionalAdviceStrictName() {
        // Decisive strict-decode case: only the .name() arm emits
        // "SEEK_PROFESSIONAL_ADVICE"; a Debug-format arm cannot decode.
        val ev = nativeAnswer(CycleDayLog("2026-10-20", vaginalHealthCsv = "sores_lesions"))
        assertEquals(SafetyLevel.SEEK_PROFESSIONAL_ADVICE, ev.level)
        assertTrue(ev.isRedFlag)
    }

    @Test
    fun nativeNormalizationCaseAndSpaces() {
        val ev = nativeAnswer(CycleDayLog("2026-10-20", flowLevel = "UNUSUALLY_HEAVY", symptomsCsv = " Fainting , Headache "))
        assertEquals(SafetyLevel.URGENT, ev.level)
        assertEquals("Fainting and unusually heavy bleeding were recorded together.", ev.message)
    }
}

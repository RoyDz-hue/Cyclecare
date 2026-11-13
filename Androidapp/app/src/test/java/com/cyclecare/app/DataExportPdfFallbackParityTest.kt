package com.cyclecare.app

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.local.OnboardingProfile
import com.cyclecare.app.util.DataExportPdf
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Twin smoke pins for the data_export_pdf_layout_v1 fallback (NEW
 * IMPLEMENTATION, 2026-11-13): the twin is the verbatim pre-wiring
 * DataExportPdf body, reached by reflection on the DataExportPdf object.
 * Decision-level parity (line text, 88-unit UTF-16 wrap, page breaks,
 * y cursor, paint roles, blank gates, Float.toString forms) is pinned in
 * the Rust layout corpus (data_export_pdf_corpus.rs, 12 pins over a
 * recording surface) - PdfDocument byte output has no stable
 * implementation-independent contract, so no byte-exact pin is claimed
 * here. These tests assert only that the twin is intact and produces a
 * non-empty document for representative inputs. Written, NOT executed in
 * this workspace.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataExportPdfFallbackParityTest {

    private fun twin(
        settings: CycleSettings,
        profile: OnboardingProfile?,
        logs: List<CycleDayLog>,
        exportedAt: String,
    ): ByteArray {
        val m = DataExportPdf::class.java.getDeclaredMethod(
            "kotlinBuild",
            CycleSettings::class.java,
            OnboardingProfile::class.java,
            List::class.java,
            String::class.java,
        )
        m.isAccessible = true
        return m.invoke(DataExportPdf, settings, profile, logs, exportedAt) as ByteArray
    }

    @Test
    fun minimalExportProducesDocument() {
        val out = twin(CycleSettings(), null, emptyList(), "2026-11-13 09:40")
        assertTrue(out.isNotEmpty())
    }

    @Test
    fun populatedExportProducesDocument() {
        val longNote = "a".repeat(95) // exercises the 88-unit wrap cut
        val logs = listOf(
            CycleDayLog(
                dateString = "2026-10-02",
                flowLevel = "LIGHT",
                bbtCelsius = 36.55f,
                sleepHours = 7.5f,
                sleepQuality = "GOOD",
                waterGlasses = 6,
                notes = longNote,
                painIntensity = 3,
            ),
            CycleDayLog(dateString = "2026-10-01", notes = "emoji \uD83C\uDF38"),
        )
        val out = twin(CycleSettings(lastPeriodStartDate = "2026-10-01"), OnboardingProfile(), logs, "2026-11-13 09:40")
        assertTrue(out.isNotEmpty())
    }
}

package com.cyclecare.app.util

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.local.OnboardingProfile
import com.cyclecare.app.rust.CycleCareCoreBridge
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a formatted, human-readable PDF of the user's exported health data.
 * Pure android.graphics.pdf - no third-party dependency, works fully offline.
 */
object DataExportPdf {

    private const val PAGE_WIDTH = 595 // A4 at 72dpi
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48f
    private const val LINE_HEIGHT = 16f
    private const val MAX_LINE_CHARS = 88

    /**
     * NEW IMPLEMENTATION (2026-11-13): native-first layout via
     * data_export_pdf_layout_v1. The layout decisions (line text, wrap,
     * page breaks, y positions, paint roles) come from the parity port as
     * a draw-op stream; this class replays the ops onto the real
     * PdfDocument. A null reply runs the verbatim pre-wiring body, kept as
     * the private twin [kotlinBuild].
     *
     * exportedAt is captured once here: the source reads the clock once,
     * textually between the title draw and the Exported line. Both routes
     * use the capture, so on the native path the read happens before the
     * layout runs - slightly earlier than the source's relative read point
     * (the rendered precision is minutes). Locale.getDefault() and the
     * timezone also stay here at the original execution point.
     */
    fun build(
        settings: CycleSettings,
        profile: OnboardingProfile?,
        logs: List<CycleDayLog>
    ): ByteArray {
        val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val ops = CycleCareCoreBridge.dataExportPdfLayout(settings, profile, logs, exportedAt)
        if (ops != null) return renderNativeOps(ops)
        return kotlinBuild(settings, profile, logs, exportedAt)
    }

    /** Replays the native layout's draw ops. Page lifecycle, the three
     * paints, writeTo and close stay in Kotlin, as before. x is always
     * MARGIN - the port only ever draws at MARGIN, exactly like line(). */
    private fun renderNativeOps(ops: List<CycleCareCoreBridge.PdfDrawOp>): ByteArray {
        val document = PdfDocument()
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        val titlePaint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 20f
        }
        val sectionPaint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 13f
        }
        val bodyPaint = Paint().apply { textSize = 11f }
        for (op in ops) {
            if (op.isPageBreak) {
                document.finishPage(page)
                pageNumber += 1
                page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            } else {
                val paint = when (op.paint) {
                    CycleCareCoreBridge.PDF_PAINT_TITLE -> titlePaint
                    CycleCareCoreBridge.PDF_PAINT_SECTION -> sectionPaint
                    else -> bodyPaint
                }
                page.canvas.drawText(op.text, MARGIN, op.y, paint)
            }
        }
        document.finishPage(page)
        val output = ByteArrayOutputStream()
        document.writeTo(output)
        document.close()
        return output.toByteArray()
    }

    /** Verbatim pre-wiring body of [build], run when the native port
     * declines (any null reply). Uses the caller's single exportedAt
     * capture at the same textual point the source read the clock. */
    private fun kotlinBuild(
        settings: CycleSettings,
        profile: OnboardingProfile?,
        logs: List<CycleDayLog>,
        exportedAt: String
    ): ByteArray {
        val document = PdfDocument()
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        var canvas = page.canvas
        var y = MARGIN

        val titlePaint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 20f
        }
        val sectionPaint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 13f
        }
        val bodyPaint = Paint().apply { textSize = 11f }

        fun startNewPage() {
            document.finishPage(page)
            pageNumber += 1
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            canvas = page.canvas
            y = MARGIN
        }

        fun line(text: String = "", paint: Paint = bodyPaint, gap: Float = LINE_HEIGHT) {
            // Naive word wrap so long values (notes, CSV lists) stay on the page.
            var remaining = text
            var first = true
            do {
                if (y > PAGE_HEIGHT - MARGIN) startNewPage()
                val chunk = if (remaining.length <= MAX_LINE_CHARS) {
                    remaining
                } else {
                    val cut = remaining.lastIndexOf(' ', MAX_LINE_CHARS).takeIf { it > 0 } ?: MAX_LINE_CHARS
                    remaining.substring(0, cut)
                }
                canvas.drawText(if (first) chunk else "    $chunk", MARGIN, y, paint)
                y += gap
                remaining = remaining.removePrefix(chunk).trimStart()
                first = false
            } while (remaining.isNotEmpty())
        }

        fun field(label: String, value: String?) {
            val display = value?.takeIf { it.isNotBlank() } ?: "—"
            line("$label: $display")
        }

        fun boolField(label: String, value: Boolean) = field(label, if (value) "Yes" else "No")

        // Header
        line("CycleCare - My Data Export", titlePaint, 30f)
        line("Exported: $exportedAt", bodyPaint, 28f)

        // Cycle settings
        line("Cycle settings", sectionPaint, 22f)
        field("Goals", settings.selectedGoalsCsv)
        field("Last period start", settings.lastPeriodStartDate)
        field("Average cycle length", "${settings.averageCycleLength} days")
        field("Period duration", "${settings.periodDuration} days")
        boolField("Period reminders", settings.remindPeriod)
        boolField("Fertile window reminders", settings.remindFertile)
        boolField("Daily log reminders", settings.remindDailyLog)
        line()

        // Profile baseline
        if (profile != null) {
            line("Profile baseline", sectionPaint, 22f)
            field("Goals", profile.selectedGoalsCsv)
            field("Last period", profile.lastPeriodDateString)
            if (profile.lastPeriodEndDateString.isNotBlank()) field("Period end", profile.lastPeriodEndDateString)
            field("Period date precision", profile.approximatePeriodChoice)
            field("Cycle length", profile.cycleLengthOption.ifBlank { "${profile.cycleLengthDays} days" })
            field("Period duration", "${profile.periodDurationDays} days")
            boolField("Fertile window reminders", profile.remindFertileWindow)
            field("Typical symptoms", profile.typicalSymptomsCsv)
            field("Typical energy", profile.typicalEnergy)
            field("Typical sleep", profile.typicalSleepQuality)
            line()
        }

        // Daily logs
        line("Daily logs (${logs.size} entries)", sectionPaint, 22f)
        logs.forEach { log ->
            if (y > PAGE_HEIGHT - MARGIN - LINE_HEIGHT * 6) startNewPage()
            line(log.dateString, sectionPaint, 18f)
            if (log.flowLevel.isNotBlank()) field("Flow", log.flowLevel)
            if (log.energyLevel.isNotBlank()) field("Energy", log.energyLevel)
            if (log.moodLevel.isNotBlank()) field("Mood", log.moodLevel)
            if (log.symptomsCsv.isNotBlank()) field("Symptoms", log.symptomsCsv)
            if (log.vaginalHealthCsv.isNotBlank()) field("Vaginal health", log.vaginalHealthCsv)
            if (log.urinaryHealthCsv.isNotBlank()) field("Urinary health", log.urinaryHealthCsv)
            if (log.cervicalMucus.isNotBlank()) field("Cervical mucus", log.cervicalMucus)
            if (log.ovulationTest.isNotBlank() && log.ovulationTest != "NONE") field("Ovulation test", log.ovulationTest)
            if (log.bbtCelsius > 0f) field("Basal body temperature", "${log.bbtCelsius} °C")
            if (log.sleepHours > 0f) field("Sleep", "${log.sleepHours} h" + if (log.sleepQuality.isNotBlank()) " (${log.sleepQuality})" else "")
            if (log.waterGlasses > 0) field("Water", "${log.waterGlasses} glasses")
            if (log.painIntensity > 0) field("Pain intensity", "${log.painIntensity}/10")
            if (log.notes.isNotBlank()) field("Notes", log.notes)
            line()
        }

        document.finishPage(page)
        val output = ByteArrayOutputStream()
        document.writeTo(output)
        document.close()
        return output.toByteArray()
    }
}

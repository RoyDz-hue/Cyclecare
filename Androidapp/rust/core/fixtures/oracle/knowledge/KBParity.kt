package com.cyclecare.app.parity

import com.cyclecare.app.data.model.CyclePhase
import com.cyclecare.app.domain.care.CareKnowledgeBase
import java.io.File

fun main(args: Array<String>) {
    val out = File(args[0], "results_kb.tsv")
    out.writeText("")
    fun row(id: String, r: List<String>) = out.appendText("$id\t${r.joinToString(",")}\n")
    row("q01", CareKnowledgeBase.search("").map { it.id })
    row("q02", CareKnowledgeBase.search("   ").map { it.id })
    row("q03", CareKnowledgeBase.search("Why am I so tired before my period?").map { it.id })
    row("q04", CareKnowledgeBase.search("cramps").map { it.id })
    row("q05", CareKnowledgeBase.search("the and for").map { it.id })
    row("q06", CareKnowledgeBase.search("PCOS").map { it.id })
    row("q07", CareKnowledgeBase.search("tired", CyclePhase.LUTEAL).map { it.id })
    row("q08", CareKnowledgeBase.search("tired", CyclePhase.MENSTRUAL).map { it.id })
    row("q09", CareKnowledgeBase.search("mood swings").map { it.id })
    row("q10", CareKnowledgeBase.search("xy").map { it.id })
    row("q11", CareKnowledgeBase.search("How to ease pelvic cramps and period pain?").map { it.id })
    row("q12", CareKnowledgeBase.search("spotting between periods").map { it.id })
    row("q13", CareKnowledgeBase.search("CRAMPS", CyclePhase.MENSTRUAL).map { it.id })
    row("q14", CareKnowledgeBase.search("sleep").map { it.id })
    row("q15", CareKnowledgeBase.search("iron fatigue energy").map { it.id })
    row("q16", CareKnowledgeBase.search("a").map { it.id })
    row("q17", CareKnowledgeBase.search("period").map { it.id })
    println("PROBE_DONE")
}

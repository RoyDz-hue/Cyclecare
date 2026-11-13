package com.cyclecare.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalLanguageLogParserTest {

    private fun labels(input: String) = NaturalLanguageLogParser.parse(input).items.map { it.label }

    @Test
    fun `negated symptoms are not logged`() {
        val labels = labels("no cramps today and no headache")
        assertFalse(labels.any { it.contains("Cramps") })
        assertFalse(labels.any { it.contains("Headache") })
    }

    @Test
    fun `negation cancels only its own clause`() {
        val labels = labels("no cramps but severe headache")
        assertFalse(labels.any { it.contains("Cramps") })
        assertTrue(labels.any { it.contains("Migraine") || it.contains("Headache") })
    }

    @Test
    fun `heavy period language logs heavy flow`() {
        val result = NaturalLanguageLogParser.parse("period started, really heavy bleeding")
        assertEquals("HEAVY", result.flowLevelResult)
    }

    @Test
    fun `no energy idiom still logs exhaustion`() {
        val result = NaturalLanguageLogParser.parse("I have no energy today")
        assertEquals("VERY_LOW", result.energyResult)
    }

    @Test
    fun `positive energy logs good energy`() {
        val result = NaturalLanguageLogParser.parse("feeling energetic and great")
        assertEquals("GOOD", result.energyResult)
    }

    @Test
    fun `swahili severe pain is detected`() {
        val result = NaturalLanguageLogParser.parse("tumbo inaniuma mbaya sana")
        assertTrue(result.painIntensityResult >= 8)
        assertTrue(result.symptomsResult.contains("cramps"))
    }

    @Test
    fun `unmatched input falls back to a general observation with a follow-up`() {
        val result = NaturalLanguageLogParser.parse("something feels different")
        assertEquals(1, result.items.size)
        assertEquals("Wellness", result.items.first().category)
        assertTrue(result.followUpQuestion != null)
    }

    @Test
    fun `negated mood words do not set mood`() {
        val result = NaturalLanguageLogParser.parse("not sad and not irritable, feeling calm")
        assertEquals("GOOD", result.moodResult)
    }
}

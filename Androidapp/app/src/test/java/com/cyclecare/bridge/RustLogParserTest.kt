package com.cyclecare.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RustLogParserTest {

    // --- fallback semantics (native absent in host tests): exact product parser behavior,
    // corpus mirrors NaturalLanguageLogParserTest ---

    @Test fun heavyPeriod() {
        val r = RustLogParser.parse("period started, really heavy bleeding")
        assertEquals("HEAVY", r.flowLevelResult)
        assertEquals("Flow", r.items[0].category)
    }
    @Test fun noEnergyIdiom() {
        val r = RustLogParser.parse("I have no energy today")
        assertEquals("VERY_LOW", r.energyResult)
    }
    @Test fun energeticMood() {
        val r = RustLogParser.parse("feeling energetic and great")
        assertEquals("GOOD", r.energyResult)
        assertEquals("GOOD", r.moodResult)
    }
    @Test fun shengSeverePain() {
        val r = RustLogParser.parse("tumbo inaniuma mbaya sana")
        assertEquals(8, r.painIntensityResult)
        assertTrue(r.symptomsResult.contains("cramps"))
        assertEquals("How long has the pain felt this strong?", r.followUpQuestion)
    }
    @Test fun fallbackCardForUnmatched() {
        val r = RustLogParser.parse("something feels different")
        assertEquals("Wellness", r.items[0].category)
        assertEquals("Would you say it's more physical, emotional, or energy related?", r.followUpQuestion)
    }
    @Test fun negationCancelsBoth() {
        val r = RustLogParser.parse("no cramps today and no headache")
        assertTrue(r.symptomsResult.isEmpty())
    }
    @Test fun negationKeepsSecondSymptom() {
        val r = RustLogParser.parse("no cramps but severe headache")
        assertTrue(r.symptomsResult.contains("headache") || r.symptomsResult.contains("migraine"))
    }
    @Test fun calmMood() {
        val r = RustLogParser.parse("not sad and not irritable, feeling calm")
        assertEquals("GOOD", r.moodResult)
    }

    // --- strict decode ---

    @Test fun decodeRejectsMissingItems() {
        val bad = org.json.JSONObject().put("rawText", "x").put("symptomsResult", org.json.JSONArray())
            .put("painIntensityResult", 0)
        assertNull(RustLogParser.decode(bad))
    }
    @Test fun decodeRejectsNonStringSymptom() {
        val item = org.json.JSONObject().put("category", "Flow").put("label", "L").put("detail", "D").put("iconEmoji", "E")
        val bad = org.json.JSONObject().put("rawText", "x")
            .put("items", org.json.JSONArray().put(item))
            .put("symptomsResult", org.json.JSONArray().put(42))
            .put("painIntensityResult", 0)
        assertNull(RustLogParser.decode(bad))
    }

    @Test fun unpairedSurrogateDetection() {
        assertTrue(RustLogParser.hasUnpairedSurrogate("bad \uD800x"))
        assertTrue(RustLogParser.hasUnpairedSurrogate("trailing\uD800"))
        assertTrue(RustLogParser.hasUnpairedSurrogate("lone \uDC00"))
        assertTrue(!RustLogParser.hasUnpairedSurrogate("paired \uD83D\uDE31 ok"))
        assertTrue(!RustLogParser.hasUnpairedSurrogate("plain text"))
    }

    // --- strict decode: required-null and coercible-type directed cases ---

    private fun validOk(): org.json.JSONObject {
        val item = org.json.JSONObject().put("category", "Flow").put("label", "Period Active")
            .put("detail", "Heavy flow").put("iconEmoji", "X")
        return org.json.JSONObject().put("rawText", "period started")
            .put("items", org.json.JSONArray().put(item))
            .put("followUpQuestion", org.json.JSONObject.NULL)
            .put("flowLevelResult", "HEAVY")
            .put("energyResult", org.json.JSONObject.NULL)
            .put("moodResult", org.json.JSONObject.NULL)
            .put("symptomsResult", org.json.JSONArray())
            .put("painIntensityResult", 0)
    }

    @Test fun decodeAcceptsValid() {
        val d = RustLogParser.decode(validOk())!!
        assertEquals("HEAVY", d.flowLevelResult)
        assertNull(d.followUpQuestion)
        assertEquals(1, d.items.size)
    }
    @Test fun decodeRejectsNullRawText() =
        assertNull(RustLogParser.decode(validOk().put("rawText", org.json.JSONObject.NULL)))
    @Test fun decodeRejectsNullPain() =
        assertNull(RustLogParser.decode(validOk().put("painIntensityResult", org.json.JSONObject.NULL)))
    @Test fun decodeRejectsDoublePain() =
        assertNull(RustLogParser.decode(validOk().put("painIntensityResult", 4.5)))
    @Test fun decodeRejectsStringPain() =
        assertNull(RustLogParser.decode(validOk().put("painIntensityResult", "8")))
    @Test fun decodeRejectsLongPain() =
        assertNull(RustLogParser.decode(validOk().put("painIntensityResult", 8L)))
    @Test fun decodeRejectsNumericCategory() {
        val bad = validOk()
        bad.getJSONArray("items").getJSONObject(0).put("category", 42)
        assertNull(RustLogParser.decode(bad))
    }
    @Test fun decodeRejectsNullItemLabel() {
        val bad = validOk()
        bad.getJSONArray("items").getJSONObject(0).put("label", org.json.JSONObject.NULL)
        assertNull(RustLogParser.decode(bad))
    }
    @Test fun decodeRejectsNumericFlowLevel() =
        assertNull(RustLogParser.decode(validOk().put("flowLevelResult", 3)))

    // --- invalid UTF-16 routed to exact Kotlin before JNI ---

    @Test fun unpairedSurrogateInputUsesKotlinPath() {
        val input = "feeling \uD800 weird"
        assertEquals(com.cyclecare.app.domain.parser.NaturalLanguageLogParser.parse(input), RustLogParser.parse(input))
    }

    @Test fun flagRemainsDisabled() = assertTrue(!RustLogParserConfig.ENABLED)
}

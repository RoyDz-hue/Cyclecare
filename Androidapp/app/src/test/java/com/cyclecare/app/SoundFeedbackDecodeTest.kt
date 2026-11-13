package com.cyclecare.app

import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Strict-decode guards for the sound_feedback_v1 bridge wiring (NEW
 * IMPLEMENTATION, 2026-11-13): the decoded plan drives real AudioTrack
 * synthesis, so a drifted, wrong-typed, or shaped-wrong reply is rejected
 * (null -> the caller keeps its verbatim Kotlin twin). The exact table
 * expectations mirror the Rust corpus pins (sound_feedback_corpus.rs).
 * Written, NOT executed in this workspace.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoundFeedbackDecodeTest {

    @Test
    fun chordDecodes() {
        val reply = JSONObject("""{"ok":{"version":1,"plan":{"kind":"chord","frequencies":[528.0,791.184,1056.0],"amplitudes":[0.2,0.1,0.05],"duration_ms":260}}}""")
        val plan = CycleCareCoreBridge.decodeSoundFeedbackPlan(reply)!!
        assertTrue(plan.isChord)
        assertEquals(3, plan.frequencies.size)
        assertEquals(528f, plan.frequencies[0])
        assertEquals(528f * 1.498f, plan.frequencies[1])
        assertEquals(0.20f, plan.amplitudes[0])
        assertEquals(260, plan.durationMs)
    }

    @Test
    fun arpeggioDecodes() {
        val reply = JSONObject("""{"ok":{"version":1,"plan":{"kind":"arpeggio","notes_hz":[528.0,660.0,792.0],"note_spacing_ms":55,"total_duration_ms":300}}}""")
        val plan = CycleCareCoreBridge.decodeSoundFeedbackPlan(reply)!!
        assertFalse(plan.isChord)
        assertEquals(listOf(528f, 660f, 792f), plan.frequencies.toList())
        assertEquals(55, plan.noteSpacingMs)
        assertEquals(300, plan.durationMs)
    }

    @Test
    fun boundaryDurationAccepted() {
        // 89478 = Int.MAX_VALUE / 24000: the largest duration whose
        // numSamples computation cannot overflow.
        val reply = JSONObject("""{"ok":{"version":1,"plan":{"kind":"chord","frequencies":[528.0],"amplitudes":[0.2],"duration_ms":89478}}}""")
        assertEquals(89478, CycleCareCoreBridge.decodeSoundFeedbackPlan(reply)!!.durationMs)
    }

    @Test
    fun noPlanVerdictDeclines() {
        // The disabled verdict declines: the twin's identical gate yields
        // the same early return (the inc22 decline-then-twin semantics).
        assertNull(CycleCareCoreBridge.decodeSoundFeedbackPlan(JSONObject("""{"ok":{"version":1,"plan":null}}""")))
        assertNull(CycleCareCoreBridge.decodeSoundFeedbackPlan(JSONObject("""{"ok":{"version":1}}""")))
    }

    @Test
    fun driftDeclines() {
        // unknown kind
        assertNull(CycleCareCoreBridge.decodeSoundFeedbackPlan(JSONObject("""{"ok":{"version":1,"plan":{"kind":"chirp","frequencies":[],"amplitudes":[],"duration_ms":1}}}""")))
        // string-coerced frequency never accepted
        assertNull(CycleCareCoreBridge.decodeSoundFeedbackPlan(JSONObject("""{"ok":{"version":1,"plan":{"kind":"chord","frequencies":["528.0"],"amplitudes":[0.2],"duration_ms":260}}}""")))
        // duration wrong type
        assertNull(CycleCareCoreBridge.decodeSoundFeedbackPlan(JSONObject("""{"ok":{"version":1,"plan":{"kind":"chord","frequencies":[528.0],"amplitudes":[0.2],"duration_ms":"260"}}}""")))
        // missing amplitudes for chord
        assertNull(CycleCareCoreBridge.decodeSoundFeedbackPlan(JSONObject("""{"ok":{"version":1,"plan":{"kind":"chord","frequencies":[528.0],"duration_ms":260}}}""")))
        // missing spacing for arpeggio
        assertNull(CycleCareCoreBridge.decodeSoundFeedbackPlan(JSONObject("""{"ok":{"version":1,"plan":{"kind":"arpeggio","notes_hz":[528.0],"total_duration_ms":300}}}""")))
        // impossible shapes: empty tables, chord freq/amp mismatch
        assertNull(CycleCareCoreBridge.decodeSoundFeedbackPlan(JSONObject("""{"ok":{"version":1,"plan":{"kind":"chord","frequencies":[],"amplitudes":[],"duration_ms":260}}}""")))
        assertNull(CycleCareCoreBridge.decodeSoundFeedbackPlan(JSONObject("""{"ok":{"version":1,"plan":{"kind":"arpeggio","notes_hz":[],"note_spacing_ms":55,"total_duration_ms":300}}}""")))
        assertNull(CycleCareCoreBridge.decodeSoundFeedbackPlan(JSONObject("""{"ok":{"version":1,"plan":{"kind":"chord","frequencies":[528.0,660.0],"amplitudes":[0.2],"duration_ms":260}}}""")))
        // unsupported durations: zero, negative, and Int-overflowing
        // (SAMPLE_RATE 24000 * durationMs must not overflow the numSamples
        // computation -> bound 89478, not an invented product limit)
        assertNull(CycleCareCoreBridge.decodeSoundFeedbackPlan(JSONObject("""{"ok":{"version":1,"plan":{"kind":"chord","frequencies":[528.0],"amplitudes":[0.2],"duration_ms":0}}}""")))
        assertNull(CycleCareCoreBridge.decodeSoundFeedbackPlan(JSONObject("""{"ok":{"version":1,"plan":{"kind":"chord","frequencies":[528.0],"amplitudes":[0.2],"duration_ms":-5}}}""")))
        assertNull(CycleCareCoreBridge.decodeSoundFeedbackPlan(JSONObject("""{"ok":{"version":1,"plan":{"kind":"arpeggio","notes_hz":[528.0],"note_spacing_ms":55,"total_duration_ms":89479}}}""")))
        // unsupported spacing
        assertNull(CycleCareCoreBridge.decodeSoundFeedbackPlan(JSONObject("""{"ok":{"version":1,"plan":{"kind":"arpeggio","notes_hz":[528.0],"note_spacing_ms":0,"total_duration_ms":300}}}""")))
        // version drift
        assertNull(CycleCareCoreBridge.decodeSoundFeedbackPlan(JSONObject("""{"ok":{"version":2,"plan":{"kind":"chord","frequencies":[],"amplitudes":[],"duration_ms":1}}}""")))
        assertNull(CycleCareCoreBridge.decodeSoundFeedbackPlan(JSONObject("""{"ok":{"version":"1","plan":null}}""")))
        // ok missing / wrong type
        assertNull(CycleCareCoreBridge.decodeSoundFeedbackPlan(JSONObject("""{"plan":null}""")))
        assertNull(CycleCareCoreBridge.decodeSoundFeedbackPlan(JSONObject("""{"ok":"plan"}""")))
    }
}

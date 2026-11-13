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
 * Strict-decode guards for the settings_steppers_v1 bridge wiring (NEW
 * IMPLEMENTATION, 2026-11-13): the decoded value feeds onUpdateSettings
 * persistence and visible text, so a drifted or wrong-typed reply is
 * rejected (null -> the caller keeps its verbatim Kotlin twin).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSteppersDecodeTest {

    private fun isSet(body: String) = CycleCareCoreBridge.decodeSettingsIsSet(JSONObject(body))
    private fun step(body: String) = CycleCareCoreBridge.decodeSettingsStepper(JSONObject(body))

    @Test
    fun validIsSetDecodes() {
        assertTrue(isSet("""{"ok":{"version":1,"is_set":true}}""")!!)
        assertFalse(isSet("""{"ok":{"version":1,"is_set":false}}""")!!)
    }

    @Test
    fun isSetDeclinesWrongTypesAndVersionDrift() {
        assertNull(isSet("""{"ok":{"version":1,"is_set":"true"}}"""))
        assertNull(isSet("""{"ok":{"version":1,"is_set":1}}"""))
        assertNull(isSet("""{"ok":{"version":2,"is_set":true}}"""))
        assertNull(isSet("""{"ok":{"version":1}}"""))
        assertNull(isSet("""{"error":"native unavailable"}"""))
    }

    @Test
    fun validStepperDecodes() {
        assertEquals(21, step("""{"ok":{"version":1,"next":21}}"""))
        assertEquals(45, step("""{"ok":{"version":1,"next":45}}"""))
    }

    @Test
    fun stepperDeclinesWrongTypesAndVersionDrift() {
        assertNull(step("""{"ok":{"version":1,"next":21.5}}"""))
        assertNull(step("""{"ok":{"version":1,"next":"21"}}"""))
        assertNull(step("""{"ok":{"version":2,"next":21}}"""))
        assertNull(step("""{"error":"native unavailable"}"""))
    }

    @Test
    fun stepperNoOpVerdictDeclinesToTwin() {
        // next:null is the port's no-op verdict: the twin's identical guard
        // produces the same no-op, so the verdict is never consumed natively.
        assertNull(step("""{"ok":{"version":1,"next":null}}"""))
        assertNull(step("""{"ok":{"version":1}}"""))
    }
}

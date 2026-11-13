package com.cyclecare.app

import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Strict-decode guards for the settings_pin_draft_v1 bridge wiring (NEW
 * IMPLEMENTATION, 2026-11-13): the decoded string becomes the passcode
 * draft, so a drifted reply (over 4 chars, non-digit chars, wrong type) is
 * rejected (null -> the caller keeps its verbatim Kotlin twin).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsPinDraftDecodeTest {

    private fun draft(body: String) = CycleCareCoreBridge.decodeSettingsPinDraft(JSONObject(body))

    @Test
    fun validDraftDecodes() {
        assertEquals("1234", draft("""{"ok":{"version":1,"draft":"1234"}}"""))
        assertEquals("", draft("""{"ok":{"version":1,"draft":""}}"""))
        assertEquals("١٢٣٤", draft("""{"ok":{"version":1,"draft":"١٢٣٤"}}"""))
    }

    @Test
    fun draftDeclinesShapeViolations() {
        assertNull(draft("""{"ok":{"version":1,"draft":"12345"}}"""))
        assertNull(draft("""{"ok":{"version":1,"draft":"12a4"}}"""))
        assertNull(draft("""{"ok":{"version":1,"draft":"12 4"}}"""))
    }

    @Test
    fun draftDeclinesWrongTypesAndVersionDrift() {
        assertNull(draft("""{"ok":{"version":1,"draft":1234}}"""))
        assertNull(draft("""{"ok":{"version":2,"draft":"1234"}}"""))
        assertNull(draft("""{"ok":{"version":"1","draft":"1234"}}"""))
        assertNull(draft("""{"ok":{"version":1}}"""))
        assertNull(draft("""{"error":"native unavailable"}"""))
    }
}

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
 * Strict-decode guards for the settings_backup_v1 bridge wiring (NEW
 * IMPLEMENTATION, 2026-11-13): the decoded message and visibility flags
 * render in the Settings "Backup check" card, so a drifted or wrong-typed
 * reply is rejected (null -> the caller keeps its verbatim Kotlin twin).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsBackupDecodeTest {

    private fun msg(body: String) = CycleCareCoreBridge.decodeSettingsBackupMessage(JSONObject(body))
    private fun show(body: String) = CycleCareCoreBridge.decodeSettingsBackupShow(JSONObject(body))

    @Test
    fun validMessageDecodesFromExactTable() {
        assertEquals(
            "Online backup is off in this version. Your records stay on this device.",
            msg("""{"ok":{"version":1,"message":"Online backup is off in this version. Your records stay on this device."}}"""),
        )
        assertEquals("Everything looks good.", msg("""{"ok":{"version":1,"message":"Everything looks good."}}"""))
        assertEquals(
            "Backup is paused or needs attention. CycleCare never overwrites one of your records without asking you first.",
            msg("""{"ok":{"version":1,"message":"Backup is paused or needs attention. CycleCare never overwrites one of your records without asking you first."}}"""),
        )
    }

    @Test
    fun messageDeclinesOutsideExactTable() {
        assertNull(msg("""{"ok":{"version":1,"message":"Everything looks good"}}"""))
        assertNull(msg("""{"ok":{"version":1,"message":""}}"""))
        assertNull(msg("""{"ok":{"version":1,"message":null}}"""))
        assertNull(msg("""{"ok":{"version":2,"message":"Everything looks good."}}"""))
        assertNull(msg("""{"ok":{"version":1}}"""))
        assertNull(msg("""{"error":"native unavailable"}"""))
    }

    @Test
    fun validShowDecodes() {
        assertTrue(show("""{"ok":{"version":1,"show":true}}""")!!)
        assertFalse(show("""{"ok":{"version":1,"show":false}}""")!!)
    }

    @Test
    fun showDeclinesWrongTypesAndVersionDrift() {
        assertNull(show("""{"ok":{"version":1,"show":"true"}}"""))
        assertNull(show("""{"ok":{"version":1,"show":1}}"""))
        assertNull(show("""{"ok":{"version":2,"show":true}}"""))
        assertNull(show("""{"ok":{"version":1}}"""))
        assertNull(show("""{"error":"native unavailable"}"""))
    }
}

package com.cyclecare.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NEW IMPLEMENTATION (recovery rebuild): pins the private Kotlin twins that
 * SettingsScreen falls back to when settings_backup_v1 declines. Twin
 * bodies are the original "Backup check" expressions verbatim; expectations
 * mirror the settings_core corpus backup rules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsBackupFallbackParityTest {

    private val facade = Class.forName("com.cyclecare.app.ui.screens.SettingsScreenKt")

    private fun msg(active: Boolean, success: Boolean): String {
        val m = facade.getDeclaredMethod(
            "kotlinBackupStatusMessage",
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        m.isAccessible = true
        return m.invoke(null, active, success) as String
    }

    private fun showLastSync(active: Boolean): Boolean {
        val m = facade.getDeclaredMethod("kotlinShowLastSyncMessage", Boolean::class.javaPrimitiveType)
        m.isAccessible = true
        return m.invoke(null, active) as Boolean
    }

    private fun showCheckAgain(active: Boolean, success: Boolean): Boolean {
        val m = facade.getDeclaredMethod(
            "kotlinShowCheckAgainButton",
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        m.isAccessible = true
        return m.invoke(null, active, success) as Boolean
    }

    @Test
    fun statusMessageMirrorsCorpusRows() {
        assertEquals(
            "Online backup is off in this version. Your records stay on this device.",
            msg(false, true),
        )
        assertEquals("Everything looks good.", msg(true, true))
        assertEquals(
            "Backup is paused or needs attention. CycleCare never overwrites one of your records without asking you first.",
            msg(true, false),
        )
    }

    @Test
    fun visibilityFlagsMirrorCorpusRows() {
        assertEquals(false, showLastSync(false))
        assertEquals(true, showLastSync(true))
        assertEquals(false, showCheckAgain(false, false))
        assertEquals(false, showCheckAgain(false, true))
        assertEquals(false, showCheckAgain(true, true))
        assertEquals(true, showCheckAgain(true, false))
    }
}

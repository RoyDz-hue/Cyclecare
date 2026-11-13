package com.cyclecare.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NEW IMPLEMENTATION (recovery rebuild): pins the private Kotlin twin that
 * SettingsScreen falls back to when settings_pin_draft_v1 declines. The
 * twin body is the original draft-filter expression verbatim; expectations
 * mirror the settings_core JVM-oracle corpus pinDraft rows, including the
 * Arabic-Indic digits (BMP, kept) and the mathematical double-struck digits
 * (non-BMP: seen as lone surrogates, NOT digits, dropped).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsPinDraftFallbackParityTest {

    private val facade = Class.forName("com.cyclecare.app.ui.screens.SettingsScreenKt")

    private fun twin(raw: String): String {
        val m = facade.getDeclaredMethod("kotlinPinDraftFilter", String::class.java)
        m.isAccessible = true
        return m.invoke(null, raw) as String
    }

    @Test
    fun pinDraftRowsMirrorCorpus() {
        assertEquals("1234", twin("12ab34"))
        assertEquals("1234", twin("12345"))
        assertEquals("١٢٣٤", twin("١٢٣٤٥"))
        assertEquals("", twin("a"))
        assertEquals("", twin(""))
        assertEquals("", twin("𝟘𝟘𝟘𝟘"))
        assertEquals("123", twin("12 3"))
    }
}

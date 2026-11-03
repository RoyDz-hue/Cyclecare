package com.cyclecare.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyclecare.app.lab.RustLabSelfCheck
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * LAB-VARIANT device proof that the packaged Rust cdylib loads under ART and
 * answers the JSON dispatcher in an R8-processed, minified package.
 *
 * Distinct from RustBridgeInstrumentedTest (debug variant, unminified): that
 * test proves the bridge works when nothing is stripped; this one proves the
 * caller path survives R8 with an explicit lab caller and the native code
 * executes on a real device/emulator.
 *
 * Scope: synthetic inputs only. No accounts, no sign-in, no network, no
 * stored user data. Runs only against the lab build type (disposable
 * applicationId com.cyclecare.app.lab, auto-generated debug cert).
 */
@RunWith(AndroidJUnit4::class)
class RustLabInstrumentedTest {

    @Test
    fun labVariantIdentityAndFlag() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.cyclecare.app.lab", ctx.packageName)
        assertTrue("lab build must set RUST_LAB", BuildConfig.RUST_LAB)
    }

    @Test
    fun nativeDispatcherAnswersEveryLabContract() {
        val results = RustLabSelfCheck.runAll()
        // Every contract must be answered by native code; a null reply means
        // the library failed to load/handshake and Kotlin fallback would have
        // silently taken over - that is a failure here, by design.
        val failures = results.filter { !it.answered }
        assertTrue(
            "native dispatcher failures: " + failures.joinToString { "${it.contract}: ${it.detail}" },
            failures.isEmpty(),
        )
        assertEquals(6, results.size)
    }

    @Test
    fun selfCheckJsonIsStructuredForLogcat() {
        val json = JSONObject(RustLabSelfCheck.runAllAsJson())
        val arr = json.getJSONArray("results")
        assertEquals(6, arr.length())
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            assertTrue(r.getString("contract") + " must be answered: " + r.getString("detail"),
                r.getBoolean("answered"))
        }
    }
}

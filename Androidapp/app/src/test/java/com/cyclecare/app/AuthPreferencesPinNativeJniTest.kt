package com.cyclecare.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.security.AuthPreferences
import com.cyclecare.app.rust.CycleCareCoreBridge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real JNI-boundary coverage for the native PIN slice: requires the
 * host-built libcyclecare_core.so (same Rust source as the Android arm64
 * cdylib) on java.library.path so CycleCareCoreBridge.ensureLoaded actually
 * loads native and verifyPin/encodePin execute in Rust. Verifies the
 * native-first wiring in AuthPreferences end to end, including legacy
 * migration re-encode, and that malformed stored values fail closed WITHOUT
 * a parser panic (reviewer M3 finding). Skips cleanly when the host .so is
 * absent so CI without it still passes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthPreferencesPinNativeJniTest {

    private lateinit var prefs: AuthPreferences

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = AuthPreferences(
            context,
            context.getSharedPreferences("pin-jni-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE),
            true
        )
    }

    private fun nativeActive() {
        org.junit.Assume.assumeTrue(
            "host libcyclecare_core.so not loadable; JNI test skipped",
            CycleCareCoreBridge.encodePin("0000", "0102030405060708090a0b0c0d0e0f10") != null
        )
    }

    private fun writeRawStored(value: String) {
        val field = AuthPreferences::class.java.getDeclaredField("prefs")
        field.isAccessible = true
        val store = field.get(prefs) as android.content.SharedPreferences
        store.edit().putString("quick_unlock_pin", value).commit()
    }

    @Test fun enrollUsesNativePbkdf2AndUnlocksNatively() {
        nativeActive()
        prefs.pin = "2468"
        val stored = prefs.pin ?: error("pin not stored")
        assertTrue("native-encoded stored string expected", stored.startsWith("pbkdf2:150000:"))
        assertTrue(prefs.verifyPin("2468"))
        assertFalse(prefs.verifyPin("1357"))
    }

    @Test fun legacyPlaintextMigratesOnFirstUnlock() {
        nativeActive()
        writeRawStored("1122")
        assertTrue(prefs.verifyPin("1122"))
        assertTrue("legacy success must re-encode to PBKDF2", (prefs.pin ?: "").startsWith("pbkdf2:150000:"))
        assertFalse(prefs.verifyPin("0000"))
    }

    @Test fun malformedStoredFailsClosedWithoutPanic() {
        nativeActive()
        for (bad in listOf(
            "pbkdf2:150000:aébbccddee00112233445566:" + "00".repeat(32),   // UTF-8 salt, even byte length (reviewer panic case)
            "pbkdf2:150000:0011223344556677889900aabbccddeef:" + "00".repeat(32), // odd-length hex (Kotlin nibble-drop divergence)
            "pbkdf2:150000:" + "zz".repeat(16) + ":" + "00".repeat(32),   // non-hex garbage
        )) {
            writeRawStored(bad)
            assertFalse("malformed stored must fail closed: $bad", prefs.verifyPin("1234"))
        }
    }
}

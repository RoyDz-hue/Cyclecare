package com.cyclecare.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.security.AuthPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Quick-unlock PIN storage: salted PBKDF2, fail-closed, legacy upgrade on verify. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthPreferencesPinTest {
    private lateinit var prefs: AuthPreferences

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = AuthPreferences(
            context,
            context.getSharedPreferences("pin-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE),
            true
        )
    }

    @Test fun pinIsStoredAsSaltedPbkdf2NotPlaintextOrFastHash() {
        prefs.pin = "1234"
        val stored = prefs.pin ?: error("pin not stored")
        assertTrue(stored.startsWith("pbkdf2:"))
        assertNotEquals("1234", stored)
        // Salted: two encodings of the same PIN never match.
        prefs.pin = "1234"
        assertNotEquals(stored, prefs.pin)
    }

    @Test fun verifyPinAcceptsCorrectAndRejectsWrong() {
        prefs.pin = "4321"
        assertTrue(prefs.verifyPin("4321"))
        assertFalse(prefs.verifyPin("1234"))
        assertFalse(prefs.verifyPin(""))
    }

    @Test fun legacyPlaintextPinMigratesToPbkdf2OnSuccessfulVerify() {
        // Simulate a pre-hardening install by writing a legacy value directly.
        val field = AuthPreferences::class.java.getDeclaredField("prefs")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val store = field.get(prefs) as android.content.SharedPreferences
        store.edit().putString("quick_unlock_pin", "1234").commit()
        assertTrue(prefs.verifyPin("1234"))
        assertTrue((prefs.pin ?: "").startsWith("pbkdf2:"))
    }
}

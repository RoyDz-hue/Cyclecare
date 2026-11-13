package com.cyclecare.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.payment.SupabasePaymentService
import com.cyclecare.app.security.AuthPreferences
import com.cyclecare.app.util.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Fallback-parity pins for the payment_phone_v1 twins (NEW IMPLEMENTATION,
 * 2026-11-13): the twins are the verbatim pre-wiring PaymentInputValidator
 * calls, reached by reflection on a Robolectric-built service. Written, NOT
 * executed in this workspace.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaymentPhoneFallbackParityTest {

    private fun service(): SupabasePaymentService {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AuthPreferences(
            context,
            context.getSharedPreferences("phone-twin-${UUID.randomUUID()}", Context.MODE_PRIVATE),
            true,
        )
        return SupabasePaymentService(prefs)
    }

    private fun validateTwin(raw: String): ValidationResult {
        val m = SupabasePaymentService::class.java.getDeclaredMethod(
            "kotlinValidateKenyanPhone", String::class.java,
        )
        m.isAccessible = true
        return m.invoke(service(), raw) as ValidationResult
    }

    private fun sanitizeTwin(raw: String): String {
        val m = SupabasePaymentService::class.java.getDeclaredMethod(
            "kotlinSanitizeKenyanPhone", String::class.java,
        )
        m.isAccessible = true
        return m.invoke(service(), raw) as String
    }

    @Test
    fun acceptedForms() {
        assertEquals(ValidationResult.Valid, validateTwin("0712345678"))
        assertEquals(ValidationResult.Valid, validateTwin("0112345678"))
        assertEquals(ValidationResult.Valid, validateTwin("+254712345678"))
        assertEquals(ValidationResult.Valid, validateTwin("254712345678"))
        assertEquals(ValidationResult.Valid, validateTwin("712345678"))
        assertEquals(ValidationResult.Valid, validateTwin(" 0712345678 "))
        assertEquals(ValidationResult.Valid, validateTwin("0712 345 678"))
    }

    @Test
    fun errorMessagesInOrder() {
        assertEquals(ValidationResult.Invalid("Phone number is required."), validateTwin(""))
        assertEquals(ValidationResult.Invalid("Phone number is required."), validateTwin("   "))
        assertEquals(ValidationResult.Invalid("Phone number must contain digits only."), validateTwin("07a2345678"))
        // Regex \s is ASCII-only: an interior NBSP survives the strip and
        // fails the digit check (Kotlin trim() removes it only at edges).
        assertEquals(ValidationResult.Invalid("Phone number must contain digits only."), validateTwin("0712\u00A0345678"))
        assertEquals(
            ValidationResult.Invalid("Please enter a valid 10-digit number (e.g. 0712345678 or 0112345678)."),
            validateTwin("07123456789"),
        )
        assertEquals(
            ValidationResult.Invalid("Kenyan mobile numbers must start with 07XX or 01XX."),
            validateTwin("+254212345678"),
        )
        assertEquals(ValidationResult.Invalid("Phone number must contain digits only."), validateTwin("++254712345678"))
        assertEquals(
            ValidationResult.Invalid("Please enter a valid 10-digit number (e.g. 0712345678 or 0112345678)."),
            validateTwin("+"),
        )
    }

    @Test
    fun sanitizeMirrorsNormalization() {
        assertEquals("254712345678", sanitizeTwin("0712 345 678"))
        assertEquals("254712345678", sanitizeTwin("+254 712 345 678"))
        assertEquals("254712345678", sanitizeTwin("712345678"))
        assertEquals("", sanitizeTwin("abc"))
        assertEquals("12345", sanitizeTwin("12345"))
    }
}

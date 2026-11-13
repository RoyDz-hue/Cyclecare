package com.cyclecare.app

import com.cyclecare.app.util.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fallback-parity pins for the checkout dialog's payment_phone_v1 twin (NEW
 * IMPLEMENTATION, 2026-11-13): the twin is the verbatim pre-wiring
 * PaymentInputValidator call, reached by reflection on the dialog file's
 * facade class. Written, NOT executed in this workspace.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckoutDialogPhoneParityTest {

    private fun twin(raw: String): ValidationResult {
        val facade = Class.forName("com.cyclecare.app.ui.screens.UnifiedGamifiedCheckoutDialogKt")
        val m = facade.getDeclaredMethod("kotlinValidateMpesaPhone", String::class.java)
        m.isAccessible = true
        return m.invoke(null, raw) as ValidationResult
    }

    @Test
    fun twinMatchesValidator() {
        assertEquals(ValidationResult.Valid, twin("0712345678"))
        assertEquals(ValidationResult.Valid, twin("+254112345678"))
        assertEquals(ValidationResult.Invalid("Phone number is required."), twin("  "))
        assertEquals(
            ValidationResult.Invalid("Kenyan mobile numbers must start with 07XX or 01XX."),
            twin("254212345678"),
        )
        assertEquals(
            // Interior NBSP survives the ASCII-only \s strip.
            ValidationResult.Invalid("Phone number must contain digits only."),
            twin("0712\u00A0345678"),
        )
    }
}

package com.example.ui.screens

import com.example.data.payment.PaymentOptionType
import com.example.data.payment.PaymentProvider
import com.example.data.payment.PaymentReleasePolicy
import org.junit.Assert.*
import org.junit.Test

class CheckoutReleaseVisibilityTest {
    @Test fun productionGateAdvertisesNoCheckoutRoute() {
        assertTrue(releasedCheckoutOptions(true, 389L).isEmpty())
        assertTrue(releasedCheckoutOptions(false, 0L).isEmpty())
    }
    @Test fun paypalPermissionCannotImplicitlyOpenMpesa() {
        val paypalOnly = PaymentReleasePolicy { provider, _ -> provider == PaymentProvider.PAYPAL_STANDARD }
        assertEquals(listOf(PaymentOptionType.PAYPAL), releasedCheckoutOptions(true, 389L, paypalOnly))
    }
    @Test fun regionalAndPriceChecksRemainRequiredForMpesa() {
        val mpesaOnly = PaymentReleasePolicy { provider, _ -> provider == PaymentProvider.PAYHERO }
        assertTrue(releasedCheckoutOptions(false, 389L, mpesaOnly).isEmpty())
        assertTrue(releasedCheckoutOptions(true, 0L, mpesaOnly).isEmpty())
        assertEquals(listOf(PaymentOptionType.MPESA), releasedCheckoutOptions(true, 389L, mpesaOnly))
    }
    @Test fun unsupportedCardAndCryptoAreNeverOfferedByTheSelector() {
        val options = releasedCheckoutOptions(true, 389L, PaymentReleasePolicy { _, _ -> true })
        assertFalse(options.contains(PaymentOptionType.DEBIT_CREDIT_CARD))
        assertFalse(options.contains(PaymentOptionType.CRYPTO))
    }
}

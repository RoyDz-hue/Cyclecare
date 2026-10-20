package com.example.data.payment

import com.example.data.economy.CoinPurchasePackage
import com.example.data.economy.EconomyProfile
import com.example.data.economy.EconomyResult
import com.example.data.economy.SupabaseEconomyService
import com.example.security.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Legacy compatibility surface. No STK push or client-side coin credit is permitted. */
@Suppress("UNUSED_PARAMETER")
class MpesaPaymentService(
    authPreferences: AuthPreferences,
    economyService: SupabaseEconomyService
) {
    suspend fun initiateStkPush(
        phoneNumber: String,
        amountKes: Double,
        pkg: CoinPurchasePackage,
        profile: EconomyProfile
    ): UnifiedPaymentResult = withContext(Dispatchers.IO) {
        UnifiedPaymentResult(false, message = "M-Pesa payments are unavailable until checkout safety review is complete.")
    }

    suspend fun verifyAndCompletePayment(
        transaction: PaymentTransaction,
        currentProfile: EconomyProfile
    ): EconomyResult<EconomyProfile> = withContext(Dispatchers.IO) {
        EconomyResult.Error("Payment completion is disabled until server reconciliation confirms settlement.")
    }
}

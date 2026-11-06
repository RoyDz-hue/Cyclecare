package com.cyclecare.app.domain.payment

import com.cyclecare.app.data.economy.CoinPurchasePackage
import com.cyclecare.app.data.economy.EconomyProfile
import com.cyclecare.app.data.economy.EconomyResult
import com.cyclecare.app.data.economy.SupabaseEconomyService
import com.cyclecare.app.data.payment.SupabasePaymentService
import com.cyclecare.app.data.payment.UnifiedPaymentResult
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.delay

sealed class PaymentPollResult {
    data class Success(val profile: EconomyProfile, val message: String) : PaymentPollResult()
    data class Failed(val status: String, val message: String) : PaymentPollResult()
    data class Timeout(val message: String) : PaymentPollResult()
}

class PaymentUseCase(
    private val supabasePaymentService: SupabasePaymentService,
    private val economyService: SupabaseEconomyService,
    private val authPreferences: AuthPreferences,
    private val checkStatus: suspend (String) -> String = { supabasePaymentService.checkTransactionStatus(it) },
    private val refreshWallet: suspend (String) -> EconomyResult<EconomyProfile> = { economyService.fetchVerifiedProfile(it) }
) {
    suspend fun processCard(
        cardNumber: String,
        expiry: String,
        cvv: String,
        pkg: CoinPurchasePackage,
        profile: EconomyProfile
    ): UnifiedPaymentResult {
        return supabasePaymentService.processCardPayment(cardNumber, expiry, cvv, pkg, profile)
    }

    suspend fun processPaypal(pkg: CoinPurchasePackage, profile: EconomyProfile): UnifiedPaymentResult {
        return supabasePaymentService.createPaypalOrder(pkg, profile)
    }

    suspend fun processCrypto(currency: String, pkg: CoinPurchasePackage, profile: EconomyProfile): UnifiedPaymentResult {
        return supabasePaymentService.createOkxCryptoOrder(currency, pkg, profile)
    }

    suspend fun initiateMpesa(
        phoneNumber: String,
        amountKes: Double,
        pkg: CoinPurchasePackage,
        profile: EconomyProfile
    ): UnifiedPaymentResult {
        return supabasePaymentService.initiateMpesaStkPush(phoneNumber, amountKes, pkg, profile)
    }

    suspend fun cancelTransaction(extRef: String) {
        supabasePaymentService.cancelTransaction(extRef)
    }

    suspend fun pollPaymentStatus(
        extRef: String,
        timeoutSeconds: Long,
        currentProfile: EconomyProfile
    ): PaymentPollResult {
        val owner = authPreferences.userId ?: return accountChanged()
        val session = authPreferences.sessionGeneration
        fun sameAccount() = !authPreferences.isLocalOnlyMode && authPreferences.userId == owner &&
            authPreferences.sessionGeneration == session
        if (!sameAccount() || currentProfile.userId != owner) return accountChanged()
        val startTime = System.nanoTime()
        // A wall-clock change must not extend polling indefinitely. Bound caller-supplied durations.
        val maxDuration = timeoutSeconds.coerceIn(0L, 300L) * 1_000_000_000L
        var finalStatus = "pending"
        val terminal = setOf("success", "failed", "cancelled", "expired", "auth_required", "refunded", "reversed")
        while (System.nanoTime() - startTime < maxDuration) {
            if (!sameAccount()) return accountChanged()
            finalStatus = checkStatus(extRef)
            if (!sameAccount()) return accountChanged()
            if (finalStatus in terminal) break
            val remainingMillis = (maxDuration - (System.nanoTime() - startTime)) / 1_000_000L
            if (remainingMillis > 0) delay(minOf(3000L, remainingMillis))
        }
        if (!sameAccount()) return accountChanged()
        return when (finalStatus) {
            "success" -> {
                val verified = refreshWallet(owner)
                if (!sameAccount()) return accountChanged()
                if (verified is EconomyResult.Success && verified.data.userId == owner) {
                    PaymentPollResult.Success(
                        profile = verified.data,
                        message = "Payment verified. Your latest wallet balance is shown; credits may first repay reversed purchases."
                    )
                } else {
                    PaymentPollResult.Timeout(
                        message = "Payment was confirmed, but your balance could not be refreshed. Refresh your wallet or check this payment again; please do not pay again."
                    )
                }
            }
            "failed", "cancelled", "expired" -> PaymentPollResult.Failed(
                finalStatus, "Payment status: ${finalStatus.uppercase()}. Transaction was not completed."
            )
            "refunded", "reversed" -> PaymentPollResult.Failed(
                finalStatus, "Payment status: ${finalStatus.uppercase()}. Purchased credit may have been reversed. Refresh your wallet before spending or starting another purchase."
            )
            "auth_required" -> accountChanged()
            else -> PaymentPollResult.Timeout(
                "Payment is not confirmed yet. Check this payment again before retrying; do not assume it failed."
            )
        }
    }

    private fun accountChanged() = PaymentPollResult.Failed(
        "auth_required", "Your checkout account or secure session changed. Sign in to the original account and check this payment again."
    )
}


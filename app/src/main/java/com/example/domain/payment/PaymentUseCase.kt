package com.example.domain.payment

import com.example.data.economy.CoinPurchasePackage
import com.example.data.economy.EconomyProfile
import com.example.data.economy.EconomyResult
import com.example.data.economy.SupabaseEconomyService
import com.example.data.payment.SupabasePaymentService
import com.example.data.payment.UnifiedPaymentResult
import com.example.security.AuthPreferences
import kotlinx.coroutines.delay

sealed class PaymentPollResult {
    data class Success(val profile: EconomyProfile, val message: String) : PaymentPollResult()
    data class Failed(val status: String, val message: String) : PaymentPollResult()
    data class Timeout(val message: String) : PaymentPollResult()
}

class PaymentUseCase(
    private val supabasePaymentService: SupabasePaymentService,
    private val economyService: SupabaseEconomyService,
    private val authPreferences: AuthPreferences
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

    suspend fun processPaypal(
        pkg: CoinPurchasePackage,
        profile: EconomyProfile
    ): UnifiedPaymentResult {
        return supabasePaymentService.createPaypalOrder(pkg, profile)
    }

    suspend fun processCrypto(
        currency: String,
        pkg: CoinPurchasePackage,
        profile: EconomyProfile
    ): UnifiedPaymentResult {
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
        val startTime = System.currentTimeMillis()
        val maxDuration = timeoutSeconds * 1000L
        var finalStatus = "pending"

        while (System.currentTimeMillis() - startTime < maxDuration) {
            finalStatus = supabasePaymentService.checkTransactionStatus(extRef)
            if (finalStatus in listOf("success", "failed", "cancelled", "expired", "auth_required")) {
                break
            }
            delay(3000L)
        }

        return when {
            finalStatus == "success" -> {
                val userId = authPreferences.userId ?: "local_user"
                val verifiedRes = economyService.fetchVerifiedProfile(userId)
                val updatedProfile = if (verifiedRes is EconomyResult.Success) {
                    verifiedRes.data
                } else {
                    currentProfile
                }
                PaymentPollResult.Success(
                    profile = updatedProfile,
                    message = "Payment verified. Cycle Coins were added to your balance."
                )
            }
            finalStatus in listOf("failed", "cancelled", "expired") -> {
                PaymentPollResult.Failed(
                    status = finalStatus,
                    message = "Payment status: ${finalStatus.uppercase()}. Transaction was not completed."
                )
            }
            finalStatus == "auth_required" -> PaymentPollResult.Failed(
                status = finalStatus,
                message = "Your secure session expired during checkout. Sign in again to refresh the payment status."
            )
            else -> {
                PaymentPollResult.Timeout(
                    message = "Payment verification timed out. Balance will update automatically when confirmed."
                )
            }
        }
    }
}

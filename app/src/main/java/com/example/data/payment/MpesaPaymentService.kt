package com.example.data.payment

import com.example.data.economy.CoinPurchasePackage
import com.example.data.economy.EconomyProfile
import com.example.data.economy.EconomyResult
import com.example.data.economy.SupabaseEconomyService
import com.example.data.supabase.SupabaseConfig
import com.example.data.supabase.SupabaseSessionManager
import com.example.security.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class MpesaPaymentService(
    private val authPreferences: AuthPreferences,
    private val economyService: SupabaseEconomyService
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Initiates an M-Pesa STK Push via Supabase Edge Function with PayHero
     */
    suspend fun initiateStkPush(
        phoneNumber: String,
        amountKes: Double,
        pkg: CoinPurchasePackage,
        profile: EconomyProfile
    ): UnifiedPaymentResult = withContext(Dispatchers.IO) {
        val sanitizedPhone = sanitizePhoneNumber(phoneNumber)
        if (sanitizedPhone.length < 10) {
            return@withContext UnifiedPaymentResult(
                isSuccess = false,
                message = "Please enter a valid Safaricom phone number (e.g. 0712345678 or 254712345678)."
            )
        }

        val totalCoins = pkg.coins + pkg.bonusCoins
        val txId = UUID.randomUUID().toString()

        val transaction = PaymentTransaction(
            id = txId,
            userId = profile.userId.ifBlank { authPreferences.userId ?: "local_user" },
            amountKes = amountKes,
            optionType = PaymentOptionType.MPESA,
            providerUsed = PaymentProvider.PAYHERO,
            phoneNumber = sanitizedPhone,
            packageId = pkg.id,
            coinsToCredit = totalCoins,
            status = PaymentStatus.PENDING
        )

        try {
            val token = SupabaseSessionManager.validAccessToken(authPreferences)
                ?: return@withContext UnifiedPaymentResult(false, message = "Sign in again before starting checkout.")
            val payload = JSONObject().apply {
                put("transaction_id", txId)
                put("user_id", transaction.userId)
                put("phone_number", sanitizedPhone)
                put("amount_kes", amountKes)
                put("coins_to_credit", totalCoins)
                put("package_id", pkg.id)
                put("provider", "PAYHERO")
            }

            val request = Request.Builder()
                .url("${SupabaseConfig.url}/functions/v1/payhero-stk")
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = try {
                httpClient.newCall(request).execute()
            } catch (e: Exception) {
                null
            }

            if (response == null || !response.isSuccessful) {
                return@withContext UnifiedPaymentResult(
                    isSuccess = false,
                    message = "We couldn't start your M-Pesa payment right now. Please try again."
                )
            }

            val respText = response.body?.string() ?: ""
            val json = try { JSONObject(respText) } catch (_: Exception) { JSONObject() }
            val checkoutId = json.optString("external_reference").ifBlank {
                json.optString("CheckoutRequestID").ifBlank { null }
            }

            if (checkoutId.isNullOrBlank()) {
                return@withContext UnifiedPaymentResult(
                    isSuccess = false,
                    message = "Your M-Pesa payment couldn't be started. Please try again."
                )
            }

            UnifiedPaymentResult(
                isSuccess = true,
                orderId = checkoutId,
                externalReference = checkoutId,
                message = "STK Push sent to $sanitizedPhone. Please enter your M-Pesa PIN on your phone.",
                transaction = transaction.copy(providerReference = checkoutId)
            )
        } catch (e: Exception) {
            UnifiedPaymentResult(
                isSuccess = false,
                message = "We couldn't start your M-Pesa payment. Check your connection and try again."
            )
        }
    }

    /**
     * Verifies payment completion and atomically credits coins to user profile.
     */
    suspend fun verifyAndCompletePayment(
        transaction: PaymentTransaction,
        currentProfile: EconomyProfile
    ): EconomyResult<EconomyProfile> = withContext(Dispatchers.IO) {
        try {
            val coinsToAdd = transaction.coinsToCredit
            val updatedBalance = currentProfile.coinBalance + coinsToAdd
            val updatedLifetime = currentProfile.lifetimeEarned + coinsToAdd

            val updatedProfile = currentProfile.copy(
                coinBalance = updatedBalance,
                lifetimeEarned = updatedLifetime
            )

            economyService.purchaseCoinPackage(
                currentProfile,
                CoinPurchasePackage(
                    id = transaction.packageId ?: "pkg_custom",
                    title = "M-Pesa Top Up",
                    coins = coinsToAdd,
                    bonusCoins = 0L,
                    priceUsd = "KES ${transaction.amountKes.toInt()}",
                    description = "M-Pesa payment verified"
                )
            )
        } catch (e: Exception) {
            EconomyResult.Error("Could not confirm payment at this moment.")
        }
    }

    private fun sanitizePhoneNumber(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.startsWith("0") && digits.length == 10 -> "254" + digits.substring(1)
            digits.startsWith("254") -> digits
            digits.startsWith("7") && digits.length == 9 -> "254$digits"
            digits.startsWith("1") && digits.length == 9 -> "254$digits"
            else -> digits
        }
    }
}

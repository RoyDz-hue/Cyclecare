package com.cyclecare.app.data.payment

import com.cyclecare.app.data.economy.CoinPurchasePackage
import com.cyclecare.app.data.economy.EconomyProfile
import com.cyclecare.app.data.supabase.SupabaseConfig
import com.cyclecare.app.data.supabase.SupabaseSessionManager
import com.cyclecare.app.rust.CycleCareCoreBridge
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SupabasePaymentService(
    private val authPreferences: AuthPreferences,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build(),
    private val releasePolicy: PaymentReleasePolicy = DisabledPaymentReleasePolicy,
    private val checkoutRequestIds: CheckoutRequestIdManager = CheckoutRequestIdManager(UnavailableCheckoutRequestStore),
    private val ownerProvider: AuthenticatedOwnerProvider = AuthenticatedOwnerProvider { authPreferences.userId }
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Dispatch M-Pesa STK Push via PayHero Supabase Edge Function
     */
    suspend fun initiateMpesaStkPush(
        phoneNumber: String,
        amountKes: Double,
        pkg: CoinPurchasePackage,
        profile: EconomyProfile
    ): UnifiedPaymentResult = withContext(Dispatchers.IO) {
        if (!releasePolicy.allows(PaymentProvider.PAYHERO, PaymentOperation.INITIATE)) return@withContext UnifiedPaymentResult(false, message = "Payments are unavailable until checkout safety review is complete.")
        val phoneValidation = com.cyclecare.app.util.PaymentInputValidator.validateKenyanPhoneNumber(phoneNumber)
        if (!phoneValidation.isValid) {
            return@withContext UnifiedPaymentResult(
                isSuccess = false,
                message = phoneValidation.message ?: "Invalid phone number."
            )
        }
        val sanitizedPhone = com.cyclecare.app.util.PaymentInputValidator.sanitizeKenyanPhoneNumber(phoneNumber)
        val totalCoins = pkg.coins + pkg.bonusCoins
        val userId = ownerProvider.currentOwnerId()?.takeIf { it.isNotBlank() }
            ?: return@withContext UnifiedPaymentResult(false, message = "Sign in again before starting checkout.")
        if (profile.userId != userId) return@withContext UnifiedPaymentResult(false, message = "Checkout identity changed. Please try again.")
        val checkoutRequestId = checkoutRequestIds.getOrCreate(userId, pkg.id)
            ?: return@withContext UnifiedPaymentResult(false, message = "Secure checkout storage is unavailable.")

        try {
            val token = SupabaseSessionManager.validAccessToken(authPreferences)
                ?: return@withContext UnifiedPaymentResult(false, message = "Sign in again before starting checkout.")
            val payload = JSONObject().apply {
                put("user_id", userId)
                put("phone_number", sanitizedPhone)
                put("amount_kes", amountKes)
                put("package_id", pkg.id)
                put("coins_to_credit", totalCoins)
                put("checkout_request_id", checkoutRequestId)
            }

            val request = Request.Builder()
                .url("${SupabaseConfig.url}/functions/v1/payhero-stk")
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = try { client.newCall(request).execute() } catch (e: Exception) { null }
            if (response == null || !response.isSuccessful) {
                return@withContext UnifiedPaymentResult(
                    isSuccess = false,
                    message = "We couldn't start your M-Pesa payment right now. Please try again."
                )
            }

            val body = response.body?.string().orEmpty()
            val obj = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            val extRef = obj.optString("external_reference").ifBlank {
                obj.optString("CheckoutRequestID").ifBlank { null }
            }

            if (extRef.isNullOrBlank()) {
                return@withContext UnifiedPaymentResult(
                    isSuccess = false,
                    message = "Your M-Pesa payment couldn't be started. Please try again."
                )
            }

            val transaction = PaymentTransaction(
                userId = userId,
                amountUsd = parsePriceUsd(pkg.priceUsd),
                amountKes = amountKes,
                optionType = PaymentOptionType.MPESA,
                providerUsed = PaymentProvider.PAYHERO,
                providerReference = extRef,
                phoneNumber = sanitizedPhone,
                packageId = pkg.id,
                coinsToCredit = totalCoins,
                status = PaymentStatus.PENDING
            )

            UnifiedPaymentResult(
                isSuccess = true,
                externalReference = extRef,
                message = "M-Pesa STK Push sent to $sanitizedPhone. Please enter your PIN on your mobile phone to complete payment.",
                transaction = transaction
            )
        } catch (e: Exception) {
            UnifiedPaymentResult(
                isSuccess = false,
                message = "We couldn't start your M-Pesa payment. Check your connection and try again."
            )
        }
    }

    /**
     * Process Debit/Credit Card payment via PayPal ACDC Edge Function
     */
    suspend fun processCardPayment(
        cardNumber: String,
        expiry: String,
        cvv: String,
        pkg: CoinPurchasePackage,
        profile: EconomyProfile
    ): UnifiedPaymentResult = withContext(Dispatchers.IO) {
        UnifiedPaymentResult(
            isSuccess = false,
            message = "Direct card collection is disabled. Use the secure PayPal checkout instead."
        )
    }

    /**
     * Initiate Standard PayPal Order via Supabase Edge Function
     */
    suspend fun createPaypalOrder(
        pkg: CoinPurchasePackage,
        profile: EconomyProfile
    ): UnifiedPaymentResult = withContext(Dispatchers.IO) {
        if (!releasePolicy.allows(PaymentProvider.PAYPAL_STANDARD, PaymentOperation.INITIATE)) return@withContext UnifiedPaymentResult(false, message = "Payments are unavailable until checkout safety review is complete.")
        val totalCoins = pkg.coins + pkg.bonusCoins
        val userId = ownerProvider.currentOwnerId()?.takeIf { it.isNotBlank() }
            ?: return@withContext UnifiedPaymentResult(false, message = "Sign in again before starting checkout.")
        if (profile.userId != userId) return@withContext UnifiedPaymentResult(false, message = "Checkout identity changed. Please try again.")
        val checkoutRequestId = checkoutRequestIds.getOrCreate(userId, pkg.id)
            ?: return@withContext UnifiedPaymentResult(false, message = "Secure checkout storage is unavailable.")
        val amountUsd = parsePriceUsd(pkg.priceUsd)

        try {
            val token = SupabaseSessionManager.validAccessToken(authPreferences)
                ?: return@withContext UnifiedPaymentResult(false, message = "Sign in again before starting checkout.")
            val payload = JSONObject().apply {
                put("user_id", userId)
                put("amount_usd", amountUsd)
                put("package_id", pkg.id)
                put("coins_to_credit", totalCoins)
                put("is_acdc_card", false)
                put("checkout_request_id", checkoutRequestId)
            }

            val request = Request.Builder()
                .url("${SupabaseConfig.url}/functions/v1/paypal-create-order")
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = try { client.newCall(request).execute() } catch (_: Exception) { null }
            if (response == null || !response.isSuccessful) {
                return@withContext UnifiedPaymentResult(
                    isSuccess = false,
                    message = "PayPal checkout couldn't be started. Please try again later."
                )
            }

            val body = response.body?.string().orEmpty()
            val obj = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            val extRef = obj.optString("external_reference").ifBlank {
                obj.optString("id").ifBlank { null }
            }
            val approvalUrl = obj.optString("approval_url").ifBlank { null }

            if (extRef.isNullOrBlank() || approvalUrl.isNullOrBlank()) {
                return@withContext UnifiedPaymentResult(
                    isSuccess = false,
                    message = "Could not initialize PayPal checkout order."
                )
            }

            val transaction = PaymentTransaction(
                userId = userId,
                amountUsd = amountUsd,
                amountKes = amountUsd * 130.0,
                optionType = PaymentOptionType.PAYPAL,
                providerUsed = PaymentProvider.PAYPAL_STANDARD,
                providerReference = extRef,
                packageId = pkg.id,
                coinsToCredit = totalCoins,
                status = PaymentStatus.PENDING
            )

            UnifiedPaymentResult(
                isSuccess = true,
                externalReference = extRef,
                checkoutUrl = approvalUrl,
                message = "PayPal order created. Confirming payment...",
                transaction = transaction
            )
        } catch (e: Exception) {
            UnifiedPaymentResult(
                isSuccess = false,
                message = "PayPal checkout couldn't be started. Check your connection and try again."
            )
        }
    }

    /**
     * Create OKX Crypto Order via Supabase Edge Function
     */
    suspend fun createOkxCryptoOrder(
        cryptoCurrency: String,
        pkg: CoinPurchasePackage,
        profile: EconomyProfile
    ): UnifiedPaymentResult = withContext(Dispatchers.IO) {
        UnifiedPaymentResult(
            isSuccess = false,
            message = "OKX checkout is disabled until an official merchant-pay integration is configured."
        )
    }

    /**
     * Verbatim pre-wiring Kotlin (NEW IMPLEMENTATION twin, 2026-11-13): the
     * original capture-status interpretation, reached whenever the native
     * payment_capture_status_v1 reply is null.
     */
    private fun kotlinCaptureStatus(httpSuccessful: Boolean, body: String): PaymentStatus =
        PaymentStatusVerifier.captureStatus(httpSuccessful, body)

    /**
     * Verbatim pre-wiring Kotlin (NEW IMPLEMENTATION twin, 2026-11-13): the
     * original checkTransactionStatus response interpretation, reached
     * whenever the native transaction_history_status_v1 reply is null. The
     * JSONArray/getJSONObject throws land in the caller's catch exactly as
     * before (the twin runs inside the same try), yielding "ambiguous".
     */
    private fun kotlinTransactionHistoryStatus(httpSuccessful: Boolean, body: String): String {
        if (httpSuccessful && body.isNotBlank()) {
            val array = JSONArray(body)
            if (array.length() > 0) {
                val obj = array.getJSONObject(0)
                val status = obj.optString("status", "pending").lowercase()
                return PaymentStatusVerifier.historyStatus(status)
            }
        }
        return "ambiguous"
    }

    /**
     * Execute PayPal Capture Edge Function
     */
    suspend fun capturePaypalOrder(externalReference: String): Boolean =
        capturePaypalOrderStatus(externalReference) == PaymentStatus.COMPLETED

    suspend fun capturePaypalOrderStatus(externalReference: String): PaymentStatus = withContext(Dispatchers.IO) {
        if (!releasePolicy.allows(PaymentProvider.PAYPAL_STANDARD, PaymentOperation.CAPTURE)) return@withContext PaymentStatus.AMBIGUOUS
        try {
            val token = SupabaseSessionManager.validAccessToken(authPreferences) ?: return@withContext PaymentStatus.AMBIGUOUS
            val payload = JSONObject().apply {
                put("external_reference", externalReference)
            }

            val request = Request.Builder()
                .url("${SupabaseConfig.url}/functions/v1/paypal-capture")
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            // NEW IMPLEMENTATION (2026-11-13): native-first via
            // payment_capture_status_v1. The response body is a
            // consumable-once stream: capture it ONCE here, in the original
            // read order (isSuccessful, then body), and hand the same two
            // captures to the native route and, on any null reply, to the
            // verbatim Kotlin twin. No transport, token, or write moves.
            val httpSuccessful = response.isSuccessful
            val responseBody = response.body?.string().orEmpty()
            CycleCareCoreBridge.paymentCaptureStatus(httpSuccessful, responseBody)
                ?: kotlinCaptureStatus(httpSuccessful, responseBody)
        } catch (_: Exception) {
            PaymentStatus.AMBIGUOUS
        }
    }

    /**
     * Poll transaction status from Supabase public.transactions table.
     * Returns: "pending", "success", "failed", "cancelled", "expired", or "not_found"
     */
    suspend fun checkTransactionStatus(externalReference: String): String = withContext(Dispatchers.IO) {
        try {
            val token = SupabaseSessionManager.validAccessToken(authPreferences) ?: return@withContext "auth_required"
            val request = Request.Builder()
                .url(encodedTransactionStatusUrl(SupabaseConfig.restUrl, externalReference))
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val response = client.newCall(request).execute()
            // NEW IMPLEMENTATION (2026-11-13): native-first via
            // transaction_history_status_v1. The response body is a
            // consumable-once stream: capture it ONCE here, in the original
            // read order (body, then isSuccessful), and hand the same two
            // captures to the native route and, on any null reply, to the
            // verbatim Kotlin twin. No transport, token, or write moves.
            val body = response.body?.string() ?: ""
            val httpSuccessful = response.isSuccessful
            CycleCareCoreBridge.transactionHistoryStatus(httpSuccessful, body)
                ?: kotlinTransactionHistoryStatus(httpSuccessful, body)
        } catch (e: Exception) {
            "ambiguous"
        }
    }

    /**
     * User-initiated cancellation: Marks transaction as 'cancelled' in Supabase.
     */
    suspend fun cancelTransaction(externalReference: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = SupabaseSessionManager.validAccessToken(authPreferences) ?: return@withContext false
            val json = JSONObject().put("p_external_reference", externalReference)

            val request = Request.Builder()
                .url("${SupabaseConfig.restUrl}/rpc/cancel_my_transaction")
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            response.isSuccessful && runCatching { JSONObject(body).optBoolean("success", false) }.getOrDefault(false)
        } catch (_: Exception) {
            false
        }
    }

    private fun parsePriceUsd(priceStr: String): Double {
        return priceStr.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 6.99
    }
}

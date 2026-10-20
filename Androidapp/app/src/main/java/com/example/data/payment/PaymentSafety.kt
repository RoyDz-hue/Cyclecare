package com.example.data.payment

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

fun interface PaymentReleasePolicy { fun allows(provider: PaymentProvider, operation: PaymentOperation): Boolean }
enum class PaymentOperation { INITIATE, CAPTURE }
object DisabledPaymentReleasePolicy : PaymentReleasePolicy { override fun allows(provider: PaymentProvider, operation: PaymentOperation) = false }

interface CheckoutRequestStore {
    fun read(ownerId: String, packageId: String): String?
    fun write(ownerId: String, packageId: String, checkoutRequestId: String): Boolean
    fun clear(ownerId: String, packageId: String, checkoutRequestId: String): Boolean
}

/** Production default: never falls back to plaintext or process-local persistence. */
object UnavailableCheckoutRequestStore : CheckoutRequestStore {
    override fun read(ownerId: String, packageId: String): String? = null
    override fun write(ownerId: String, packageId: String, checkoutRequestId: String) = false
    override fun clear(ownerId: String, packageId: String, checkoutRequestId: String) = false
}

class InMemoryCheckoutRequestStore : CheckoutRequestStore {
    private val values = mutableMapOf<Pair<String, String>, String>()
    @Synchronized override fun read(ownerId: String, packageId: String): String? = values[ownerId to packageId]
    @Synchronized override fun write(ownerId: String, packageId: String, checkoutRequestId: String): Boolean { values[ownerId to packageId] = checkoutRequestId; return true }
    @Synchronized override fun clear(ownerId: String, packageId: String, checkoutRequestId: String): Boolean { val key = ownerId to packageId; if (values[key] != checkoutRequestId) return false; values.remove(key); return true }
}

class CheckoutRequestIdManager(private val store: CheckoutRequestStore) {
    @Synchronized fun getOrCreate(ownerId: String, packageId: String): String? {
        if (ownerId.isBlank() || packageId.isBlank()) return null
        return store.read(ownerId, packageId) ?: UUID.randomUUID().toString().takeIf { store.write(ownerId, packageId, it) }
    }
    fun clearAfterAuthoritativeTerminal(ownerId: String, packageId: String, checkoutRequestId: String, status: PaymentStatus): Boolean =
        if (status.isAuthoritativeTerminal) store.clear(ownerId, packageId, checkoutRequestId) else false
}

/** Throws if Android Keystore/EncryptedSharedPreferences is unavailable; callers must fail closed. */
class EncryptedCheckoutRequestStore(context: Context) : CheckoutRequestStore {
    private val prefs = run {
        val key = MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context.applicationContext, "cyclecare_payment_checkout_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    private fun key(ownerId: String, packageId: String) = "checkout_request_id_v1_${ownerId.trim()}_${packageId.trim()}"
    @Synchronized override fun read(ownerId: String, packageId: String): String? = prefs.getString(key(ownerId, packageId), null)
    @Synchronized override fun write(ownerId: String, packageId: String, checkoutRequestId: String) = prefs.edit().putString(key(ownerId, packageId), checkoutRequestId).commit()
    @Synchronized override fun clear(ownerId: String, packageId: String, checkoutRequestId: String): Boolean { if (read(ownerId, packageId) != checkoutRequestId) return false; return prefs.edit().remove(key(ownerId, packageId)).commit() }
}

fun interface AuthenticatedOwnerProvider { fun currentOwnerId(): String? }

object PaymentStatusVerifier {
    fun captureStatus(httpSuccessful: Boolean, body: String): PaymentStatus {
        if (!httpSuccessful) return PaymentStatus.AMBIGUOUS
        val obj = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return PaymentStatus.AMBIGUOUS
        val status = obj.optString("status").trim().lowercase()
        if (status == "refunded") return PaymentStatus.REFUNDED
        if (status == "reversed") return PaymentStatus.REVERSED
        if (obj.optBoolean("success", false) && (status == "success" || status == "settled" || obj.optBoolean("settled", false))) return PaymentStatus.COMPLETED
        return when (status) { "failed", "cancelled", "canceled", "expired" -> PaymentStatus.FAILED; else -> PaymentStatus.AMBIGUOUS }
    }
    fun historyStatus(status: String): String = when (status.trim().lowercase()) {
        "success", "completed", "paid", "settled" -> "success"
        "pending", "processing", "initiated" -> "pending"
        "failed", "cancelled", "canceled", "expired", "refunded", "reversed", "ambiguous" -> status.trim().lowercase()
        else -> "ambiguous"
    }
}

fun encodedTransactionStatusUrl(restUrl: String, externalReference: String): String =
    "$restUrl/transactions?external_reference=eq.${URLEncoder.encode(externalReference, StandardCharsets.UTF_8.toString())}&select=status"

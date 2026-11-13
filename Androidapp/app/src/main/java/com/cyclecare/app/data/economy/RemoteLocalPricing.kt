package com.cyclecare.app.data.economy

import android.content.Context
import com.cyclecare.app.data.supabase.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

/**
 * Local-currency DISPLAY prices from get_public_pricing. Charging never
 * changes (PayHero KES, PayPal/OKX USD): [LocalizedPrice.chargeCurrency] and
 * [LocalizedPrice.chargeAmount] state the actual charge; [LocalizedPrice.approx]
 * marks conversions. Any failure leaves the KES/USD display untouched.
 */
object RemoteLocalPricing {
    private const val PREFS = "cyclecare_remote_local_pricing"
    private const val KEY_JSON = "pricing_json"
    private const val KEY_REGION = "pricing_region"
    private const val KEY_FETCHED_AT = "pricing_fetched_at"
    private const val MAX_CACHE_AGE_MS = 12L * 60 * 60 * 1000

    @Volatile private var memoryCache: Pair<String, Map<String, LocalizedPrice>>? = null

    data class LocalizedPrice(
        val currency: String,
        val amount: Double,
        val approx: Boolean,
        val chargeCurrency: String,
        val chargeAmount: Double
    ) {
        /** Store display: "UGX 3,699" or "~UGX 3,699" when approximate. */
        val displayText: String
            get() = (if (approx) "~" else "") + formatAmount(currency, amount)

        /** Checkout truth line, e.g. "Charged $0.99 USD" or "Charged KES 50 via M-Pesa". */
        val chargeText: String
            get() = if (chargeCurrency == "KES") "Charged KES ${formatAmount("KES", chargeAmount).removePrefix("KES ").trim()} via M-Pesa"
                else "Charged ${formatAmount(chargeCurrency, chargeAmount)}"

        companion object {
            fun formatAmount(currency: String, amount: Double): String {
                val whole = amount == amount.toLong().toDouble()
                val body = if (amount >= 100 || whole) String.format(Locale.US, "%,.0f", amount)
                    else String.format(Locale.US, "%.2f", amount)
                return "$currency $body"
            }
        }
    }

    /** Parses the get_public_pricing response. Returns null when malformed. */
    fun parse(raw: String?): Map<String, LocalizedPrice>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = JSONObject(raw)
            val packages = root.optJSONArray("packages") ?: return null
            val out = HashMap<String, LocalizedPrice>(packages.length())
            for (i in 0 until packages.length()) {
                val pkg = packages.getJSONObject(i)
                val id = pkg.optString("id", "")
                val display = pkg.optJSONObject("display") ?: return null
                val currency = display.optString("currency", "")
                val amount = display.optDouble("amount", Double.NaN)
                val chargeCurrency = display.optString("charge_currency", "")
                val chargeAmount = display.optDouble("charge_amount", Double.NaN)
                if (id.isBlank() || currency.length != 3 || amount.isNaN() || amount <= 0) return null
                if (chargeCurrency.length != 3 || chargeAmount.isNaN() || chargeAmount <= 0) return null
                out[id] = LocalizedPrice(currency, amount, display.optBoolean("approx", false), chargeCurrency, chargeAmount)
            }
            if (out.isEmpty()) null else out
        } catch (_: Exception) {
            null
        }
    }

    /** Cached prices for [region] within the freshness window, else null. */
    fun cached(context: Context, region: String): Map<String, LocalizedPrice>? {
        memoryCache?.let { if (it.first == region) return it.second }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_REGION, null) != region) return null
        val fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
        if (fetchedAt <= 0L || System.currentTimeMillis() - fetchedAt > MAX_CACHE_AGE_MS) return null
        val parsed = parse(prefs.getString(KEY_JSON, null)) ?: return null
        memoryCache = region to parsed
        return parsed
    }

    /** Refreshes local display prices for [region]. Any failure keeps the cache. */
    suspend fun refresh(context: Context, region: String, client: OkHttpClient = OkHttpClient()): Boolean = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured || region.isBlank()) return@withContext false
        try {
            val request = Request.Builder()
                .url(SupabaseConfig.url + "/rest/v1/rpc/get_public_pricing")
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer " + SupabaseConfig.anonKey)
                .header("Content-Type", "application/json")
                .post(JSONObject().put("p_region", region).toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body?.use { it.string() }
                val parsed = parse(body) ?: return@withContext false
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_JSON, body)
                    .putString(KEY_REGION, region)
                    .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                    .apply()
                memoryCache = region to parsed
                // Lazy once-a-day rate refresh: when the server marks rates
                // stale, this app instance pings fx-refresh. The server lease
                // (one claim per day) makes concurrent pings lose harmlessly.
                if (try { JSONObject(body).optBoolean("rates_stale", false) } catch (_: Exception) { false }) {
                    try {
                        val ping = Request.Builder()
                            .url(SupabaseConfig.url + "/functions/v1/fx-refresh")
                            .header("apikey", SupabaseConfig.anonKey)
                            .header("Authorization", "Bearer " + SupabaseConfig.anonKey)
                            .post("{}".toRequestBody("application/json".toMediaType()))
                            .build()
                        client.newCall(ping).execute().use { }
                    } catch (_: Exception) { /* refresh stays for the next open */ }
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Test hook: clears both cache layers. */
    fun resetForTesting() { memoryCache = null }
}

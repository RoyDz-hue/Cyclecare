package com.cyclecare.app.data.economy

import android.content.Context
import com.cyclecare.app.data.supabase.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray

/**
 * Coin packages are owned by Supabase (app_config.coin_packages) so admins can
 * reprice from the Admin PWA without shipping an app update. The bundled
 * [DefaultCoinPackages.PACKAGES] remains the offline/failure fallback: a stale
 * price beats a broken store, and checkout still validates server-side.
 */
object RemoteCoinPackages {
    private const val PREFS = "cyclecare_remote_coin_packages"
    private const val KEY_JSON = "packages_json"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val MAX_CACHE_AGE_MS = 24L * 60 * 60 * 1000
    private const val MAX_PACKAGES = 20

    @Volatile private var memoryCache: List<CoinPurchasePackage>? = null

    /** Parses the app_config JSON array. Returns null when anything is malformed. */
    fun parse(raw: String?): List<CoinPurchasePackage>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val array = JSONArray(raw)
            if (array.length() < 1 || array.length() > MAX_PACKAGES) return null
            val out = ArrayList<CoinPurchasePackage>(array.length())
            val ids = HashSet<String>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val id = o.optString("id", "")
                val title = o.optString("title", "")
                val coins = o.optLong("coins", 0L)
                val bonus = o.optLong("bonusCoins", 0L)
                val kes = o.optDouble("priceKes", 0.0)
                val usd = o.optDouble("priceUsd", 0.0)
                if (!id.matches(Regex("^[a-z0-9_]{3,40}$")) || !ids.add(id)) return null
                if (title.isBlank() || title.length > 60) return null
                if (coins < 1 || coins > 1_000_000 || bonus < 0 || bonus > 1_000_000) return null
                if (kes < 0 || usd < 0 || (kes == 0.0 && usd == 0.0)) return null
                out.add(
                    CoinPurchasePackage(
                        id = id,
                        title = title,
                        coins = coins,
                        bonusCoins = bonus,
                        priceUsd = "$" + trimPrice(usd),
                        priceKes = kes.toLong(),
                        tag = o.optString("tag", "").ifBlank { null },
                        description = o.optString("description", "").ifBlank { "From the CycleCare store catalog." }
                    )
                )
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun trimPrice(usd: Double): String =
        if (usd == usd.toLong().toDouble()) usd.toLong().toString() + ".00" else usd.toString()

    /** Last good server list within its freshness window, else null. */
    fun cached(context: Context): List<CoinPurchasePackage>? {
        memoryCache?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
        if (fetchedAt <= 0L || System.currentTimeMillis() - fetchedAt > MAX_CACHE_AGE_MS) return null
        val parsed = parse(prefs.getString(KEY_JSON, null)) ?: return null
        memoryCache = parsed
        return parsed
    }

    /** The packages the store should show right now. Never fails. */
    fun active(context: Context): List<CoinPurchasePackage> =
        cached(context) ?: DefaultCoinPackages.PACKAGES

    /** Refreshes the cache from Supabase. Any failure keeps the previous cache. */
    suspend fun refresh(context: Context, client: OkHttpClient = OkHttpClient()): Boolean = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext false
        try {
            val request = Request.Builder()
                .url(SupabaseConfig.url + "/rest/v1/rpc/get_public_app_config")
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer " + SupabaseConfig.anonKey)
                .header("Content-Type", "application/json")
                .post("{\"p_key\":\"coin_packages\"}".toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val parsed = parse(response.body?.use { it.string() }) ?: return@withContext false
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_JSON, JSONArray(parsed.map { pkg ->
                        mapOf(
                            "id" to pkg.id, "title" to pkg.title, "coins" to pkg.coins,
                            "bonusCoins" to pkg.bonusCoins, "priceKes" to pkg.priceKes,
                            "priceUsd" to pkg.priceUsd.removePrefix("$").toDoubleOrNull(),
                            "tag" to (pkg.tag ?: ""), "description" to pkg.description
                        )
                    }).toString())
                    .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                    .apply()
                memoryCache = parsed
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Test hook: clears both cache layers. */
    fun resetForTesting() { memoryCache = null }
}

package com.cyclecare.app.data.economy

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

data class LocalizedCoinPackage(
    val id: String,
    val title: String,
    val coins: Long,
    val bonusCoins: Long,
    val priceDisplay: String,
    val isHeroPackage: Boolean = false,
    val badge: String? = null,
    val description: String
)

object CurrencyLocaleManager {
    /** Resolves the country from SIM/network first, then the device locale. */
    fun detectCountryCode(context: Context? = null): String {
        try {
            if (context != null) {
                val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val simCountry = telephony?.simCountryIso
                if (!simCountry.isNullOrBlank()) return simCountry.uppercase(Locale.US)
                val networkCountry = telephony?.networkCountryIso
                if (!networkCountry.isNullOrBlank()) return networkCountry.uppercase(Locale.US)
            }
        } catch (_: Exception) {
            // Locale fallback is intentionally non-fatal on devices without telephony permission.
        }
        return Locale.getDefault().country.takeIf { it.isNotBlank() }?.uppercase(Locale.US) ?: "KE"
    }

    /**
     * The server accepts only these fixed package IDs and charges the matching
     * KES or USD amount. Other countries use USD/PayPal until a local provider
     * is implemented; no unsupported UGX/TZS prices are advertised.
     */
    fun getLocalizedPackages(context: Context? = null): List<LocalizedCoinPackage> {
        val kenya = detectCountryCode(context) == "KE"
        val prices = if (kenya) {
            listOf("KES 389", "KES 909", "KES 1,949")
        } else {
            listOf("\$2.99", "\$6.99", "\$14.99")
        }
        return listOf(
            LocalizedCoinPackage(
                id = "pkg_seed",
                title = "Bloom Seed",
                coins = 250L,
                bonusCoins = 0L,
                priceDisplay = prices[0],
                isHeroPackage = true,
                badge = "Starter",
                description = "A starter bundle from the CycleCare server catalog."
            ),
            LocalizedCoinPackage(
                id = "pkg_sanctuary",
                title = "Sanctuary Vault",
                coins = 750L,
                bonusCoins = 100L,
                priceDisplay = prices[1],
                isHeroPackage = true,
                badge = "Most Cherished",
                description = "Includes 100 bonus coins at the fixed server price."
            ),
            LocalizedCoinPackage(
                id = "pkg_celestial",
                title = "Celestial Treasury",
                coins = 2000L,
                bonusCoins = 400L,
                priceDisplay = prices[2],
                badge = "Grand Value",
                description = "Includes 400 bonus coins at the fixed server price."
            )
        )
    }

    /** Builds a shareable request without changing the caller's wallet. */
    fun buildShareableCoinRequestMessage(userEmail: String?, missingCoins: Long): String {
        val email = userEmail ?: "my registered email"
        return "Hi! I'm using CycleCare to track my cycle health. Could you send me $missingCoins Cycle Coins to help me unlock an artifact? My registered account is: $email. Let's track together on CycleCare!"
    }
}

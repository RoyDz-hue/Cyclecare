package com.example.data.economy

import com.example.data.supabase.SupabaseConfig
import com.example.data.supabase.SupabaseSessionManager
import com.example.data.supabase.UserFacingServerMessage
import com.example.security.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class EconomyResult<out T> {
    data class Success<out T>(val data: T, val message: String = "") : EconomyResult<T>()
    data class Error(val errorMessage: String) : EconomyResult<Nothing>()
}

class SupabaseEconomyService(
    private val authPreferences: AuthPreferences,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Edge verification: sync and fetch verified economy state from Supabase.
     */
    suspend fun fetchVerifiedProfile(userId: String): EconomyResult<EconomyProfile> = withContext(Dispatchers.IO) {
        val effectiveId = userId.ifBlank { authPreferences.effectiveUserId }
        val accessToken = SupabaseSessionManager.validAccessToken(authPreferences)
            ?: return@withContext EconomyResult.Error("A secure CycleCare session is required to load your cloud wallet.")
        try {
            val token = accessToken.trim()
            val ensureRequest = Request.Builder()
                .url("${SupabaseConfig.restUrl}/rpc/ensure_my_economy_profile")
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            val ensureResponse = client.newCall(ensureRequest).execute()
            val ensureBody = ensureResponse.body?.string().orEmpty()
            if (!ensureResponse.isSuccessful || !runCatching { JSONObject(ensureBody).optBoolean("success", false) }.getOrDefault(false)) {
                return@withContext EconomyResult.Error("Cloud wallet setup could not be verified. Sign in again or retry later.")
            }

            val request = Request.Builder()
                .url("${SupabaseConfig.restUrl}/economy_profiles?user_id=eq.$effectiveId")
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext EconomyResult.Error("Your balance could not be refreshed right now. Please try again.")
            }
            if (body.isBlank()) {
                return@withContext EconomyResult.Error("Cloud wallet returned an empty response.")
            }

            var profileStatus = "ACTIVE"
            var profileSuspensionReason: String? = null
            var profileSuspendedUntil: String? = null
            try {
                val profReq = Request.Builder()
                    .url("${SupabaseConfig.restUrl}/profiles?user_id=eq.$effectiveId&select=status,suspension_reason,suspended_until")
                    .header("apikey", SupabaseConfig.anonKey)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                val profRes = client.newCall(profReq).execute()
                val profBody = profRes.body?.string() ?: ""
                if (!profRes.isSuccessful) {
                    return@withContext EconomyResult.Error("Your account status couldn't be checked. Please try again.")
                }
                if (profRes.isSuccessful && profBody.isNotBlank()) {
                    val pArr = JSONArray(profBody)
                    if (pArr.length() > 0) {
                        val pObj = pArr.getJSONObject(0)
                        profileStatus = pObj.optString("status", "ACTIVE")
                        profileSuspensionReason = pObj.optString("suspension_reason").ifBlank { null }
                        profileSuspendedUntil = pObj.optString("suspended_until").ifBlank { null }
                    }
                }
            } catch (_: Exception) {
                return@withContext EconomyResult.Error("Account status could not be verified. Please retry before using the shared vault.")
            }

            if (response.isSuccessful && body.isNotBlank()) {
                val array = JSONArray(body)
                if (array.length() > 0) {
                    val obj = array.getJSONObject(0)
                    val tierStr = obj.optString("tier", "FREE")
                    val rankStr = obj.optString("rank", "LUMEN")
                    val balance = obj.optLong("coin_balance", 0L)
                    val earned = obj.optLong("lifetime_earned", 0L)
                    val spent = obj.optLong("lifetime_spent", 0L)
                    val activeFrame = obj.optString("active_frame").ifBlank { null }
                    val activeArtifact = obj.optString("active_artifact", "cycle_coin")
                    val activeTheme = obj.optString("active_theme", "SOOTHING_ROSE")
                    val unlockedList = obj.optJSONArray("unlocked_items")

                    val unlockedSet = mutableSetOf("cycle_coin")
                    if (unlockedList != null) {
                        for (i in 0 until unlockedList.length()) {
                            val rawItem = when (val value = unlockedList.opt(i)) {
                                is String -> value
                                is org.json.JSONObject -> value.optString("id").ifBlank { value.optString("name") }
                                else -> ""
                            }
                            if (rawItem.isNotBlank()) {
                                unlockedSet.add(EconomyCatalog.findItem(rawItem)?.id ?: rawItem)
                            }
                        }
                    }

                    val econStatus = obj.optString("status", "ACTIVE")
                    val econSuspensionReason = obj.optString("suspension_reason").ifBlank { null }
                    val econSuspendedUntil = obj.optString("suspended_until").ifBlank { null }

                    val finalStatus = when {
                        profileStatus.equals("BANNED", ignoreCase = true) || econStatus.equals("BANNED", ignoreCase = true) -> "BANNED"
                        profileStatus.equals("SUSPENDED", ignoreCase = true) || econStatus.equals("SUSPENDED", ignoreCase = true) -> "SUSPENDED"
                        else -> "ACTIVE"
                    }
                    val finalReason = profileSuspensionReason ?: econSuspensionReason
                    val finalUntil = profileSuspendedUntil ?: econSuspendedUntil

                    val isSuspensionExpired = if (finalStatus == "SUSPENDED" && !finalUntil.isNullOrBlank()) {
                        try {
                            java.time.Instant.now().isAfter(java.time.Instant.parse(finalUntil))
                        } catch (_: Exception) { false }
                    } else false

                    val activeAccountStatus = if (isSuspensionExpired) "ACTIVE" else finalStatus
                    authPreferences.setAccountRestriction(activeAccountStatus, finalReason, finalUntil)

                    val lastMinedDate = obj.optString("last_mined_date").ifBlank { null }
                    val artifactsJson = obj.optJSONArray("artifacts")
                    val giftedList = mutableListOf<String>()
                    if (artifactsJson != null) {
                        for (i in 0 until artifactsJson.length()) {
                            val artItem = artifactsJson.optJSONObject(i)
                            val rawName = if (artItem != null) {
                                artItem.optString("id").ifBlank { artItem.optString("name") }
                            } else {
                                artifactsJson.opt(i) as? String ?: ""
                            }
                            if (rawName.isNotBlank()) {
                                val resolved = EconomyCatalog.findItem(rawName)
                                if (resolved != null) {
                                    giftedList.add(resolved.name)
                                    unlockedSet.add(resolved.id)
                                } else {
                                    giftedList.add(rawName)
                                }
                            }
                        }
                    }

                    val profile = EconomyProfile(
                        userId = effectiveId,
                        tier = UserEconomyTier.entries.find { it.name.equals(tierStr, true) } ?: UserEconomyTier.FREE,
                        rank = IdentityRank.entries.find { it.name.equals(rankStr, true) } ?: IdentityRank.LUMEN,
                        coinBalance = balance,
                        lifetimeEarned = earned,
                        lifetimeSpent = spent,
                        activeFrame = activeFrame,
                        activeArtifact = activeArtifact,
                        activeTheme = activeTheme,
                        lastMinedDateString = lastMinedDate,
                        unlockedItemIds = unlockedSet,
                        status = activeAccountStatus,
                        suspensionReason = finalReason,
                        suspendedUntil = finalUntil,
                        giftedArtifacts = giftedList
                    )
                    return@withContext EconomyResult.Success(profile)
                }
            }

            EconomyResult.Error("Cloud wallet record is missing after initialization. Please retry.")
        } catch (e: Exception) {
            val cachedStatus = authPreferences.accountStatus
            val isSuspensionExpired = if (cachedStatus == "SUSPENDED" && !authPreferences.suspendedUntil.isNullOrBlank()) {
                try {
                    java.time.Instant.now().isAfter(java.time.Instant.parse(authPreferences.suspendedUntil))
                } catch (_: Exception) { false }
            } else false

            val finalOfflineStatus = if (isSuspensionExpired) "ACTIVE" else cachedStatus

            val cachedProfile = EconomyProfile(
                userId = effectiveId,
                tier = UserEconomyTier.FREE,
                rank = IdentityRank.LUMEN,
                coinBalance = 0L,
                lifetimeEarned = 0L,
                status = finalOfflineStatus,
                suspensionReason = authPreferences.suspensionReason,
                suspendedUntil = authPreferences.suspendedUntil
            )
            EconomyResult.Success(cachedProfile, "Operating offline")
        }
    }

    suspend fun reconcilePendingOfflineClaims(
        userId: String,
        currentProfile: EconomyProfile
    ): EconomyResult<EconomyProfile> = withContext(Dispatchers.IO) {
        if (!authPreferences.isPendingReconciliation) {
            return@withContext fetchVerifiedProfile(userId)
        }

        val effectiveId = userId.ifBlank { authPreferences.effectiveUserId }
        val pendingDate = authPreferences.pendingOfflineClaimDate ?: java.time.LocalDate.now().toString()
        val pendingReward = authPreferences.pendingOfflineClaimAmount

        val token = SupabaseSessionManager.validAccessToken(authPreferences)
            ?: return@withContext EconomyResult.Error("Sign in to sync your daily reward.")

        try {
            val rpcPayload = JSONObject().apply {
                put("p_user_id", effectiveId)
                put("p_base_reward", pendingReward)
                put("p_timezone_name", java.time.ZoneId.systemDefault().id)
            }

            val rpcRequest = Request.Builder()
                .url("${SupabaseConfig.restUrl}/rpc/verify_and_mine_coins")
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(rpcPayload.toString().toRequestBody(jsonMediaType))
                .build()

            val rpcResponse = client.newCall(rpcRequest).execute()
            if (rpcResponse.isSuccessful) {
                val body = rpcResponse.body?.string().orEmpty()
                if (body.isNotBlank()) {
                    val resObj = JSONObject(body)
                    if (resObj.optBoolean("success", false)) {
                        authPreferences.clearPendingOfflineClaim()
                        val newBalance = resObj.optLong("new_balance", currentProfile.coinBalance)
                        val newLifetime = resObj.optLong("lifetime_earned", currentProfile.lifetimeEarned)
                        val rankStr = resObj.optString("new_rank", currentProfile.rank.name)
                        val newRank = IdentityRank.entries.find { it.name.equals(rankStr, true) }
                            ?: calculateRankForLifetimeCoins(newLifetime)

                        val reconciled = currentProfile.copy(
                            coinBalance = newBalance,
                            lifetimeEarned = newLifetime,
                            rank = newRank,
                            lastMinedTimestamp = System.currentTimeMillis(),
                            lastMinedDateString = resObj.optString("mined_date", pendingDate)
                        )
                        return@withContext EconomyResult.Success(reconciled, "Your offline daily reward is ready.")
                    } else {
                        authPreferences.clearPendingOfflineClaim()
                        val verifiedCloud = fetchVerifiedProfile(effectiveId)
                        val rolledBackProfile = if (verifiedCloud is EconomyResult.Success) verifiedCloud.data else currentProfile
                        return@withContext EconomyResult.Success(
                            rolledBackProfile,
                            "Your daily reward was already recorded."
                        )
                    }
                }
            }

            fetchVerifiedProfile(effectiveId)
        } catch (e: Exception) {
            EconomyResult.Success(currentProfile, "Your reward will sync when you're back online.")
        }
    }

    suspend fun verifyAndMineCoins(
        userId: String,
        currentProfile: EconomyProfile,
        baseReward: Long = 30L
    ): EconomyResult<EconomyProfile> = withContext(Dispatchers.IO) {
        val effectiveId = userId.ifBlank { authPreferences.effectiveUserId }
        val todayStr = java.time.LocalDate.now().toString()

        if (currentProfile.lastMinedDateString == todayStr || authPreferences.pendingOfflineClaimDate == todayStr) {
            return@withContext EconomyResult.Error("You've already claimed today's reward. Next claim unlocks at midnight.")
        }

        if (authPreferences.isPendingReconciliation && authPreferences.pendingOfflineClaimDate != todayStr) {
            return@withContext EconomyResult.Error("Connect to the internet to sync your previous offline reward first.")
        }

        val token = SupabaseSessionManager.validAccessToken(authPreferences)
            ?: return@withContext EconomyResult.Error("Sign in to claim your daily reward.")

        try {
            val rpcPayload = JSONObject().apply {
                put("p_user_id", effectiveId)
                put("p_base_reward", baseReward)
                put("p_timezone_name", java.time.ZoneId.systemDefault().id)
            }

            val rpcRequest = Request.Builder()
                .url("${SupabaseConfig.restUrl}/rpc/verify_and_mine_coins")
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(rpcPayload.toString().toRequestBody(jsonMediaType))
                .build()

            val rpcResponse = try {
                client.newCall(rpcRequest).execute()
            } catch (e: Exception) {
                null
            }

            if (rpcResponse != null && rpcResponse.isSuccessful) {
                val body = rpcResponse.body?.string().orEmpty()
                if (body.isNotBlank()) {
                    val resObj = JSONObject(body)
                    if (resObj.optBoolean("success", false)) {
                        authPreferences.clearPendingOfflineClaim()
                        val newBalance = resObj.optLong("new_balance", currentProfile.coinBalance)
                        val newLifetime = resObj.optLong("lifetime_earned", currentProfile.lifetimeEarned)
                        val rankStr = resObj.optString("new_rank", currentProfile.rank.name)
                        val newRank = IdentityRank.entries.find { it.name.equals(rankStr, true) }
                            ?: calculateRankForLifetimeCoins(newLifetime)
                        val minedDate = resObj.optString("mined_date", todayStr)
                        val awarded = resObj.optLong("awarded_coins", (baseReward * currentProfile.tier.multiplier).toLong())
                        val msg = UserFacingServerMessage.sanitize(
                            resObj.optString("message"),
                            "+$awarded Cycle Coins added to your collection!"
                        )

                        val updated = currentProfile.copy(
                            coinBalance = newBalance,
                            lifetimeEarned = newLifetime,
                            rank = newRank,
                            lastMinedTimestamp = System.currentTimeMillis(),
                            lastMinedDateString = minedDate
                        )
                        return@withContext EconomyResult.Success(updated, msg)
                    } else {
                        val errMsg = UserFacingServerMessage.sanitize(
                            resObj.optString("message"),
                            "Your daily reward isn't available right now. Please try again later."
                        )
                        return@withContext EconomyResult.Error(errMsg)
                    }
                }
            }

            if (rpcResponse != null) {
                val detail = rpcResponse.body?.string()?.takeIf { it.isNotBlank() }?.let { body ->
                    runCatching { JSONObject(body).optString("message") }.getOrNull()
                }
                return@withContext EconomyResult.Error(
                    UserFacingServerMessage.sanitize(detail, "Your daily reward couldn't be confirmed. Please try again.")
                )
            }

            val multiplier = currentProfile.tier.multiplier
            val awardedCoins = (baseReward * multiplier).toLong()
            val updatedBalance = currentProfile.coinBalance + awardedCoins
            val updatedEarned = currentProfile.lifetimeEarned + awardedCoins
            val newRank = calculateRankForLifetimeCoins(updatedEarned)

            authPreferences.recordOfflineClaim(todayStr, baseReward)

            val updated = currentProfile.copy(
                coinBalance = updatedBalance,
                lifetimeEarned = updatedEarned,
                rank = newRank,
                lastMinedTimestamp = System.currentTimeMillis(),
                lastMinedDateString = todayStr
            )

            EconomyResult.Success(
                updated,
                "+$awardedCoins Cycle Coins (${currentProfile.tier.multiplier}× ${currentProfile.tier.title}). Will sync when online."
            )
        } catch (e: Exception) {
            EconomyResult.Error("Mining verification delayed. Please check network.")
        }
    }

    suspend fun transferCoins(
        currentProfile: EconomyProfile,
        recipientEmail: String,
        amount: Long
    ): EconomyResult<EconomyProfile> = withContext(Dispatchers.IO) {
        try {
            if (amount <= 0) {
                return@withContext EconomyResult.Error("Enter a valid transfer amount.")
            }
            if (currentProfile.coinBalance < amount) {
                return@withContext EconomyResult.Error("Insufficient Cycle Coins balance.")
            }
            if (recipientEmail.trim().equals(authPreferences.userEmail, ignoreCase = true)) {
                return@withContext EconomyResult.Error("Cannot transfer coins to your own account.")
            }

            val token = SupabaseSessionManager.validAccessToken(authPreferences)
                ?: return@withContext EconomyResult.Error("Sign in to transfer Cycle Coins securely.")
            val rpcPayload = JSONObject().apply {
                put("p_sender_id", currentProfile.userId.ifBlank { authPreferences.userId ?: "local_user" })
                put("p_recipient_email", recipientEmail.trim().lowercase())
                put("p_amount", amount)
            }

            val rpcRequest = Request.Builder()
                .url("${SupabaseConfig.restUrl}/rpc/transfer_coins_atomic")
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(rpcPayload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = try {
                client.newCall(rpcRequest).execute()
            } catch (e: Exception) {
                null
            }

            if (response != null && response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                if (body.isNotBlank()) {
                    val resObj = JSONObject(body)
                    if (resObj.optBoolean("success", false)) {
                        val remBalance = resObj.optLong("remaining_balance", currentProfile.coinBalance - amount)
                        val updated = currentProfile.copy(
                            coinBalance = remBalance,
                            lifetimeSpent = currentProfile.lifetimeSpent + amount
                        )
                        return@withContext EconomyResult.Success(
                            updated,
                            UserFacingServerMessage.sanitize(
                                resObj.optString("message"),
                                "Sent $amount Cycle Coins to $recipientEmail."
                            )
                        )
                    } else {
                        return@withContext EconomyResult.Error(
                            UserFacingServerMessage.sanitize(
                                resObj.optString("message"),
                                "Your transfer couldn't be completed. Please try again."
                            )
                        )
                    }
                }
            }

            val errMsg = response?.body?.string()
            EconomyResult.Error(
                UserFacingServerMessage.sanitize(errMsg, "Your transfer couldn't be verified. Please try again.")
            )
        } catch (e: Exception) {
            EconomyResult.Error("Transfer could not be verified. Please try again.")
        }
    }

    suspend fun purchaseArtifact(
        currentProfile: EconomyProfile,
        item: EconomyItem
    ): EconomyResult<EconomyProfile> = withContext(Dispatchers.IO) {
        try {
            if (currentProfile.unlockedItemIds.contains(item.id)) {
                return@withContext EconomyResult.Error("${item.name} is already in your collection.")
            }
            val token = SupabaseSessionManager.validAccessToken(authPreferences)
                ?: return@withContext EconomyResult.Error("Sign in to unlock items from the shared vault.")
            val payload = JSONObject().put("p_item_id", item.id)
            val request = Request.Builder()
                .url("${SupabaseConfig.restUrl}/rpc/purchase_economy_item")
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer ${token.trim()}")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) {
                return@withContext EconomyResult.Error("Could not verify this unlock. No coins were deducted.")
            }
            val result = JSONObject(body)
            if (!result.optBoolean("success", false)) {
                return@withContext EconomyResult.Error(
                    UserFacingServerMessage.sanitize(result.optString("message"), "This item couldn't be unlocked. Please try again.")
                )
            }
            when (val refreshed = fetchVerifiedProfile(currentProfile.userId)) {
                is EconomyResult.Success -> EconomyResult.Success(refreshed.data, "Unlocked ${item.name}!")
                is EconomyResult.Error -> EconomyResult.Error(refreshed.errorMessage)
            }
        } catch (e: Exception) {
            EconomyResult.Error("Could not complete unlock. Please try again.")
        }
    }

    suspend fun purchaseCoinPackage(
        currentProfile: EconomyProfile,
        pkg: CoinPurchasePackage
    ): EconomyResult<EconomyProfile> = withContext(Dispatchers.IO) {
        when (val refreshed = fetchVerifiedProfile(currentProfile.userId)) {
            is EconomyResult.Success -> {
                val expectedMinimum = currentProfile.coinBalance + pkg.coins + pkg.bonusCoins
                if (refreshed.data.coinBalance >= expectedMinimum) {
                    EconomyResult.Success(refreshed.data, "${pkg.title} credit verified in your cloud balance.")
                } else {
                    EconomyResult.Error("Coin packages are credited after payment verification.")
                }
            }
            is EconomyResult.Error -> EconomyResult.Error(refreshed.errorMessage)
        }
    }

    suspend fun equipItem(
        currentProfile: EconomyProfile,
        item: EconomyItem
    ): EconomyResult<EconomyProfile> = withContext(Dispatchers.IO) {
        val token = SupabaseSessionManager.validAccessToken(authPreferences)
            ?: return@withContext EconomyResult.Error("Sign in to equip items in the shared vault.")
        try {
            val payload = JSONObject().put("p_item_id", item.id)
            val request = Request.Builder()
                .url("${SupabaseConfig.restUrl}/rpc/equip_economy_item")
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer ${token.trim()}")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) {
                return@withContext EconomyResult.Error("The server could not verify this item.")
            }
            val result = JSONObject(body)
            if (!result.optBoolean("success", false)) {
                return@withContext EconomyResult.Error(
                    UserFacingServerMessage.sanitize(result.optString("message"), "This item couldn't be equipped. Please try again.")
                )
            }
            when (val refreshed = fetchVerifiedProfile(currentProfile.userId)) {
                is EconomyResult.Success -> EconomyResult.Success(refreshed.data, "${item.name} is now active.")
                is EconomyResult.Error -> EconomyResult.Error(refreshed.errorMessage)
            }
        } catch (_: Exception) {
            EconomyResult.Error("Could not equip this item while offline.")
        }
    }

    suspend fun upgradeTier(
        currentProfile: EconomyProfile,
        newTier: UserEconomyTier
    ): EconomyResult<EconomyProfile> = withContext(Dispatchers.IO) {
        EconomyResult.Error("Top up coins in the Coin Store to unlock ${newTier.title} perks and artifacts.")
    }

    private fun calculateRankForLifetimeCoins(lifetimeCoins: Long): IdentityRank {
        return when {
            lifetimeCoins >= IdentityRank.FOUNDER.minLifetimeCoins -> IdentityRank.FOUNDER
            lifetimeCoins >= IdentityRank.NOCTURNE.minLifetimeCoins -> IdentityRank.NOCTURNE
            lifetimeCoins >= IdentityRank.SOLARA.minLifetimeCoins -> IdentityRank.SOLARA
            lifetimeCoins >= IdentityRank.CELESTIA.minLifetimeCoins -> IdentityRank.CELESTIA
            lifetimeCoins >= IdentityRank.AURELIA.minLifetimeCoins -> IdentityRank.AURELIA
            else -> IdentityRank.LUMEN
        }
    }
}

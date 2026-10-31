package com.cyclecare.app.data.economy

import com.cyclecare.app.data.supabase.SupabaseConfig
import com.cyclecare.app.data.supabase.SupabaseSessionManager
import com.cyclecare.app.data.supabase.UserFacingServerMessage
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.CancellationException
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
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
    private val restUrl: String = SupabaseConfig.restUrl,
    private val anonKey: String = SupabaseConfig.anonKey
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
        val VALID_ACCOUNT_STATUSES = setOf("ACTIVE", "SUSPENDED", "BANNED")
    }
    private data class OwnerSnapshot(val owner: String, val session: Long)
    private class Rejected(message: String) : Exception(message)

    private fun snapshot(): OwnerSnapshot? {
        val owner = authPreferences.userId?.trim().orEmpty()
        return owner.takeIf { it.isNotEmpty() }?.let { OwnerSnapshot(it, authPreferences.sessionGeneration) }
    }

    private fun stillOwns(snapshot: OwnerSnapshot): Boolean =
        authPreferences.userId?.trim() == snapshot.owner && authPreferences.sessionGeneration == snapshot.session

    private fun boundedBody(response: okhttp3.Response): String? {
        val source = response.body?.source() ?: return ""
        source.request(MAX_RESPONSE_BYTES.toLong() + 1)
        if (source.buffer.size > MAX_RESPONSE_BYTES) return null
        return source.readByteArray().toString(Charsets.UTF_8)
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Edge verification: sync and fetch verified economy state from Supabase.
     */
    /** Minimal account presence only. Never restores health fields or grants backup consent. */
    suspend fun hasRemoteProfile(): Boolean? = withContext(Dispatchers.IO) {
        val initial = snapshot() ?: return@withContext null
        try {
            val token = SupabaseSessionManager.validAccessToken(authPreferences) ?: return@withContext null
            val request = Request.Builder().url("$restUrl/rpc/my_account_start_state")
                .header("apikey", anonKey).header("Authorization", "Bearer $token").post("{}".toRequestBody(jsonMediaType)).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || !stillOwns(initial)) return@withContext null
                val state = JSONObject(boundedBody(response) ?: return@withContext null)
                if (state.optString("user_id") != initial.owner || !state.has("exists")) null else state.getBoolean("exists")
            }
        } catch (_: Exception) { null }
    }

    suspend fun fetchVerifiedProfile(userId: String): EconomyResult<EconomyProfile> = withContext(Dispatchers.IO) {
        val initial = snapshot()
            ?: return@withContext EconomyResult.Error("A secure CycleCare session is required to load your cloud wallet.")
        val effectiveId = userId.ifBlank { initial.owner }
        if (effectiveId != initial.owner) {
            return@withContext EconomyResult.Error("Your cloud wallet does not belong to this session.")
        }
        try {
            val accessToken = SupabaseSessionManager.validAccessToken(authPreferences)
                ?: return@withContext EconomyResult.Error("A secure CycleCare session is required to load your cloud wallet.")
            if (!stillOwns(initial)) throw Rejected("Session changed while loading wallet")
            val token = accessToken.trim()

            if (!stillOwns(initial)) throw Rejected("Session changed before wallet setup")
            val ensureRequest = Request.Builder()
                .url("$restUrl/rpc/ensure_my_economy_profile")
                .header("apikey", anonKey).header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json").post("{}".toRequestBody(jsonMediaType)).build()
            client.newCall(ensureRequest).execute().use { response ->
                val body = boundedBody(response) ?: throw Rejected("Wallet setup response was too large")
                if (!stillOwns(initial)) throw Rejected("Session changed while verifying wallet")
                if (!response.isSuccessful || !runCatching { JSONObject(body).optBoolean("success", false) }.getOrDefault(false))
                    throw Rejected("Cloud wallet setup could not be verified")
            }

            if (!stillOwns(initial)) throw Rejected("Session changed before wallet read")
            val request = Request.Builder().url("$restUrl/economy_profiles?user_id=eq.${java.net.URLEncoder.encode(effectiveId, "UTF-8")}&select=*")
                .header("apikey", anonKey).header("Authorization", "Bearer $token").get().build()
            val body = client.newCall(request).execute().use { response ->
                val raw = boundedBody(response) ?: throw Rejected("Cloud wallet response was too large")
                if (!stillOwns(initial)) throw Rejected("Session changed while loading wallet")
                if (!response.isSuccessful) throw Rejected("Your balance could not be refreshed right now")
                raw
            }
            val array = runCatching { JSONArray(body) }.getOrElse { throw Rejected("Cloud wallet response was malformed") }
            if (array.length() != 1) throw Rejected("Cloud wallet record is missing or ambiguous")
            val obj = array.optJSONObject(0) ?: throw Rejected("Cloud wallet record was malformed")
            if (obj.optString("user_id").trim() != initial.owner) throw Rejected("Cloud wallet owner mismatch")
            val econStatus = obj.optString("status").trim().uppercase()
            if (econStatus !in VALID_ACCOUNT_STATUSES) throw Rejected("Cloud wallet account status could not be verified")
            fun verifiedCounter(field: String): Long {
                val value = obj.opt(field)
                if (value !is Int && value !is Long) throw Rejected("Cloud wallet $field was not an exact integer")
                return (value as Number).toLong().takeIf { it >= 0L } ?: throw Rejected("Cloud wallet $field was negative")
            }
            val balance = verifiedCounter("coin_balance")
            val earned = verifiedCounter("lifetime_earned")
            val spent = verifiedCounter("lifetime_spent")

            if (!stillOwns(initial)) throw Rejected("Session changed before account read")
            val profReq = Request.Builder().url("$restUrl/rpc/my_account_start_state")
                .header("apikey", anonKey).header("Authorization", "Bearer $token").post("{}".toRequestBody(jsonMediaType)).build()
            val profBody = client.newCall(profReq).execute().use { response ->
                val raw = boundedBody(response) ?: throw Rejected("Account status response was too large")
                if (!stillOwns(initial)) throw Rejected("Session changed while checking account")
                if (!response.isSuccessful) throw Rejected("Your account status couldn't be checked")
                raw
            }
            val pObj = runCatching { JSONObject(profBody) }.getOrElse { throw Rejected("Account status response was malformed") }
            if (pObj.optString("user_id").trim() != initial.owner) throw Rejected("Account status owner mismatch")
            val profileStatus = pObj.optString("status").trim().uppercase()
            if (profileStatus !in VALID_ACCOUNT_STATUSES) throw Rejected("Account status could not be verified")
            if (!stillOwns(initial)) throw Rejected("Session changed before wallet acceptance")

            val tierStr = obj.optString("tier", "FREE")
            val rankStr = obj.optString("rank", "LUMEN")
            val unlockedSet = mutableSetOf("cycle_coin")
            obj.optJSONArray("unlocked_items")?.let { list -> for (i in 0 until list.length()) {
                val rawItem = when (val value = list.opt(i)) { is String -> value; is JSONObject -> value.optString("id").ifBlank { value.optString("name") }; else -> "" }
                if (rawItem.isNotBlank()) unlockedSet.add(EconomyCatalog.findItem(rawItem)?.id ?: rawItem)
            } }
            fun until(record: JSONObject) = (record.opt("suspended_until") as? String)?.takeIf { it.isNotBlank() }
            fun effectiveStatus(record: JSONObject): String {
                val status = record.getString("status").trim().uppercase()
                val ended = until(record)?.let { runCatching { java.time.Instant.now().isAfter(java.time.Instant.parse(it)) }.getOrDefault(false) } ?: false
                return if (status == "SUSPENDED" && ended) "ACTIVE" else status
            }
            val restrictions = listOf(pObj, obj).filter { effectiveStatus(it) != "ACTIVE" }
            val activeStatus = when {
                restrictions.any { effectiveStatus(it) == "BANNED" } -> "BANNED"
                restrictions.isNotEmpty() -> "SUSPENDED"
                else -> "ACTIVE"
            }
            val strongest = restrictions.filter { effectiveStatus(it) == activeStatus }
            val finalReason = strongest.mapNotNull { it.opt("suspension_reason") as? String }.firstOrNull { it.isNotBlank() }
            val expiries = strongest.map { record -> until(record)?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() } }
            // One expired suspension must never lift the other source's indefinite or later restriction.
            val finalUntil = if (activeStatus == "SUSPENDED" && expiries.isNotEmpty() && expiries.none { it == null })
                expiries.filterNotNull().maxOrNull()?.toString() else null
            val gifted = mutableListOf<String>()
            obj.optJSONArray("artifacts")?.let { list -> for (i in 0 until list.length()) { val a = list.optJSONObject(i); val raw = a?.optString("id").orEmpty().ifBlank { a?.optString("name").orEmpty() }; if (raw.isNotBlank()) { EconomyCatalog.findItem(raw)?.let { gifted += it.name; unlockedSet += it.id } ?: gifted.add(raw) } } }
            authPreferences.withCurrentSession(initial.owner, initial.session) {
                authPreferences.setAccountRestriction(activeStatus, finalReason, finalUntil)
                EconomyResult.Success(EconomyProfile(initial.owner, UserEconomyTier.entries.find { it.name.equals(tierStr, true) } ?: UserEconomyTier.FREE, IdentityRank.entries.find { it.name.equals(rankStr, true) } ?: IdentityRank.LUMEN, balance, earned, spent, obj.optString("active_frame").ifBlank { null }, obj.optString("active_artifact", "cycle_coin"), null, obj.optString("active_avatar_art").ifBlank { null }, obj.optString("active_theme", "SOOTHING_ROSE"), 0L, obj.optString("last_mined_date").ifBlank { null }, unlockedSet, activeStatus, finalReason, finalUntil, gifted))
            } ?: throw Rejected("Session changed before wallet acceptance")
        } catch (e: CancellationException) { throw e
        } catch (e: Rejected) { EconomyResult.Error("${e.message}. Please retry before using the shared vault.")
        } catch (_: Exception) {
            EconomyResult.Error("Your wallet could not be verified. Refresh it before spending or retrying a payment.")
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

        val token = SupabaseSessionManager.validAccessToken(authPreferences)
            ?: return@withContext EconomyResult.Error("Sign in to sync your daily reward.")

        try {
            // The reward amount is fixed server-side; the client sends no amount.
            val rpcPayload = JSONObject().apply {
                put("p_user_id", effectiveId)
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
            // The reward amount is fixed server-side; the client sends no amount.
            val rpcPayload = JSONObject().apply {
                put("p_user_id", effectiveId)
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


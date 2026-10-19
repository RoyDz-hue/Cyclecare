package com.example.data.supabase

import com.example.data.local.CycleCareDatabase
import com.example.data.local.CycleDayLog
import com.example.data.local.CycleSettings
import com.example.data.local.OnboardingProfile
import com.example.data.local.OwnerDataGate
import com.example.data.local.OwnerScopedDatabaseProvider
import com.example.security.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class SupabaseSyncService(
    private val legacyDatabase: CycleCareDatabase,
    private val authPreferences: AuthPreferences,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val databaseProvider: OwnerScopedDatabaseProvider =
        OwnerScopedDatabaseProvider(authPreferences.context, authPreferences)
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    // Release gate: full-field codecs alone do not provide safe two-device conflict handling.
    // Keep health transfer off until the forward schema AND revision/tombstone protocol are validated.
    private fun conflictSafeProtocolReleased(): Boolean = false

    /** Never fall back to a legacy handle: it may belong to the account that was active before a switch. */
    private fun currentDatabase(): CycleCareDatabase {
        return databaseProvider.current()
    }

    suspend fun syncAll(): SyncResult = withContext(Dispatchers.IO) {
        val ownerAtStart = authPreferences.userId
        if (OwnerDataGate.isWiped(authPreferences.context, ownerAtStart)) {
            return@withContext SyncResult(true, "Local data was wiped; cloud sync is paused until explicitly resumed.", timestamp = System.currentTimeMillis())
        }
        // Consent is enforced here (and in pullRemoteUpdates), not only by UI/worker callers.
        // A denied/revoked consent must perform no health upload or download.
        if (!authPreferences.isCloudSyncAllowed) {
            authPreferences.lastSyncSuccess = false
            val message = "Cloud health sync is disabled. Local data remains on this device."
            authPreferences.lastSyncMessage = message
            return@withContext SyncResult(
                isSuccess = true, message = message, timestamp = System.currentTimeMillis()
            )
        }
        if (!conflictSafeProtocolReleased()) return@withContext SyncResult(
            isSuccess = false,
            message = "Cloud health sync is paused until the conflict-safe backend protocol is ready. Your records remain on this device.",
            timestamp = System.currentTimeMillis()
        )
        try {
            val token = when (val tokenState = SupabaseSessionManager.resolveAccessToken(authPreferences)) {
                is AccessTokenResolution.Valid -> tokenState.token
                AccessTokenResolution.Missing, AccessTokenResolution.ReauthenticationRequired -> {
                    val message = "A secure CycleCare session is required to sync. Reconnect and sign in again."
                    authPreferences.lastSyncMessage = message
                    authPreferences.lastSyncSuccess = false
                    return@withContext SyncResult(
                        isSuccess = false,
                        message = message,
                        timestamp = System.currentTimeMillis(),
                        requiresAuthentication = true
                    )
                }
                AccessTokenResolution.TemporarilyUnavailable -> {
                    val message = "The secure session could not be refreshed because of a temporary connection problem. Retry when connected."
                    authPreferences.lastSyncMessage = message
                    authPreferences.lastSyncSuccess = false
                    return@withContext SyncResult(
                        isSuccess = false,
                        message = message,
                        timestamp = System.currentTimeMillis(),
                        shouldRetry = true
                    )
                }
            }
            var settings = currentDatabase().cycleDao().getSettings() ?: CycleSettings()
            var profile = currentDatabase().onboardingDao().getProfile()
            var logs = currentDatabase().cycleDao().getAllLogsList()

            val effectiveUserId = authPreferences.effectiveUserId
            val deviceId = authPreferences.deviceId
            val authMode = authPreferences.accountMode

            // Restore the account's existing profile before uploading anything from
            // a new device. This prevents empty local defaults from replacing it.
            if (!isOwnerStillCurrent(ownerAtStart)) return@withContext staleOwnerResult()
            val pulledCount = pullRemoteUpdatesInternal(effectiveUserId, token)
            if (!isOwnerStillCurrent(ownerAtStart)) return@withContext staleOwnerResult()
            settings = currentDatabase().cycleDao().getSettings() ?: settings
            profile = currentDatabase().onboardingDao().getProfile()
            logs = currentDatabase().cycleDao().getAllLogsList()

            var profileSynced = false
            var cyclesSynced = 0
            var logsSynced = 0

            // Create the remote profile on first auth so the app-scoped device ID
            // is bound to this guest/email identity before onboarding is complete.
            if (!isOwnerStillCurrent(ownerAtStart) || !authPreferences.isCloudSyncAllowed) return@withContext SyncResult(
                isSuccess = true,
                message = "Cloud health sync revoked before upload.",
                timestamp = System.currentTimeMillis()
            )
            profileSynced = syncProfile(
                profile = profile,
                settings = settings,
                userId = effectiveUserId,
                deviceId = deviceId,
                accountMode = authMode,
                token = token
            )
            if (!profileSynced) {
                val message = "We couldn't sync your profile right now. Your local data is safe; please try again."
                authPreferences.lastSyncMessage = message
                authPreferences.lastSyncSuccess = false
                return@withContext SyncResult(
                    isSuccess = false,
                    message = message,
                    timestamp = System.currentTimeMillis()
                )
            }

            // 2. Sync Cycle Record
            if (settings.lastPeriodStartDate.isNotBlank()) {
                if (!isOwnerStillCurrent(ownerAtStart) || !authPreferences.isCloudSyncAllowed) return@withContext SyncResult(
                    isSuccess = true,
                    message = "Cloud health sync revoked before upload.",
                    timestamp = System.currentTimeMillis()
                )
                syncCycle(
                    settings = settings,
                    userId = effectiveUserId,
                    token = token
                )
                cyclesSynced = 1
            }

            // All profile, cycle, and daily-log payloads are health data and are consent-gated above.
            if (logs.isNotEmpty()) {
                if (!isOwnerStillCurrent(ownerAtStart) || !authPreferences.isCloudSyncAllowed) return@withContext SyncResult(
                    isSuccess = true,
                    message = "Cloud health sync revoked before upload.",
                    timestamp = System.currentTimeMillis()
                )
                logsSynced = syncDailyLogs(logs, effectiveUserId, token)
            }

            val timestamp = System.currentTimeMillis()
            val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
            val summaryMsg = if (pulledCount > 0) {
                "Synced successfully at $timeStr ($logsSynced uploaded, $pulledCount pulled from cloud)"
            } else {
                "Synced successfully at $timeStr ($logsSynced logs, $cyclesSynced cycle, profile updated)"
            }

            authPreferences.lastSyncTimestamp = timestamp
            authPreferences.lastSyncMessage = summaryMsg
            authPreferences.lastSyncSuccess = true

            SyncResult(
                isSuccess = true,
                message = summaryMsg,
                syncedCyclesCount = cyclesSynced,
                syncedLogsCount = logsSynced,
                profileSynced = profileSynced,
                timestamp = timestamp
            )
        } catch (e: SyncFailureException) {
            val errMsg = if (e.requiresAuthentication) {
                "Your session needs a quick refresh. Sign in again before syncing private data."
            } else {
                "Sync couldn't finish. Your local data is safe. Please try again when connected."
            }
            authPreferences.lastSyncMessage = errMsg
            authPreferences.lastSyncSuccess = false

            SyncResult(
                isSuccess = false,
                message = errMsg,
                timestamp = System.currentTimeMillis(),
                shouldRetry = e.shouldRetry,
                requiresAuthentication = e.requiresAuthentication
            )
        } catch (e: Exception) {
            val errMsg = if (e is IOException) {
                "Network interruption prevented cloud sync. Your local data is safe; retry when connected."
            } else {
                "Cloud sync could not be completed. Your local data is safe; review the connection and try again."
            }
            authPreferences.lastSyncMessage = errMsg
            authPreferences.lastSyncSuccess = false
            SyncResult(
                isSuccess = false,
                message = errMsg,
                timestamp = System.currentTimeMillis(),
                shouldRetry = e is IOException
            )
        }
    }

    private fun syncProfile(
        profile: OnboardingProfile?,
        settings: CycleSettings,
        userId: String,
        deviceId: String,
        accountMode: String,
        token: String
    ): Boolean {
        val json = JSONObject().apply {
            put("p_device_id", deviceId)
            put("p_account_mode", accountMode)
            put("p_goals", profile?.selectedGoalsCsv ?: settings.selectedGoalsCsv)
            val lastDate = profile?.lastPeriodDateString?.ifBlank { settings.lastPeriodStartDate }
            put("p_last_period_date", lastDate ?: JSONObject.NULL)
            put("p_cycle_length", profile?.cycleLengthDays ?: settings.averageCycleLength)
            put("p_period_duration", profile?.periodDurationDays ?: settings.periodDuration)
            put("p_typical_symptoms", profile?.typicalSymptomsCsv ?: "")
            put("p_typical_energy", profile?.typicalEnergy ?: "")
            put("p_typical_sleep", profile?.typicalSleepQuality ?: "")
        }

        val request = Request.Builder()
            .url("${SupabaseConfig.restUrl}/rpc/sync_my_profile")
            .header("apikey", SupabaseConfig.anonKey)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw syncFailure("profile/device handshake", response.code)
            if (!runCatching { JSONObject(body).optBoolean("success", false) }.getOrDefault(false)) {
                throw syncFailure("profile/device handshake", response.code)
            }
        }
        return true
    }

    private fun syncCycle(settings: CycleSettings, userId: String, token: String) {
        val cycleId = UUID.nameUUIDFromBytes("$userId:${settings.lastPeriodStartDate}".toByteArray()).toString()
        val json = JSONObject().apply {
            put("id", cycleId)
            put("user_id", userId)
            put("start_date", settings.lastPeriodStartDate)
            // Do not fabricate an end date from duration; absence is safer than an
            // estimated date in the canonical period_end_date field.
            put("cycle_length", settings.averageCycleLength)
            put("period_length", settings.periodDuration)
            put("notes", "Synced from CycleCare mobile client")
        }

        val request = Request.Builder()
            .url("${SupabaseConfig.restUrl}/cycles?on_conflict=id")
            .header("apikey", SupabaseConfig.anonKey)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Prefer", "resolution=merge-duplicates")
            .post(json.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw syncFailure("cycle upload", response.code)
        }
    }

    private fun syncDailyLogs(logs: List<CycleDayLog>, userId: String, token: String): Int {
        val array = JSONArray()
        for (log in logs) {
            val logId = UUID.nameUUIDFromBytes("$userId:${log.dateString}".toByteArray()).toString()
            val obj = JSONObject().apply {
                put("schema_version", 2)
                put("id", logId)
                put("user_id", userId)
                put("date", log.dateString)
                put("flow", log.flowLevel)
                put("energy", log.energyLevel)
                put("pain", if (log.painIntensity > 0) "${log.painIntensity}/10" else "NONE")
                put("pain_intensity", log.painIntensity)
                put("mood", log.moodLevel)
                put("symptoms", log.symptomsCsv)
                put("vaginal_health", log.vaginalHealthCsv)
                put("urinary_health", log.urinaryHealthCsv)
                put("cervical_mucus", log.cervicalMucus)
                put("ovulation_test", log.ovulationTest)
                put("temperature", if (log.bbtCelsius > 0.0f) log.bbtCelsius.toDouble() else JSONObject.NULL)
                put("sleep_hours", if (log.sleepHours > 0.0f) log.sleepHours.toDouble() else JSONObject.NULL)
                put("sleep_quality", log.sleepQuality)
                put("water_glasses", log.waterGlasses)
                put("notes", log.notes)
                // Never infer sexual activity from free text or symptom labels.
                // The field is intentionally omitted until an explicit, consented
                // first-class observation exists in the local model and backend.
            }
            array.put(obj)
        }

        val request = Request.Builder()
            .url("${SupabaseConfig.restUrl}/daily_logs?on_conflict=id")
            .header("apikey", SupabaseConfig.anonKey)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Prefer", "resolution=merge-duplicates")
            .post(array.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw syncFailure("daily-log upload", response.code)
        }
        return logs.size
    }

    /** Pull remote profile and daily-log data; failures are surfaced to the caller. */
    suspend fun pullRemoteUpdates(userId: String): Int = withContext(Dispatchers.IO) {
        if (!authPreferences.isCloudSyncAllowed || !conflictSafeProtocolReleased()) return@withContext 0
        val token = SupabaseSessionManager.validAccessToken(authPreferences)
            ?: throw syncFailure("session validation", 401)
        pullRemoteUpdatesInternal(userId, token)
    }

    private suspend fun pullRemoteUpdatesInternal(userId: String, token: String): Int = withContext(Dispatchers.IO) {
        if (authPreferences.effectiveUserId != userId || !authPreferences.isCloudSyncAllowed) return@withContext 0
        // Defense in depth for revocation racing an in-flight worker.
        if (!authPreferences.isCloudSyncAllowed) return@withContext 0
        var pulledCount = 0

        // Pull the profile when this device has not completed onboarding.
        val profileReq = Request.Builder()
            .url("${SupabaseConfig.restUrl}/profiles?user_id=eq.$userId")
            .header("apikey", SupabaseConfig.anonKey)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        client.newCall(profileReq).execute().use { profileRes ->
            if (!profileRes.isSuccessful) throw syncFailure("profile download", profileRes.code)
            val pArray = JSONArray(profileRes.body?.string().orEmpty())
            if (pArray.length() > 0) {
                val pObj = pArray.getJSONObject(0)
                val lastDate = pObj.optString("last_period_date", "")
                val cycleLen = pObj.optInt("cycle_length", 28)
                val periodDur = pObj.optInt("period_duration", 5)
                val goals = pObj.optString("goals", "")
                val symptoms = pObj.optString("typical_symptoms", "")
                val energy = pObj.optString("typical_energy", "")
                val sleep = pObj.optString("typical_sleep", "")

                val localSettings = currentDatabase().cycleDao().getSettings() ?: CycleSettings()
                if (!localSettings.isOnboardingCompleted && (lastDate.isNotBlank() || goals.isNotBlank())) {
                    val safeCycleLength = cycleLen.takeIf { it in 21..45 } ?: 28
                    val safePeriodDuration = periodDur.takeIf { it in 2..10 } ?: 5
                    currentDatabase().cycleDao().saveSettings(
                        localSettings.copy(
                            isOnboardingCompleted = true,
                            lastPeriodStartDate = lastDate,
                            averageCycleLength = safeCycleLength,
                            periodDuration = safePeriodDuration,
                            selectedGoalsCsv = goals
                        )
                    )
                    currentDatabase().onboardingDao().insertOrUpdateProfile(
                        OnboardingProfile(
                            id = 1,
                            isCompleted = true,
                            approximatePeriodChoice = if (lastDate.isNotBlank()) "APPROXIMATE" else "UNKNOWN",
                            lastPeriodDateString = lastDate,
                            cycleLengthOption = "${safeCycleLength} days",
                            cycleLengthDays = safeCycleLength,
                            periodDurationDays = safePeriodDuration,
                            selectedGoalsCsv = goals,
                            typicalSymptomsCsv = symptoms,
                            typicalEnergy = energy,
                            typicalSleepQuality = sleep
                        )
                    )
                }
            }
        }

        // Paginate instead of silently truncating at 100 rows. This remains bounded
        // per request and stops when the server returns a short page.
        val pageSize = 100
        var offset = 0
        do {
            if (authPreferences.effectiveUserId != userId || !authPreferences.isCloudSyncAllowed) return@withContext pulledCount
            val logsReq = Request.Builder()
                .url("${SupabaseConfig.restUrl}/daily_logs?user_id=eq.$userId&order=date.desc&limit=$pageSize&offset=$offset")
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val pageCount = client.newCall(logsReq).execute().use { logsRes ->
                if (!logsRes.isSuccessful) throw syncFailure("daily-log download", logsRes.code)
                val lArray = JSONArray(logsRes.body?.string().orEmpty())
                for (i in 0 until lArray.length()) {
                    val item = lArray.getJSONObject(i)
                    val dateStr = item.optString("date", "")
                    if (authPreferences.effectiveUserId != userId || !authPreferences.isCloudSyncAllowed) return@withContext pulledCount
                    if (dateStr.isNotBlank() && currentDatabase().cycleDao().getLogByDate(dateStr) == null) {
                    val newLog = CycleDayLog(
                        dateString = dateStr,
                        flowLevel = item.optString("flow", ""),
                        energyLevel = item.optString("energy", ""),
                        moodLevel = item.optString("mood", ""),
                        symptomsCsv = item.optString("symptoms", ""),
                        vaginalHealthCsv = item.optString("vaginal_health", ""),
                        urinaryHealthCsv = item.optString("urinary_health", ""),
                        cervicalMucus = item.optString("cervical_mucus", ""),
                        ovulationTest = item.optString("ovulation_test", "NONE"),
                        notes = item.optString("notes", ""),
                        painIntensity = item.optInt("pain_intensity", parsePainString(item.optString("pain", "0"))),
                        bbtCelsius = item.optDouble("temperature", 0.0).toFloat(),
                        sleepHours = item.optDouble("sleep_hours", 0.0).toFloat(),
                        sleepQuality = item.optString("sleep_quality", ""),
                        waterGlasses = item.optInt("water_glasses", 0)
                    )
                    currentDatabase().cycleDao().insertOrUpdateLog(newLog)
                    pulledCount++
                    }
                }
                lArray.length()
            }
            offset += pageCount
        } while (pageCount == pageSize)
        pulledCount
    }

    private fun isOwnerStillCurrent(ownerAtStart: String?): Boolean =
        authPreferences.userId == ownerAtStart &&
            !OwnerDataGate.isWiped(authPreferences.context, ownerAtStart)

    private fun staleOwnerResult() = SyncResult(
        isSuccess = true,
        message = "Sync abandoned because the account changed or local data was wiped.",
        timestamp = System.currentTimeMillis()
    )

    private fun syncFailure(stage: String, httpStatus: Int): SyncFailureException =
        SyncFailureException(stage, httpStatus)

    private class SyncFailureException(
        val stage: String,
        val httpStatus: Int,
        val requiresAuthentication: Boolean = httpStatus == 401,
        val shouldRetry: Boolean = httpStatus == 408 || httpStatus == 425 || httpStatus == 429 || httpStatus in 500..599
    ) : IOException("$stage failed (HTTP $httpStatus)")

    private fun parsePainString(pain: String): Int {
        return try {
            val num = pain.takeWhile { it.isDigit() }
            if (num.isNotBlank()) num.toInt().coerceIn(0, 10) else 0
        } catch (_: Exception) { 0 }
    }
}

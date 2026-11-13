package com.cyclecare.app.data.supabase

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SupabaseUser(
    val id: String,
    val email: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "email_confirmed_at") val emailConfirmedAt: String? = null,
    @Json(name = "is_anonymous") val isAnonymous: Boolean = false
)

@JsonClass(generateAdapter = true)
data class AuthSessionResponse(
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "token_type") val tokenType: String? = null,
    @Json(name = "expires_in") val expiresIn: Long? = null,
    @Json(name = "refresh_token") val refreshToken: String? = null,
    val user: SupabaseUser? = null
)

@JsonClass(generateAdapter = true)
data class AuthErrorResponse(
    val error: String? = null,
    @Json(name = "error_description") val errorDescription: String? = null,
    val msg: String? = null,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class SupabaseProfileDto(
    val id: String? = null,
    @Json(name = "user_id") val userId: String? = null,
    @Json(name = "device_id") val deviceId: String? = null,
    @Json(name = "account_mode") val accountMode: String = "ANONYMOUS",
    val goals: String? = null,
    @Json(name = "last_period_date") val lastPeriodDate: String? = null,
    @Json(name = "cycle_length") val cycleLength: Int = 28,
    @Json(name = "period_duration") val periodDuration: Int = 5,
    @Json(name = "typical_symptoms") val typicalSymptoms: String? = null,
    @Json(name = "typical_energy") val typicalEnergy: String? = null,
    @Json(name = "typical_sleep") val typicalSleep: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class SupabaseCycleDto(
    val id: String,
    @Json(name = "user_id") val userId: String,
    @Json(name = "start_date") val startDate: String,
    @Json(name = "end_date") val endDate: String,
    @Json(name = "cycle_length") val cycleLength: Int = 28,
    @Json(name = "period_length") val periodLength: Int = 5,
    val notes: String? = null
)

@JsonClass(generateAdapter = true)
data class SupabaseDailyLogDto(
    val id: String,
    @Json(name = "user_id") val userId: String,
    val date: String,
    val flow: String = "NONE",
    val pain: String = "NONE",
    val mood: String = "NEUTRAL",
    val symptoms: String = "",
    @Json(name = "sexual_activity") val sexualActivity: Boolean = false,
    val temperature: Double? = null,
    val notes: String = ""
)

data class SyncResult(
    val isSuccess: Boolean,
    val message: String,
    val syncedCyclesCount: Int = 0,
    val syncedLogsCount: Int = 0,
    val profileSynced: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val shouldRetry: Boolean = false,
    val requiresAuthentication: Boolean = false
)

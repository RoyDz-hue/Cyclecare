package com.cyclecare.app.data.notification

import com.cyclecare.app.data.supabase.SupabaseConfig
import com.cyclecare.app.data.supabase.SupabaseSessionManager
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class SupabaseNotificationService(
    private val authPreferences: AuthPreferences,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Fetch user-specific notifications and active broadcasts using a single consolidated
     * filter query per table to minimize network round-trips and battery consumption.
     */
    suspend fun fetchNotifications(userIdentifier: String): List<UserNotification> = withContext(Dispatchers.IO) {
        val list = mutableListOf<UserNotification>()
        val seenIds = mutableSetOf<String>()

        try {
            val token = SupabaseSessionManager.validAccessToken(authPreferences) ?: return@withContext emptyList()
            val effectiveId = userIdentifier.ifBlank { authPreferences.effectiveUserId }
            val deviceId = authPreferences.deviceId
            val userEmail = authPreferences.userEmail ?: ""

            val identifiers = listOfNotNull(
                effectiveId.takeIf { it.isNotBlank() },
                deviceId.takeIf { it.isNotBlank() && it != effectiveId },
                userEmail.takeIf { it.isNotBlank() && it != effectiveId }
            ).distinct()

            // 1. Single consolidated query for user_notifications across all aliases + ALL
            val clauses = mutableListOf("user_id.eq.ALL", "target_identifier.eq.ALL")
            for (ident in identifiers) {
                val enc = URLEncoder.encode(ident, "UTF-8")
                clauses.add("user_id.eq.$enc")
                clauses.add("target_identifier.eq.$enc")
            }
            val filter = "or=(${clauses.joinToString(",")})"
            val url = "${SupabaseConfig.restUrl}/user_notifications?$filter&order=created_at.desc&limit=30"

            val request = Request.Builder()
                .url(url)
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .get()
                .build()

            val response = try { client.newCall(request).execute() } catch (_: Exception) { null }
            val body = response?.body?.string() ?: ""

            if (response != null && response.isSuccessful && body.isNotBlank()) {
                val array = JSONArray(body)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.optString("id", "")
                    if (id.isNotBlank() && !seenIds.add(id)) continue

                    list.add(
                        UserNotification(
                            id = id,
                            messageId = obj.optString("message_id").ifBlank { null },
                            targetIdentifier = obj.optString("target_identifier", effectiveId),
                            title = obj.optString("title", "CycleCare Official"),
                            body = obj.optString("body", ""),
                            type = obj.optString("type", "SYSTEM"),
                            priority = explicitPriorityOrNormal(obj.optString("priority", null)),
                            sender = obj.optString("sender", "CYCLECARE_OFFICIAL"),
                            actionUrl = obj.optString("action_url").ifBlank { null },
                            isRead = obj.optBoolean("is_read", false),
                            createdAt = parseIsoTimestamp(obj.optString("created_at"))
                        )
                    )
                }
            }

            // 2. Query official broadcast_dispatches table for active admin banners
            try {
                val encEff = URLEncoder.encode(effectiveId, "UTF-8")
                val dispatchUrl = "${SupabaseConfig.restUrl}/broadcast_dispatches?is_active=eq.true&or=(target_user_id.is.null,target_user_id.eq.$encEff)&order=created_at.desc&limit=15"
                val dispatchReq = Request.Builder()
                    .url(dispatchUrl)
                    .header("apikey", SupabaseConfig.anonKey)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .get()
                    .build()

                val dispatchRes = client.newCall(dispatchReq).execute()
                val dispatchBody = dispatchRes.body?.string() ?: ""
                if (dispatchRes.isSuccessful && dispatchBody.isNotBlank()) {
                    val dArray = JSONArray(dispatchBody)
                    for (i in 0 until dArray.length()) {
                        val obj = dArray.getJSONObject(i)
                        val dId = "dispatch_" + obj.optString("id", "")
                        if (!seenIds.add(dId)) continue

                        list.add(
                            UserNotification(
                                id = dId,
                                messageId = null,
                                targetIdentifier = "ALL",
                                title = obj.optString("title", "Official Announcement"),
                                body = obj.optString("body", ""),
                                type = obj.optString("type", "OFFICIAL_DISPATCH"),
                                priority = explicitPriorityOrNormal(obj.optString("priority", null)),
                                sender = obj.optString("sender", "CYCLECARE_OFFICIAL"),
                                actionUrl = obj.optString("action_url").ifBlank { null },
                                isRead = false,
                                createdAt = parseIsoTimestamp(obj.optString("created_at"))
                            )
                        )
                    }
                }
            } catch (_: Exception) {}

        } catch (_: Exception) {}

        list.sortedByDescending { it.createdAt }
    }

    suspend fun markAsRead(notificationId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = SupabaseSessionManager.validAccessToken(authPreferences) ?: return@withContext false
            val cleanId = notificationId.removePrefix("dispatch_")
            val rpcBody = JSONObject().put("p_notification_id", cleanId)
            val request = Request.Builder()
                .url("${SupabaseConfig.restUrl}/rpc/mark_my_notification_read")
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(rpcBody.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    suspend fun markAllAsRead(userIdentifier: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = SupabaseSessionManager.validAccessToken(authPreferences) ?: return@withContext false
            val rpcBody = JSONObject().put("p_notification_id", JSONObject.NULL)
            val request = Request.Builder()
                .url("${SupabaseConfig.restUrl}/rpc/mark_my_notification_read")
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(rpcBody.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (_: Exception) {
            false
        }
    }

}

/** Urgency must come from an explicit sender-set priority; a missing, blank or
 * unrecognized value must never render as urgent. */
internal fun explicitPriorityOrNormal(raw: String?): String =
    when (raw?.trim()?.uppercase(Locale.US)) {
        "URGENT" -> "URGENT"
        "HIGH" -> "HIGH"
        else -> "NORMAL"
    }

private val ISO_TIMESTAMP_PATTERN =
    Regex("^(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:?\\d{2})?$")

/**
 * Parses Supabase timestamps deterministically. A string carrying its own
 * offset (Z or +/-hh:mm) is honored; a bare timestamp is server UTC.
 * Fractional seconds are kept to millisecond precision because notifications
 * created within the same second must still order correctly. Strict parsing:
 * impossible dates and trailing garbage are rejected. Unparseable input
 * returns 0 so a malformed row sorts oldest instead of masquerading as fresh.
 */
internal fun parseIsoTimestamp(isoString: String?): Long {
    if (isoString.isNullOrBlank()) return 0L
    return runCatching {
        val match = ISO_TIMESTAMP_PATTERN.matchEntire(isoString.trim()) ?: return 0L
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        format.isLenient = false
        var time = format.parse("${match.groupValues[1]}T${match.groupValues[2]}")?.time ?: return 0L
        val fraction = match.groupValues[3]
        if (fraction.isNotEmpty()) {
            time += fraction.take(3).padEnd(3, '0').toLong()
        }
        val zone = match.groupValues[4]
        if (zone.isNotEmpty() && zone != "Z") {
            val sign = if (zone.startsWith("-")) -1 else 1
            val digits = zone.drop(1).replace(":", "")
            val offsetMs = sign * (digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()) * 60_000L
            time -= offsetMs
        }
        time
    }.getOrElse { 0L }
}

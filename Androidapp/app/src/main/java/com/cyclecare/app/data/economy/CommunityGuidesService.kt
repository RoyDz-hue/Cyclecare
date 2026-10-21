package com.cyclecare.app.data.economy

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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Live Community Circles bridge: loads server-backed topics and messages,
 * joins circles, posts reflections, and flags messages for review. Community
 * Guide (persona) posts arrive through the same stream and are tagged in the
 * UI. Every call degrades gracefully - offline or unsigned sessions simply
 * yield no remote content.
 */
class CommunityGuidesService(
    private val authPreferences: AuthPreferences,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    val currentUserId: String? get() = authPreferences.userId?.trim()?.takeIf { it.isNotEmpty() }

    private fun isoTimestamp(raw: String?): Long {
        if (raw.isNullOrBlank()) return System.currentTimeMillis()
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        for (pattern in patterns) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                val parsed = fmt.parse(raw)
                if (parsed != null) return parsed.time
            } catch (_: Exception) { }
        }
        return System.currentTimeMillis()
    }

    private suspend fun authedRequest(configure: Request.Builder.() -> Unit): okhttp3.Response? {
        val token = SupabaseSessionManager.validAccessToken(authPreferences) ?: return null
        val builder = Request.Builder()
            .header("apikey", SupabaseConfig.anonKey)
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Accept", "application/json")
        builder.configure()
        return try {
            client.newCall(builder.build()).execute()
        } catch (_: Exception) {
            null
        }
    }

    private fun boundedBody(response: okhttp3.Response): String? {
        val source = response.body?.source() ?: return ""
        return try {
            source.request(MAX_RESPONSE_BYTES.toLong() + 1)
            if (source.buffer.size > MAX_RESPONSE_BYTES) null
            else source.buffer.readByteArray().toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** Active server-backed circle topics, oldest first. Empty on any failure. */
    suspend fun fetchTopics(): List<ServerTopic> = withContext(Dispatchers.IO) {
        val response = authedRequest {
            url("${SupabaseConfig.restUrl}/chat_topics?select=id,title,description&is_active=eq.true&order=created_at.asc&limit=20")
            get()
        } ?: return@withContext emptyList()
        response.use {
            if (!it.isSuccessful) return@withContext emptyList()
            val raw = boundedBody(it) ?: return@withContext emptyList()
            try {
                val array = JSONArray(raw)
                val topics = mutableListOf<ServerTopic>()
                for (i in 0 until array.length()) {
                    val row = array.optJSONObject(i) ?: continue
                    val id = row.optString("id").trim()
                    val title = row.optString("title").trim()
                    if (id.isEmpty() || title.isEmpty()) continue
                    topics.add(ServerTopic(id = id, title = title, description = row.optString("description").trim()))
                }
                topics
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** Joins a circle so its messages become visible. Idempotent; failure is non-fatal. */
    suspend fun ensureMembership(serverTopicId: String): Boolean = withContext(Dispatchers.IO) {
        val userId = currentUserId ?: return@withContext false
        val payload = JSONObject().put("user_id", userId).put("topic_id", serverTopicId)
        val response = authedRequest {
            url("${SupabaseConfig.restUrl}/chat_memberships")
            header("Prefer", "resolution=ignore-duplicates")
            header("Content-Type", "application/json")
            post(payload.toString().toRequestBody(jsonMediaType))
        } ?: return@withContext false
        response.use { it.isSuccessful || it.code == 409 }
    }

    /** All messages in a circle (members and Community Guides), oldest first. */
    suspend fun fetchMessages(serverTopicId: String): List<GuidedChatMessage> = withContext(Dispatchers.IO) {
        val selfId = currentUserId
        val response = authedRequest {
            url("${SupabaseConfig.restUrl}/chat_messages?select=id,user_id,persona_id,sender_alias,content,created_at,is_flagged&topic_id=eq.$serverTopicId&order=created_at.asc&limit=100")
            get()
        } ?: return@withContext emptyList()
        response.use {
            if (!it.isSuccessful) return@withContext emptyList()
            val raw = boundedBody(it) ?: return@withContext emptyList()
            try {
                val array = JSONArray(raw)
                val messages = mutableListOf<GuidedChatMessage>()
                for (i in 0 until array.length()) {
                    val row = array.optJSONObject(i) ?: continue
                    val content = row.optString("content").trim()
                    if (content.isEmpty()) continue
                    val personaId = row.optString("persona_id").trim().lowercase().ifBlank { null }
                    val authorId = row.optString("user_id").trim().ifBlank { null }
                    messages.add(
                        GuidedChatMessage(
                            channelId = serverTopicId,
                            senderAlias = row.optString("sender_alias").ifBlank { if (personaId != null) "Community Guide" else "Member" },
                            content = content.take(500),
                            timestampMs = isoTimestamp(row.optString("created_at")),
                            isSelf = authorId != null && authorId == selfId,
                            personaId = personaId,
                            remoteId = row.optString("id").trim().ifBlank { null },
                            isFlagged = row.optBoolean("is_flagged", false)
                        )
                    )
                }
                messages
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** Posts a reflection to a server-backed circle. Null on failure (caller falls back). */
    suspend fun postMessage(serverTopicId: String, content: String): GuidedChatMessage? = withContext(Dispatchers.IO) {
        val userId = currentUserId ?: return@withContext null
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed.length > 500) return@withContext null
        val alias = softAliasFor(userId)
        val payload = JSONObject()
            .put("topic_id", serverTopicId)
            .put("user_id", userId)
            .put("sender_alias", alias)
            .put("content", trimmed)
        val response = authedRequest {
            url("${SupabaseConfig.restUrl}/chat_messages")
            header("Prefer", "return=representation")
            header("Content-Type", "application/json")
            post(payload.toString().toRequestBody(jsonMediaType))
        } ?: return@withContext null
        response.use {
            if (!it.isSuccessful) return@withContext null
            val raw = boundedBody(it) ?: return@withContext null
            try {
                val row = JSONArray(raw).optJSONObject(0) ?: return@withContext null
                GuidedChatMessage(
                    channelId = serverTopicId,
                    senderAlias = alias,
                    content = trimmed,
                    timestampMs = isoTimestamp(row.optString("created_at")),
                    isSelf = true,
                    remoteId = row.optString("id").trim().ifBlank { null }
                )
            } catch (_: Exception) {
                // The write may still have landed; a soft local echo keeps the UI calm.
                GuidedChatMessage(channelId = serverTopicId, senderAlias = alias, content = trimmed, isSelf = true)
            }
        }
    }

    /** Flags a message for moderator review. True when the server accepted it. */
    suspend fun flagMessage(remoteMessageId: String): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("p_message_id", remoteMessageId)
        val response = authedRequest {
            url("${SupabaseConfig.restUrl}/rpc/flag_chat_message")
            header("Content-Type", "application/json")
            post(payload.toString().toRequestBody(jsonMediaType))
        } ?: return@withContext false
        response.use {
            if (!it.isSuccessful) return@withContext false
            boundedBody(it)?.trim().equals("true", ignoreCase = true)
        }
    }

    data class ServerTopic(val id: String, val title: String, val description: String)

    private companion object {
        const val MAX_RESPONSE_BYTES = 96 * 1024
        private val ALIAS_ADJECTIVES = listOf("Gentle", "Quiet", "Warm", "Bright", "Calm", "Soft", "Golden", "Kind", "Serene", "Radiant")
        private val ALIAS_NOUNS = listOf("Dawn", "River", "Meadow", "Bloom", "Willow", "Amber", "Moon", "Star", "Breeze", "Leaf")

        /** Stable privacy-friendly circle alias derived from the user id. */
        fun softAliasFor(userId: String): String {
            var hash = 0
            for (c in userId) hash = (hash * 31 + c.code) and Int.MAX_VALUE
            return "${ALIAS_ADJECTIVES[hash % ALIAS_ADJECTIVES.size]} ${ALIAS_NOUNS[(hash / ALIAS_ADJECTIVES.size) % ALIAS_NOUNS.size]}"
        }
    }
}

/**
 * Fires one community-pulse round. The server enforces the kill switch, the
 * atomic lease, cooldowns and budgets, so a ping is always safe and cheap.
 * All failures are swallowed: the pulse must never break the daily sync.
 */
object CommunityPulsePinger {
    suspend fun ping(accessToken: String?): Boolean = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext false
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val bearer = accessToken?.trim().takeUnless { it.isNullOrEmpty() } ?: SupabaseConfig.anonKey
            val request = Request.Builder()
                .url("${SupabaseConfig.url}/functions/v1/community-pulse")
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer $bearer")
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }
}

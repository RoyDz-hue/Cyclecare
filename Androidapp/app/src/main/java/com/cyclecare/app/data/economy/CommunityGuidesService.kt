package com.cyclecare.app.data.economy

import com.cyclecare.app.data.supabase.SupabaseConfig
import com.cyclecare.app.data.supabase.AccessTokenResolution
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

    val currentUserId: String?
        get() {
            // Native-first: core community_transport parity port (NEW
            // IMPLEMENTATION wiring, 2026-11-13). A null reply (native
            // unavailable/malformed) keeps the Kotlin read below, byte for byte.
            com.cyclecare.app.rust.CycleCareCoreBridge.communityCurrentUserId(authPreferences.userId)?.let { return it.userId }
            return authPreferences.userId?.trim()?.takeIf { it.isNotEmpty() }
        }

    /** Verbatim pre-wiring fetchTopics row parse (org.json, AOSP); null
     * mirrors the original catch (UNAVAILABLE). Reached when the native
     * parse declines or fails. */
    private fun kotlinParseTopicRows(raw: String): List<ServerTopic>? = try {
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
        null
    }

    /** Verbatim pre-wiring fetchMessages row expressions (org.json, AOSP),
     * returned as wire rows so the caller's construction points (fresh
     * UUID per message) are unchanged; null mirrors the original catch
     * (emptyList). */
    private fun kotlinParseMessageRows(raw: String, selfId: String?): List<com.cyclecare.app.rust.CycleCareCoreBridge.CommunityMessageRow>? = try {
        val array = JSONArray(raw)
        val rows = mutableListOf<com.cyclecare.app.rust.CycleCareCoreBridge.CommunityMessageRow>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val content = row.optString("content").trim()
            if (content.isEmpty()) continue
            val personaId = row.optString("persona_id").trim().lowercase().ifBlank { null }
            val authorId = row.optString("user_id").trim().ifBlank { null }
            rows.add(
                com.cyclecare.app.rust.CycleCareCoreBridge.CommunityMessageRow(
                    content = content.take(500),
                    senderAlias = row.optString("sender_alias").ifBlank { if (personaId != null) "Community Guide" else "Member" },
                    personaId = personaId,
                    remoteId = row.optString("id").trim().ifBlank { null },
                    timestampMs = isoTimestamp(row.optString("created_at")),
                    isSelf = authorId != null && authorId == selfId,
                    isFlagged = row.optBoolean("is_flagged", false)
                )
            )
        }
        rows
    } catch (_: Exception) {
        null
    }

    /** Verbatim pre-wiring postMessage response interpretation (org.json,
     * AOSP): parse throw (malformed OR valid non-array) -> SOFT_ECHO; a
     * null optJSONObject(0) -> FAILED; else CONFIRMED with the
     * optString("id").trim().ifBlank remote id and iso created_at. */
    private fun kotlinPostRow(raw: String): com.cyclecare.app.rust.CycleCareCoreBridge.CommunityPostRow = try {
        when (val row = JSONArray(raw).optJSONObject(0)) {
            null -> com.cyclecare.app.rust.CycleCareCoreBridge.CommunityPostRow(
                com.cyclecare.app.rust.CycleCareCoreBridge.CommunityPostVerdict.FAILED, null, 0L)
            else -> com.cyclecare.app.rust.CycleCareCoreBridge.CommunityPostRow(
                com.cyclecare.app.rust.CycleCareCoreBridge.CommunityPostVerdict.CONFIRMED,
                row.optString("id").trim().ifBlank { null },
                isoTimestamp(row.optString("created_at")))
        }
    } catch (_: Exception) {
        com.cyclecare.app.rust.CycleCareCoreBridge.CommunityPostRow(
            com.cyclecare.app.rust.CycleCareCoreBridge.CommunityPostVerdict.SOFT_ECHO, null, 0L)
    }

    // Strict shared parsing: honors offsets and fraction precision, and returns
    // 0 for unparseable input so a malformed message sorts oldest in the
    // oldest-first stream instead of masquerading as the freshest.
    private fun isoTimestamp(raw: String?): Long =
        com.cyclecare.app.data.notification.parseIsoTimestamp(raw)

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

    /** Why a live-circle request did not return usable topics. */
    enum class CircleServiceStatus { OK, SIGN_IN_UNCONFIRMED, NETWORK, UNAVAILABLE, EMPTY }

    data class TopicsFetch(val topics: List<ServerTopic>, val status: CircleServiceStatus)

    /** Active server-backed circle topics, oldest first. Empty on any failure. */
    suspend fun fetchTopics(): List<ServerTopic> = fetchTopicsWithStatus().topics

    /**
     * Active topics with the real failure reason, so the UI never has to guess
     * "offline" when the actual cause is an expired session or a server error.
     */
    suspend fun fetchTopicsWithStatus(): TopicsFetch = withContext(Dispatchers.IO) {
        val token = when (val resolution = SupabaseSessionManager.resolveAccessToken(authPreferences)) {
            is AccessTokenResolution.Valid -> resolution.token
            is AccessTokenResolution.Missing,
            is AccessTokenResolution.ReauthenticationRequired ->
                return@withContext TopicsFetch(emptyList(), CircleServiceStatus.SIGN_IN_UNCONFIRMED)
            is AccessTokenResolution.TemporarilyUnavailable ->
                return@withContext TopicsFetch(emptyList(), CircleServiceStatus.UNAVAILABLE)
        }
        val request = Request.Builder()
            .header("apikey", SupabaseConfig.anonKey)
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Accept", "application/json")
            .url(SupabaseConfig.restUrl + (com.cyclecare.app.rust.CycleCareCoreBridge.communityFetchTopicsPath() ?: "/chat_topics?select=id,title,description&is_active=eq.true&order=created_at.asc&limit=20"))
            .get()
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (_: java.io.IOException) {
            return@withContext TopicsFetch(emptyList(), CircleServiceStatus.NETWORK)
        } catch (_: Exception) {
            return@withContext TopicsFetch(emptyList(), CircleServiceStatus.UNAVAILABLE)
        }
        response.use {
            when {
                it.code == 401 || it.code == 403 ->
                    return@withContext TopicsFetch(emptyList(), CircleServiceStatus.SIGN_IN_UNCONFIRMED)
                !it.isSuccessful ->
                    return@withContext TopicsFetch(emptyList(), CircleServiceStatus.UNAVAILABLE)
            }
            val raw = boundedBody(it) ?: return@withContext TopicsFetch(emptyList(), CircleServiceStatus.UNAVAILABLE)
            // Native-first row parsing (NEW IMPLEMENTATION wiring,
            // 2026-11-13): org.json row semantics via community_parse_v1.
            // A null reply (native unavailable/declined/parse failure) runs
            // the verbatim Kotlin parse, whose own failure is UNAVAILABLE.
            val topics = com.cyclecare.app.rust.CycleCareCoreBridge.communityParseTopics(raw)
                ?: kotlinParseTopicRows(raw)
            if (topics == null) {
                TopicsFetch(emptyList(), CircleServiceStatus.UNAVAILABLE)
            } else {
                TopicsFetch(topics, if (topics.isEmpty()) CircleServiceStatus.EMPTY else CircleServiceStatus.OK)
            }
        }
    }

    /** Joins a circle so its messages become visible. Idempotent; failure is non-fatal. */
    suspend fun ensureMembership(serverTopicId: String): Boolean = withContext(Dispatchers.IO) {
        val userId = currentUserId ?: return@withContext false
        // Native-first request shape (NEW IMPLEMENTATION wiring, 2026-11-13);
        // null reply keeps the Kotlin payload, byte for byte.
        val bodyString = com.cyclecare.app.rust.CycleCareCoreBridge.communityEnsureMembershipBody(userId, serverTopicId)
            ?: JSONObject().put("user_id", userId).put("topic_id", serverTopicId).toString()
        val response = authedRequest {
            url("${SupabaseConfig.restUrl}/chat_memberships")
            header("Prefer", "resolution=ignore-duplicates")
            header("Content-Type", "application/json")
            post(bodyString.toRequestBody(jsonMediaType))
        } ?: return@withContext false
        response.use { com.cyclecare.app.rust.CycleCareCoreBridge.communityMembershipSuccess(it.code) ?: (it.isSuccessful || it.code == 409) }
    }

    /** All messages in a circle (members and Community Guides), oldest first. */
    suspend fun fetchMessages(serverTopicId: String): List<GuidedChatMessage> = withContext(Dispatchers.IO) {
        val selfId = currentUserId
        val response = authedRequest {
            url(SupabaseConfig.restUrl + (com.cyclecare.app.rust.CycleCareCoreBridge.communityFetchMessagesPath(serverTopicId) ?: "/chat_messages?select=id,user_id,persona_id,sender_alias,content,created_at,is_flagged&topic_id=eq.$serverTopicId&order=created_at.asc&limit=100"))
            get()
        } ?: return@withContext emptyList()
        response.use {
            if (!it.isSuccessful) return@withContext emptyList()
            val raw = boundedBody(it) ?: return@withContext emptyList()
            // Native-first row parsing (NEW IMPLEMENTATION wiring,
            // 2026-11-13); a null reply runs the verbatim Kotlin parse. The
            // message objects are still constructed HERE, so their fresh
            // random UUIDs (and any clock defaults) keep their original
            // per-row construction points.
            val rows = com.cyclecare.app.rust.CycleCareCoreBridge.communityParseMessages(raw, serverTopicId, selfId)
                ?: kotlinParseMessageRows(raw, selfId)
            rows?.map { row ->
                GuidedChatMessage(
                    channelId = serverTopicId,
                    senderAlias = row.senderAlias,
                    content = row.content,
                    timestampMs = row.timestampMs,
                    isSelf = row.isSelf,
                    personaId = row.personaId,
                    remoteId = row.remoteId,
                    isFlagged = row.isFlagged
                )
            } ?: emptyList()
        }
    }

    /** Posts a reflection to a server-backed circle. Null on failure (caller falls back). */
    suspend fun postMessage(serverTopicId: String, content: String): GuidedChatMessage? = withContext(Dispatchers.IO) {
        val userId = currentUserId ?: return@withContext null
        val trimmed = content.trim()
        // Native-first validation (NEW IMPLEMENTATION wiring, 2026-11-13);
        // null reply keeps the Kotlin check, byte for byte.
        val contentAllowed = com.cyclecare.app.rust.CycleCareCoreBridge.communityPostContentAllowed(trimmed)
        if (contentAllowed != null) { if (!contentAllowed) return@withContext null }
        else if (trimmed.isEmpty() || trimmed.length > 500) return@withContext null
        val alias = softAliasFor(userId)
        val postBody = com.cyclecare.app.rust.CycleCareCoreBridge.communityPostMessageBody(serverTopicId, userId, alias, trimmed)
            ?: JSONObject()
            .put("topic_id", serverTopicId)
            .put("user_id", userId)
            .put("sender_alias", alias)
            .put("content", trimmed)
            .toString()
        val response = authedRequest {
            url("${SupabaseConfig.restUrl}/chat_messages")
            header("Prefer", "return=representation")
            header("Content-Type", "application/json")
            post(postBody.toRequestBody(jsonMediaType))
        } ?: return@withContext null
        response.use {
            if (!it.isSuccessful) return@withContext null
            val raw = boundedBody(it) ?: return@withContext null
            // Native-first response interpretation (NEW IMPLEMENTATION
            // wiring, 2026-11-13); a null reply runs the verbatim Kotlin
            // interpretation. Construction stays HERE: the confirmed path's
            // fresh UUID and the soft echo's wall-clock default are read at
            // their original points.
            val decision = com.cyclecare.app.rust.CycleCareCoreBridge.communityPostRow(raw) ?: kotlinPostRow(raw)
            when (decision.verdict) {
                com.cyclecare.app.rust.CycleCareCoreBridge.CommunityPostVerdict.FAILED -> null
                com.cyclecare.app.rust.CycleCareCoreBridge.CommunityPostVerdict.SOFT_ECHO ->
                    // The write may still have landed; a soft local echo keeps the UI calm.
                    GuidedChatMessage(channelId = serverTopicId, senderAlias = alias, content = trimmed, isSelf = true)
                com.cyclecare.app.rust.CycleCareCoreBridge.CommunityPostVerdict.CONFIRMED ->
                    GuidedChatMessage(
                        channelId = serverTopicId,
                        senderAlias = alias,
                        content = trimmed,
                        timestampMs = decision.timestampMs,
                        isSelf = true,
                        remoteId = decision.remoteId
                    )
            }
        }
    }

    /** Flags a message for moderator review. True when the server accepted it. */
    suspend fun flagMessage(remoteMessageId: String): Boolean = withContext(Dispatchers.IO) {
        val flagBody = com.cyclecare.app.rust.CycleCareCoreBridge.communityFlagMessageBody(remoteMessageId)
            ?: JSONObject().put("p_message_id", remoteMessageId).toString()
        val response = authedRequest {
            url("${SupabaseConfig.restUrl}/rpc/flag_chat_message")
            header("Content-Type", "application/json")
            post(flagBody.toRequestBody(jsonMediaType))
        } ?: return@withContext false
        response.use {
            if (!it.isSuccessful) return@withContext false
            val raw = boundedBody(it)
            // Native-first verdict (NEW IMPLEMENTATION wiring, 2026-11-13);
            // null reply (or null body, which the Kotlin read maps to false)
            // keeps the Kotlin check, byte for byte.
            if (raw != null) com.cyclecare.app.rust.CycleCareCoreBridge.communityFlagSuccess(it.code, raw)?.let { return@withContext it }
            raw?.trim().equals("true", ignoreCase = true)
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
    /** Test-only destination override: keeps the production caller/client
     * path intact but makes a REAL destination impossible to construct
     * (loopback only), so a negative control can prove the process network
     * deny wall engages this production route with zero real-egress risk. */
    @Volatile internal var endpointBaseOverrideForTests: String? = null

    suspend fun ping(accessToken: String?): Boolean = withContext(Dispatchers.IO) {
        val baseOverride = endpointBaseOverrideForTests
        if (baseOverride == null && !SupabaseConfig.isConfigured) return@withContext false
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            // Native-first bearer selection (NEW IMPLEMENTATION wiring,
            // 2026-11-13); null reply keeps the Kotlin selection, byte for byte.
            val bearer = com.cyclecare.app.rust.CycleCareCoreBridge.communityPulseBearer(accessToken, SupabaseConfig.anonKey)
                ?: (accessToken?.trim().takeUnless { it.isNullOrEmpty() } ?: SupabaseConfig.anonKey)
            val request = Request.Builder()
                .url((baseOverride ?: SupabaseConfig.url) + (com.cyclecare.app.rust.CycleCareCoreBridge.communityPulsePath() ?: "/functions/v1/community-pulse"))
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

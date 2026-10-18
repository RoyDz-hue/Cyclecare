package com.example.domain.ai

import com.example.data.local.CycleDayLog
import com.example.data.local.CycleSettings
import com.example.data.model.SafetyEvaluation
import com.example.data.model.SafetyLevel
import com.example.data.supabase.SupabaseConfig
import com.example.domain.care.CareKnowledgeBase
import com.example.domain.safety.SafetyEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit

data class CareResponse(
    val summary: String,
    val whatText: String,
    val understandText: String,
    val careText: String,
    val watchText: String,
    val escalateText: String,
    val safetyAlert: SafetyEvaluation? = null,
    val source: String
)

data class CareMemoryMessage(val role: String, val content: String)

internal const val REMOTE_CARE_SOURCE = "CycleCare AI"

/** The server, not Android, chooses the cloud provider. */
internal fun serverSelectedSource(selectedValue: String): String {
    val selected = selectedValue.trim().uppercase().removePrefix("CYCLECARE AI · ")
    val providerLabel = when (selected) {
        "COHERE" -> "Cohere"
        "GEMINI" -> "Gemini"
        "OPENROUTER" -> "OpenRouter"
        // Hybrid is not a supported client contract until consent-safe
        // multi-provider semantics are approved; keep the generic label.
        else -> null
    }
    return providerLabel?.let { "$REMOTE_CARE_SOURCE · $it" } ?: REMOTE_CARE_SOURCE
}

internal fun serverSelectedSource(data: JSONObject): String = serverSelectedSource(
    data.optString("provider", "").ifBlank { data.optString("source", "") }
)

internal fun isRemoteCareResponse(source: String): Boolean =
    source == REMOTE_CARE_SOURCE || source.startsWith("$REMOTE_CARE_SOURCE · ")

object CareAiService {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        // Leave room for the gateway's full provider budget before Android
        // reports a timeout and falls back locally.
        .callTimeout(65, TimeUnit.SECONDS)
        .build()

    internal fun requestPayload(question: String, forceAi: Boolean, contextSummary: String?, conversationHistory: List<CareMemoryMessage> = emptyList()): JSONObject = JSONObject().apply {
        put("schema_version", 2)
        put("user_question", question.trim().take(CareConversationPolicy.MAX_QUESTION_LENGTH))
        put("ai_consent", true)
        if (conversationHistory.isNotEmpty()) put("conversation_history", JSONArray().apply {
            conversationHistory.filter { it.role in setOf("user", "assistant") }.takeLast(6).forEach {
                put(JSONObject().put("role", it.role).put("content", it.content.take(600)))
            }
        })
        put("context_mode", if (forceAi && !contextSummary.isNullOrBlank()) "expanded" else "minimal")
        if (forceAi && !contextSummary.isNullOrBlank()) {
            put("context", JSONObject().put("summary", contextSummary.take(CareConversationPolicy.MAX_CONTEXT_LENGTH)))
        }
    }

    suspend fun consult(
        userQuestion: String,
        recentLog: CycleDayLog?,
        settings: CycleSettings,
        accessToken: String? = null,
        forceAi: Boolean = false,
        contextSummary: String? = null,
        aiSharingAllowed: Boolean = false,
        conversationHistory: List<CareMemoryMessage> = emptyList()
    ): CareResponse = withContext(Dispatchers.IO) {
        val safetyEval = SafetyEngine.evaluateInput(userQuestion, recentLog)
        if (safetyEval.isRedFlag) return@withContext CareResponse(
            summary = safetyEval.title,
            whatText = safetyEval.message,
            understandText = "This safety guidance is based on what you shared, not a diagnosis.",
            careText = safetyEval.actionAdvice,
            watchText = "Do not wait for an online explanation if symptoms are severe or worsening.",
            escalateText = safetyEval.actionAdvice,
            safetyAlert = safetyEval,
            source = "Deterministic Safety Engine"
        )
        val article = CareKnowledgeBase.search(userQuestion).firstOrNull()
        if (article != null && !forceAi) return@withContext CareResponse(
            summary = article.question, whatText = article.whatText,
            understandText = article.understandText, careText = article.careText,
            watchText = article.watchText, escalateText = article.escalateText,
            safetyAlert = if (safetyEval.level != SafetyLevel.LOW) safetyEval else null,
            source = "Curated care information"
        )
        if (aiSharingAllowed && !accessToken.isNullOrBlank()) {
            try {
                val request = Request.Builder()
                    .url("${SupabaseConfig.url}/functions/v1/ai-gateway")
                    .header("apikey", SupabaseConfig.anonKey)
                    .header("Authorization", "Bearer ${accessToken.trim()}")
                    .header("Content-Type", "application/json")
                    .post(requestPayload(userQuestion, forceAi, contextSummary, conversationHistory).toString().toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body
                    // Bound even a chunked/malformed gateway response before parsing it.
                    val source = body?.source()
                    source?.request(64 * 1024L + 1)
                    val raw = source?.buffer?.readByteArray(minOf(source.buffer.size, 64 * 1024L + 1))
                    if (response.isSuccessful && raw != null && raw.size <= 64 * 1024) {
                        val root = JSONObject(raw.toString(Charsets.UTF_8))
                        val data = root.optJSONObject("data")
                        val fields = listOf("summary", "whatText", "understandText", "careText", "watchText", "escalateText")
                        if (root.optBoolean("success") && data != null && fields.all {
                                data.opt(it) is String && data.getString(it).isNotBlank() && data.getString(it).length <= 4000
                            }) {
                            return@withContext CareResponse(
                                summary = data.getString("summary"), whatText = data.getString("whatText"),
                                understandText = data.getString("understandText"), careText = data.getString("careText"),
                                watchText = data.getString("watchText"), escalateText = data.getString("escalateText"),
                                safetyAlert = if (safetyEval.level != SafetyLevel.LOW) safetyEval else null,
                                source = serverSelectedSource(root)
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Never log raw health questions or provider response details.
            }
        }
        CareResponse(
            summary = if (forceAi) "Deeper explanation unavailable" else "Let's clarify your question",
            whatText = if (!aiSharingAllowed) "Online explanations are off. You can still log, read guidance and use safety support locally."
                else "An online explanation could not be completed. No health entry was saved from this conversation.",
            understandText = article?.understandText ?: "An app cannot diagnose symptoms or confirm ovulation from a calendar estimate.",
            careText = article?.careText ?: "You can rephrase your question or use the local tracking and care guidance.",
            watchText = article?.watchText ?: "Notice symptoms that are new, persistent or worsening.",
            escalateText = article?.escalateText ?: "Seek urgent medical help for severe, sudden or worsening symptoms.",
            safetyAlert = if (safetyEval.level != SafetyLevel.LOW) safetyEval else null,
            source = "Local guidance · online explanation unavailable"
        )
    }
}

package com.example.domain.ai

import com.example.BuildConfig
import com.example.data.local.CycleDayLog
import com.example.data.local.CycleSettings
import com.example.domain.care.CareArticle
import com.example.domain.care.CareKnowledgeBase
import com.example.data.model.SafetyEvaluation
import com.example.domain.safety.SafetyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class CareResponse(
    val summary: String,
    val whatText: String,
    val understandText: String,
    val careText: String,
    val watchText: String,
    val escalateText: String,
    val safetyAlert: SafetyEvaluation? = null,
    val source: String // "Curated Clinical Knowledge" or "Intelligent Health Guidance"
)

object CareAiService {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun consult(
        userQuestion: String,
        recentLog: CycleDayLog?,
        settings: CycleSettings
    ): CareResponse = withContext(Dispatchers.IO) {
        // Step 1: DETERMINISTIC SAFETY CHECK FIRST (AI can never downgrade or suppress this)
        val safetyEval = SafetyEngine.evaluate(recentLog)
        if (safetyEval.isRedFlag) {
            return@withContext CareResponse(
                summary = "Important Clinical Alert: Deterministic Safety Flag Detected",
                whatText = safetyEval.title,
                understandText = safetyEval.message,
                careText = safetyEval.actionAdvice,
                watchText = "Monitor hourly progression and vital signs.",
                escalateText = "Immediate evaluation required. Contact emergency services or healthcare professionals.",
                safetyAlert = safetyEval,
                source = "Deterministic Safety Engine"
            )
        }

        // Step 2: Check matching curated knowledge in CareKnowledgeBase first
        val matchingArticles = CareKnowledgeBase.search(userQuestion)
        if (matchingArticles.isNotEmpty()) {
            val article = matchingArticles.first()
            return@withContext CareResponse(
                summary = article.question,
                whatText = article.whatText,
                understandText = article.understandText,
                careText = article.careText,
                watchText = article.watchText,
                escalateText = article.escalateText,
                safetyAlert = if (safetyEval.level != com.example.data.model.SafetyLevel.LOW) safetyEval else null,
                source = "Curated Clinical Knowledge"
            )
        }

        val apiKey = try {
            val field = BuildConfig::class.java.getField("GEMINI_API_KEY")
            field.get(null) as? String ?: ""
        } catch (_: Exception) { "" }
        if (!apiKey.isNullOrBlank() && apiKey != "MY_GEMINI_API_KEY") {
            try {
                val prompt = """
                    You are CycleCare, a private, compassionate, intelligent women's health educator.
                    Question: "$userQuestion"
                    Context: Cycle phase duration ${settings.averageCycleLength} days.
                    Format your response strictly as valid JSON with keys:
                    "summary": "1 sentence warm, clear overview",
                    "what": "1-2 sentences on what is happening in the body",
                    "understand": "1-2 sentences on the hormonal/physiological mechanism",
                    "care": "2 practical, actionable self-care or nutritional steps",
                    "watch": "symptoms or timelines to monitor",
                    "escalate": "red flags that would require consulting a healthcare provider"
                    Do not mention that you are an AI. Respond with calm, respectful, medically accurate language.
                """.trimIndent()

                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
                val requestJson = JSONObject().apply {
                    put("contents", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", org.json.JSONArray().apply {
                                put(JSONObject().apply { put("text", prompt) })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("responseMimeType", "application/json")
                    })
                }

                val body = requestJson.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(url).post(body).build()
                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    val respBody = response.body?.string() ?: ""
                    val root = JSONObject(respBody)
                    val candidates = root.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val text = candidates.getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")
                        val structured = JSONObject(text)
                        return@withContext CareResponse(
                            summary = structured.optString("summary", "Personalized Health Insights"),
                            whatText = structured.optString("what", "Physiological adjustment."),
                            understandText = structured.optString("understand", "Natural hormonal fluctuations throughout the cycle."),
                            careText = structured.optString("care", "Hydration, restorative rest, and gentle movement."),
                            watchText = structured.optString("watch", "Observe how your body responds over the next 48 hours."),
                            escalateText = structured.optString("escalate", "Consult your physician if symptoms worsen or become acute."),
                            safetyAlert = if (safetyEval.level != com.example.data.model.SafetyLevel.LOW) safetyEval else null,
                            source = "Intelligent Health Guidance"
                        )
                    }
                }
            } catch (_: Exception) {
                // Fallback gracefully to curated repository
            }
        }

        // Step 4: Graceful fallback to default curated overview
        val fallback = CareKnowledgeBase.articles.first()
        return@withContext CareResponse(
            summary = "Understanding Your Body & Cycle",
            whatText = "Hormones ebb and flow across follicular, ovulatory, and luteal stages.",
            understandText = "Estrogen supports energy and tissue growth, while progesterone nurtures calming rest and internal balance.",
            careText = "Honor your current phase with balanced meals, gentle hydration, and restorative sleep.",
            watchText = "Note the timing and recurrence of any symptoms in your daily Track log.",
            escalateText = "Consult a physician for sudden, severe, or recurrent pain or unusual bleeding.",
            safetyAlert = if (safetyEval.level != com.example.data.model.SafetyLevel.LOW) safetyEval else null,
            source = "Curated Clinical Knowledge"
        )
    }
}

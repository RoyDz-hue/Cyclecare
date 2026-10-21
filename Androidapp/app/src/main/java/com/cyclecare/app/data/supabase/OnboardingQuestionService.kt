package com.cyclecare.app.data.supabase

import com.cyclecare.app.domain.onboarding.OnboardingQuestion
import com.cyclecare.app.util.NetworkHelper
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
 * Client for the `onboarding-questions` edge function: AI-driven question and
 * option generation for onboarding steps 4-6, adapted to the user's earlier
 * answers. Any failure returns null - callers fall back to the local adaptive
 * bank, so onboarding never blocks on connectivity or account state.
 */
class OnboardingQuestionService(private val authPreferences: com.cyclecare.app.security.AuthPreferences) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun generateQuestions(
        step: Int,
        goals: Set<String>,
        priorAnswers: Map<String, Set<String>>
    ): List<OnboardingQuestion>? = withContext(Dispatchers.IO) {
        if (step !in 4..6) return@withContext null
        if (!NetworkHelper.isOnline(authPreferences.context)) return@withContext null
        val token = SupabaseSessionManager.validAccessToken(authPreferences) ?: return@withContext null
        try {
            val payload = JSONObject().apply {
                put("step", step)
                put("goals", JSONArray(goals.toList()))
                put("prior_answers", JSONObject().apply {
                    priorAnswers.forEach { (id, answers) -> put(id, JSONArray(answers.toList())) }
                })
            }
            val request = Request.Builder()
                .url("${SupabaseConfig.url}/functions/v1/onboarding-questions")
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = JSONObject(response.body?.string().orEmpty())
            if (body.optBoolean("success") != true) return@withContext null
            val questions = body.optJSONArray("questions") ?: return@withContext null
            buildList {
                for (i in 0 until questions.length()) {
                    val q = questions.optJSONObject(i) ?: continue
                    val id = q.optString("id").trim()
                    val prompt = q.optString("prompt").trim()
                    val optionsJson = q.optJSONArray("options")
                    val options = buildList<String> {
                        if (optionsJson != null) for (j in 0 until optionsJson.length()) {
                            optionsJson.optString(j).trim().takeIf { it.isNotEmpty() }?.let { add(it.take(60)) }
                        }
                    }
                    if (id.isNotEmpty() && prompt.isNotEmpty() && options.size >= 2) {
                        add(OnboardingQuestion(id.take(48), prompt.take(200), options.take(6), q.optBoolean("multi_select", true)))
                    }
                }
            }.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}

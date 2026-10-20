package com.example.domain.ai

import com.example.data.supabase.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Illustration moods; custom decorative prompts never include automatically attached health context. */
enum class CareImageStyle(val apiValue: String, val title: String, val subtitle: String) {
    BOTANICAL("botanical", "Botanical", "Soft greens and wildflowers"),
    MOONLIT("moonlit", "Moonlit", "Quiet indigo and lavender"),
    SUNRISE("sunrise", "Sunrise", "Warm peach and gold"),
    OCEAN("ocean", "Ocean", "Calm teal and sand"),
}

class CareImageGenerationException(val userMessage: String) : Exception(userMessage)

object CareImageGenerationService {
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(190, TimeUnit.SECONDS)
        .build()

    suspend fun generate(style: CareImageStyle, accessToken: String, customPrompt: String = ""): ByteArray = generateUsing(
        style = style,
        accessToken = accessToken,
        baseUrl = SupabaseConfig.url,
        anonKey = SupabaseConfig.anonKey,
        client = httpClient,
        customPrompt = customPrompt
    )

    internal suspend fun generateUsing(
        style: CareImageStyle,
        accessToken: String,
        baseUrl: String,
        anonKey: String,
        client: OkHttpClient,
        customPrompt: String = ""
    ): ByteArray = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) {
            throw CareImageGenerationException("Please sign in again before creating an image.")
        }

        val prompt = customPrompt.trim()
        if (prompt.length > 500 || prompt.any { it.code < 32 && it != '\n' }) {
            throw CareImageGenerationException("Use a decorative prompt of up to 500 characters, without personal or health details.")
        }
        val payload = JSONObject().put("style", style.apiValue).put("image_consent", true).apply {
            if (prompt.isNotBlank()) put("custom_prompt", prompt)
        }.toString()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/functions/v1/image-generation")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${accessToken.trim()}")
            .header("Content-Type", "application/json")
            .header("Accept", "image/png, image/jpeg, image/webp, image/avif, application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val message = when (response.code) {
                        400 -> "Use a decorative prompt without personal or health details."
                        401 -> "Please sign in again before creating an image."
                        403 -> "Image creation is a Premium feature."
                        429 -> "You’ve made an image recently. Please try again later."
                        else -> "Image creation is temporarily unavailable. Please try again later."
                    }
                    throw CareImageGenerationException(message)
                }

                val body = response.body ?: throw CareImageGenerationException(
                    "Image creation couldn't finish. Please try again later."
                )
                val contentType = response.header("Content-Type")
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
                    .orEmpty()
                if (contentType !in setOf("image/png", "image/jpeg", "image/webp", "image/avif")) {
                    throw CareImageGenerationException("Image creation couldn't finish. Please try again later.")
                }
                if (body.contentLength() > MAX_IMAGE_BYTES) {
                    throw CareImageGenerationException("This image is too large to display. Please try again.")
                }

                val output = ByteArrayOutputStream()
                val input = body.byteStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_IMAGE_BYTES) {
                        throw CareImageGenerationException("This image is too large to display. Please try again.")
                    }
                    output.write(buffer, 0, read)
                }
                val image = output.toByteArray()
                if (image.isEmpty()) throw CareImageGenerationException("Image creation couldn't finish. Please try again later.")
                image
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: CareImageGenerationException) {
            throw error
        } catch (_: Exception) {
            throw CareImageGenerationException("Image creation is temporarily unavailable. Please try again later.")
        }
    }
}

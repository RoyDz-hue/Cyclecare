package com.cyclecare.app

import com.cyclecare.app.domain.ai.CareImageGenerationException
import com.cyclecare.app.domain.ai.CareImageGenerationService
import com.cyclecare.app.domain.ai.CareImageStyle
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CareImageGenerationServiceTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun requestSendsOnlyStyleAndAuthenticatedSessionAndReturnsImageBytes() = runBlocking {
        val expected = ByteArray(128) { it.toByte() }
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png")
            .setBody(Buffer().write(expected)))

        val result = CareImageGenerationService.generateUsing(
            style = CareImageStyle.BOTANICAL,
            accessToken = "owner-session-token",
            baseUrl = server.url("/").toString(),
            anonKey = "public-client-key",
            client = client
        )
        val request = server.takeRequest()
        val requestBody = request.body.readUtf8()
        val payload = JSONObject(requestBody)

        assertArrayEquals(expected, result)
        assertEquals("/functions/v1/image-generation", request.path)
        assertEquals("public-client-key", request.getHeader("apikey"))
        assertEquals("Bearer owner-session-token", request.getHeader("Authorization"))
        assertEquals("botanical", payload.getString("style"))
        assertTrue(payload.getBoolean("image_consent"))
        assertEquals(2, payload.length())
        assertFalse(requestBody.contains("health"))
    }

    @Test
    fun rateLimitResponseBecomesFriendlyMessageWithoutEchoingServerDetails() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Content-Type", "application/json")
            .setBody("""{"error":"quota record 22334 provider internals"}"""))

        try {
            CareImageGenerationService.generateUsing(
                style = CareImageStyle.MOONLIT,
                accessToken = "owner-session-token",
                baseUrl = server.url("/").toString(),
                anonKey = "public-client-key",
                client = client
            )
            throw AssertionError("Expected the user-safe quota error")
        } catch (error: CareImageGenerationException) {
            assertEquals("You’ve made an image recently. Please try again later.", error.userMessage)
            assertFalse(error.userMessage.contains("provider"))
            assertTrue(server.takeRequest().method == "POST")
        }
    }
    @Test
    fun customPromptSendsOnlyDecorativeInputAndNeverHealthContext() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png")
            .setBody(Buffer().write(byteArrayOf(1, 2, 3))))
        CareImageGenerationService.generateUsing(CareImageStyle.OCEAN, "session", server.url("/").toString(), "public", client,
            customPrompt = "An abstract teal landscape with gold lines")
        val payload = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("An abstract teal landscape with gold lines", payload.getString("custom_prompt"))
        assertEquals(3, payload.length())
        assertTrue(payload.getBoolean("image_consent"))
        assertFalse(payload.has("context"))
    }

    @Test
    fun overlongPromptIsRejectedBeforeAnyRequest() = runBlocking {
        try {
            CareImageGenerationService.generateUsing(CareImageStyle.OCEAN, "session", server.url("/").toString(), "public", client,
                customPrompt = "a".repeat(501))
            throw AssertionError("Expected validation error")
        } catch (_: CareImageGenerationException) { assertEquals(0, server.requestCount) }
    }

}

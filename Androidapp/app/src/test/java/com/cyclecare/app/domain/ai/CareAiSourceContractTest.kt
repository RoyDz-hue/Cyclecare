package com.cyclecare.app.domain.ai

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CareAiSourceContractTest {
    @Test
    fun deployedCycleCareAiSourceIsRecognizedAndCuratedFallbackIsNot() {
        assertTrue(isRemoteCareResponse("CycleCare AI"))
        assertTrue(isRemoteCareResponse("CycleCare AI · Cohere"))
        assertFalse(isRemoteCareResponse("Curated Clinical Knowledge"))
        assertFalse(isRemoteCareResponse("Edge AI Gateway"))
    }

    @Test
    fun providerLabelComesOnlyFromServerMetadataAndHybridStaysGeneric() {
        assertEquals("CycleCare AI · Gemini", serverSelectedSource("GEMINI"))
        assertEquals("CycleCare AI · OpenRouter", serverSelectedSource("OPENROUTER"))
        assertEquals("CycleCare AI", serverSelectedSource("HYBRID"))
        assertEquals("CycleCare AI", serverSelectedSource("POLLINATIONS"))
        assertEquals("CycleCare AI", serverSelectedSource("arbitrary-provider"))
    }

    @Test
    fun actualGatewayRootSourceIsUsedRatherThanNestedAnswerData() {
        for ((provider, label) in listOf("COHERE" to "Cohere", "GEMINI" to "Gemini", "OPENROUTER" to "OpenRouter")) {
            val envelope = JSONObject().put("success", true)
                .put("source", "CycleCare AI · $provider")
                .put("data", JSONObject().put("summary", "Educational answer"))
            assertEquals("CycleCare AI · $label", serverSelectedSource(envelope))
        }
    }

    @Test
    fun unsupportedServerMetadataCannotClaimHybridOrAnArbitraryProvider() {
        for (source in listOf("CycleCare AI · HYBRID", "CycleCare AI · POLLINATIONS", "CycleCare AI · COHERE · injected")) {
            assertEquals("CycleCare AI", serverSelectedSource(JSONObject().put("source", source)))
        }
        assertEquals("CycleCare AI", serverSelectedSource(JSONObject()))
        assertEquals("CycleCare AI · Cohere", serverSelectedSource(JSONObject().put("provider", "COHERE")))
    }
}

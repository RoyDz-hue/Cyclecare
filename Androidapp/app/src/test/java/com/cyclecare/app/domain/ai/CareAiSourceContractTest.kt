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
        assertTrue(isRemoteCareResponse("CycleCare Assistant"))
        assertTrue(isRemoteCareResponse("CycleCare Assistant · Cohere"))
        assertFalse(isRemoteCareResponse("Curated Clinical Knowledge"))
        assertFalse(isRemoteCareResponse("Edge AI Gateway"))
    }

    @Test
    fun serverMetadataNeverLeaksProviderOrRoutingDetails() {
        for (value in listOf("GEMINI", "OPENROUTER", "COHERE", "HYBRID", "POLLINATIONS", "arbitrary-provider")) {
            assertEquals("CycleCare Assistant", serverSelectedSource(value))
        }
    }

    @Test
    fun actualGatewayRootSourceIsNormalizedRatherThanNestedAnswerData() {
        for (provider in listOf("COHERE", "GEMINI", "OPENROUTER")) {
            val envelope = JSONObject().put("success", true)
                .put("source", "CycleCare Assistant · $provider")
                .put("data", JSONObject().put("summary", "Educational answer"))
            assertEquals("CycleCare Assistant", serverSelectedSource(envelope))
        }
    }

    @Test
    fun legacyPreRebrandServerLabelsStayClassifiedAndNormalize() {
        // Labels emitted by older app/server versions must stay classified so
        // saved history keeps its remote-response treatment after the renames.
        assertTrue(isRemoteCareResponse("CycleCare AI"))
        assertTrue(isRemoteCareResponse("CycleCare AI · Cohere"))
        assertTrue(isRemoteCareResponse("CycleCare Expert"))
        assertTrue(isRemoteCareResponse("CycleCare Expert · Cohere"))
        assertEquals("CycleCare Assistant", serverSelectedSource(JSONObject().put("source", "CycleCare AI · Cohere")))
        assertEquals("CycleCare Assistant", serverSelectedSource(JSONObject().put("source", "CycleCare Expert · Cohere")))
        assertEquals("CycleCare Assistant", serverSelectedSource(JSONObject().put("provider", "COHERE")))
        assertEquals("CycleCare Assistant", serverSelectedSource(JSONObject()))
    }
}

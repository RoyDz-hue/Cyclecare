package com.cyclecare.app.domain.ai

import com.cyclecare.app.data.economy.EconomyProfile
import com.cyclecare.app.data.local.CycleSettings
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayeredChatOrchestratorTest {

    private val settings = CycleSettings()

    @Test
    fun coinQuestionDoesNotReceiveUnrelatedMedicalAdvice() = runBlocking {
        val profile = EconomyProfile(coinBalance = BigDecimal("42.5"))
        val result = LayeredChatOrchestrator.processRawChat(
            rawInput = "How many coins do I have?",
            todayLog = null,
            settings = settings,
            economyProfile = profile
        )
        assertTrue(result.mainResponse.summary.contains("42.5"))
        // No article matched, so no medical article body may be attached at all.
        assertTrue(result.mainResponse.whatText.isBlank())
        assertTrue(result.mainResponse.understandText.isBlank())
        assertTrue(result.mainResponse.careText.isBlank())
        assertTrue(result.mainResponse.watchText.isBlank())
        assertTrue(result.mainResponse.escalateText.isBlank())
        val combined = with(result.mainResponse) { summary + whatText + understandText + careText + watchText + escalateText }
        assertFalse(combined.contains("progesterone", ignoreCase = true))
        assertFalse(combined.contains("fatigue", ignoreCase = true))
    }

    @Test
    fun mpesaQuestionDoesNotReceiveFatigueArticle() = runBlocking {
        val result = LayeredChatOrchestrator.processRawChat(
            rawInput = "How do I buy coins with M-Pesa?",
            todayLog = null,
            settings = settings,
            economyProfile = EconomyProfile()
        )
        val combined = with(result.mainResponse) { summary + whatText + understandText + careText }
        assertFalse(combined.contains("progesterone", ignoreCase = true))
        assertFalse(combined.contains("Premenstrual", ignoreCase = true))
    }

    @Test
    fun adviceQuestionReceivesArticleAnswerNotLogProposal() = runBlocking {
        val result = LayeredChatOrchestrator.processRawChat(
            rawInput = "How can I ease period cramps?",
            todayLog = null,
            settings = settings,
            economyProfile = EconomyProfile()
        )
        assertEquals("Curated Clinical Knowledge", result.mainResponse.source)
        assertTrue(result.detectedSymptoms.isEmpty())
        assertTrue(result.mainResponse.careText.isNotBlank())
        assertFalse(result.mainResponse.summary.contains("possible observations", ignoreCase = true))
    }

    @Test
    fun genericAdviceQuestionDoesNotAssertPersonalSymptoms() = runBlocking {
        val result = LayeredChatOrchestrator.processRawChat(
            rawInput = "Are cramps normal before a period?",
            todayLog = null,
            settings = settings,
            economyProfile = EconomyProfile()
        )
        assertFalse("Gentle Health Logger" == result.mainResponse.source)
        assertTrue(result.detectedSymptoms.isEmpty())
        assertNull(result.detectedFlow)
        assertFalse(result.mainResponse.whatText.contains("Possible observations", ignoreCase = true))
    }

    @Test
    fun explicitLogRequestStillProposesObservations() = runBlocking {
        val result = LayeredChatOrchestrator.processRawChat(
            rawInput = "Please log cramps today",
            todayLog = null,
            settings = settings,
            economyProfile = EconomyProfile()
        )
        assertEquals("Gentle Health Logger", result.mainResponse.source)
        assertTrue(result.detectedSymptoms.isNotEmpty())
    }

    @Test
    fun personalReportStillRoutesToGentleHealthLogger() = runBlocking {
        val result = LayeredChatOrchestrator.processRawChat(
            rawInput = "I have bad cramps today",
            todayLog = null,
            settings = settings,
            economyProfile = EconomyProfile()
        )
        assertEquals("Gentle Health Logger", result.mainResponse.source)
        assertTrue(result.detectedSymptoms.isNotEmpty())
    }

    @Test
    fun redFlagResponseUsesPlainSeekHelpHeaderAndKeepsEscalation() = runBlocking {
        val result = LayeredChatOrchestrator.processRawChat(
            rawInput = "I fainted and I am soaking through pads",
            todayLog = null,
            settings = settings,
            economyProfile = EconomyProfile()
        )
        assertEquals("Please seek medical help", result.mainResponse.summary)
        assertFalse(result.mainResponse.summary.contains("Clinical Notice", ignoreCase = true))
        assertNotNull(result.mainResponse.safetyAlert)
        assertTrue(result.mainResponse.safetyAlert!!.isRedFlag)
        assertTrue(result.mainResponse.escalateText.contains("immediately", ignoreCase = true))
    }
}

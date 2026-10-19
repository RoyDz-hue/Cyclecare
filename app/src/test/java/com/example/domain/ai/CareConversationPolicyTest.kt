package com.example.domain.ai

import com.example.data.local.CycleDayLog
import com.example.data.local.CycleSettings
import com.example.data.economy.EconomyProfile
import com.example.data.model.SafetyLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CareConversationPolicyTest {
    @Test fun educationalQuestionsNeverCreateHealthDrafts() {
        listOf("Why do periods cause cramps?", "Can fatigue mean something else?", "What is heavy bleeding?", "I have cramps, should I worry?").forEach {
            assertNull(CareConversationPolicy.proposal("A", "2026-10-18", it))
        }
    }
    @Test fun personalReportIsOnlyAnEphemeralProposalUntilConfirmedOnce() {
        val gate = CareLogConsentGate()
        val draft = gate.propose("A", "2026-10-18", "I have cramps and I feel tired")!!
        assertNull(gate.confirm(draft.id, "B"))
        assertEquals(draft, gate.confirm(draft.id, "A"))
        assertNull(gate.confirm(draft.id, "A"))
    }
    @Test fun cancelLeavesNoConfirmableDraftAcrossEveryRoute() {
        val gate = CareLogConsentGate()
        listOf("I have cramps", "log heavy bleeding", "I feel exhausted").forEach {
            val draft = gate.propose("A", "2026-10-18", it)!!
            gate.cancel()
            assertNull(gate.confirm(draft.id, "A"))
        }
    }
    @Test fun mergePreservesOtherFieldsAndNeverPersistsRawChat() {
        val original = CycleDayLog("2026-10-18", notes = "Existing note", waterGlasses = 6)
        val draft = CareConversationPolicy.proposal("A", original.dateString, "I have cramps")!!
        val merged = CareConversationPolicy.merge(original, draft.interpretation)
        assertEquals("Existing note", merged.notes)
        assertEquals(6, merged.waterGlasses)
        assertTrue(merged.symptomsCsv.contains("cramps"))
    }
    @Test fun selectedContextExcludesNotesIdentityIntimateFieldsAndStaleRecords() {
        val now = LocalDate.of(2026, 10, 18)
        val log = CycleDayLog(now.toString(), energyLevel = "LOW", moodLevel = "SECRET_MOOD",
            urinaryHealthCsv = "SECRET_URINARY", vaginalHealthCsv = "SECRET_VAGINAL", notes = "SECRET_NOTE")
        val old = CycleDayLog(now.minusDays(40).toString(), energyLevel = "STALE_ENERGY")
        val context = CareConversationPolicy.compileContext("Why is my energy low?", listOf(log, old), CycleSettings(), now)
        assertTrue(context.contains("energy=LOW"))
        listOf("SECRET", "STALE_ENERGY").forEach { assertFalse(context.contains(it)) }
        assertTrue(context.length <= CareConversationPolicy.MAX_CONTEXT_LENGTH)
    }
    @Test fun unrelatedQuestionDoesNotSendHealthDatabase() {
        val now = LocalDate.of(2026, 10, 18)
        val context = CareConversationPolicy.compileContext("Hello there", listOf(CycleDayLog(now.toString(), symptomsCsv = "PRIVATE_SYMPTOM")), CycleSettings(), now)
        assertFalse(context.contains("PRIVATE_SYMPTOM"))
    }
    @Test fun minimalPayloadContainsQuestionAndConsentButNoHiddenHealthFields() {
        val payload = CareAiService.requestPayload("Hello", false, "NOT_APPROVED")
        assertEquals(3, payload.getInt("schema_version"))
        assertEquals(0, payload.getJSONArray("processor_receipts").length())
        assertEquals("minimal", payload.getString("context_mode"))
        assertTrue(payload.getBoolean("ai_consent"))
        assertFalse(payload.has("context"))
        assertFalse(payload.has("cycle_day"))
        assertFalse(payload.has("recent_symptoms"))
    }
    @Test fun expandedPayloadKeepsContextSeparateFromQuestion() {
        val payload = CareAiService.requestPayload("Why tired?", true, "User reviewed energy=LOW")
        assertEquals("Why tired?", payload.getString("user_question"))
        assertEquals("User reviewed energy=LOW", payload.getJSONObject("context").getString("summary"))
    }

    @Test fun unsupportedInputKeepsAssessmentLimitOnClarificationWithAiDenied() = runBlocking {
        for (forceAi in listOf(false, true)) {
            val result = LayeredChatOrchestrator.processRawChat(
                "Tengo sangrado muy abundante", null, CycleSettings(), EconomyProfile(),
                forceAi = forceAi, allowRemoteAi = false
            )
            assertEquals(SafetyLevel.REVIEW, result.mainResponse.safetyAlert?.level)
            assertTrue(result.mainResponse.safetyAlert!!.actionAdvice.contains("interpreter"))
            assertFalse(isRemoteCareResponse(result.mainResponse.source))
            assertTrue(result.detectedSymptoms.isEmpty())
        }
    }

    @Test fun disabledOnlineExplanationCannotRemoveAssessmentLimitation() = runBlocking {
        val response = CareAiService.consult("我很不舒服", null, CycleSettings(), forceAi = true, aiSharingAllowed = false)
        assertEquals(SafetyLevel.REVIEW, response.safetyAlert?.level)
        assertFalse(isRemoteCareResponse(response.source))
    }

    @Test fun closedMpesaCheckoutIsNotAdvertisedAsAvailable() = runBlocking {
        val result = LayeredChatOrchestrator.processRawChat("M-Pesa top up", null, CycleSettings(), EconomyProfile())
        assertTrue(result.mainResponse.summary.contains("currently unavailable"))
        assertFalse(result.mainResponse.summary.contains("start from"))
    }
}

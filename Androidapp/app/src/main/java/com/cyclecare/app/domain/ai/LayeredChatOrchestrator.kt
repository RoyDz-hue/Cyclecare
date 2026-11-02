package com.cyclecare.app.domain.ai

import com.cyclecare.app.util.CoinFormatter
import com.cyclecare.app.data.economy.EconomyProfile
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.model.SafetyEvaluation
import com.cyclecare.app.domain.care.CareKnowledgeBase
import com.cyclecare.app.domain.parser.NaturalLanguageLogParser
import com.cyclecare.app.domain.safety.SafetyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.cyclecare.app.security.AuthPreferences

data class TiedDownChoice(
    val id: String,
    val iconEmoji: String,
    val label: String,
    val prefilledQuery: String
)

enum class EconomyChatIntent {
    BALANCE_INQUIRY,
    ARTIFACT_CATALOG,
    COIN_STORE,
    TRANSFER_INQUIRY,
    MINING_PROGRESS
}

enum class PaymentChatIntent {
    MPESA_TOPUP,
    CHECK_PENDING,
    PAYMENT_HELP
}

data class LayeredChatResult(
    val mainResponse: CareResponse,
    val detectedSymptoms: List<String> = emptyList(),
    val detectedFlow: String? = null,
    val detectedEnergy: String? = null,
    val detectedMood: String? = null,
    val painIntensity: Int = 0,
    val gentleProgressCoinsAwarded: Long = 0L,
    val economyIntent: EconomyChatIntent? = null,
    val paymentIntent: PaymentChatIntent? = null,
    val isClarificationNeeded: Boolean = false,
    val clarificationMessage: String? = null,
    val tiedDownChoices: List<TiedDownChoice> = emptyList(),
    val rawQuery: String
)

object LayeredChatOrchestrator {

    /**
     * Translates raw, messy user input into structured, typed actions.
     * Silent AI Failure: If the AI API fails for any reason, it NEVER crashes
     * or alerts the user—it continues silently using the deterministic engine.
     * Graceful Disambiguation: If the input is genuinely unparseable/too complicated,
     * it prompts tailored questions with tied-down choices or offers a gentle
     * "our experts are busy at the moment, we can handle this later" card.
     */
    suspend fun processRawChat(
        rawInput: String,
        todayLog: CycleDayLog?,
        settings: CycleSettings,
        economyProfile: EconomyProfile,
        accessToken: String? = null,
        forceAi: Boolean = false,
        contextSummary: String? = null,
        allowRemoteAi: Boolean = false,
        conversationHistory: List<CareMemoryMessage> = emptyList(),
        authPreferences: AuthPreferences? = null
    ): LayeredChatResult = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()

        // 1. Normalize and evaluate NEW input locally before any network or context gating.
        val inputSafety = SafetyEngine.evaluateInput(trimmed, todayLog)
        val safetyEval = inputSafety
        if (safetyEval.isRedFlag) {
            return@withContext LayeredChatResult(
                mainResponse = CareResponse(
                    summary = "Please seek medical help",
                    whatText = safetyEval.title,
                    understandText = safetyEval.message,
                    careText = safetyEval.actionAdvice,
                    watchText = "Monitor vital signs and progression closely.",
                    escalateText = "Please contact emergency healthcare services or your doctor immediately.",
                    safetyAlert = safetyEval,
                    source = "Deterministic Safety Engine"
                ),
                rawQuery = trimmed
            )
        }

        // 2. Deterministic local parsing owns all structured actions and logging.
        val localResult = parseDeterministically(trimmed, todayLog, settings, economyProfile, safetyEval)
        if ((!localResult.isClarificationNeeded && !forceAi) || !allowRemoteAi || accessToken.isNullOrBlank()) {
            return@withContext localResult
        }

        // 3. Only unresolved free-text questions may be enriched by the authenticated
        // server gateway. Its prose can never create a symptom log, award coins,
        // trigger a payment, or overwrite a deterministic safety result.
        val guided = CareAiService.consult(
            userQuestion = trimmed,
            recentLog = todayLog,
            settings = settings,
            accessToken = accessToken,
            forceAi = forceAi,
            contextSummary = contextSummary,
            aiSharingAllowed = true,
            conversationHistory = conversationHistory
            ,authPreferences = authPreferences
        )
        if (isRemoteCareResponse(guided.source)) {
            return@withContext localResult.copy(
                mainResponse = guided,
                isClarificationNeeded = false,
                clarificationMessage = null,
                tiedDownChoices = emptyList()
            )
        }
        if (forceAi) {
            return@withContext localResult.copy(
                mainResponse = guided,
                isClarificationNeeded = false,
                clarificationMessage = null,
                tiedDownChoices = emptyList()
            )
        }
        return@withContext localResult
    }

    /**
     * Deterministic translation engine running 100% locally on device.
     */
    private fun parseDeterministically(
        input: String,
        todayLog: CycleDayLog?,
        settings: CycleSettings,
        economyProfile: EconomyProfile,
        safetyEval: SafetyEvaluation
    ): LayeredChatResult {
        val lower = input.lowercase()

        // Check for Economy intents
        val isBalanceQuery = lower.contains("how many coins") || lower.contains("my balance") ||
                lower.contains("coin balance") || lower.contains("how many coin") || lower.contains("check coins")
        val isShopQuery = lower.contains("charm") || lower.contains("artifact") || lower.contains("frame") ||
                lower.contains("store") || lower.contains("shop") || lower.contains("buy artifact") || lower.contains("moon")
        val isTransferQuery = lower.contains("transfer") || lower.contains("send coin") || lower.contains("give coin")

        val economyIntent = when {
            isBalanceQuery -> EconomyChatIntent.BALANCE_INQUIRY
            isShopQuery -> EconomyChatIntent.ARTIFACT_CATALOG
            isTransferQuery -> EconomyChatIntent.TRANSFER_INQUIRY
            else -> null
        }

        // Check for Payment intents
        val isMpesaQuery = lower.contains("mpesa") || lower.contains("m-pesa") || lower.contains("stk") ||
                lower.contains("top up") || lower.contains("buy coins") || lower.contains("49") ||
                lower.contains("99") || lower.contains("kes") || lower.contains("pay")
        val isPendingQuery = lower.contains("failed") || lower.contains("pending") || lower.contains("not received")

        val paymentIntent = when {
            isPendingQuery && isMpesaQuery -> PaymentChatIntent.CHECK_PENDING
            isMpesaQuery -> PaymentChatIntent.MPESA_TOPUP
            else -> null
        }

        // Check for Health Symptoms via NaturalLanguageLogParser
        val healthParse = NaturalLanguageLogParser.parse(input)
        val hasRecognizedHealth = healthParse.symptomsResult.isNotEmpty() ||
                healthParse.flowLevelResult != null ||
                healthParse.energyResult != null ||
                healthParse.moodResult != null

        // An advice question that mentions symptoms ("How can I ease period
        // cramps?") is not a personal note. Only treat recognized observations
        // as loggable when the input reads as a personal report or explicit log
        // request - the same gate the consent proposal uses.
        val isPersonalNote = hasRecognizedHealth && CareConversationPolicy.isLoggingIntent(input)

        // Check Knowledge Base
        val articles = CareKnowledgeBase.search(input)
        val hasArticle = articles.isNotEmpty()

        // CASE A: Successfully recognized intent (Health, Economy, Payment, or Clinical Article)
        if (isPersonalNote || economyIntent != null || paymentIntent != null || hasArticle) {
            val article = articles.firstOrNull()

            val summaryText = buildString {
                if (economyIntent == EconomyChatIntent.BALANCE_INQUIRY) {
                    append("You currently have ${CoinFormatter.format(economyProfile.coinBalance)} Cycle Coins in your account. ")
                } else if (economyIntent == EconomyChatIntent.ARTIFACT_CATALOG) {
                    append("Our artifacts and charms can be unlocked using your Cycle Coins. ")
                }
                if (paymentIntent == PaymentChatIntent.MPESA_TOPUP) {
                    append("M-Pesa checkout is currently unavailable while payment safety review is incomplete. Do not send a payment. ")
                }
                if (isEmpty()) {
                    append(article?.question ?: "I found possible observations in your note. Review them below before choosing whether to save.")
                }
            }

            return LayeredChatResult(
                mainResponse = CareResponse(
                    summary = summaryText,
                    whatText = if (isPersonalNote) "Possible observations found: ${healthParse.items.joinToString(", ") { it.label }}. Review them before choosing whether to save." else article?.whatText.orEmpty(),
                    understandText = article?.understandText.orEmpty(),
                    careText = article?.careText.orEmpty(),
                    watchText = article?.watchText.orEmpty(),
                    escalateText = article?.escalateText.orEmpty(),
                    safetyAlert = if (safetyEval.level != com.cyclecare.app.data.model.SafetyLevel.LOW) safetyEval else null,
                    source = if (isPersonalNote) "Gentle Health Logger" else "Curated Clinical Knowledge"
                ),
                detectedSymptoms = if (isPersonalNote) healthParse.symptomsResult else emptyList(),
                detectedFlow = if (isPersonalNote) healthParse.flowLevelResult else null,
                detectedEnergy = if (isPersonalNote) healthParse.energyResult else null,
                detectedMood = if (isPersonalNote) healthParse.moodResult else null,
                painIntensity = if (isPersonalNote) healthParse.painIntensityResult else 0,
                gentleProgressCoinsAwarded = 0L,
                economyIntent = economyIntent,
                paymentIntent = paymentIntent,
                isClarificationNeeded = false,
                rawQuery = input
            )
        }

        // CASE B: The language is too complicated, obscure, or ambiguous for the system to understand!
        // Gracefully fail by asking tailored questions with tied-down choices, or offering the calm "experts are busy" message.
        return LayeredChatResult(
            mainResponse = CareResponse(
                summary = "We're here with you",
                whatText = "Your message was gently received, but needs a moment of clarity so we can assist you accurately.",
                understandText = "Every body is unique. Sometimes words overlap between symptoms, questions, and rewards.",
                careText = "Take a calm breath. Select one of the guided options below to help us understand right away.",
                watchText = "If you are experiencing acute physical pain or unusual heavy bleeding, seek clinical help immediately.",
                escalateText = "Never hesitate to consult a doctor for urgent symptoms.",
                safetyAlert = if (safetyEval.level != com.cyclecare.app.data.model.SafetyLevel.LOW) safetyEval else null,
                source = "Care Concierge"
            ),
            isClarificationNeeded = true,
            clarificationMessage = "I want to understand you accurately. Is your message mainly about how your body feels, cycle timing, or rewards? Choose the closest option below, or leave a note to return to later.",
            tiedDownChoices = listOf(
                TiedDownChoice(
                    id = "choice_symptoms",
                    iconEmoji = "🩸",
                    label = "Log symptoms or how my body feels",
                    prefilledQuery = "I want to log my symptoms and energy today"
                ),
                TiedDownChoice(
                    id = "choice_coins",
                    iconEmoji = "🪙",
                    label = "Check Cycle Coins & Rewards",
                    prefilledQuery = "How many coins do I have and what can I unlock?"
                ),
                TiedDownChoice(
                    id = "choice_mpesa",
                    iconEmoji = "📱",
                    label = "M-Pesa payment or coin top-up",
                    prefilledQuery = "I need help with M-Pesa coin package checkout"
                ),
                TiedDownChoice(
                    id = "choice_advice",
                    iconEmoji = "🌿",
                    label = "Cycle phase or period advice",
                    prefilledQuery = "What does my current cycle phase mean for my body?"
                )
            ),
            rawQuery = input
        )
    }


}

package com.example.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.CycleDayLog
import com.example.data.local.CycleSettings
import com.example.data.local.OnboardingProfile
import com.example.data.local.HealthSyncConflictEntity
import com.example.data.model.DayPrediction
import com.example.data.model.HealthPattern
import com.example.data.model.LearnedObservation
import com.example.data.model.ParsedLogInterpretation
import com.example.data.model.SafetyEvaluation
import com.example.data.model.TodayAttentionItem
import com.example.data.model.UserTonePreference
import com.example.data.repository.CycleRepository
import com.example.domain.ai.CareImageGenerationService
import com.example.domain.ai.CareImageGenerationException
import com.example.domain.ai.CareImageStyle
import com.example.domain.ai.CareResponse
import com.example.domain.attention.AttentionEngine
import com.example.domain.economy.MiningUseCase
import com.example.domain.learning.LearningEngine
import com.example.domain.parser.NaturalLanguageLogParser
import com.example.domain.pattern.BaselineMetrics
import com.example.domain.pattern.PatternEngine
import com.example.domain.payment.PaymentPollResult
import com.example.domain.payment.PaymentUseCase
import com.example.domain.safety.SafetyEngine
import com.example.domain.sync.SyncUseCase
import com.example.util.SoundFeedbackManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class AppNavTab(val title: String) {
    HOME("Today"),
    CYCLE("Cycle"),
    TRACK("Log"),
    INSIGHTS("Insights"),
    CARE("Care")
}

data class CareChatTurn(
    val id: String,
    val userQuery: String,
    val response: CareResponse,
    val detectedItemsSummary: String? = null,
    val coinsEarned: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis()
)

data class CycleUiState(
    val currentDate: LocalDate = LocalDate.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val activeTab: AppNavTab = AppNavTab.HOME,
    val settings: CycleSettings = CycleSettings(),
    val todayPrediction: DayPrediction? = null,
    val selectedDatePrediction: DayPrediction? = null,
    val hasCycleAnchor: Boolean = false,
    val cycleStartIsApproximate: Boolean = false,
    val daysUntilNextPeriod: Int = 0,
    val daysUntilOvulation: Int = 0,
    val isPeriodActiveToday: Boolean = false,
    val safetyEvaluation: SafetyEvaluation = SafetyEngine.evaluate(null),
    val baselineMetrics: BaselineMetrics = BaselineMetrics(28.0, 28, 28, 28, 0, false, 0, 0),
    val patterns: List<HealthPattern> = emptyList(),
    val todayAttentionItem: TodayAttentionItem? = null,
    val learnedObservations: List<LearnedObservation> = emptyList(),
    val userTonePreference: UserTonePreference = UserTonePreference.WARM,
    val doctorQuestions: List<String> = emptyList(),
    val careResponse: CareResponse? = null,
    val careChatHistory: List<CareChatTurn> = emptyList(),
    val isCareLoading: Boolean = false,
    val pendingCareLog: ParsedLogInterpretation? = null,
    val pendingCareTurnId: String? = null,
    val careContextPreview: String? = null,
    val isAiSharingAllowed: Boolean = false,
    val aiProviderConsents: Map<String, Boolean> = mapOf("COHERE" to false, "GEMINI" to false, "OPENROUTER" to false),
    val aiProcessorManifest: com.example.domain.ai.AiProcessorManifest? = null,
    val isLoadingAiProcessorManifest: Boolean = false,
    val generatedCareImage: ByteArray? = null,
    val isGeneratingCareImage: Boolean = false,
    val careImageError: String? = null,
    val isAppLocked: Boolean = false,
    val showOnboarding: Boolean = true,
    val isCheckingCloudProfile: Boolean = false,
    val isSomethingDifferentSheetOpen: Boolean = false,
    val currentInterpretation: ParsedLogInterpretation? = null,
    val monthlyTimeline: List<com.example.data.model.MonthlyTimelineEntry> = emptyList(),
    val clinicianReportText: String = "",
    val explainMyBodyResult: com.example.data.model.ExplainMyBodyResult? = null,
    val onboardingProfile: OnboardingProfile? = null,
    val isSyncing: Boolean = false,
    val lastSyncTimestamp: Long = 0L,
    val lastSyncMessage: String = "Never synced",
    val lastSyncSuccess: Boolean = false,
    val sessionNeedsReauthentication: Boolean = false,
    val authMode: String = "ANONYMOUS",
    val userEmail: String? = null,
    val deviceId: String = "",
    val isPinEnabled: Boolean = false,
    val isFingerprintEnabled: Boolean = false,
    val isQuickUnlockAsked: Boolean = false,
    val hasCompletedInitialAuth: Boolean = false,
    val localDataOwnerKey: String = "",
    val isCloudSyncAllowed: Boolean = false,
    val isDeletingCloudHealth: Boolean = false,
    val cloudHealthDeletionMessage: String? = null,
    val isSoundEnabled: Boolean = false,
    val showAuthSheet: Boolean = false,
    val showQuickUnlockDialog: Boolean = false,
    val economyProfile: com.example.data.economy.EconomyProfile = com.example.data.economy.EconomyProfile(),
    val isAdmin: Boolean = false,
    val isVaultOpen: Boolean = false,
    val economyToastMessage: String? = null,
    val layeredChatResult: com.example.domain.ai.LayeredChatResult? = null,
    val activeMpesaPackage: com.example.data.economy.CoinPurchasePackage? = null,
    val isMpesaProcessing: Boolean = false,
    val mpesaStatusMessage: String? = null,
    val isMpesaSuccess: Boolean = false,
    val careSavedReviewMessage: String? = null,
    val isBanned: Boolean = false,
    val isSuspended: Boolean = false,
    val accountNoticeMessage: String? = null,
    val notifications: List<com.example.data.notification.UserNotification> = emptyList(),
    val isNotificationCenterOpen: Boolean = false,
    val giftedModalArtifact: String? = null,
    val activeGiftDialogItem: com.example.data.economy.EconomyItem? = null,
    val dismissedFloatingBannerId: String? = null,
    val activeCheckoutPackage: com.example.data.economy.CoinPurchasePackage? = null,
    val isPaymentProcessing: Boolean = false,
    val paymentStatusMessage: String? = null,
    val isPaymentSuccess: Boolean = false,
    val isEastAfricaRegion: Boolean = false,
    val isPasswordSetupRequired: Boolean = false,
    val passwordSetupMessage: String? = null
    ,val syncConflicts: List<HealthSyncConflictEntity> = emptyList()
    ,val isLoadingSyncConflicts: Boolean = false
    ,val isResolvingSyncConflict: Boolean = false
    ,val syncConflictMessage: String? = null
)

class CycleCareViewModel(
    private val repository: CycleRepository,
    val authPreferences: com.example.security.AuthPreferences,
    val syncService: com.example.data.supabase.SupabaseSyncService,
    val authService: com.example.data.supabase.SupabaseAuthService
) : ViewModel() {

    val economyService = com.example.data.economy.SupabaseEconomyService(authPreferences)
    val guidedChatsRepo = com.example.data.economy.GuidedChatsRepository()
    val paymentService = com.example.data.payment.MpesaPaymentService(authPreferences, economyService)
    val supabasePaymentService = com.example.data.payment.SupabasePaymentService(authPreferences)
    val notificationService = com.example.data.notification.SupabaseNotificationService(authPreferences)

    val paymentUseCase = PaymentUseCase(supabasePaymentService, economyService, authPreferences)
    val miningUseCase = MiningUseCase(economyService, authPreferences)
    val syncUseCase = SyncUseCase(syncService, authPreferences)

    private val _uiState = MutableStateFlow(CycleUiState())
    val uiState: StateFlow<CycleUiState> = _uiState.asStateFlow()
    private val careLogConsentGate = com.example.domain.ai.CareLogConsentGate()
    private var careJob: kotlinx.coroutines.Job? = null
    private var imageJob: kotlinx.coroutines.Job? = null
    private var activeUiOwner = authPreferences.effectiveUserId
    private var activeUiSession = authPreferences.sessionGeneration
    private var careRequestGeneration = 0L
    private var pendingQuickLogOwner: String? = null
    private var pendingCareProposal: com.example.domain.ai.ProposedCareLog? = null
    private val processorConsentService = com.example.domain.ai.AiProcessorConsentService(authPreferences)

    private data class ConflictOwnerSnapshot(val owner: String, val session: Long)

    private fun captureConflictOwnerSnapshot(): ConflictOwnerSnapshot {
        resetIdentityUiIfNeeded()
        return ConflictOwnerSnapshot(authPreferences.effectiveUserId, authPreferences.sessionGeneration)
    }

    private fun isCurrentConflictSnapshot(snapshot: ConflictOwnerSnapshot): Boolean =
        activeUiOwner == snapshot.owner && authPreferences.effectiveUserId == snapshot.owner &&
            authPreferences.sessionGeneration == snapshot.session

    /** Loads only unresolved conflict metadata for the currently authenticated/local owner. */
    fun loadSyncConflicts() {
        val snapshot = captureConflictOwnerSnapshot()
        _uiState.value = _uiState.value.copy(isLoadingSyncConflicts = true, syncConflictMessage = null)
        viewModelScope.launch {
            val result = runCatching { repository.pendingSyncConflicts() }
            if (!isCurrentConflictSnapshot(snapshot)) return@launch
            _uiState.value = _uiState.value.copy(
                syncConflicts = result.getOrDefault(emptyList()),
                isLoadingSyncConflicts = false,
                syncConflictMessage = result.exceptionOrNull()?.let { "Could not load local conflict review." }
            )
        }
    }

    fun resolveSyncConflict(recordId: String, keepLocal: Boolean) {
        if (_uiState.value.isResolvingSyncConflict) return
        val snapshot = captureConflictOwnerSnapshot()
        if (!isCurrentConflictSnapshot(snapshot)) return
        _uiState.value = _uiState.value.copy(isResolvingSyncConflict = true, syncConflictMessage = null)
        viewModelScope.launch {
            val operation = runCatching {
                if (keepLocal) repository.retainLocalConflict(recordId)
                else repository.acceptRemoteConflict(recordId)
            }
            if (!isCurrentConflictSnapshot(snapshot)) return@launch
            val remaining = runCatching { repository.pendingSyncConflicts() }.getOrNull()
            if (!isCurrentConflictSnapshot(snapshot)) return@launch
            val stillUnresolved = remaining?.any { it.recordId == recordId } == true
            _uiState.value = _uiState.value.copy(
                syncConflicts = remaining ?: _uiState.value.syncConflicts,
                isResolvingSyncConflict = false,
                syncConflictMessage = when {
                    operation.isFailure -> "Conflict was not changed: ${operation.exceptionOrNull()?.message ?: "review is unavailable"}."
                    stillUnresolved -> "This conflict changed while it was being reviewed. Refresh before choosing again."
                    else -> "Conflict resolved. Review is complete; no automatic merge was performed."
                }
            )
        }
    }

    private fun resetPrivateConversation() {
        careRequestGeneration++
        careJob?.cancel()
        imageJob?.cancel()
        careLogConsentGate.cancel()
        pendingCareProposal = null
        pendingQuickLogOwner = null
        _uiState.value = _uiState.value.copy(
            careChatHistory = emptyList(), careResponse = null, layeredChatResult = null,
            currentInterpretation = null, pendingCareLog = null, pendingCareTurnId = null,
            careContextPreview = null, isCareLoading = false, generatedCareImage = null,
            isGeneratingCareImage = false, careImageError = null, careSavedReviewMessage = null
        )
    }

    private fun resetIdentityUiIfNeeded() {
        val owner = authPreferences.effectiveUserId
        val session = authPreferences.sessionGeneration
        if (activeUiOwner == owner && activeUiSession == session) return
        activeUiOwner = owner
        activeUiSession = session
        repository.activateCurrentOwner()
        _logsMap.value = emptyMap()
        resetPrivateConversation()
        com.example.data.supabase.SupabaseSyncWorker.cancelSync(authPreferences.context)
        _uiState.value = _uiState.value.copy(
            localDataOwnerKey = owner,
            economyProfile = com.example.data.economy.EconomyProfile(), isAdmin = false,
            safetyEvaluation = SafetyEngine.evaluate(null), monthlyTimeline = emptyList(), doctorQuestions = emptyList(),
            baselineMetrics = BaselineMetrics(28.0, 28, 28, 28, 0, false, 0, 0), todayAttentionItem = null,
            cycleStartIsApproximate = false, daysUntilNextPeriod = 0, daysUntilOvulation = 0,
            isPeriodActiveToday = false, isSomethingDifferentSheetOpen = false, isVaultOpen = false,
            isDeletingCloudHealth = false, cloudHealthDeletionMessage = null,
            notifications = emptyList(), activeGiftDialogItem = null, giftedModalArtifact = null,
            isBanned = false, isSuspended = false, accountNoticeMessage = null,
            onboardingProfile = null, settings = CycleSettings(), hasCycleAnchor = false,
            todayPrediction = null, selectedDatePrediction = null, patterns = emptyList(),
            learnedObservations = emptyList(), clinicianReportText = "", explainMyBodyResult = null,
            isCloudSyncAllowed = authPreferences.isCloudSyncAllowed,
            isAiSharingAllowed = authPreferences.isAiSharingAllowed,
            aiProviderConsents = authPreferences.providerConsents().mapValues { it.value.granted },
            syncConflicts = emptyList(), isLoadingSyncConflicts = false,
            isResolvingSyncConflict = false, syncConflictMessage = null
            ,aiProcessorManifest = null, isLoadingAiProcessorManifest = false
        )
    }

    private val _logsMap = MutableStateFlow<Map<String, CycleDayLog>>(emptyMap())
    val logsMap: StateFlow<Map<String, CycleDayLog>> = _logsMap.asStateFlow()

    init {
        viewModelScope.launch {
            repository.scopedLogs.collect { snapshot ->
                _logsMap.value = if (repository.isCurrentSnapshot(snapshot)) snapshot.logs.associateBy { it.dateString } else emptyMap()
            }
        }
        SoundFeedbackManager.isSoundEnabled = authPreferences.isSoundEnabled

        viewModelScope.launch {
            val settings = repository.getSettings()
            val profile = repository.getOnboardingProfile()
            val hasCompletedInitialAuth = authPreferences.hasCompletedInitialAuth
            val needsCloudProfileCheck = hasCompletedInitialAuth && !authPreferences.isLocalOnlyMode &&
                (profile == null || !settings.isOnboardingCompleted) &&
                com.example.util.NetworkHelper.isOnline(authPreferences.context)
            val isPinSet = authPreferences.isPinEnabled && !authPreferences.pin.isNullOrBlank()
            val isLocked = hasCompletedInitialAuth && (isPinSet || authPreferences.isFingerprintEnabled)
            val showOnboarding = hasCompletedInitialAuth && !needsCloudProfileCheck &&
                (profile == null || !settings.isOnboardingCompleted)

            val isCachedBanned = authPreferences.accountStatus.equals("BANNED", ignoreCase = true)
            val isCachedSuspended = authPreferences.accountStatus.equals("SUSPENDED", ignoreCase = true)
            val cachedNotice = authPreferences.suspensionReason

            _uiState.value = _uiState.value.copy(
                hasCompletedInitialAuth = hasCompletedInitialAuth,
                isAppLocked = isLocked,
                isEastAfricaRegion = com.example.data.economy.CurrencyLocaleManager.detectCountryCode(authPreferences.context) == "KE",
                showOnboarding = showOnboarding,
                isCheckingCloudProfile = needsCloudProfileCheck,
                isCloudSyncAllowed = authPreferences.isCloudSyncAllowed,
                isAiSharingAllowed = authPreferences.isAiSharingAllowed,
                isSoundEnabled = authPreferences.isSoundEnabled,
                settings = settings,
                onboardingProfile = profile,
                authMode = authPreferences.accountMode,
                userEmail = authPreferences.userEmail,
                deviceId = authPreferences.deviceId,
                lastSyncTimestamp = authPreferences.lastSyncTimestamp,
                lastSyncMessage = authPreferences.lastSyncMessage,
                lastSyncSuccess = authPreferences.lastSyncSuccess,
                sessionNeedsReauthentication = false,
                isPinEnabled = authPreferences.isPinEnabled || settings.isAppLockEnabled,
                isFingerprintEnabled = authPreferences.isFingerprintEnabled,
                isQuickUnlockAsked = authPreferences.isQuickUnlockAsked,
                isBanned = isCachedBanned,
                isSuspended = isCachedSuspended,
                accountNoticeMessage = cachedNotice,
                isPasswordSetupRequired = authPreferences.needsPasswordSetup
            )

            if (hasCompletedInitialAuth && !authPreferences.isLocalOnlyMode && authPreferences.accountMode == "ANONYMOUS" &&
                com.example.util.NetworkHelper.isOnline(authPreferences.context)
            ) {
                when (val session = authService.continueAsGuest()) {
                    is com.example.data.supabase.AuthResult.Error -> _uiState.value = _uiState.value.copy(
                        lastSyncMessage = session.errorMessage,
                        lastSyncSuccess = false
                    )
                    else -> Unit
                }
            }

            updateCalculations(settings, LocalDate.now(), LocalDate.now())

            val initOwner = authPreferences.effectiveUserId
            val verifiedEconomy = if (hasCompletedInitialAuth && !authPreferences.isLocalOnlyMode && authPreferences.userId != null)
                economyService.fetchVerifiedProfile(initOwner) else null
            if (authPreferences.effectiveUserId == initOwner && verifiedEconomy is com.example.data.economy.EconomyResult.Success) {
                val econ = verifiedEconomy.data
                _uiState.value = _uiState.value.copy(
                    economyProfile = econ,
                    isBanned = econ.isBanned,
                    isSuspended = econ.isSuspended,
                    accountNoticeMessage = econ.suspensionReason
                )
            }

            refreshNotifications()

            if (!authPreferences.isLocalOnlyMode && authPreferences.userId != null && com.example.util.NetworkHelper.isOnline(authPreferences.context)) {
                val reconciled = miningUseCase.reconcilePendingOfflineClaims(_uiState.value.economyProfile)
                if (reconciled is com.example.data.economy.EconomyResult.Success) {
                    _uiState.value = _uiState.value.copy(economyProfile = reconciled.data)
                }
            }

            if (hasCompletedInitialAuth) {
                autoSyncSilently()
            }
            refreshAiProcessorManifest()
        }

        // Reactive network connectivity observer
        viewModelScope.launch {
            com.example.util.NetworkHelper.observeNetwork(authPreferences.context).collect { isConnected ->
                if (isConnected && !authPreferences.isLocalOnlyMode && authPreferences.userId != null) {
                    val reconciled = miningUseCase.reconcilePendingOfflineClaims(_uiState.value.economyProfile)
                    if (reconciled is com.example.data.economy.EconomyResult.Success) {
                        _uiState.value = _uiState.value.copy(economyProfile = reconciled.data)
                    }
                    val verified = economyService.fetchVerifiedProfile(authPreferences.effectiveUserId)
                    if (verified is com.example.data.economy.EconomyResult.Success) {
                        val econ = verified.data
                        _uiState.value = _uiState.value.copy(
                            economyProfile = econ,
                            isBanned = econ.isBanned,
                            isSuspended = econ.isSuspended,
                            accountNoticeMessage = econ.suspensionReason
                        )
                    }
                    refreshNotifications()
                }
            }
        }

        // Reactively recompute baseline & pattern metrics whenever settings OR logs change
        viewModelScope.launch {
            combine(repository.settingsFlow, repository.scopedLogs) { _, scoped -> scoped }
                .collect { scoped ->
                if (!repository.isCurrentSnapshot(scoped)) return@collect
                val owner = authPreferences.effectiveUserId
                val newSettings = repository.getSettings()
                if (owner != authPreferences.effectiveUserId || !repository.isCurrentSnapshot(scoped)) return@collect
                val allLogs = scoped.logs
                val activeSettings = newSettings ?: _uiState.value.settings
                updateCalculations(
                    settings = activeSettings,
                    today = _uiState.value.currentDate,
                    selectedDate = _uiState.value.selectedDate,
                    explicitLogs = allLogs
                )
            }
        }
    }

    private fun updateCalculations(
        settings: CycleSettings,
        today: LocalDate,
        selectedDate: LocalDate,
        explicitLogs: List<CycleDayLog>? = null
    ) {
        val profile = _uiState.value.onboardingProfile
        val dateChoice = profile?.approximatePeriodChoice?.trim()?.lowercase().orEmpty()
        val unknownDateChoice = dateChoice.isBlank() || dateChoice == "unknown" || dateChoice.contains("don't know") ||
            dateChoice.contains("don’t know") || dateChoice == "it varies"
        val cycleLengthChoice = profile?.cycleLengthOption?.trim()?.lowercase().orEmpty()
        val unknownCycleLengthChoice = cycleLengthChoice.isBlank() || cycleLengthChoice == "unknown" ||
            cycleLengthChoice.contains("don't know") || cycleLengthChoice.contains("don’t know") ||
            cycleLengthChoice.contains("changes")
        val hasCycleAnchor = settings.lastPeriodStartDate.isNotBlank() && !unknownDateChoice && !unknownCycleLengthChoice
        val approximateChoice = dateChoice.startsWith("approximate:") || dateChoice in setOf(
            "today", "a few days ago", "about a week ago", "about two weeks ago"
        )
        val todayPrediction = if (hasCycleAnchor) repository.calculateDayPrediction(today, settings) else null
        val selectedPrediction = if (hasCycleAnchor) repository.calculateDayPrediction(selectedDate, settings) else null
        val cycleLength = settings.averageCycleLength.coerceIn(21, 45)
        val nextPeriodDate = if (hasCycleAnchor) {
            runCatching { LocalDate.parse(settings.lastPeriodStartDate) }.getOrNull()?.let { lastStart ->
                var nextStart = lastStart
                while (!nextStart.isAfter(today)) nextStart = nextStart.plusDays(cycleLength.toLong())
                nextStart
            }
        } else null
        val daysUntilPeriod = nextPeriodDate?.let {
            java.time.temporal.ChronoUnit.DAYS.between(today, it).toInt().coerceAtLeast(0)
        } ?: 0

        val ovulationCycleDay = (cycleLength - settings.lutealPhaseDays).coerceAtLeast(settings.periodDuration + 2)
        val daysUntilOvu = todayPrediction?.let { prediction ->
            if (prediction.dayOfCycle <= ovulationCycleDay) {
                ovulationCycleDay - prediction.dayOfCycle
            } else {
                cycleLength - prediction.dayOfCycle + ovulationCycleDay
            }
        } ?: 0

        val allLogsList = explicitLogs ?: logsMap.value.values.toList()
        val currentLog = allLogsList.find { it.dateString == today.toString() } ?: logsMap.value[today.toString()]
        val safety = SafetyEngine.evaluate(currentLog)
        val baseline = PatternEngine.computeBaseline(settings, allLogsList)
        val detectedPatterns = PatternEngine.detectPatterns(settings, allLogsList)
        val attentionItem = AttentionEngine.evaluateTodayPriority(todayPrediction, safety, settings, allLogsList)
        val learnedObs = LearningEngine.compileLearnedObservations(settings, allLogsList, profile)
        val doctorQ = LearningEngine.generateDoctorSummary(settings, allLogsList)
        val clinicianReport = LearningEngine.generateClinicianReportText(settings, allLogsList, profile)
        val timeline = LearningEngine.generateMonthlyTimeline(settings, allLogsList)
        val explainMyBody = todayPrediction?.let {
            LearningEngine.synthesizeExplainMyBody(settings, allLogsList, it, "")
        }

        _uiState.value = _uiState.value.copy(
            currentDate = today,
            selectedDate = selectedDate,
            settings = settings,
            todayPrediction = todayPrediction,
            selectedDatePrediction = selectedPrediction,
            hasCycleAnchor = hasCycleAnchor,
            cycleStartIsApproximate = approximateChoice,
            daysUntilNextPeriod = daysUntilPeriod,
            daysUntilOvulation = daysUntilOvu,
            isPeriodActiveToday = todayPrediction?.isPeriod == true,
            safetyEvaluation = safety,
            baselineMetrics = baseline,
            patterns = detectedPatterns,
            todayAttentionItem = attentionItem,
            learnedObservations = learnedObs,
            doctorQuestions = doctorQ,
            clinicianReportText = clinicianReport,
            monthlyTimeline = timeline,
            explainMyBodyResult = explainMyBody
        )
    }

    fun selectTab(tab: AppNavTab) {
        _uiState.value = _uiState.value.copy(activeTab = tab)
    }

    fun selectDate(date: LocalDate) {
        val current = _uiState.value
        val selectedPrediction = if (current.hasCycleAnchor) {
            repository.calculateDayPrediction(date, current.settings)
        } else null
        _uiState.value = _uiState.value.copy(
            selectedDate = date,
            selectedDatePrediction = selectedPrediction
        )
    }

    fun openTrackForDate(date: LocalDate) {
        selectDate(date)
        selectTab(AppNavTab.TRACK)
    }

    fun logPeriodStart(date: LocalDate) {
        viewModelScope.launch {
            val current = _uiState.value
            val updatedSettings = current.settings.copy(
                lastPeriodStartDate = date.toString()
            )
            repository.saveSettings(updatedSettings)

            val updatedProfile = current.onboardingProfile?.copy(
                lastPeriodDateString = date.toString(),
                approximatePeriodChoice = "EXACT"
            )
            if (updatedProfile != null) repository.saveOnboardingProfile(updatedProfile)

            SoundFeedbackManager.playGentleRewardChime()
            _uiState.value = _uiState.value.copy(onboardingProfile = updatedProfile)
            updateCalculations(updatedSettings, _uiState.value.currentDate, _uiState.value.selectedDate)
        }
    }

    /**
     * 10-second micro check-in directly from the Home screen with appreciative reward feedback.
     */
    fun quickCheckIn(mood: String, energy: String? = null) {
        viewModelScope.launch {
            val todayStr = _uiState.value.currentDate.toString()
            val existing = repository.getLogForDate(todayStr) ?: CycleDayLog(dateString = todayStr)
            val updated = existing.copy(
                moodLevel = mood,
                energyLevel = energy ?: existing.energyLevel
            )
            repository.saveLog(updated)
            SoundFeedbackManager.playGentleRewardChime()
            updateCalculations(_uiState.value.settings, _uiState.value.currentDate, _uiState.value.selectedDate)


        }
    }

    fun openSomethingDifferentSheet() {
        _uiState.value = _uiState.value.copy(
            isSomethingDifferentSheetOpen = true,
            currentInterpretation = null
        )
    }

    fun closeSomethingDifferentSheet() {
        _uiState.value = _uiState.value.copy(
            isSomethingDifferentSheetOpen = false,
            currentInterpretation = null
        )
    }

    fun parseNaturalLanguageInput(rawText: String) {
        val interpretation = NaturalLanguageLogParser.parse(rawText.take(1200))
        pendingQuickLogOwner = authPreferences.effectiveUserId
        _uiState.value = _uiState.value.copy(currentInterpretation = interpretation)
    }

    fun confirmParsedLog(interpretation: ParsedLogInterpretation) {
        if (_uiState.value.currentInterpretation != interpretation || pendingQuickLogOwner != authPreferences.effectiveUserId) return
        val owner = authPreferences.effectiveUserId
        val date = _uiState.value.currentDate.toString()
        pendingQuickLogOwner = null
        _uiState.value = _uiState.value.copy(currentInterpretation = null, isSomethingDifferentSheetOpen = false)
        viewModelScope.launch {
            if (owner != authPreferences.effectiveUserId) return@launch
            val existing = repository.getLogForDate(date) ?: CycleDayLog(dateString = date)
            if (owner != authPreferences.effectiveUserId) return@launch
            repository.saveLog(com.example.domain.ai.CareConversationPolicy.merge(existing, interpretation))
            updateCalculations(_uiState.value.settings, _uiState.value.currentDate, _uiState.value.selectedDate)
            autoSyncSilently()
        }
    }

    fun updateTonePreference(tone: UserTonePreference) {
        _uiState.value = _uiState.value.copy(userTonePreference = tone)
    }

    fun setSoundEnabled(enabled: Boolean) {
        authPreferences.isSoundEnabled = enabled
        SoundFeedbackManager.isSoundEnabled = enabled
        _uiState.value = _uiState.value.copy(isSoundEnabled = enabled)
        if (enabled) {
            SoundFeedbackManager.playGentleRewardChime()
        }
    }

    fun saveDayLog(log: CycleDayLog) {
        viewModelScope.launch {
            if (!log.hasRecordedObservations()) return@launch
            repository.saveLog(log)
            SoundFeedbackManager.playGentleRewardChime()

            if (log.flowLevel in listOf("LIGHT", "MODERATE", "HEAVY", "UNUSUALLY_HEAVY")) {
                val logDate = LocalDate.parse(log.dateString)
                val currentStart = try { LocalDate.parse(_uiState.value.settings.lastPeriodStartDate) } catch (e: Exception) { null }
                if (currentStart == null || logDate.isAfter(currentStart.plusDays(15))) {
                    val updatedSettings = _uiState.value.settings.copy(
                        lastPeriodStartDate = log.dateString
                    )
                    repository.saveSettings(updatedSettings)
                }
            }

            val safety = SafetyEngine.evaluate(log)
            _uiState.value = _uiState.value.copy(safetyEvaluation = safety)
            autoSyncSilently()


        }
    }

    fun deleteDayLog(dateString: String) {
        viewModelScope.launch {
            repository.deleteLog(dateString)
            autoSyncSilently()
        }
    }

    fun updateSettings(newSettings: CycleSettings) {
        viewModelScope.launch {
            val previousSettings = _uiState.value.settings
            val profile = repository.getOnboardingProfile()
            val updatedProfile = profile?.copy(
                cycleLengthOption = if (newSettings.averageCycleLength != previousSettings.averageCycleLength) {
                    "${newSettings.averageCycleLength} days"
                } else profile.cycleLengthOption,
                cycleLengthDays = newSettings.averageCycleLength,
                periodDurationDays = newSettings.periodDuration
            )
            repository.saveSettings(newSettings)
            if (updatedProfile != null) repository.saveOnboardingProfile(updatedProfile)
            _uiState.value = _uiState.value.copy(onboardingProfile = updatedProfile ?: profile)
            updateCalculations(newSettings, _uiState.value.currentDate, _uiState.value.selectedDate)
            autoSyncSilently()
        }
    }

    fun deleteCloudHealthCopy() {
        if (_uiState.value.isDeletingCloudHealth) return
        if (authPreferences.isLocalOnlyMode || authPreferences.userId.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(cloudHealthDeletionMessage = "No cloud account is connected. Local records have not been deleted.")
            return
        }
        val owner = authPreferences.effectiveUserId
        val session = authPreferences.sessionGeneration
        // A deletion request must also invalidate any queued sync work. Persist
        // the withdrawal before making the RPC so a restart or worker cannot
        // upload health data while deletion is in flight (or after a failed
        // request). The pending revoke is completed by the normal sync path.
        if (authPreferences.pauseHealthSyncFor(owner, session) == null) {
            _uiState.value = _uiState.value.copy(cloudHealthDeletionMessage = "The privacy decision could not be saved or your account changed. No deletion was sent. Retry before assuming deletion succeeded.")
            return
        }
        authPreferences.isAiSharingAllowed = false
        resetPrivateConversation()
        _uiState.value = _uiState.value.copy(isDeletingCloudHealth = true, isCloudSyncAllowed = false,
            isAiSharingAllowed = false, cloudHealthDeletionMessage = "Deleting the cloud health copy…")
        viewModelScope.launch {
            val token = com.example.data.supabase.SupabaseSessionManager.validAccessToken(authPreferences)
            if (authPreferences.effectiveUserId != owner || authPreferences.sessionGeneration != session) return@launch
            val deleted = com.example.domain.ai.CarePrivacyService.deleteCloudHealthCopy(token)
            if (authPreferences.effectiveUserId != owner || authPreferences.sessionGeneration != session) return@launch
            _uiState.value = _uiState.value.copy(isDeletingCloudHealth = false,
                cloudHealthDeletionMessage = if (deleted) "Cloud health records were deleted. Local records remain; sync is off. Account, payment, economy and audit records are retained. Provider-side AI records are not deleted by this action."
                    else "Cloud deletion was not confirmed. Sync remains off. The secure deletion service may not be deployed or the connection failed; retry before assuming deletion succeeded.")
        }
    }

    fun setAiSharingAllowed(enabled: Boolean) {
        // Compatibility path for existing callers. The care UI uses the explicit
        // provider mutation below and never infers consent from server selection.
        if (enabled) {
            authPreferences.isAiSharingAllowed = true
        } else {
            // Compatibility callers (including cloud-health deletion) must
            // revoke provider receipts as well as the aggregate UI switch.
            authPreferences.revokeAllProviderConsents()
            resetPrivateConversation()
        }
        _uiState.value = _uiState.value.copy(
            isAiSharingAllowed = authPreferences.isAiSharingAllowed,
            aiProviderConsents = authPreferences.providerConsents().mapValues { it.value.granted },
            careContextPreview = null
        )
    }

    fun setAiProviderSharingAllowed(provider: String, enabled: Boolean) {
        val owner = authPreferences.effectiveUserId
        if (!enabled) {
            // Revoke locally before network I/O; the pending queue survives offline operation.
            authPreferences.revokeProcessorReceipt(provider)
            authPreferences.setProviderConsent(provider, false)
            resetPrivateConversation()
            _uiState.value = _uiState.value.copy(isAiSharingAllowed = authPreferences.isAiSharingAllowed,
                aiProviderConsents = authPreferences.providerConsents().mapValues { it.value.granted }, careContextPreview = null)
            viewModelScope.launch {
                val token = authPreferences.accessToken ?: return@launch
                val manifest = (processorConsentService.fetchManifest(token) as? com.example.domain.ai.ConsentResult.Success)?.manifest ?: return@launch
                if (owner == authPreferences.effectiveUserId) processorConsentService.setConsent(token, manifest, provider, false)
            }
            return
        }
        // Enabling is explicit but never optimistic: require a fresh eligible manifest and server receipt.
        viewModelScope.launch {
            val token = authPreferences.accessToken
            val manifestResult = if (!token.isNullOrBlank()) processorConsentService.fetchManifest(token) else com.example.domain.ai.ConsentResult.Failure("Sign in to enable online AI")
            val manifest = (manifestResult as? com.example.domain.ai.ConsentResult.Success)?.manifest
            val result = if (manifest != null) processorConsentService.setConsent(token!!, manifest, provider, true) else manifestResult
            if (owner != authPreferences.effectiveUserId) return@launch
            if (result is com.example.domain.ai.ConsentResult.Success) authPreferences.setProviderConsent(provider, true)
            _uiState.value = _uiState.value.copy(isAiSharingAllowed = authPreferences.isAiSharingAllowed,
                aiProviderConsents = authPreferences.providerConsents().mapValues { it.value.granted }, careContextPreview = null,
                careSavedReviewMessage = if (result is com.example.domain.ai.ConsentResult.Failure) result.reason else null)
        }
    }

    fun refreshAiProcessorManifest() {
        val token = authPreferences.accessToken ?: return
        if (authPreferences.isLocalOnlyMode) return
        val owner = authPreferences.effectiveUserId
        _uiState.value = _uiState.value.copy(isLoadingAiProcessorManifest = true)
        viewModelScope.launch {
            val result = processorConsentService.fetchManifest(token)
            if (owner != authPreferences.effectiveUserId) return@launch
            _uiState.value = _uiState.value.copy(
                aiProcessorManifest = (result as? com.example.domain.ai.ConsentResult.Success)?.manifest,
                isLoadingAiProcessorManifest = false
            )
        }
    }

    fun previewCareContext(question: String) {
        val preview = com.example.domain.ai.CareConversationPolicy.compileContext(
            question, logsMap.value.values.toList(), _uiState.value.settings, _uiState.value.currentDate
        )
        _uiState.value = _uiState.value.copy(careContextPreview = preview)
    }

    fun editCareLog(rawText: String) {
        val proposal = careLogConsentGate.propose(authPreferences.effectiveUserId, _uiState.value.currentDate.toString(), rawText)
        pendingCareProposal = proposal
        _uiState.value = _uiState.value.copy(pendingCareLog = proposal?.interpretation)
    }

    fun cancelCareLog() {
        careLogConsentGate.cancel()
        pendingCareProposal = null
        _uiState.value = _uiState.value.copy(pendingCareLog = null, pendingCareTurnId = null)
    }

    fun confirmCareLog() {
        val pending = pendingCareProposal ?: return
        val confirmed = careLogConsentGate.confirm(pending.id, authPreferences.effectiveUserId) ?: return
        pendingCareProposal = null
        _uiState.value = _uiState.value.copy(pendingCareLog = null, pendingCareTurnId = null)
        viewModelScope.launch {
            if (confirmed.ownerId != authPreferences.effectiveUserId) return@launch
            val existing = repository.getLogForDate(confirmed.date) ?: CycleDayLog(dateString = confirmed.date)
            if (confirmed.ownerId != authPreferences.effectiveUserId) return@launch
            repository.saveLog(com.example.domain.ai.CareConversationPolicy.merge(existing, confirmed.interpretation))
            _uiState.value = _uiState.value.copy(careSavedReviewMessage = "Saved to your health log. No coins were awarded for this health entry.")
            updateCalculations(_uiState.value.settings, _uiState.value.currentDate, _uiState.value.selectedDate)
            autoSyncSilently()
        }
    }

    fun consultCare(question: String, includeFullContext: Boolean = false) {
        val cleanQuestion = question.trim()
        if (cleanQuestion.isEmpty() || cleanQuestion.length > 1200 || _uiState.value.isCareLoading) return
        resetIdentityUiIfNeeded()
        val owner = authPreferences.effectiveUserId
        val session = authPreferences.sessionGeneration
        val current = _uiState.value
        val safety = SafetyEngine.evaluateInput(cleanQuestion, logsMap.value[current.currentDate.toString()])
        // Safety and local help are never blocked by connectivity, consent, restrictions or tier.
        val remoteAllowed = authPreferences.isAiSharingAllowed &&
            com.example.util.NetworkHelper.isOnline(authPreferences.context) && !current.isBanned && !current.isSuspended && !safety.isRedFlag
        val eligible = current.isAdmin || current.economyProfile.tier == com.example.data.economy.UserEconomyTier.PREMIUM
        val contextSummary = if (includeFullContext && remoteAllowed && eligible && !safety.isRedFlag) {
            val compiled = com.example.domain.ai.CareConversationPolicy.compileContext(
                cleanQuestion, logsMap.value.values.toList(), current.settings, current.currentDate
            )
            // The exact context must have been previewed. Changed data/question requires another review.
            if (current.careContextPreview != compiled) {
                _uiState.value = current.copy(careContextPreview = compiled,
                    careSavedReviewMessage = "Review the selected context, then send again to include it.")
                return
            }
            compiled
        } else null
        val proposal = careLogConsentGate.propose(owner, current.currentDate.toString(), cleanQuestion)
        pendingCareProposal = proposal
        val turnId = java.util.UUID.randomUUID().toString()
        val requestGeneration = ++careRequestGeneration
        fun isCurrentCareRequest() = owner == authPreferences.effectiveUserId && session == authPreferences.sessionGeneration &&
            requestGeneration == careRequestGeneration
        _uiState.value = current.copy(isCareLoading = true, careResponse = null, layeredChatResult = null, safetyEvaluation = safety,
            pendingCareLog = proposal?.interpretation, pendingCareTurnId = if (proposal != null) turnId else null,
            careSavedReviewMessage = null)
        careJob = viewModelScope.launch {
            try {
                val consentReady = remoteAllowed && com.example.domain.ai.CarePrivacyService.updateConsents(
                    authPreferences.accessToken, authPreferences.isAiSharingAllowed, authPreferences.isCloudSyncAllowed, false
                )
                if (!isCurrentCareRequest()) return@launch
                val result = com.example.domain.ai.LayeredChatOrchestrator.processRawChat(
                    rawInput = cleanQuestion, todayLog = logsMap.value[current.currentDate.toString()],
                    settings = current.settings, economyProfile = current.economyProfile,
                    accessToken = if (consentReady && authPreferences.isAiSharingAllowed) authPreferences.accessToken else null,
                    forceAi = includeFullContext && contextSummary != null,
                    contextSummary = contextSummary,
                    allowRemoteAi = consentReady && authPreferences.isAiSharingAllowed,
                    conversationHistory = if (consentReady) current.careChatHistory
                        .filter { it.response.source == "CycleCare AI" }.takeLast(3).flatMap {
                            listOf(com.example.domain.ai.CareMemoryMessage("user", it.userQuery.take(600)),
                                com.example.domain.ai.CareMemoryMessage("assistant", (it.response.summary + " " + it.response.whatText).take(600)))
                        } else emptyList(),
                    authPreferences = authPreferences
                )
                if (!isCurrentCareRequest()) return@launch
                val turn = CareChatTurn(id = turnId, userQuery = cleanQuestion, response = result.mainResponse,
                    detectedItemsSummary = proposal?.interpretation?.items?.joinToString(" · ") { it.label }, coinsEarned = 0L)
                _uiState.value = _uiState.value.copy(careResponse = result.mainResponse, layeredChatResult = result,
                    careChatHistory = (_uiState.value.careChatHistory + turn).takeLast(24), isCareLoading = false)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrentCareRequest()) _uiState.value = _uiState.value.copy(
                    isCareLoading = false, careSavedReviewMessage = "This explanation couldn't finish. Your health log has not changed.")
            } finally {
                if (isCurrentCareRequest()) _uiState.value = _uiState.value.copy(isCareLoading = false)
            }
        }
    }

    fun selectTiedDownChoice(choice: com.example.domain.ai.TiedDownChoice) {
        consultCare(choice.prefilledQuery)
    }

    fun generateCareImage(style: CareImageStyle, customPrompt: String = "") {
        val current = _uiState.value
        if (current.isGeneratingCareImage) return
        val hasPremiumAccess = current.isAdmin ||
            current.economyProfile.tier == com.example.data.economy.UserEconomyTier.PREMIUM
        if (!hasPremiumAccess) {
            _uiState.value = current.copy(careImageError = "Image creation is available with CycleCare Premium.")
            return
        }
        val accessToken = authPreferences.accessToken
        if (accessToken.isNullOrBlank()) {
            _uiState.value = current.copy(careImageError = "Please sign in again before creating an image.")
            return
        }

        if (customPrompt.length > 500) {
            _uiState.value = current.copy(careImageError = "Keep your decorative prompt under 500 characters.")
            return
        }
        val owner = authPreferences.effectiveUserId
        _uiState.value = current.copy(isGeneratingCareImage = true, careImageError = null, generatedCareImage = null)
        imageJob = viewModelScope.launch {
            try {
                val consentReady = com.example.domain.ai.CarePrivacyService.updateConsents(
                    accessToken, authPreferences.isAiSharingAllowed, authPreferences.isCloudSyncAllowed, true
                )
                if (!consentReady) throw CareImageGenerationException("Image creation isn't ready. Your privacy choice could not be verified.")
                if (owner != authPreferences.effectiveUserId) return@launch
                val bytes = CareImageGenerationService.generate(style, accessToken, customPrompt)
                if (owner != authPreferences.effectiveUserId) return@launch
                _uiState.value = _uiState.value.copy(
                    generatedCareImage = bytes,
                    isGeneratingCareImage = false,
                    careImageError = null
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: CareImageGenerationException) {
                _uiState.value = _uiState.value.copy(
                    isGeneratingCareImage = false,
                    careImageError = error.userMessage
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    isGeneratingCareImage = false,
                    careImageError = "Image creation is temporarily unavailable. Please try again later."
                )
            }
        }
    }

    fun clearGeneratedCareImage() {
        _uiState.value = _uiState.value.copy(generatedCareImage = null, careImageError = null)
    }

    fun saveThoughtForCareReview(note: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                careSavedReviewMessage = "This conversation stays on this screen until you clear it or leave this session. It has not been sent to a clinician or saved as a health record.",
                layeredChatResult = _uiState.value.layeredChatResult?.copy(isClarificationNeeded = false)
            )
        }
    }

    fun openMpesaDialog(pkg: com.example.data.economy.CoinPurchasePackage) {
        openUnifiedCheckout(pkg)
    }

    fun openUnifiedCheckout(pkg: com.example.data.economy.CoinPurchasePackage) {
        _uiState.value = _uiState.value.copy(
            activeCheckoutPackage = pkg,
            isPaymentProcessing = false,
            isPaymentSuccess = false,
            paymentStatusMessage = null
        )
    }

    fun dismissUnifiedCheckout() {
        _uiState.value = _uiState.value.copy(
            activeCheckoutPackage = null,
            isPaymentProcessing = false,
            isPaymentSuccess = false,
            paymentStatusMessage = null
        )
    }

    fun toggleLocationRegion(isEastAfrica: Boolean) {
        _uiState.value = _uiState.value.copy(isEastAfricaRegion = isEastAfrica)
    }

    fun processUnifiedMpesa(phoneNumber: String, amountKes: Double) {
        val pkg = _uiState.value.activeCheckoutPackage ?: return
        if (!com.example.util.NetworkHelper.isOnline(authPreferences.context)) {
            _uiState.value = _uiState.value.copy(
                isPaymentProcessing = false,
                isPaymentSuccess = false,
                paymentStatusMessage = "Network required. Please connect to the internet to process M-Pesa payments."
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isPaymentProcessing = true,
                paymentStatusMessage = "Dispatching M-Pesa STK Push to $phoneNumber..."
            )
            val result = supabasePaymentService.initiateMpesaStkPush(phoneNumber, amountKes, pkg, _uiState.value.economyProfile)
            val extRef = result.externalReference ?: ""
            if (result.isSuccess && extRef.isNotBlank()) {
                _uiState.value = _uiState.value.copy(
                    paymentStatusMessage = "M-Pesa STK prompt sent. Enter your PIN on your phone to complete..."
                )
                pollAndCompletePayment(extRef, timeoutSeconds = 60L)
            } else {
                _uiState.value = _uiState.value.copy(
                    isPaymentProcessing = false,
                    isPaymentSuccess = false,
                    paymentStatusMessage = result.message
                )
            }
        }
    }

    fun processUnifiedCard(cardNumber: String, expiry: String, cvv: String) {
        val pkg = _uiState.value.activeCheckoutPackage ?: return
        if (!com.example.util.NetworkHelper.isOnline(authPreferences.context)) {
            _uiState.value = _uiState.value.copy(
                isPaymentProcessing = false,
                isPaymentSuccess = false,
                paymentStatusMessage = "Network required. Please connect to the internet to process Card payments."
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isPaymentProcessing = true,
                paymentStatusMessage = "Verifying Card payment with PayPal ACDC..."
            )
            val result = paymentUseCase.processCard(cardNumber, expiry, cvv, pkg, _uiState.value.economyProfile)
            val extRef = result.externalReference ?: ""
            if (result.isSuccess && extRef.isNotBlank()) {
                pollAndCompletePayment(extRef, timeoutSeconds = 45L)
            } else {
                _uiState.value = _uiState.value.copy(
                    isPaymentProcessing = false,
                    isPaymentSuccess = false,
                    paymentStatusMessage = result.message
                )
            }
        }
    }

    fun processUnifiedPaypal() {
        val pkg = _uiState.value.activeCheckoutPackage ?: return
        if (!com.example.util.NetworkHelper.isOnline(authPreferences.context)) {
            _uiState.value = _uiState.value.copy(
                isPaymentProcessing = false,
                isPaymentSuccess = false,
                paymentStatusMessage = "Network required. Please connect to the internet to process PayPal payments."
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isPaymentProcessing = true,
                paymentStatusMessage = "Verifying PayPal order..."
            )
            val result = paymentUseCase.processPaypal(pkg, _uiState.value.economyProfile)
            val extRef = result.externalReference ?: ""
            if (result.isSuccess && extRef.isNotBlank()) {
                pollAndCompletePayment(extRef, timeoutSeconds = 45L)
            } else {
                _uiState.value = _uiState.value.copy(
                    isPaymentProcessing = false,
                    isPaymentSuccess = false,
                    paymentStatusMessage = result.message
                )
            }
        }
    }

    fun processUnifiedCrypto(currency: String) {
        val pkg = _uiState.value.activeCheckoutPackage ?: return
        if (!com.example.util.NetworkHelper.isOnline(authPreferences.context)) {
            _uiState.value = _uiState.value.copy(
                isPaymentProcessing = false,
                isPaymentSuccess = false,
                paymentStatusMessage = "Network required. Please connect to the internet to process Crypto payments."
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isPaymentProcessing = true,
                paymentStatusMessage = "Waiting for OKX $currency deposit confirmation..."
            )
            val result = paymentUseCase.processCrypto(currency, pkg, _uiState.value.economyProfile)
            val extRef = result.externalReference ?: ""
            if (result.isSuccess && extRef.isNotBlank()) {
                pollAndCompletePayment(extRef, timeoutSeconds = 120L)
            } else {
                _uiState.value = _uiState.value.copy(
                    isPaymentProcessing = false,
                    isPaymentSuccess = false,
                    paymentStatusMessage = result.message
                )
            }
        }
    }

    fun dismissMpesaDialog() {
        _uiState.value = _uiState.value.copy(
            activeMpesaPackage = null,
            isMpesaProcessing = false,
            isMpesaSuccess = false,
            mpesaStatusMessage = null
        )
    }

    fun initiateMpesaStkPush(phoneNumber: String, amountKes: Double, pkg: com.example.data.economy.CoinPurchasePackage) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isMpesaProcessing = true,
                mpesaStatusMessage = "M-Pesa STK prompt sent to $phoneNumber. Confirm with PIN..."
            )
            val result = paymentUseCase.initiateMpesa(
                phoneNumber = phoneNumber,
                amountKes = amountKes,
                pkg = pkg,
                profile = _uiState.value.economyProfile
            )
            val extRef = result.externalReference ?: ""
            if (result.isSuccess && extRef.isNotBlank()) {
                pollMpesaStatus(extRef, timeoutSeconds = 60L)
            } else {
                _uiState.value = _uiState.value.copy(
                    isMpesaProcessing = false,
                    mpesaStatusMessage = result.message
                )
            }
        }
    }

    private fun pollAndCompletePayment(extRef: String, timeoutSeconds: Long) {
        viewModelScope.launch {
            when (val pollResult = paymentUseCase.pollPaymentStatus(extRef, timeoutSeconds, _uiState.value.economyProfile)) {
                is PaymentPollResult.Success -> {
                    SoundFeedbackManager.playArtifactAcquired(528f)
                    _uiState.value = _uiState.value.copy(
                        economyProfile = pollResult.profile,
                        isPaymentProcessing = false,
                        isPaymentSuccess = true,
                        paymentStatusMessage = pollResult.message
                    )
                }
                is PaymentPollResult.Failed -> {
                    _uiState.value = _uiState.value.copy(
                        isPaymentProcessing = false,
                        isPaymentSuccess = false,
                        paymentStatusMessage = pollResult.message
                    )
                }
                is PaymentPollResult.Timeout -> {
                    _uiState.value = _uiState.value.copy(
                        isPaymentProcessing = false,
                        isPaymentSuccess = false,
                        paymentStatusMessage = pollResult.message
                    )
                }
            }
        }
    }

    private fun pollMpesaStatus(extRef: String, timeoutSeconds: Long) {
        viewModelScope.launch {
            when (val pollResult = paymentUseCase.pollPaymentStatus(extRef, timeoutSeconds, _uiState.value.economyProfile)) {
                is PaymentPollResult.Success -> {
                    SoundFeedbackManager.playArtifactAcquired(528f)
                    _uiState.value = _uiState.value.copy(
                        economyProfile = pollResult.profile,
                        isMpesaProcessing = false,
                        isMpesaSuccess = true,
                        mpesaStatusMessage = "M-Pesa payment verified! Coins added to your vault."
                    )
                }
                is PaymentPollResult.Failed -> {
                    _uiState.value = _uiState.value.copy(
                        isMpesaProcessing = false,
                        isMpesaSuccess = false,
                        mpesaStatusMessage = "M-Pesa status: ${pollResult.status.uppercase()}. Transaction was not completed."
                    )
                }
                is PaymentPollResult.Timeout -> {
                    _uiState.value = _uiState.value.copy(
                        isMpesaProcessing = false,
                        isMpesaSuccess = false,
                        mpesaStatusMessage = "M-Pesa verification timed out. If money was deducted, balance will update automatically."
                    )
                }
            }
        }
    }

    fun cancelActivePayment(extRef: String) {
        viewModelScope.launch {
            paymentUseCase.cancelTransaction(extRef)
            _uiState.value = _uiState.value.copy(
                isPaymentProcessing = false,
                isMpesaProcessing = false,
                paymentStatusMessage = "Payment request cancelled.",
                mpesaStatusMessage = "M-Pesa transaction cancelled."
            )
        }
    }

    fun triggerManualSync() {
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)
            val result = syncUseCase.executeSync()
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                lastSyncTimestamp = result.timestamp,
                lastSyncMessage = result.message,
                lastSyncSuccess = result.isSuccess,
                sessionNeedsReauthentication = if (result.isSuccess) false else
                    (result.requiresAuthentication || _uiState.value.sessionNeedsReauthentication)
            )
        }
    }

    fun autoSyncSilently() {
        if (authPreferences.isLocalOnlyMode || authPreferences.userId == null) {
            _uiState.value = _uiState.value.copy(isCheckingCloudProfile = false,
                showOnboarding = !_uiState.value.settings.isOnboardingCompleted)
            return
        }
        val owner = authPreferences.effectiveUserId
        viewModelScope.launch {
            val result = syncUseCase.executeSync()
            if (owner != authPreferences.effectiveUserId) return@launch
            val restoredSettings = repository.getSettings()
            val restoredProfile = repository.getOnboardingProfile()
            val current = _uiState.value
            val showOnboarding = if (current.isCheckingCloudProfile) {
                restoredProfile == null || !restoredSettings.isOnboardingCompleted
            } else current.showOnboarding
            val isAdmin = runCatching { authService.isCycleCareAdmin() }.getOrDefault(false)
            if (owner != authPreferences.effectiveUserId) return@launch
            _uiState.value = _uiState.value.copy(
                lastSyncTimestamp = result.timestamp,
                lastSyncMessage = result.message,
                lastSyncSuccess = result.isSuccess,
                settings = restoredSettings,
                onboardingProfile = restoredProfile,
                showOnboarding = showOnboarding,
                isCheckingCloudProfile = false,
                isAdmin = isAdmin,
                sessionNeedsReauthentication = if (result.isSuccess) false else
                    (result.requiresAuthentication || _uiState.value.sessionNeedsReauthentication)
            )
            updateCalculations(restoredSettings, current.currentDate, current.selectedDate)
        }
    }

    fun unlockApp(pin: String): Boolean {
        if (authPreferences.verifyPin(pin)) {
            _uiState.value = _uiState.value.copy(isAppLocked = false)
            return true
        }
        if (authPreferences.pin.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(isAppLocked = false)
            return true
        }
        return false
    }

    fun unlockViaBiometric() {
        _uiState.value = _uiState.value.copy(isAppLocked = false)
    }

    fun saveQuickUnlockPin(pin: String) {
        authPreferences.pin = pin
        authPreferences.isPinEnabled = true
        authPreferences.isQuickUnlockAsked = true
        _uiState.value = _uiState.value.copy(
            isPinEnabled = true,
            isQuickUnlockAsked = true,
            showQuickUnlockDialog = false
        )
        val current = _uiState.value.settings
        updateSettings(current.copy(isAppLockEnabled = true))
    }

    fun enableFingerprintUnlock() {
        authPreferences.isFingerprintEnabled = true
        authPreferences.isQuickUnlockAsked = true
        _uiState.value = _uiState.value.copy(
            isFingerprintEnabled = true,
            isQuickUnlockAsked = true,
            showQuickUnlockDialog = false
        )
    }

    fun setPinProtectionEnabled(enabled: Boolean) {
        authPreferences.isPinEnabled = enabled
        _uiState.value = _uiState.value.copy(isPinEnabled = enabled)
        val current = _uiState.value.settings
        updateSettings(current.copy(isAppLockEnabled = enabled))
    }

    fun setFingerprintProtectionEnabled(enabled: Boolean) {
        authPreferences.isFingerprintEnabled = enabled
        _uiState.value = _uiState.value.copy(isFingerprintEnabled = enabled)
    }

    fun markQuickUnlockPromptDismissed() {
        authPreferences.isQuickUnlockAsked = true
        _uiState.value = _uiState.value.copy(
            isQuickUnlockAsked = true,
            showQuickUnlockDialog = false
        )
    }

    fun setShowAuthSheet(show: Boolean) {
        _uiState.value = _uiState.value.copy(showAuthSheet = show)
    }

    fun setShowQuickUnlockDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showQuickUnlockDialog = show)
    }

    fun onAuthCompleted(isAnonymous: Boolean) {
        authPreferences.isLocalOnlyMode = false
        resetIdentityUiIfNeeded()
        _uiState.value = _uiState.value.copy(
            showAuthSheet = false,
            isAppLocked = false,
            sessionNeedsReauthentication = false,
            authMode = authPreferences.accountMode,
            userEmail = authPreferences.userEmail,
            deviceId = authPreferences.deviceId,
            showQuickUnlockDialog = !authPreferences.isQuickUnlockAsked
        )
        autoSyncSilently()
    }

    fun startLocalOnly() {
        authPreferences.clearSession()
        authPreferences.isLocalOnlyMode = true
        authPreferences.hasCompletedInitialAuth = true
        authPreferences.isCloudSyncAllowed = false
        authPreferences.isAiSharingAllowed = false
        resetIdentityUiIfNeeded()
        viewModelScope.launch {
            val settings = repository.getSettings()
            val profile = repository.getOnboardingProfile()
            _uiState.value = _uiState.value.copy(hasCompletedInitialAuth = true, authMode = "ANONYMOUS",
                settings = settings, onboardingProfile = profile, showOnboarding = !settings.isOnboardingCompleted,
                isCheckingCloudProfile = false, isAppLocked = authPreferences.isPinEnabled || authPreferences.isFingerprintEnabled,
                isCloudSyncAllowed = false, isAiSharingAllowed = false, lastSyncMessage = "Local only · no cloud account created")
        }
    }

    fun completeInitialAuth(isAnonymous: Boolean) {
        authPreferences.isLocalOnlyMode = false
        resetIdentityUiIfNeeded()
        authPreferences.hasCompletedInitialAuth = true
        _uiState.value = _uiState.value.copy(
            hasCompletedInitialAuth = true,
            isAppLocked = false,
            sessionNeedsReauthentication = false,
            authMode = authPreferences.accountMode,
            userEmail = authPreferences.userEmail,
            deviceId = authPreferences.deviceId,
            showQuickUnlockDialog = !authPreferences.isQuickUnlockAsked,
            showOnboarding = false,
            isCheckingCloudProfile = true
        )
        autoSyncSilently()
    }

    fun handleAuthDeepLink(uri: Uri) {
        viewModelScope.launch {
            when (val result = authService.completeEmailLink(uri)) {
                is com.example.data.supabase.AuthResult.Success -> {
                    authPreferences.hasCompletedInitialAuth = true
                    resetIdentityUiIfNeeded()
                    val settings = repository.getSettings()
                    val profile = repository.getOnboardingProfile()
                    _uiState.value = _uiState.value.copy(
                        hasCompletedInitialAuth = true,
                        isAppLocked = false,
                        sessionNeedsReauthentication = false,
                        authMode = authPreferences.accountMode,
                        userEmail = authPreferences.userEmail,
                        deviceId = authPreferences.deviceId,
                        showOnboarding = false,
                        isCheckingCloudProfile = true,
                        onboardingProfile = profile,
                        settings = settings,
                        isPasswordSetupRequired = authPreferences.needsPasswordSetup,
                        passwordSetupMessage = result.message,
                        lastSyncMessage = result.message,
                        lastSyncSuccess = true
                    )
                    autoSyncSilently()
                }
                is com.example.data.supabase.AuthResult.VerificationRequired -> _uiState.value = _uiState.value.copy(
                    lastSyncMessage = result.message,
                    lastSyncSuccess = false
                )
                is com.example.data.supabase.AuthResult.Error -> _uiState.value = _uiState.value.copy(
                    lastSyncMessage = result.errorMessage,
                    lastSyncSuccess = false
                )
            }
        }
    }

    fun completePasswordSetup(password: String) {
        viewModelScope.launch {
            when (val result = authService.setPassword(password)) {
                is com.example.data.supabase.AuthResult.Success -> _uiState.value = _uiState.value.copy(
                    isPasswordSetupRequired = false,
                    passwordSetupMessage = result.message
                )
                is com.example.data.supabase.AuthResult.VerificationRequired -> _uiState.value = _uiState.value.copy(
                    passwordSetupMessage = result.message
                )
                is com.example.data.supabase.AuthResult.Error -> _uiState.value = _uiState.value.copy(
                    passwordSetupMessage = result.errorMessage
                )
            }
        }
    }

    fun dismissPasswordSetup() {
        _uiState.value = _uiState.value.copy(isPasswordSetupRequired = false)
    }

    fun setVaultOpen(open: Boolean) {
        if (open && (_uiState.value.isBanned || _uiState.value.isSuspended)) return
        _uiState.value = _uiState.value.copy(isVaultOpen = open)
    }

    fun mineCoinsManual() {
        viewModelScope.launch {
            val res = miningUseCase.mineDailyAttunement(_uiState.value.economyProfile, baseReward = 30L)
            if (res is com.example.data.economy.EconomyResult.Success) {
                SoundFeedbackManager.playGentleRewardChime()
                _uiState.value = _uiState.value.copy(
                    economyProfile = res.data,
                    economyToastMessage = res.message
                )
            } else if (res is com.example.data.economy.EconomyResult.Error) {
                _uiState.value = _uiState.value.copy(economyToastMessage = res.errorMessage)
            }
        }
    }

    fun transferCoins(recipientEmail: String, amount: Long) {
        if (!com.example.util.NetworkHelper.isOnline(authPreferences.context)) {
            _uiState.value = _uiState.value.copy(
                economyToastMessage = "Peer coin transfers require an active network connection."
            )
            return
        }
        viewModelScope.launch {
            val res = miningUseCase.transferCoins(_uiState.value.economyProfile, recipientEmail, amount)
            if (res is com.example.data.economy.EconomyResult.Success) {
                SoundFeedbackManager.playGentleRewardChime()
                _uiState.value = _uiState.value.copy(
                    economyProfile = res.data,
                    economyToastMessage = res.message
                )
            } else if (res is com.example.data.economy.EconomyResult.Error) {
                _uiState.value = _uiState.value.copy(economyToastMessage = res.errorMessage)
            }
        }
    }

    fun unlockArtifact(item: com.example.data.economy.EconomyItem) {
        viewModelScope.launch {
            val res = miningUseCase.purchaseArtifact(_uiState.value.economyProfile, item)
            if (res is com.example.data.economy.EconomyResult.Success) {
                SoundFeedbackManager.playArtifactAcquired(item.harmonicPitchHz)
                _uiState.value = _uiState.value.copy(
                    economyProfile = res.data,
                    economyToastMessage = res.message
                )
            } else if (res is com.example.data.economy.EconomyResult.Error) {
                _uiState.value = _uiState.value.copy(economyToastMessage = res.errorMessage)
            }
        }
    }

    fun equipItem(item: com.example.data.economy.EconomyItem) {
        viewModelScope.launch {
            when (val res = miningUseCase.equipItem(_uiState.value.economyProfile, item)) {
                is com.example.data.economy.EconomyResult.Success -> {
                    SoundFeedbackManager.playArtifactInteraction(item.harmonicPitchHz)
                    _uiState.value = _uiState.value.copy(
                        economyProfile = res.data,
                        economyToastMessage = res.message
                    )
                }
                is com.example.data.economy.EconomyResult.Error -> _uiState.value = _uiState.value.copy(economyToastMessage = res.errorMessage)
            }
        }
    }

    fun purchasePackage(pkg: com.example.data.economy.CoinPurchasePackage) {
        viewModelScope.launch {
            when (val res = miningUseCase.purchasePackage(_uiState.value.economyProfile, pkg)) {
                is com.example.data.economy.EconomyResult.Success -> _uiState.value = _uiState.value.copy(
                    economyProfile = res.data,
                    economyToastMessage = res.message
                )
                is com.example.data.economy.EconomyResult.Error -> _uiState.value = _uiState.value.copy(economyToastMessage = res.errorMessage)
            }
        }
    }

    fun upgradeEconomyTier(tier: com.example.data.economy.UserEconomyTier) {
        viewModelScope.launch {
            when (val res = miningUseCase.upgradeTier(_uiState.value.economyProfile, tier)) {
                is com.example.data.economy.EconomyResult.Success -> _uiState.value = _uiState.value.copy(
                    economyProfile = res.data,
                    economyToastMessage = res.message
                )
                is com.example.data.economy.EconomyResult.Error -> _uiState.value = _uiState.value.copy(economyToastMessage = res.errorMessage)
            }
        }
    }

    fun clearEconomyToast() {
        _uiState.value = _uiState.value.copy(economyToastMessage = null)
    }

    fun clearCareChatHistory() {
        resetPrivateConversation()
    }

    fun setNotificationCenterOpen(open: Boolean) {
        _uiState.value = _uiState.value.copy(isNotificationCenterOpen = open)
        if (open) refreshNotifications()
    }

    fun dismissGiftModal() {
        _uiState.value = _uiState.value.copy(
            giftedModalArtifact = null,
            activeGiftDialogItem = null
        )
    }

    fun dismissFloatingBanner(notificationId: String) {
        _uiState.value = _uiState.value.copy(dismissedFloatingBannerId = notificationId)
    }

    fun reviveInactivityStreak() {
        viewModelScope.launch {
            val res = miningUseCase.reviveStreak(_uiState.value.economyProfile)
            if (res is com.example.data.economy.EconomyResult.Error) {
                _uiState.value = _uiState.value.copy(economyToastMessage = res.errorMessage)
            }
        }
    }

    fun refreshNotifications() {
        if (authPreferences.isLocalOnlyMode || authPreferences.userId == null) return
        viewModelScope.launch {
            val userIdentifier = authPreferences.effectiveUserId
            val list = notificationService.fetchNotifications(userIdentifier)

            val verified = economyService.fetchVerifiedProfile(userIdentifier)
            var giftItem: com.example.data.economy.EconomyItem? = null

            if (verified is com.example.data.economy.EconomyResult.Success) {
                val econ = verified.data
                val lastGiftName = econ.giftedArtifacts.lastOrNull()
                if (!lastGiftName.isNullOrBlank()) {
                    giftItem = com.example.data.economy.EconomyCatalog.findItem(lastGiftName)
                        ?: com.example.data.economy.EconomyCatalog.ALL_ITEMS[1]
                }

                if (giftItem == null) {
                    val unreadGiftNotif = list.firstOrNull { !it.isRead && it.type.equals("GIFT", ignoreCase = true) }
                    if (unreadGiftNotif != null) {
                        giftItem = com.example.data.economy.EconomyCatalog.ALL_ITEMS.find {
                            unreadGiftNotif.body.contains(it.name, ignoreCase = true) || unreadGiftNotif.title.contains(it.name, ignoreCase = true)
                        } ?: com.example.data.economy.EconomyCatalog.ALL_ITEMS[1]
                    }
                }

                _uiState.value = _uiState.value.copy(
                    notifications = list,
                    economyProfile = econ,
                    isBanned = econ.isBanned,
                    isSuspended = econ.isSuspended,
                    accountNoticeMessage = econ.suspensionReason,
                    activeGiftDialogItem = giftItem ?: _uiState.value.activeGiftDialogItem
                )
            } else {
                _uiState.value = _uiState.value.copy(notifications = list)
            }
        }
    }

    fun markNotificationAsRead(id: String) {
        viewModelScope.launch {
            notificationService.markAsRead(id)
            val updated = _uiState.value.notifications.map {
                if (it.id == id) it.copy(isRead = true) else it
            }
            _uiState.value = _uiState.value.copy(notifications = updated)
        }
    }

    fun markAllNotificationsAsRead() {
        viewModelScope.launch {
            val userIdentifier = authPreferences.userId ?: authPreferences.deviceId
            notificationService.markAllAsRead(userIdentifier)
            val updated = _uiState.value.notifications.map { it.copy(isRead = true) }
            _uiState.value = _uiState.value.copy(notifications = updated)
        }
    }

    fun signOut() {
        authPreferences.isLocalOnlyMode = false
        authPreferences.hasCompletedInitialAuth = false
        resetPrivateConversation()
        com.example.data.supabase.SupabaseSyncWorker.cancelSync(authPreferences.context)
        authService.signOut()
        resetIdentityUiIfNeeded()
        _uiState.value = _uiState.value.copy(
            authMode = "ANONYMOUS",
            hasCompletedInitialAuth = false,
            showOnboarding = true,
            sessionNeedsReauthentication = false,
            userEmail = null,
            deviceId = authPreferences.deviceId,
            isAdmin = false
        )
    }

    fun lockApp() {
        val isLocked = _uiState.value.isPinEnabled || _uiState.value.isFingerprintEnabled || _uiState.value.settings.isAppLockEnabled
        if (isLocked) {
            _uiState.value = _uiState.value.copy(isAppLocked = true)
        }
    }

    fun completeOnboarding(
        goals: List<String>,
        lastPeriodDate: LocalDate,
        periodStartChoice: String = "EXACT",
        cycleLength: Int,
        cycleLengthOption: String = "",
        periodDuration: Int,
        pin: String,
        enableLock: Boolean,
        remindFertile: Boolean = false,
        typicalSymptoms: List<String> = emptyList(),
        typicalEnergy: String = "",
        typicalSleep: String = "",
        hasCompletedDeepBaseline: Boolean = false,
        isCloudSyncAllowed: Boolean = false
    ) {
        viewModelScope.launch {
            authPreferences.isCloudSyncAllowed = isCloudSyncAllowed
            if (pin.isNotBlank()) {
                authPreferences.pin = pin
                authPreferences.isPinEnabled = enableLock
                authPreferences.isQuickUnlockAsked = true
            }

            val profile = OnboardingProfile(
                id = 1,
                isCompleted = true,
                completedAtTimestamp = System.currentTimeMillis(),
                isAgeVerified = true,
                isTermsAgreed = true,
                accountMode = authPreferences.accountMode,
                selectedGoalsCsv = goals.joinToString(","),
                lastPeriodDateString = if (periodStartChoice == "UNKNOWN") "" else lastPeriodDate.toString(),
                approximatePeriodChoice = periodStartChoice,
                cycleLengthOption = cycleLengthOption.ifBlank { "UNKNOWN" },
                cycleLengthDays = cycleLength,
                periodDurationDays = periodDuration,
                remindFertileWindow = remindFertile,
                typicalSymptomsCsv = typicalSymptoms.joinToString(","),
                typicalEnergy = typicalEnergy,
                typicalSleepQuality = typicalSleep,
                hasCompletedDeepBaseline = hasCompletedDeepBaseline
            )
            repository.completeOnboardingWithProfile(
                profile = profile,
                pin = pin,
                enableLock = enableLock
            )
            SoundFeedbackManager.playGentleRewardChime()
            val updated = repository.getSettings()
            _uiState.value = _uiState.value.copy(
                showOnboarding = false,
                isAppLocked = false,
                settings = updated,
                onboardingProfile = profile,
                isCloudSyncAllowed = isCloudSyncAllowed,
                showQuickUnlockDialog = !authPreferences.isQuickUnlockAsked && pin.isBlank()
            )
            updateCalculations(updated, LocalDate.now(), LocalDate.now())
            autoSyncSilently()
        }
    }

    fun refreshExplainMyBody(query: String = "") {
        val result = _uiState.value.todayPrediction?.let { prediction ->
            LearningEngine.synthesizeExplainMyBody(
                settings = _uiState.value.settings,
                logs = logsMap.value.values.toList(),
                prediction = prediction,
                query = query
            )
        }
        _uiState.value = _uiState.value.copy(explainMyBodyResult = result)
    }

    fun openOnboarding() {
        _uiState.value = _uiState.value.copy(showOnboarding = true)
    }

    fun buildDataExportJson(): String {
        val current = _uiState.value
        val settings = current.settings
        val profile = current.onboardingProfile
        val settingsJson = org.json.JSONObject().apply {
            put("selected_goals", settings.selectedGoalsCsv)
            put("last_period_start_date", settings.lastPeriodStartDate)
            put("average_cycle_length_days", settings.averageCycleLength)
            put("period_duration_days", settings.periodDuration)
            put("remind_period", settings.remindPeriod)
            put("remind_fertile_window", settings.remindFertile)
            put("remind_daily_log", settings.remindDailyLog)
            put("private_lock_screen_notifications", settings.isDiscreetNotificationMode)
        }
        val profileJson = profile?.let {
            org.json.JSONObject().apply {
                put("selected_goals", it.selectedGoalsCsv)
                put("last_period_date", it.lastPeriodDateString)
                put("period_date_precision", it.approximatePeriodChoice)
                put("cycle_length_choice", it.cycleLengthOption)
                put("cycle_length_days", it.cycleLengthDays)
                put("period_duration_days", it.periodDurationDays)
                put("fertility_reminders_enabled", it.remindFertileWindow)
                put("typical_symptoms", it.typicalSymptomsCsv)
                put("typical_energy", it.typicalEnergy)
                put("typical_sleep_quality", it.typicalSleepQuality)
            }
        } ?: org.json.JSONObject()
        val logsJson = org.json.JSONArray().apply {
            logsMap.value.values.sortedBy { it.dateString }.forEach { log ->
                put(org.json.JSONObject().apply {
                    put("date", log.dateString)
                    put("flow", log.flowLevel)
                    put("energy", log.energyLevel)
                    put("mood", log.moodLevel)
                    put("symptoms", log.symptomsCsv)
                    put("vaginal_health", log.vaginalHealthCsv)
                    put("urinary_health", log.urinaryHealthCsv)
                    put("cervical_mucus", log.cervicalMucus)
                    put("ovulation_test", log.ovulationTest)
                    put("basal_body_temperature_celsius", log.bbtCelsius)
                    put("sleep_hours", log.sleepHours)
                    put("sleep_quality", log.sleepQuality)
                    put("water_glasses", log.waterGlasses)
                    put("notes", log.notes)
                    put("pain_intensity", log.painIntensity)
                })
            }
        }
        return org.json.JSONObject().apply {
            put("format", "CycleCare personal data export")
            put("format_version", 1)
            put("exported_at_epoch_ms", System.currentTimeMillis())
            put("cycle_settings", settingsJson)
            put("onboarding_profile", profileJson)
            put("daily_logs", logsJson)
        }.toString(2)
    }

    fun wipeAllData() {
        authPreferences.isCloudSyncAllowed = false
        com.example.data.supabase.SupabaseSyncWorker.cancelSync(authPreferences.context)
        resetPrivateConversation()
        viewModelScope.launch {
            repository.deleteAllLogs()
            val resetSettings = repository.getSettings()
            _uiState.value = _uiState.value.copy(
                showOnboarding = true,
                isCloudSyncAllowed = false,
                settings = resetSettings,
                onboardingProfile = null,
                careChatHistory = emptyList(),
                careResponse = null,
                layeredChatResult = null,
                explainMyBodyResult = null,
                generatedCareImage = null,
                isGeneratingCareImage = false,
                careImageError = null
            )
            updateCalculations(resetSettings, LocalDate.now(), LocalDate.now())
        }
    }

    fun getPredictionForDate(date: LocalDate): DayPrediction? {
        val current = _uiState.value
        if (!current.hasCycleAnchor) return null
        return repository.calculateDayPrediction(date, current.settings)
    }

    class Factory(
        private val repository: CycleRepository,
        private val authPreferences: com.example.security.AuthPreferences,
        private val syncService: com.example.data.supabase.SupabaseSyncService,
        private val authService: com.example.data.supabase.SupabaseAuthService
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CycleCareViewModel(repository, authPreferences, syncService, authService) as T
        }
    }
}

package com.cyclecare.app.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.local.OnboardingProfile
import com.cyclecare.app.data.local.HealthSyncConflictEntity
import com.cyclecare.app.data.model.DayPrediction
import com.cyclecare.app.data.model.HealthPattern
import com.cyclecare.app.data.model.LearnedObservation
import com.cyclecare.app.data.model.ParsedLogInterpretation
import com.cyclecare.app.data.model.SafetyEvaluation
import com.cyclecare.app.data.model.TodayAttentionItem
import com.cyclecare.app.data.model.UserTonePreference
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.domain.ai.CareImageGenerationService
import com.cyclecare.app.domain.ai.CareImageGenerationException
import com.cyclecare.app.domain.ai.CareImageStyle
import com.cyclecare.app.domain.ai.CareResponse
import com.cyclecare.app.domain.attention.AttentionEngine
import com.cyclecare.app.domain.economy.MiningUseCase
import com.cyclecare.app.domain.learning.LearningEngine
import com.cyclecare.app.domain.parser.NaturalLanguageLogParser
import com.cyclecare.app.domain.pattern.BaselineMetrics
import com.cyclecare.app.domain.pattern.PatternEngine
import com.cyclecare.app.domain.payment.PaymentPollResult
import com.cyclecare.app.domain.payment.PaymentUseCase
import com.cyclecare.app.domain.safety.SafetyEngine
import com.cyclecare.app.domain.sync.SyncUseCase
import com.cyclecare.app.util.SoundFeedbackManager
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
    TODAY("Today"),
    CYCLE("Cycle"),
    ATELIER("Hub"),
    SPACE("Space");

    companion object {
        val HOME get() = TODAY
    }
}

data class CareChatTurn(
    val id: String,
    val userQuery: String,
    val response: CareResponse,
    val detectedItemsSummary: String? = null,
    val coinsEarned: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis()
)

/** Wallet display state, always bound to the current identity: NO_SESSION
 * (no usable credentials for a cloud wallet), LOADING, READY (fresh),
 * STALE (last-known value for THIS owner, refresh failed), UNAVAILABLE
 * (no same-owner value to show). Never render a default zero as a real
 * balance. Anonymous Supabase sessions with valid credentials fetch like any
 * signed-in wallet; only truly credential-less modes map to NO_SESSION. */
enum class WalletStatus { NO_SESSION, LOADING, READY, STALE, UNAVAILABLE }

data class CycleUiState(
    val currentDate: LocalDate = LocalDate.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    // When set, the Daily log is open as a full-screen overlay (opened from
    // Home, or from a date action on the Cycle/Calendar screens).
    val isDailyLogOpen: Boolean = false,
    // When set, the Ask CycleCare chat is open as a full-screen overlay
    // (opened from the floating Ask bubble).
    val isAskOpen: Boolean = false,
    val activeTab: AppNavTab = AppNavTab.TODAY,
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
    val aiSharingDirectionMessage: String? = null,
    val aiProviderConsents: Map<String, Boolean> = mapOf("COHERE" to false, "GEMINI" to false, "OPENROUTER" to false, "LAJIWA" to false),
    val aiProcessorManifest: com.cyclecare.app.domain.ai.AiProcessorManifest? = null,
    val isLoadingAiProcessorManifest: Boolean = false,
    val generatedCareImage: ByteArray? = null,
    val isGeneratingCareImage: Boolean = false,
    val careImageError: String? = null,
    val isAppLocked: Boolean = false,
    val showOnboarding: Boolean = true,
    val isCheckingCloudProfile: Boolean = true,
    val cloudProfileCheckFailed: Boolean = false,
    /** Local data could not be read steadily at start; shows a neutral retry that re-reads it. */
    val localReadFailed: Boolean = false,
    val isCompletingOnboarding: Boolean = false,
    val onboardingCompletionMessage: String? = null,
    val isSomethingDifferentSheetOpen: Boolean = false,
    val currentInterpretation: ParsedLogInterpretation? = null,
    val monthlyTimeline: List<com.cyclecare.app.data.model.MonthlyTimelineEntry> = emptyList(),
    val clinicianReportText: String = "",
    val explainMyBodyResult: com.cyclecare.app.data.model.ExplainMyBodyResult? = null,
    val onboardingProfile: OnboardingProfile? = null,
    val isSyncing: Boolean = false,
    val lastSyncTimestamp: Long = 0L,
    val lastSyncMessage: String = "Not backed up yet",
    val isOnlineBackupActive: Boolean = false,
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
    val economyProfile: com.cyclecare.app.data.economy.EconomyProfile = com.cyclecare.app.data.economy.EconomyProfile(),
    val walletStatus: WalletStatus = WalletStatus.NO_SESSION,
    val syncGatedForConflicts: Boolean = false,
    val isAdmin: Boolean = false,
    val isVaultOpen: Boolean = false,
    val economyToastMessage: String? = null,
    val layeredChatResult: com.cyclecare.app.domain.ai.LayeredChatResult? = null,
    val activeMpesaPackage: com.cyclecare.app.data.economy.CoinPurchasePackage? = null,
    val isMpesaProcessing: Boolean = false,
    val mpesaStatusMessage: String? = null,
    val isMpesaSuccess: Boolean = false,
    val careSavedReviewMessage: String? = null,
    val isBanned: Boolean = false,
    val isSuspended: Boolean = false,
    val accountNoticeMessage: String? = null,
    val notifications: List<com.cyclecare.app.data.notification.UserNotification> = emptyList(),
    val isNotificationCenterOpen: Boolean = false,
    val giftedModalArtifact: String? = null,
    val activeGiftDialogItem: com.cyclecare.app.data.economy.EconomyItem? = null,
    val dismissedFloatingBannerId: String? = null,
    val activeCheckoutPackage: com.cyclecare.app.data.economy.CoinPurchasePackage? = null,
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
    val authPreferences: com.cyclecare.app.security.AuthPreferences,
    val syncService: com.cyclecare.app.data.supabase.SupabaseSyncService,
    val authService: com.cyclecare.app.data.supabase.SupabaseAuthService
) : ViewModel() {

    val economyService = com.cyclecare.app.data.economy.SupabaseEconomyService(authPreferences)
    val guidedChatsRepo = com.cyclecare.app.data.economy.GuidedChatsRepository()
    val communityGuidesService = com.cyclecare.app.data.economy.CommunityGuidesService(authPreferences)
    val paymentService = com.cyclecare.app.data.payment.MpesaPaymentService(authPreferences, economyService)
    val supabasePaymentService = com.cyclecare.app.data.payment.SupabasePaymentService(authPreferences)
    val notificationService = com.cyclecare.app.data.notification.SupabaseNotificationService(authPreferences)

    val paymentUseCase = PaymentUseCase(supabasePaymentService, economyService, authPreferences)
    val earnCoinsUseCase = com.cyclecare.app.domain.economy.EarnCoinsUseCase(economyService, authPreferences)
    val miningUseCase = MiningUseCase(economyService, authPreferences)
    val syncUseCase = SyncUseCase(syncService, authPreferences)

    private val _uiState = MutableStateFlow(CycleUiState())
    val uiState: StateFlow<CycleUiState> = _uiState.asStateFlow()
    private val careLogConsentGate = com.cyclecare.app.domain.ai.CareLogConsentGate()
    private var careJob: kotlinx.coroutines.Job? = null
    private var imageJob: kotlinx.coroutines.Job? = null
    private var activeUiOwner = authPreferences.effectiveUserId
    private var activeUiSession = authPreferences.sessionGeneration
    private var careRequestGeneration = 0L
    private var pendingQuickLogOwner: String? = null
    private var pendingCareProposal: com.cyclecare.app.domain.ai.ProposedCareLog? = null
    private val processorConsentService = com.cyclecare.app.domain.ai.AiProcessorConsentService(authPreferences)

    private companion object {
        const val ONBOARDING_RETRY_MESSAGE = "Your answers couldn't be saved completely. Please try again."
    }

    /** Identifies the current onboarding save, so an older save's cleanup never clears a newer one's flag. */
    private var onboardingOperationId = 0L

    private data class ConflictOwnerSnapshot(val owner: String, val session: Long)

    private fun captureConflictOwnerSnapshot(): ConflictOwnerSnapshot {
        resetIdentityUiIfNeeded()
        return ConflictOwnerSnapshot(authPreferences.effectiveUserId, authPreferences.sessionGeneration)
    }

    private fun isCurrentConflictSnapshot(snapshot: ConflictOwnerSnapshot): Boolean =
        activeUiOwner == snapshot.owner && authPreferences.effectiveUserId == snapshot.owner &&
            authPreferences.sessionGeneration == snapshot.session

    /**
     * Reads settings and profile from ONE repository database/owner capture and
     * returns them only if the UI owner, session and repository context are all
     * unchanged afterwards (so a switch away and back is also rejected). A
     * rejected read is retried a bounded number of times, then null.
     */
    private suspend fun readStableLocalSnapshot(resetFirst: Boolean): com.cyclecare.app.data.repository.CycleRepository.StoredSnapshot? {
        repeat(3) {
            val owner = if (resetFirst) captureConflictOwnerSnapshot()
                else ConflictOwnerSnapshot(authPreferences.effectiveUserId, authPreferences.sessionGeneration)
            val stored = try { repository.getStoredSnapshot() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { return@repeat }
            if (isCurrentConflictSnapshot(owner) && repository.isCurrentContext(stored.context)) return stored
        }
        return null
    }

    /**
     * For flows that must publish a start decision: waits for an owner switch to
     * settle (40 attempts, 250 ms apart, about 10 s). Never publishes an unstable
     * read. On exhaustion the caller leaves the UI unchanged: it deliberately does
     * NOT show the cloud "could not be checked" screen (wrong for local-only users,
     * and its retry does nothing for them) and never routes to onboarding.
     */
    private suspend fun readStableLocalSnapshotWithBackoff(resetFirst: Boolean): com.cyclecare.app.data.repository.CycleRepository.StoredSnapshot? {
        repeat(40) {
            readStableLocalSnapshot(resetFirst)?.let { return it }
            kotlinx.coroutines.delay(250L)
        }
        return null
    }

    private var localReadEpoch = 0L
    /** Bumped whenever resetIdentityUiIfNeeded actually switches identity. */
    private var identityResetEpoch = 0L

    /**
     * Immutable scope of ONE start decision. The snapshot, owner/session and repository
     * context were all captured together in the same synchronous step; the epochs say which
     * start call and which identity generation it belongs to. A continuation that suspends
     * must call isStartScopeCurrent before every publish.
     */
    private class StartScope(
        val startEpoch: Long,
        val resetEpoch: Long,
        val owner: ConflictOwnerSnapshot,
        val snapshot: com.cyclecare.app.data.repository.CycleRepository.StoredSnapshot
    )

    private fun isStartScopeCurrent(scope: StartScope): Boolean =
        scope.startEpoch == localReadEpoch && scope.resetEpoch == identityResetEpoch &&
            isCurrentConflictSnapshot(scope.owner) && repository.isCurrentContext(scope.snapshot.context)

    /**
     * Start-decision read shared by startup, local-only start and deep link. Up to 40
     * attempts, 250 ms apart. Each attempt captures the owner, then reads, and accepts the
     * read only if that SAME capture is still current, so owner and snapshot always belong
     * together; an owner change mid-read retargets the whole attempt, never a mix. Only the
     * newest call may publish (superseded: null, silently). On exhaustion localReadFailed is
     * set only if the call is still newest. A success clears it. Unexecuted.
     */
    private suspend fun readStartScope(supersede: Boolean = true): StartScope? {
        // supersede = false joins the current start generation without invalidating it; a later
        // superseding start still invalidates the result.
        val startEpoch = if (supersede) ++localReadEpoch else localReadEpoch
        repeat(40) {
            if (startEpoch != localReadEpoch) return null
            // capture, resetEpoch and the read of both happen with no suspension between them
            val owner = captureConflictOwnerSnapshot()
            val resetEpoch = identityResetEpoch
            val stored = try { repository.getStoredSnapshot() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            if (startEpoch != localReadEpoch) return null
            if (stored != null && isCurrentConflictSnapshot(owner) && resetEpoch == identityResetEpoch &&
                repository.isCurrentContext(stored.context)) {
                _uiState.value = _uiState.value.copy(localReadFailed = false)
                return StartScope(startEpoch, resetEpoch, owner, stored)
            }
            kotlinx.coroutines.delay(250L)
        }
        if (startEpoch == localReadEpoch) _uiState.value = _uiState.value.copy(localReadFailed = true)
        return null
    }

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
                syncConflictMessage = result.exceptionOrNull()?.let { "The review couldn't be loaded. Please try again." }
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
                    operation.isFailure -> "That choice couldn't be saved. Please check again and retry."
                    stillUnresolved -> "This record changed while you were reviewing it. Please check again before choosing."
                    else -> "Done — your choice was saved. Nothing was merged automatically."
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
        identityResetEpoch++
        walletRealtime.stop()
        accountRefreshJob?.cancel()
        walletRefreshJob?.cancel()
        lastReadyWalletOwner = null
        repository.activateCurrentOwner()
        _logsMap.value = emptyMap()
        resetPrivateConversation()
        com.cyclecare.app.data.supabase.SupabaseSyncWorker.cancelSync(authPreferences.context)
        _uiState.value = _uiState.value.copy(
            localDataOwnerKey = owner,
            economyProfile = com.cyclecare.app.data.economy.EconomyProfile(), isAdmin = false,
            walletStatus = WalletStatus.NO_SESSION, syncGatedForConflicts = false,
            safetyEvaluation = SafetyEngine.evaluate(null), monthlyTimeline = emptyList(), doctorQuestions = emptyList(),
            baselineMetrics = BaselineMetrics(28.0, 28, 28, 28, 0, false, 0, 0), todayAttentionItem = null,
            cycleStartIsApproximate = false, daysUntilNextPeriod = 0, daysUntilOvulation = 0,
            isPeriodActiveToday = false, isSomethingDifferentSheetOpen = false, isVaultOpen = false,
            isDeletingCloudHealth = false, cloudHealthDeletionMessage = null,
            notifications = emptyList(), activeGiftDialogItem = null, giftedModalArtifact = null,
            isBanned = false, isSuspended = false, accountNoticeMessage = null,
            onboardingProfile = null, settings = CycleSettings(), hasCycleAnchor = false,
            localReadFailed = false, isCompletingOnboarding = false, onboardingCompletionMessage = null,
            todayPrediction = null, selectedDatePrediction = null, patterns = emptyList(),
            learnedObservations = emptyList(), clinicianReportText = "", explainMyBodyResult = null,
            isCloudSyncAllowed = authPreferences.isCloudSyncAllowed,
            isAiSharingAllowed = authPreferences.isAiSharingAllowed,
            aiProviderConsents = authPreferences.providerConsents().mapValues { it.value.granted },
            syncConflicts = emptyList(), isLoadingSyncConflicts = false,
            isResolvingSyncConflict = false, syncConflictMessage = null
            ,aiProcessorManifest = null, isLoadingAiProcessorManifest = false
        )
        // The wipe above protects against leaking the previous owner's data; now
        // load the NEW owner's own settings/profile so nothing downstream reads
        // factory defaults (which look like "never completed onboarding").
        viewModelScope.launch {
            val reloaded = runCatching { readStableLocalSnapshot(resetFirst = false) }.getOrNull()
            if (reloaded != null && activeUiOwner == owner && activeUiSession == session) {
                _uiState.value = _uiState.value.copy(settings = reloaded.settings, onboardingProfile = reloaded.profile)
            }
        }
    }

    private val _logsMap = MutableStateFlow<Map<String, CycleDayLog>>(emptyMap())
    val logsMap: StateFlow<Map<String, CycleDayLog>> = _logsMap.asStateFlow()

    private val reconnectWorkState = com.cyclecare.app.domain.sync.ReconnectWorkPolicy.State()

    private var startupJob: kotlinx.coroutines.Job? = null

    /** Re-runs the startup read after a failed steady read. Real re-read, not a cloud refresh. */
    fun retryLocalRead() {
        if (startupJob?.isActive == true) return
        _uiState.value = _uiState.value.copy(localReadFailed = false)
        runStartup()
    }

    private fun runStartup() {
        startupJob = viewModelScope.launch {
            // One database/owner capture; a read that straddled an owner or
            // session switch is retried, never published.
            val scope = readStartScope() ?: return@launch
            val stored = scope.snapshot
            val settings = stored.settings
            val profile = stored.profile
            // Isolated spike wiring (RustLifecycleConfig.STARTUP_GATE_ENABLED=false):
            // when enabled, the Rust startup gate computes these flags with a
            // Kotlin fallback; when disabled, gate stays null and the original
            // expressions below run unchanged.
            val gate = if (com.cyclecare.app.rust.RustLifecycleConfig.STARTUP_GATE_ENABLED)
                com.cyclecare.app.rust.RustLifecycleAdapters.startupGate(
                    authPreferences, profile != null, settings.isOnboardingCompleted) else null
            val hasCompletedInitialAuth = gate?.hasCompletedInitialAuth ?: authPreferences.hasCompletedInitialAuth
            val needsCloudProfileCheck = gate?.needsCloudProfileCheck ?: (hasCompletedInitialAuth && !authPreferences.isLocalOnlyMode &&
                (profile == null || !settings.isOnboardingCompleted))
            val isPinSet = authPreferences.isPinEnabled && !authPreferences.pin.isNullOrBlank()
            val isLocked = gate?.isLocked ?: (hasCompletedInitialAuth && (isPinSet || authPreferences.isFingerprintEnabled))
            val showOnboarding = gate?.showOnboarding ?: (hasCompletedInitialAuth && !needsCloudProfileCheck &&
                (profile == null || !settings.isOnboardingCompleted))

            val isCachedBanned = authPreferences.accountStatus.equals("BANNED", ignoreCase = true)
            val isCachedSuspended = authPreferences.accountStatus.equals("SUSPENDED", ignoreCase = true)
            val cachedNotice = authPreferences.suspensionReason

            _uiState.value = _uiState.value.copy(
                hasCompletedInitialAuth = hasCompletedInitialAuth,
                isAppLocked = isLocked,
                isEastAfricaRegion = com.cyclecare.app.data.economy.CurrencyLocaleManager.detectCountryCode(authPreferences.context) == "KE",
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
                isOnlineBackupActive = syncService.isOnlineBackupActive,
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
                com.cyclecare.app.util.NetworkHelper.isOnline(authPreferences.context)
            ) {
                val session = authService.continueAsGuest()
                // Suspended above: publish only if this start is still the current one.
                if (!isStartScopeCurrent(scope)) return@launch
                when (session) {
                    is com.cyclecare.app.data.supabase.AuthResult.Error -> _uiState.value = _uiState.value.copy(
                        lastSyncMessage = session.errorMessage,
                        lastSyncSuccess = false
                    )
                    else -> Unit
                }
            }

            if (!isStartScopeCurrent(scope)) return@launch
            updateCalculations(settings, LocalDate.now(), LocalDate.now())

            if (hasCompletedInitialAuth) runAutoSync(false, scope)
            val initOwner = authPreferences.effectiveUserId
            val initSession = authPreferences.sessionGeneration
            val fetchWallet = gate?.fetchWallet ?: (hasCompletedInitialAuth && !authPreferences.isLocalOnlyMode && authPreferences.userId != null)
            val verifiedEconomy = if (fetchWallet)
                economyService.fetchVerifiedProfile(initOwner) else null
            if (!isStartScopeCurrent(scope)) return@launch
            if (authPreferences.effectiveUserId == initOwner && authPreferences.sessionGeneration == initSession &&
                verifiedEconomy is com.cyclecare.app.data.economy.EconomyResult.Success) {
                val econ = verifiedEconomy.data
                lastReadyWalletOwner = initOwner
                _uiState.value = _uiState.value.copy(
                    economyProfile = econ,
                    walletStatus = WalletStatus.READY,
                    isBanned = econ.isBanned,
                    isSuspended = econ.isSuspended,
                    accountNoticeMessage = econ.suspensionReason
                )
            }

            refreshNotifications()

            if (!authPreferences.isLocalOnlyMode && authPreferences.userId != null && com.cyclecare.app.util.NetworkHelper.isOnline(authPreferences.context)) {
                val reconciled = miningUseCase.reconcilePendingOfflineClaims(_uiState.value.economyProfile)
                if (!isStartScopeCurrent(scope)) return@launch
                if (reconciled is com.cyclecare.app.data.economy.EconomyResult.Success) {
                    _uiState.value = _uiState.value.copy(economyProfile = reconciled.data, walletStatus = WalletStatus.READY)
                }
            }

            // One startup sync is enough: the earlier autoSyncSilently() call
            // already covers this, and repeating it only duplicated the
            // wallet refresh.
            refreshAiProcessorManifest()
        }
    }

    init {
        viewModelScope.launch {
            repository.scopedLogs.collect { snapshot ->
                _logsMap.value = if (repository.isCurrentSnapshot(snapshot)) snapshot.logs.associateBy { it.dateString } else emptyMap()
            }
        }
        SoundFeedbackManager.isSoundEnabled = authPreferences.isSoundEnabled

        runStartup()

        // Reactive network connectivity observer. Reconnect work runs only on
        // an offline -> online transition, throttled per owner, so flappy
        // networks cannot trigger unbounded reconcile/fetch loops. A throttled
        // transition is deferred, not dropped: one trailing run fires when the
        // cooldown expires, and a failed run gets exactly one bounded retry,
        // so pending offline claims are reconciled once the network settles.
        viewModelScope.launch {
            com.cyclecare.app.util.NetworkHelper.observeNetwork(authPreferences.context).collect { isConnected ->
                val eligible = !authPreferences.isLocalOnlyMode && authPreferences.userId != null
                when (val decision = com.cyclecare.app.domain.sync.ReconnectWorkPolicy.decide(
                    reconnectWorkState, isConnected, eligible,
                    authPreferences.effectiveUserId, System.currentTimeMillis()
                )) {
                    is com.cyclecare.app.domain.sync.ReconnectWorkPolicy.Decision.Run -> {
                        if (!runReconnectWorkOnce()) {
                            scheduleTrailingReconnectWork(
                                com.cyclecare.app.domain.sync.ReconnectWorkPolicy.MIN_INTERVAL_MS,
                                allowReschedule = false
                            )
                        }
                    }
                    is com.cyclecare.app.domain.sync.ReconnectWorkPolicy.Decision.Throttled ->
                        scheduleTrailingReconnectWork(decision.retryAfterMs, allowReschedule = true)
                    com.cyclecare.app.domain.sync.ReconnectWorkPolicy.Decision.Skip -> Unit
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
        val baseline = if (com.cyclecare.bridge.RustBaselineConfig.ENABLED)
            com.cyclecare.bridge.RustBaseline.compute(settings, allLogsList)
        else PatternEngine.computeBaseline(settings, allLogsList)
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
        // The Daily log lives on Home now; open it as a full-screen overlay.
        _uiState.value = _uiState.value.copy(isDailyLogOpen = true)
    }

    fun closeDailyLog() {
        _uiState.value = _uiState.value.copy(isDailyLogOpen = false)
    }

    fun openAsk() {
        _uiState.value = _uiState.value.copy(isAskOpen = true)
    }

    fun closeAsk() {
        _uiState.value = _uiState.value.copy(isAskOpen = false)
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
        val interpretation = if (com.cyclecare.bridge.RustLogParserConfig.ENABLED)
            com.cyclecare.bridge.RustLogParser.parse(rawText.take(1200))
        else NaturalLanguageLogParser.parse(rawText.take(1200))
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
            repository.saveLog(com.cyclecare.app.domain.ai.CareConversationPolicy.merge(existing, interpretation))
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
        val owner = captureConflictOwnerSnapshot()
        // Intent-time token; during an owner transition nothing is saved (no message: the
        // settings screen has no failure surface, so the change is simply not applied).
        val token = try { repository.beginOperation() } catch (_: com.cyclecare.app.data.repository.OwnerTransitionException) { return }
        if (token.session != owner.session || !isCurrentConflictSnapshot(owner)) return
        val previousSettings = _uiState.value.settings
        viewModelScope.launch {
            // Profile read and both writes use the token's database, owner and consent generation.
            val update = try { repository.updateSettingsBound(newSettings, previousSettings.averageCycleLength, token) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { return@launch }
            // The write stays with its own owner; only the current owner's UI is updated.
            if (!isCurrentConflictSnapshot(owner) || !repository.isCurrentContext(update.context)) return@launch
            _uiState.value = _uiState.value.copy(onboardingProfile = update.profile)
            updateCalculations(newSettings, _uiState.value.currentDate, _uiState.value.selectedDate)
            autoSyncSilently()
        }
    }

    fun deleteCloudHealthCopy() {
        if (_uiState.value.isDeletingCloudHealth) return
        if (authPreferences.isLocalOnlyMode || authPreferences.userId.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(cloudHealthDeletionMessage = "No online account is connected, so only this device's records were erased.")
            return
        }
        val owner = authPreferences.effectiveUserId
        val session = authPreferences.sessionGeneration
        // A deletion request must also invalidate any queued sync work. Persist
        // the withdrawal before making the RPC so a restart or worker cannot
        // upload health data while deletion is in flight (or after a failed
        // request). The pending revoke is completed by the normal sync path.
        if (authPreferences.pauseHealthSyncFor(owner, session) == null) {
            _uiState.value = _uiState.value.copy(cloudHealthDeletionMessage = "Your choice couldn't be saved, so nothing was erased online. Please try again.")
            return
        }
        authPreferences.isAiSharingAllowed = false
        resetPrivateConversation()
        _uiState.value = _uiState.value.copy(isDeletingCloudHealth = true, isCloudSyncAllowed = false,
            isAiSharingAllowed = false, cloudHealthDeletionMessage = "Erasing your online copy…")
        viewModelScope.launch {
            val token = com.cyclecare.app.data.supabase.SupabaseSessionManager.validAccessToken(authPreferences)
            if (authPreferences.effectiveUserId != owner || authPreferences.sessionGeneration != session) return@launch
            val deleted = com.cyclecare.app.domain.ai.CarePrivacyService.deleteCloudHealthCopy(token)
            if (authPreferences.effectiveUserId != owner || authPreferences.sessionGeneration != session) return@launch
            _uiState.value = _uiState.value.copy(isDeletingCloudHealth = false,
                cloudHealthDeletionMessage = if (deleted) "Your online records were erased. The copies on this device remain, and backup is off. Your account, purchases and coins are kept."
                    else "The online erase couldn't be confirmed. Backup stays off. Please try again before assuming your online copy is gone.")
        }
    }

    fun setAiSharingAllowed(enabled: Boolean) {
        // Compatibility path for existing callers. The care UI uses the explicit
        // provider mutation below and never infers consent from server selection.
        if (enabled) {
            // Grant nothing from Settings: consent requires the Ask tab card,
            // where the sharing description is shown before any processor
            // receipt exists. Keep the switch off until that flow completes.
            _uiState.value = _uiState.value.copy(
                aiSharingDirectionMessage = "Assistant answers stay off here. On the Ask tab, review who answers and what gets shared, then tap Allow - you can revoke anytime.")
        } else {
            // Compatibility callers (including cloud-health deletion) must
            // revoke provider receipts as well as the aggregate UI switch.
            authPreferences.revokeAllProviderConsents()
            resetPrivateConversation()
        }
        _uiState.value = _uiState.value.copy(
            isAiSharingAllowed = authPreferences.isAiSharingAllowed,
            aiSharingDirectionMessage = if (enabled) _uiState.value.aiSharingDirectionMessage else null,
            aiProviderConsents = authPreferences.providerConsents().mapValues { it.value.granted },
            careContextPreview = null
        )
    }

    fun setAiProviderSharingAllowed(provider: String, enabled: Boolean, expectedSnapshot: com.cyclecare.app.domain.ai.AiProcessorManifest? = null) {
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
                val manifest = (processorConsentService.fetchManifest(token) as? com.cyclecare.app.domain.ai.ConsentResult.Success)?.manifest ?: return@launch
                if (owner == authPreferences.effectiveUserId) processorConsentService.setConsent(token, manifest, provider, false)
            }
            return
        }
        // Enabling is explicit but never optimistic: require a fresh eligible manifest and server receipt.
        viewModelScope.launch {
            val token = authPreferences.accessToken
            val manifestResult = if (!token.isNullOrBlank()) processorConsentService.fetchManifest(token) else com.cyclecare.app.domain.ai.ConsentResult.Failure("Sign in to enable your CycleCare Assistant")
            val manifest = (manifestResult as? com.cyclecare.app.domain.ai.ConsentResult.Success)?.manifest
            // The fresh manifest must still match what the user was shown; a changed
            // fingerprint/revision/set aborts and re-displays instead of granting
            // against unseen terms.
            if (manifest != null && expectedSnapshot != null &&
                !com.cyclecare.app.domain.ai.aiConsentBindingEqual(expectedSnapshot, manifest)) {
                if (owner == authPreferences.effectiveUserId) {
                    _uiState.value = _uiState.value.copy(aiProcessorManifest = manifest,
                        careSavedReviewMessage = "The provider list changed. Review it again before allowing.")
                }
                return@launch
            }
            val result = if (manifest != null) processorConsentService.setConsent(token!!, manifest, provider, true) else manifestResult
            if (owner != authPreferences.effectiveUserId) return@launch
            if (result is com.cyclecare.app.domain.ai.ConsentResult.Success) authPreferences.setProviderConsent(provider, true)
            _uiState.value = _uiState.value.copy(isAiSharingAllowed = authPreferences.isAiSharingAllowed,
                aiProviderConsents = authPreferences.providerConsents().mapValues { it.value.granted }, careContextPreview = null,
                careSavedReviewMessage = if (result is com.cyclecare.app.domain.ai.ConsentResult.Failure) result.reason else null)
        }
    }

        /**
     * Allow-tap from the consent card: grant exactly the providers displayed as
     * eligible in the rendered manifest snapshot. The rendered snapshot object
     * is compared with live state field-for-field; any change - even under the
     * same manifest revision - fails closed and refreshes the card instead of
     * granting. Each grant additionally re-validates a fresh manifest and a
     * server receipt per provider.
     */
    fun enableDisplayedAiProviders(displayedManifest: com.cyclecare.app.domain.ai.AiProcessorManifest?) {
        when (val decision = com.cyclecare.app.domain.ai.decideAiConsentTap(
            displayedManifest, _uiState.value.aiProcessorManifest)) {
            is com.cyclecare.app.domain.ai.AiConsentTapDecision.FailClosed -> {
                refreshAiProcessorManifest()
                _uiState.value = _uiState.value.copy(careSavedReviewMessage = decision.reason)
            }
            is com.cyclecare.app.domain.ai.AiConsentTapDecision.Enable ->
                decision.providerIds.forEach { setAiProviderSharingAllowed(it, true, expectedSnapshot = displayedManifest) }
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
                aiProcessorManifest = (result as? com.cyclecare.app.domain.ai.ConsentResult.Success)?.manifest,
                isLoadingAiProcessorManifest = false
            )
        }
    }

    fun previewCareContext(question: String) {
        val preview = com.cyclecare.app.domain.ai.CareConversationPolicy.compileContext(
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
            repository.saveLog(com.cyclecare.app.domain.ai.CareConversationPolicy.merge(existing, confirmed.interpretation))
            _uiState.value = _uiState.value.copy(careSavedReviewMessage = "Saved to your health log. No coins were awarded for this health entry.")
            updateCalculations(_uiState.value.settings, _uiState.value.currentDate, _uiState.value.selectedDate)
            autoSyncSilently()
        }
    }

    fun consultCare(question: String, includeFullContext: Boolean = true) {
        val cleanQuestion = question.trim()
        if (cleanQuestion.isEmpty() || cleanQuestion.length > 1200 || _uiState.value.isCareLoading) return
        resetIdentityUiIfNeeded()
        val owner = authPreferences.effectiveUserId
        val session = authPreferences.sessionGeneration
        val current = _uiState.value
        val safety = SafetyEngine.evaluateInput(cleanQuestion, logsMap.value[current.currentDate.toString()])
        // Safety and local help are never blocked by connectivity, consent, restrictions or tier.
        val remoteAllowed = authPreferences.isAiSharingAllowed &&
            com.cyclecare.app.util.NetworkHelper.isOnline(authPreferences.context) && !current.isBanned && !current.isSuspended && !safety.isRedFlag
        // Relevant cycle context is compiled and attached automatically whenever
        // remote AI is permitted - users no longer manage a context toggle or a
        // preview-then-resend step.
        val contextSummary = if (includeFullContext && remoteAllowed && !safety.isRedFlag) {
            com.cyclecare.app.domain.ai.CareConversationPolicy.compileContext(
                cleanQuestion, logsMap.value.values.toList(), current.settings, current.currentDate
            )
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
                val consentReady = remoteAllowed && com.cyclecare.app.domain.ai.CarePrivacyService.updateConsents(
                    authPreferences.accessToken, authPreferences.isAiSharingAllowed, authPreferences.isCloudSyncAllowed, false
                )
                if (!isCurrentCareRequest()) return@launch
                val result = com.cyclecare.app.domain.ai.LayeredChatOrchestrator.processRawChat(
                    rawInput = cleanQuestion, todayLog = logsMap.value[current.currentDate.toString()],
                    settings = current.settings, economyProfile = current.economyProfile,
                    accessToken = if (consentReady && authPreferences.isAiSharingAllowed) authPreferences.accessToken else null,
                    forceAi = includeFullContext && contextSummary != null,
                    contextSummary = contextSummary,
                    allowRemoteAi = consentReady && authPreferences.isAiSharingAllowed,
                    conversationHistory = if (consentReady) current.careChatHistory
                        .filter { com.cyclecare.app.domain.ai.isRemoteCareResponse(it.response.source) }.takeLast(3).flatMap {
                            listOf(com.cyclecare.app.domain.ai.CareMemoryMessage("user", it.userQuery.take(600)),
                                com.cyclecare.app.domain.ai.CareMemoryMessage("assistant", (it.response.summary + " " + it.response.whatText).take(600)))
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

    fun selectTiedDownChoice(choice: com.cyclecare.app.domain.ai.TiedDownChoice) {
        consultCare(choice.prefilledQuery)
    }

    fun generateCareImage(style: CareImageStyle, customPrompt: String = "") {
        val current = _uiState.value
        if (current.isGeneratingCareImage) return
        val hasPremiumAccess = current.isAdmin ||
            current.economyProfile.tier == com.cyclecare.app.data.economy.UserEconomyTier.PREMIUM
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
                val consentReady = com.cyclecare.app.domain.ai.CarePrivacyService.updateConsents(
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

    fun openMpesaDialog(pkg: com.cyclecare.app.data.economy.CoinPurchasePackage) {
        openUnifiedCheckout(pkg)
    }

    fun openUnifiedCheckout(pkg: com.cyclecare.app.data.economy.CoinPurchasePackage) {
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
        if (!com.cyclecare.app.util.NetworkHelper.isOnline(authPreferences.context)) {
            _uiState.value = _uiState.value.copy(
                isPaymentProcessing = false,
                isPaymentSuccess = false,
                paymentStatusMessage = "You're offline. Connect to the internet to pay with M-Pesa."
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isPaymentProcessing = true,
                paymentStatusMessage = "Sending a payment prompt to $phoneNumber…"
            )
            val result = supabasePaymentService.initiateMpesaStkPush(phoneNumber, amountKes, pkg, _uiState.value.economyProfile)
            val extRef = result.externalReference ?: ""
            if (result.isSuccess && extRef.isNotBlank()) {
                _uiState.value = _uiState.value.copy(
                    paymentStatusMessage = "Payment prompt sent. Enter your M-Pesa PIN on your phone to finish…"
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
        if (!com.cyclecare.app.util.NetworkHelper.isOnline(authPreferences.context)) {
            _uiState.value = _uiState.value.copy(
                isPaymentProcessing = false,
                isPaymentSuccess = false,
                paymentStatusMessage = "You're offline. Connect to the internet to pay by card."
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isPaymentProcessing = true,
                paymentStatusMessage = "Checking your card payment…"
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
        if (!com.cyclecare.app.util.NetworkHelper.isOnline(authPreferences.context)) {
            _uiState.value = _uiState.value.copy(
                isPaymentProcessing = false,
                isPaymentSuccess = false,
                paymentStatusMessage = "You're offline. Connect to the internet to pay with PayPal."
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isPaymentProcessing = true,
                paymentStatusMessage = "Checking your PayPal payment…"
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
        if (!com.cyclecare.app.util.NetworkHelper.isOnline(authPreferences.context)) {
            _uiState.value = _uiState.value.copy(
                isPaymentProcessing = false,
                isPaymentSuccess = false,
                paymentStatusMessage = "You're offline. Connect to the internet to pay with crypto."
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isPaymentProcessing = true,
                paymentStatusMessage = "Waiting for your $currency deposit to confirm…"
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

    fun initiateMpesaStkPush(phoneNumber: String, amountKes: Double, pkg: com.cyclecare.app.data.economy.CoinPurchasePackage) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isMpesaProcessing = true,
                mpesaStatusMessage = "Payment prompt sent to $phoneNumber. Confirm with your M-Pesa PIN…"
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
        val owner = authPreferences.effectiveUserId
        val session = authPreferences.sessionGeneration
        viewModelScope.launch {
            when (val pollResult = paymentUseCase.pollPaymentStatus(extRef, timeoutSeconds, _uiState.value.economyProfile)) {
                is PaymentPollResult.Success -> {
                    // A poll that outlives its identity must never publish the
                    // old owner's wallet onto the new owner's card.
                    if (owner != authPreferences.effectiveUserId || session != authPreferences.sessionGeneration) return@launch
                    SoundFeedbackManager.playArtifactAcquired(528f)
                    _uiState.value = _uiState.value.copy(
                        economyProfile = pollResult.profile,
                        walletStatus = WalletStatus.READY,
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
        val owner = authPreferences.effectiveUserId
        val session = authPreferences.sessionGeneration
        viewModelScope.launch {
            when (val pollResult = paymentUseCase.pollPaymentStatus(extRef, timeoutSeconds, _uiState.value.economyProfile)) {
                is PaymentPollResult.Success -> {
                    if (owner != authPreferences.effectiveUserId || session != authPreferences.sessionGeneration) return@launch
                    SoundFeedbackManager.playArtifactAcquired(528f)
                    _uiState.value = _uiState.value.copy(
                        economyProfile = pollResult.profile,
                        walletStatus = WalletStatus.READY,
                        isMpesaProcessing = false,
                        isMpesaSuccess = true,
                        mpesaStatusMessage = "Payment confirmed! Coins added to your vault."
                    )
                }
                is PaymentPollResult.Failed -> {
                    _uiState.value = _uiState.value.copy(
                        isMpesaProcessing = false,
                        isMpesaSuccess = false,
                        mpesaStatusMessage = "The payment wasn't completed. No money was taken and no coins were added."
                    )
                }
                is PaymentPollResult.Timeout -> {
                    _uiState.value = _uiState.value.copy(
                        isMpesaProcessing = false,
                        isMpesaSuccess = false,
                        mpesaStatusMessage = "We couldn't confirm the payment in time. If money left your M-Pesa, your coins will appear automatically."
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
            // Same preservation gate as cold start: never upload with pending
            // conflicts or an unreadable local state.
            val conflictsPending = runCatching { repository.pendingSyncConflicts().isNotEmpty() }.getOrDefault(true)
            if (conflictsPending) {
                _uiState.value = _uiState.value.copy(
                    syncGatedForConflicts = true,
                    lastSyncSuccess = false,
                    lastSyncMessage = "Review the pending sync choices in Settings first. Nothing was uploaded."
                )
                return@launch
            }
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

    private val walletRealtime by lazy { com.cyclecare.app.data.economy.WalletRealtimeObserver(authPreferences, viewModelScope) { refreshWalletNow(force = true) } }
    private var accountRefreshJob: kotlinx.coroutines.Job? = null
    private var walletRefreshJob: kotlinx.coroutines.Job? = null
    private var lastWalletRefreshAtMs: Long = 0L
    private var lastWalletRefreshOwner: String? = null
    private var lastReadyWalletOwner: String? = null
    private var lastWalletRefreshSession: Long = 0L
    private val walletRefreshMinIntervalMs: Long = 30_000L

    /**
     * Fetch the verified wallet profile. Automatic callers are deduplicated:
     * one refresh per walletRefreshMinIntervalMs per owner, so rapid autoSync
     * entrants cannot duplicate fetches while a sync is already running.
     * Manual retries and realtime invalidations pass force = true and always
     * fetch. An owner change always fetches: a fresh sign-in never inherits
     * the previous account's dedupe.
     */
    fun refreshWalletNow(force: Boolean = false) {
        // Only truly credential-less modes have no wallet to show. A guest with
        // a live anonymous Supabase session has a real cloud wallet and fetches
        // like any signed-in account.
        if (authPreferences.isLocalOnlyMode || authPreferences.userId == null) {
            _uiState.value = _uiState.value.copy(walletStatus = WalletStatus.NO_SESSION)
            return
        }
        if (walletRefreshJob?.isActive == true) return
        val owner = authPreferences.effectiveUserId
        val session = authPreferences.sessionGeneration
        if (!force && owner == lastWalletRefreshOwner && session == lastWalletRefreshSession &&
            System.currentTimeMillis() - lastWalletRefreshAtMs < walletRefreshMinIntervalMs
        ) return
        // Show LOADING only when there is nothing trustworthy on screen; a
        // ready or stale balance keeps showing while the refresh runs quietly.
        val currentWalletStatus = _uiState.value.walletStatus
        if (currentWalletStatus == WalletStatus.NO_SESSION || currentWalletStatus == WalletStatus.UNAVAILABLE) {
            _uiState.value = _uiState.value.copy(walletStatus = WalletStatus.LOADING)
        }
        walletRefreshJob = viewModelScope.launch {
            val result = economyService.fetchVerifiedProfile(owner)
            if (owner != authPreferences.effectiveUserId || session != authPreferences.sessionGeneration) return@launch
            if (result is com.cyclecare.app.data.economy.EconomyResult.Success) {
                // Only a successful, still-current fetch earns freshness:
                // failures and stale-session results never mark the cache.
                lastWalletRefreshAtMs = System.currentTimeMillis()
                lastWalletRefreshOwner = owner
                lastWalletRefreshSession = session
                lastReadyWalletOwner = owner
                val econ = result.data
                _uiState.value = _uiState.value.copy(economyProfile=econ, walletStatus = WalletStatus.READY,
                    isBanned=econ.isBanned,
                    isSuspended=econ.isSuspended, accountNoticeMessage=econ.suspensionReason)
            } else {
                // A failure must never look like a real zero balance. Show the
                // last-known value only when it belongs to THIS owner.
                _uiState.value = _uiState.value.copy(
                    walletStatus = if (lastReadyWalletOwner == owner) WalletStatus.STALE
                    else WalletStatus.UNAVAILABLE
                )
            }
        }
    }

    // One trailing reconnect run per cooldown window; coalesced so flapping
    // can never stack up pending runs.
    private var reconnectTrailingJob: kotlinx.coroutines.Job? = null

    /**
     * One pass of reconnect work: reconcile pending offline claims, fetch the
     * verified profile, refresh notifications. Owner and session are
     * re-checked after every network call. Returns false when a network step
     * failed and the run deserves one bounded retry; owner/session aborts
     * return true (the new session's own events drive its work).
     */
    private suspend fun runReconnectWorkOnce(): Boolean {
        val owner = authPreferences.effectiveUserId
        val session = authPreferences.sessionGeneration
        val reconciled = runCatching { miningUseCase.reconcilePendingOfflineClaims(_uiState.value.economyProfile) }.getOrNull()
        if (owner != authPreferences.effectiveUserId || session != authPreferences.sessionGeneration) return true
        if (reconciled is com.cyclecare.app.data.economy.EconomyResult.Success) {
            _uiState.value = _uiState.value.copy(economyProfile = reconciled.data, walletStatus = WalletStatus.READY)
        }
        val verified = runCatching { economyService.fetchVerifiedProfile(owner) }.getOrNull()
        if (owner != authPreferences.effectiveUserId || session != authPreferences.sessionGeneration) return true
        if (verified is com.cyclecare.app.data.economy.EconomyResult.Success) {
            val econ = verified.data
            _uiState.value = _uiState.value.copy(
                economyProfile = econ,
                walletStatus = WalletStatus.READY,
                isBanned = econ.isBanned,
                isSuspended = econ.isSuspended,
                accountNoticeMessage = econ.suspensionReason
            )
        }
        runCatching { refreshNotifications() }
        return reconciled is com.cyclecare.app.data.economy.EconomyResult.Success &&
            verified is com.cyclecare.app.data.economy.EconomyResult.Success
    }

    /** Schedule the single trailing reconnect run; an active one covers new requests. */
    private fun scheduleTrailingReconnectWork(delayMs: Long, allowReschedule: Boolean) {
        if (reconnectTrailingJob?.isActive == true) return
        reconnectTrailingJob = launchTrailingReconnectWork(delayMs, allowReschedule)
    }

    private fun launchTrailingReconnectWork(delayMs: Long, allowReschedule: Boolean): kotlinx.coroutines.Job {
        val ownerAtSchedule = authPreferences.effectiveUserId
        return viewModelScope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (ownerAtSchedule != authPreferences.effectiveUserId) return@launch
            val eligible = !authPreferences.isLocalOnlyMode && authPreferences.userId != null
            val online = com.cyclecare.app.util.NetworkHelper.isOnline(authPreferences.context)
            when (val decision = com.cyclecare.app.domain.sync.ReconnectWorkPolicy.decideTrailing(
                reconnectWorkState, online, eligible,
                authPreferences.effectiveUserId, System.currentTimeMillis()
            )) {
                is com.cyclecare.app.domain.sync.ReconnectWorkPolicy.Decision.Run -> runReconnectWorkOnce()
                is com.cyclecare.app.domain.sync.ReconnectWorkPolicy.Decision.Throttled -> {
                    // Another emission ran the work just before us; keep one
                    // trailing run alive for the remaining cooldown.
                    if (allowReschedule) {
                        reconnectTrailingJob = launchTrailingReconnectWork(decision.retryAfterMs, allowReschedule = true)
                    }
                }
                com.cyclecare.app.domain.sync.ReconnectWorkPolicy.Decision.Skip -> Unit
            }
        }
    }

    fun onForeground() {
        walletRealtime.start()
        refreshWalletNow(force = true)
        if (_uiState.value.hasCompletedInitialAuth) autoSyncSilently()
    }

    fun onBackground() { walletRealtime.stop() }

    fun autoSyncSilently(forceWalletRefresh: Boolean = false) = runAutoSync(forceWalletRefresh, null)

    /**
     * With a [scope] (startup) the owner, session, repository context and local snapshot come from
     * that scope, and nothing runs unless it is still current. Without one (every other caller)
     * owner and session are read live at call time and the local settings/profile are two separate
     * reads, guarded by the owner/session checks and the repository context captured at call time.
     */
    private fun runAutoSync(forceWalletRefresh: Boolean, scope: StartScope?) {
        if (scope != null && !isStartScopeCurrent(scope)) return
        if (authPreferences.isLocalOnlyMode || authPreferences.userId == null) {
            // Only resolves a pending "checking" state, and only from a stable stored snapshot.
            // It never derives routing from in-memory settings, which are unread defaults
            // (isOnboardingCompleted = false) before a successful read or after a failed one.
            if (!_uiState.value.isCheckingCloudProfile) return
            if (scope != null) {
                _uiState.value = _uiState.value.copy(isCheckingCloudProfile = false,
                    showOnboarding = !scope.snapshot.settings.isOnboardingCompleted)
                return
            }
            viewModelScope.launch {
                val stable = readStartScope(supersede = false) ?: return@launch
                if (!isStartScopeCurrent(stable)) return@launch
                _uiState.value = _uiState.value.copy(isCheckingCloudProfile = false,
                    showOnboarding = !stable.snapshot.settings.isOnboardingCompleted)
            }
            return
        }
        refreshWalletNow(forceWalletRefresh)
        if (accountRefreshJob?.isActive == true) return
        val owner = scope?.owner?.owner ?: authPreferences.effectiveUserId
        val session = scope?.owner?.session ?: authPreferences.sessionGeneration
        val syncContext = scope?.snapshot?.context ?: repository.currentContext()
        accountRefreshJob = viewModelScope.launch {
            val checking = _uiState.value.isCheckingCloudProfile || _uiState.value.cloudProfileCheckFailed
            if (checking) _uiState.value = _uiState.value.copy(isCheckingCloudProfile=true, cloudProfileCheckFailed=false,showOnboarding=false)
            val localRead = if (scope != null) Result.success(scope.snapshot.settings to scope.snapshot.profile)
                else runCatching { repository.getSettings() to repository.getOnboardingProfile() }
            val localSettings = localRead.getOrNull()?.first ?: CycleSettings()
            val localProfile = localRead.getOrNull()?.second
            val conflictsCheck = runCatching { repository.pendingSyncConflicts().isNotEmpty() }
            // A failed read is UNKNOWN, never "damaged -> re-onboard" and never
            // "clean -> safe to sync over".
            val localState = when {
                localRead.isFailure || conflictsCheck.isFailure ->
                    com.cyclecare.app.domain.sync.LocalDataState.UNKNOWN
                conflictsCheck.getOrDefault(false) ->
                    com.cyclecare.app.domain.sync.LocalDataState.CONFLICTED
                else -> com.cyclecare.app.domain.sync.LocalDataState.CLEAN
            }
            val localValid = localState != com.cyclecare.app.domain.sync.LocalDataState.UNKNOWN &&
                localProfile?.isCompleted == true && localSettings.isOnboardingCompleted
            val remote = if (checking && (localProfile == null || !localSettings.isOnboardingCompleted))
                kotlinx.coroutines.withTimeoutOrNull(20_000) { economyService.hasRemoteProfile() } else true
            if (owner != authPreferences.effectiveUserId || session != authPreferences.sessionGeneration) return@launch
            if (!repository.isCurrentContext(syncContext) || (scope != null && !isStartScopeCurrent(scope))) return@launch
            val destination = com.cyclecare.app.domain.sync.AccountStartPolicy.decide(localValid,localState,remote)
            if (checking) _uiState.value = _uiState.value.copy(isCheckingCloudProfile=false,
                cloudProfileCheckFailed=destination==com.cyclecare.app.domain.sync.AccountStartDestination.RETRY,
                showOnboarding=destination==com.cyclecare.app.domain.sync.AccountStartDestination.ONBOARDING,
                syncGatedForConflicts=localState==com.cyclecare.app.domain.sync.LocalDataState.CONFLICTED,
                settings=localSettings,onboardingProfile=localProfile)
            // Preservation gate: never run sync while the start decision is not
            // HOME, and never while sync conflicts are unresolved - uploading
            // could strand or overwrite owner data. Conflicted users still
            // reach HOME (and the Settings conflict review) when their local
            // data is valid; only the upload is gated.
            if (destination != com.cyclecare.app.domain.sync.AccountStartDestination.HOME) return@launch
            if (localState == com.cyclecare.app.domain.sync.LocalDataState.CONFLICTED) return@launch
            val result = syncUseCase.executeSync()
            if (owner != authPreferences.effectiveUserId || session != authPreferences.sessionGeneration) return@launch
            val restoredSettings = repository.getSettings()
            val restoredProfile = repository.getOnboardingProfile()
            val isAdmin = runCatching { authService.isCycleCareAdmin() }.getOrDefault(false)
            if (owner != authPreferences.effectiveUserId || session != authPreferences.sessionGeneration) return@launch
            if (!repository.isCurrentContext(syncContext) || (scope != null && !isStartScopeCurrent(scope))) return@launch
            _uiState.value = _uiState.value.copy(lastSyncTimestamp=result.timestamp,lastSyncMessage=result.message,
                lastSyncSuccess=result.isSuccess,settings=restoredSettings,onboardingProfile=restoredProfile,isAdmin=isAdmin,
                sessionNeedsReauthentication=if(result.isSuccess) false else result.requiresAuthentication || _uiState.value.sessionNeedsReauthentication)
            updateCalculations(restoredSettings,_uiState.value.currentDate,_uiState.value.selectedDate)
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
            showOnboarding = false,
            isCheckingCloudProfile = true,
            cloudProfileCheckFailed = false,
            isAppLocked = false,
            sessionNeedsReauthentication = false,
            authMode = authPreferences.accountMode,
            userEmail = authPreferences.userEmail,
            deviceId = authPreferences.deviceId,
            showQuickUnlockDialog = !authPreferences.isQuickUnlockAsked
        )
        walletRealtime.start()
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
            val scope = readStartScope() ?: return@launch
            val stored = scope.snapshot
            val settings = stored.settings
            val profile = stored.profile
            _uiState.value = _uiState.value.copy(hasCompletedInitialAuth = true, authMode = "ANONYMOUS",
                settings = settings, onboardingProfile = profile, showOnboarding = !settings.isOnboardingCompleted,
                isCheckingCloudProfile = false, isAppLocked = authPreferences.isPinEnabled || authPreferences.isFingerprintEnabled,
                isCloudSyncAllowed = false, isAiSharingAllowed = false, lastSyncMessage = "On this device only · no online account")
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
        walletRealtime.start()
        autoSyncSilently()
    }

    fun handleAuthDeepLink(uri: Uri) {
        viewModelScope.launch {
            when (val result = authService.completeEmailLink(uri)) {
                is com.cyclecare.app.data.supabase.AuthResult.Success -> {
                    authPreferences.hasCompletedInitialAuth = true
                    resetIdentityUiIfNeeded()
                    val scope = readStartScope() ?: return@launch
                    val stored = scope.snapshot
                    val settings = stored.settings
                    val profile = stored.profile
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
                    walletRealtime.start()
                    autoSyncSilently()
                    // The sign-in start owns its own first notification and manifest fill (a
                    // superseded startup no longer does). Both carry their own owner guards.
                    // Offline-claim reconcile is NOT added here: it stays with reconnect/startup.
                    if (isStartScopeCurrent(scope)) {
                        refreshNotifications()
                        refreshAiProcessorManifest()
                    }
                }
                is com.cyclecare.app.data.supabase.AuthResult.VerificationRequired -> _uiState.value = _uiState.value.copy(
                    lastSyncMessage = result.message,
                    lastSyncSuccess = false
                )
                is com.cyclecare.app.data.supabase.AuthResult.Error -> _uiState.value = _uiState.value.copy(
                    lastSyncMessage = result.errorMessage,
                    lastSyncSuccess = false
                )
            }
        }
    }

    fun completePasswordSetup(password: String) {
        viewModelScope.launch {
            when (val result = authService.setPassword(password)) {
                is com.cyclecare.app.data.supabase.AuthResult.Success -> _uiState.value = _uiState.value.copy(
                    isPasswordSetupRequired = false,
                    passwordSetupMessage = result.message
                )
                is com.cyclecare.app.data.supabase.AuthResult.VerificationRequired -> _uiState.value = _uiState.value.copy(
                    passwordSetupMessage = result.message
                )
                is com.cyclecare.app.data.supabase.AuthResult.Error -> _uiState.value = _uiState.value.copy(
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

    fun claimCoinsManual() {
        viewModelScope.launch {
            val res = earnCoinsUseCase.claimDailyAttunement(_uiState.value.economyProfile, baseReward = 30L)
            if (res is com.cyclecare.app.data.economy.EconomyResult.Success) {
                SoundFeedbackManager.playGentleRewardChime()
                _uiState.value = _uiState.value.copy(
                    economyProfile = res.data,
                    economyToastMessage = res.message
                )
            } else if (res is com.cyclecare.app.data.economy.EconomyResult.Error) {
                _uiState.value = _uiState.value.copy(economyToastMessage = res.errorMessage)
            }
        }
    }

    fun mineCoinsManual() {
        claimCoinsManual()
    }

    fun transferCoins(recipientEmail: String, amount: java.math.BigDecimal) {
        if (!com.cyclecare.app.util.NetworkHelper.isOnline(authPreferences.context)) {
            _uiState.value = _uiState.value.copy(
                economyToastMessage = "Sending coins needs an internet connection. Please reconnect and try again."
            )
            return
        }
        viewModelScope.launch {
            val res = miningUseCase.transferCoins(_uiState.value.economyProfile, recipientEmail, amount)
            if (res is com.cyclecare.app.data.economy.EconomyResult.Success) {
                SoundFeedbackManager.playGentleRewardChime()
                _uiState.value = _uiState.value.copy(
                    economyProfile = res.data,
                    economyToastMessage = res.message
                )
            } else if (res is com.cyclecare.app.data.economy.EconomyResult.Error) {
                _uiState.value = _uiState.value.copy(economyToastMessage = res.errorMessage)
            }
        }
    }

    fun unlockArtifact(item: com.cyclecare.app.data.economy.EconomyItem) {
        viewModelScope.launch {
            val res = miningUseCase.purchaseArtifact(_uiState.value.economyProfile, item)
            if (res is com.cyclecare.app.data.economy.EconomyResult.Success) {
                SoundFeedbackManager.playArtifactAcquired(item.harmonicPitchHz)
                _uiState.value = _uiState.value.copy(
                    economyProfile = res.data,
                    economyToastMessage = res.message
                )
            } else if (res is com.cyclecare.app.data.economy.EconomyResult.Error) {
                _uiState.value = _uiState.value.copy(economyToastMessage = res.errorMessage)
            }
        }
    }

    fun equipItem(item: com.cyclecare.app.data.economy.EconomyItem) {
        viewModelScope.launch {
            when (val res = miningUseCase.equipItem(_uiState.value.economyProfile, item)) {
                is com.cyclecare.app.data.economy.EconomyResult.Success -> {
                    SoundFeedbackManager.playArtifactInteraction(item.harmonicPitchHz)
                    _uiState.value = _uiState.value.copy(
                        economyProfile = res.data,
                        economyToastMessage = res.message
                    )
                }
                is com.cyclecare.app.data.economy.EconomyResult.Error -> _uiState.value = _uiState.value.copy(economyToastMessage = res.errorMessage)
            }
        }
    }

    fun purchasePackage(pkg: com.cyclecare.app.data.economy.CoinPurchasePackage) {
        viewModelScope.launch {
            when (val res = miningUseCase.purchasePackage(_uiState.value.economyProfile, pkg)) {
                is com.cyclecare.app.data.economy.EconomyResult.Success -> _uiState.value = _uiState.value.copy(
                    economyProfile = res.data,
                    economyToastMessage = res.message
                )
                is com.cyclecare.app.data.economy.EconomyResult.Error -> _uiState.value = _uiState.value.copy(economyToastMessage = res.errorMessage)
            }
        }
    }

    fun upgradeEconomyTier(tier: com.cyclecare.app.data.economy.UserEconomyTier) {
        viewModelScope.launch {
            when (val res = miningUseCase.upgradeTier(_uiState.value.economyProfile, tier)) {
                is com.cyclecare.app.data.economy.EconomyResult.Success -> _uiState.value = _uiState.value.copy(
                    economyProfile = res.data,
                    economyToastMessage = res.message
                )
                is com.cyclecare.app.data.economy.EconomyResult.Error -> _uiState.value = _uiState.value.copy(economyToastMessage = res.errorMessage)
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
            if (res is com.cyclecare.app.data.economy.EconomyResult.Error) {
                _uiState.value = _uiState.value.copy(economyToastMessage = res.errorMessage)
            }
        }
    }

    fun refreshNotifications() {
        if (authPreferences.isLocalOnlyMode || authPreferences.userId == null) return
        viewModelScope.launch {
            val userIdentifier = authPreferences.effectiveUserId
            val session = authPreferences.sessionGeneration
            val list = notificationService.fetchNotifications(userIdentifier)

            val verified = economyService.fetchVerifiedProfile(userIdentifier)
            if (userIdentifier != authPreferences.effectiveUserId || session != authPreferences.sessionGeneration) return@launch
            var giftItem: com.cyclecare.app.data.economy.EconomyItem? = null

            if (verified is com.cyclecare.app.data.economy.EconomyResult.Success) {
                val econ = verified.data
                val lastGiftName = econ.giftedArtifacts.lastOrNull()
                if (!lastGiftName.isNullOrBlank()) {
                    giftItem = com.cyclecare.app.data.economy.EconomyCatalog.findItem(lastGiftName)
                        ?: com.cyclecare.app.data.economy.EconomyCatalog.ALL_ITEMS[1]
                }

                if (giftItem == null) {
                    val unreadGiftNotif = list.firstOrNull { !it.isRead && it.type.equals("GIFT", ignoreCase = true) }
                    if (unreadGiftNotif != null) {
                        giftItem = com.cyclecare.app.data.economy.EconomyCatalog.ALL_ITEMS.find {
                            unreadGiftNotif.body.contains(it.name, ignoreCase = true) || unreadGiftNotif.title.contains(it.name, ignoreCase = true)
                        } ?: com.cyclecare.app.data.economy.EconomyCatalog.ALL_ITEMS[1]
                    }
                }

                _uiState.value = _uiState.value.copy(
                    notifications = list,
                    economyProfile = econ,
                    walletStatus = WalletStatus.READY,
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
        com.cyclecare.app.data.supabase.SupabaseSyncWorker.cancelSync(authPreferences.context)
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
        lastPeriodEndDate: LocalDate? = null,
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
        isCloudSyncAllowed: Boolean = false,
        dynamicAnswers: Map<String, Set<String>> = emptyMap()
    ) {
        if (_uiState.value.isCompletingOnboarding) return
        val onboardingOwner = captureConflictOwnerSnapshot()
        // Intent-time token: database, owner, session and that owner's consent generation,
        // captured when the user taps Finish. Everything below uses it, never a later lookup.
        val token = try { repository.beginOperation() } catch (_: com.cyclecare.app.data.repository.OwnerTransitionException) {
            _uiState.value = _uiState.value.copy(onboardingCompletionMessage = ONBOARDING_RETRY_MESSAGE)
            return
        }
        // The UI snapshot and the token are two captures; refuse unless they are the same
        // owner and session, so one tap can never pair owner A's intent with owner B's token.
        if (token.session != onboardingOwner.session || !isCurrentConflictSnapshot(onboardingOwner)) {
            _uiState.value = _uiState.value.copy(onboardingCompletionMessage = ONBOARDING_RETRY_MESSAGE)
            return
        }
        val operationId = ++onboardingOperationId
        _uiState.value = _uiState.value.copy(isCompletingOnboarding = true, onboardingCompletionMessage = null)
        viewModelScope.launch {
            try {
                fun failAttempt() {
                    if (operationId == onboardingOperationId && isCurrentConflictSnapshot(onboardingOwner)) {
                        _uiState.value = _uiState.value.copy(isCompletingOnboarding = false, onboardingCompletionMessage = ONBOARDING_RETRY_MESSAGE)
                    }
                }
                // The preference writes run under the preferences lock (shared by the account,
                // session and cloud-sync consent setters), only while the account and session are
                // still the ones the user acted under. The consent generation AFTER the user's own
                // cloud-sync choice is read in the same locked block and bound to the original
                // context. Atomic with preference changes, NOT with the database write that follows;
                // if that write fails the user's own choices stay saved and Finish can be tapped again.
                val applied = try {
                    authPreferences.runIfIdentityUnchanged(token.context.owner, token.session) {
                        authPreferences.isCloudSyncAllowed = isCloudSyncAllowed
                        if (dynamicAnswers.isNotEmpty()) {
                            // Personalization context gathered by the dynamic onboarding
                            // questions; local-only, feeds future insight personalization.
                            authPreferences.onboardingDynamicInsightsJson = org.json.JSONObject().apply {
                                dynamicAnswers.forEach { (id, answers) -> put(id, org.json.JSONArray(answers.toList())) }
                            }.toString()
                        }
                        if (pin.isNotBlank()) {
                            authPreferences.pin = pin
                            authPreferences.isPinEnabled = enableLock
                            authPreferences.isQuickUnlockAsked = true
                        }
                        authPreferences.accountMode to authPreferences.operationSnapshot().consentGeneration
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (_: Exception) { failAttempt(); return@launch }
                if (applied == null) { failAttempt(); return@launch }
                val accountMode = applied.first
                val writeToken = token.copy(consentGeneration = applied.second)

                val profile = OnboardingProfile(
                    id = 1,
                    isCompleted = true,
                    completedAtTimestamp = System.currentTimeMillis(),
                    isAgeVerified = true,
                    isTermsAgreed = true,
                    accountMode = accountMode,
                    selectedGoalsCsv = goals.joinToString(","),
                    lastPeriodDateString = if (periodStartChoice == "UNKNOWN") "" else lastPeriodDate.toString(),
                    // Raw episode record: the end date exactly as entered and
                    // confirmed, never clamped into the typical-duration range.
                    lastPeriodEndDateString = lastPeriodEndDate?.toString() ?: "",
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
                // The write uses the intent-time token (post-choice consent generation), never a later lookup.
                val completion = try {
                    repository.completeOnboardingWithProfile(profile = profile, pin = pin, enableLock = enableLock, token = writeToken)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (_: Exception) { failAttempt(); return@launch }
                SoundFeedbackManager.playGentleRewardChime()
                // The write above is bound to the database/owner captured at its start.
                // Publish only if that owner, session and repository context are still
                // current; otherwise the completed record stays with its own owner and
                // the current owner's state is left to its own load.
                if (!isCurrentConflictSnapshot(onboardingOwner) || !repository.isCurrentContext(completion.context)) return@launch
                val updated = completion.settings
                _uiState.value = _uiState.value.copy(
                    showOnboarding = false,
                    isCompletingOnboarding = false,
                    onboardingCompletionMessage = null,
                    isAppLocked = false,
                    settings = updated,
                    onboardingProfile = profile,
                    isCloudSyncAllowed = isCloudSyncAllowed,
                    showQuickUnlockDialog = !authPreferences.isQuickUnlockAsked && pin.isBlank()
                )
                updateCalculations(updated, LocalDate.now(), LocalDate.now())
                autoSyncSilently()
            } finally {
                // Cleared on every exit (including cancellation) by the operation that set it,
                // and only if no newer save has started since.
                if (operationId == onboardingOperationId) {
                    _uiState.value = _uiState.value.copy(isCompletingOnboarding = false)
                }
            }
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
                put("last_period_end_date", it.lastPeriodEndDateString)
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

    /** User-readable PDF export (primary format shown in Settings). */
    fun buildDataExportPdf(): ByteArray {
        val current = _uiState.value
        val logs = logsMap.value.values.sortedBy { it.dateString }
        return com.cyclecare.app.util.DataExportPdf.build(current.settings, current.onboardingProfile, logs)
    }

    fun wipeAllData() {
        authPreferences.isCloudSyncAllowed = false
        com.cyclecare.app.data.supabase.SupabaseSyncWorker.cancelSync(authPreferences.context)
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

    /**
     * Unified erase: wipes this device's health data AND the synced cloud health
     * copy in parallel. Each half degrades independently - a failed cloud
     * deletion never blocks the local wipe, and the local wipe never sends
     * anything to the cloud.
     */
    fun eraseAllDataUnified() {
        if (_uiState.value.isDeletingCloudHealth) return
        val hasCloudAccount = !authPreferences.isLocalOnlyMode && !authPreferences.userId.isNullOrBlank()
        val owner = authPreferences.effectiveUserId
        val session = authPreferences.sessionGeneration

        // Persist the sync withdrawal before any network I/O so no queued worker
        // can upload health data while deletion is in flight.
        var cloudArmed = false
        if (hasCloudAccount) {
            cloudArmed = authPreferences.pauseHealthSyncFor(owner, session) != null
        }
        authPreferences.isCloudSyncAllowed = false
        com.cyclecare.app.data.supabase.SupabaseSyncWorker.cancelSync(authPreferences.context)
        authPreferences.revokeAllProviderConsents()
        resetPrivateConversation()
        _uiState.value = _uiState.value.copy(
            isDeletingCloudHealth = true,
            isCloudSyncAllowed = false,
            isAiSharingAllowed = false,
            cloudHealthDeletionMessage = "Erasing your data…"
        )

        viewModelScope.launch {
            // null = no cloud account; true = deleted; false = not confirmed
            var cloudDeleted: Boolean? = null
            val cloudJob = if (hasCloudAccount && cloudArmed) {
                launch {
                    val token = com.cyclecare.app.data.supabase.SupabaseSessionManager.validAccessToken(authPreferences)
                    cloudDeleted =
                        if (token != null && authPreferences.effectiveUserId == owner && authPreferences.sessionGeneration == session) {
                            com.cyclecare.app.domain.ai.CarePrivacyService.deleteCloudHealthCopy(token)
                        } else false
                }
            } else null

            // Local wipe runs concurrently with the cloud deletion.
            val localJob = launch { repository.deleteAllLogs() }
            localJob.join()
            cloudJob?.join()

            val message = when {
                !hasCloudAccount -> "This device's records were erased. No online account is connected."
                !cloudArmed -> "This device's records were erased. The online erase wasn't sent because your choice couldn't be saved — please try again while signed in."
                cloudDeleted == true -> "Your records were erased from this device and your online copy was erased too."
                else -> "This device's records were erased, but the online erase couldn't be confirmed. Please try again before assuming your online copy is gone."
            }

            val resetSettings = repository.getSettings()
            _uiState.value = _uiState.value.copy(
                showOnboarding = true,
                isDeletingCloudHealth = false,
                isCloudSyncAllowed = false,
                settings = resetSettings,
                onboardingProfile = null,
                careChatHistory = emptyList(),
                careResponse = null,
                layeredChatResult = null,
                explainMyBodyResult = null,
                generatedCareImage = null,
                isGeneratingCareImage = false,
                careImageError = null,
                cloudHealthDeletionMessage = message
            )
            updateCalculations(resetSettings, LocalDate.now(), LocalDate.now())
        }
    }

    /** Read-only calculation over a captured owner/settings snapshot. No DB,
     * auth, network or writes. Caller rejects results if the owner changes. */
    fun getPredictionForSnapshot(snapshot: CycleUiState, date: LocalDate): DayPrediction? {
        if (!snapshot.hasCycleAnchor) return null
        return repository.calculateDayPrediction(date, snapshot.settings)
    }

    fun getPredictionsForSnapshot(
        snapshot: CycleUiState,
        month: java.time.YearMonth
    ): Map<String, DayPrediction> {
        if (!snapshot.hasCycleAnchor) return emptyMap()
        return (1..month.lengthOfMonth()).mapNotNull { day ->
            val date = month.atDay(day)
            getPredictionForSnapshot(snapshot, date)?.let { date.toString() to it }
        }.toMap()
    }

    fun getPredictionForDate(date: LocalDate): DayPrediction? {
        val current = _uiState.value
        if (!current.hasCycleAnchor) return null
        return repository.calculateDayPrediction(date, current.settings)
    }

    class Factory(
        private val repository: CycleRepository,
        private val authPreferences: com.cyclecare.app.security.AuthPreferences,
        private val syncService: com.cyclecare.app.data.supabase.SupabaseSyncService,
        private val authService: com.cyclecare.app.data.supabase.SupabaseAuthService
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CycleCareViewModel(repository, authPreferences, syncService, authService) as T
        }
    }
}


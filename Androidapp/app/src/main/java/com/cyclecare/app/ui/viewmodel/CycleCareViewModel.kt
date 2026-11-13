package com.cyclecare.app.ui.viewmodel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
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
    val canRepairOnboardingChoice:Boolean=false,
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

    @Volatile private var selectedDateIntent=LocalDate.now()
    private data class OnboardingStatus(val owner:String?,val session:Long,val lifecycle:com.cyclecare.app.data.local.OwnerDataGate.Snapshot,val operationId:Long,val running:Boolean,val message:String?)
    @Volatile private var onboardingStatus:OnboardingStatus?=null
    private fun normalizedOnboardingStatus(state:CycleUiState):CycleUiState {
        val status=onboardingStatus?:return state
        if(status.operationId!=onboardingOperationId)return state.copy(isCompletingOnboarding=false,onboardingCompletionMessage=null,canRepairOnboardingChoice=false)
        val current=authPreferences.rustOperationSnapshot().operation
        if(current.owner!=status.owner||current.session!=status.session||com.cyclecare.app.data.local.OwnerDataGate.snapshot(authPreferences.context,status.owner)!=status.lifecycle)return state.copy(isCompletingOnboarding=false,onboardingCompletionMessage=null,canRepairOnboardingChoice=false)
        val choice=uncertainOnboardingChoice
        val repair=choice!=null&&choice.operationId==status.operationId&&choice.original.token.preference.operation.owner==status.owner&&choice.original.token.preference.operation.session==status.session&&choice.original.token.lifecycle==status.lifecycle
        return state.copy(isCompletingOnboarding=status.running,onboardingCompletionMessage=status.message,canRepairOnboardingChoice=repair&&!status.running)
    }
    private val _uiState = com.cyclecare.app.security.AdmissionStateFlow(CycleUiState(),{authPreferences.publicationAdmission},normalize={state->
        if(authPreferences.publicationAdmission==null)state else {
            val date=selectedDateIntent
            normalizedOnboardingStatus(state.copy(localReadFailed=state.localReadFailed||recoveryMutable.value.isNotEmpty(),isCloudSyncAllowed=authPreferences.isCloudSyncAllowed,selectedDate=date,selectedDatePrediction=if(state.hasCycleAnchor)repository.calculateDayPrediction(date,state.settings)else null))
        }
    },afterPaint={state->traceStartup(com.cyclecare.app.security.StartupRouteDiagnostics.Event.UI_AFTER_ASSIGN,state.hasCompletedInitialAuth)})
    val uiState: StateFlow<CycleUiState> = _uiState.asStateFlow()
    private val careLogConsentGate = com.cyclecare.app.domain.ai.CareLogConsentGate()
    private var careJob: kotlinx.coroutines.Job? = null
    private var imageJob: kotlinx.coroutines.Job? = null
    private var activeUiOwner = authPreferences.effectiveUserId
    private var activeUiSession = authPreferences.sessionGeneration
    /** RECONSTRUCTION (2026-11-13, labeled NEW WORK, not recovered bytes):
     * synchronous construction stamp for the rust-revision observer in init:
     * an install landing BEFORE the collector's first emission must still
     * invalidate (StateFlow replays the bumped value to the late collector;
     * an asynchronous baseline would swallow it). */
    private var observedRustRevision = repository.currentRustPortRevision()
    private var careRequestGeneration = 0L
    private var pendingQuickLogOwner: String? = null
    private var pendingCareProposal: com.cyclecare.app.domain.ai.ProposedCareLog? = null
    private val processorConsentService = com.cyclecare.app.domain.ai.AiProcessorConsentService(authPreferences)

    /** Flags: pid,prefs,repository,database,local,auth,uiAuth,session,
     * readEpoch,resetEpoch,portRevision,selectedPort,scopeCurrent,originCurrent,admissionId,contextEpoch.
     * Instance IDs are ring-local counters, never owner/database names. */
    internal fun traceStartup(event:com.cyclecare.app.security.StartupRouteDiagnostics.Event,
        uiAuth:Boolean=_uiState.value.hasCompletedInitialAuth,scopeCurrent:Boolean=true,originCurrent:Boolean=true){
        val trace=com.cyclecare.app.security.StartupRouteDiagnostics
        if(!trace.isEnabled())return
        try{trace.record(event,this,android.os.Process.myPid().toLong(),trace.opaqueId(authPreferences),trace.opaqueId(repository),
            trace.opaqueId(repository.currentContext().database),if(authPreferences.isLocalOnlyMode)1 else 0,
            if(authPreferences.hasCompletedInitialAuth)1 else 0,if(uiAuth)1 else 0,authPreferences.sessionGeneration,
            localReadEpoch,identityResetEpoch,repository.currentRustPortRevision(),if(repository.currentRustPortForOnboarding()!=null)1 else 0,
            if(scopeCurrent)1 else 0,if(originCurrent)1 else 0,trace.opaqueId(authPreferences.publicationAdmission),repository.currentContext().epoch)}catch(_:Throwable){}
    }

    /** One-shot jobs retain intent-time owner/mode/consent/lifecycle through
     * every suspension. Long-lived flows establish a fresh origin per emission.
     */
    private fun launchIdentity(block:suspend kotlinx.coroutines.CoroutineScope.()->Unit):kotlinx.coroutines.Job {
        val identity=authPreferences.rustOperationSnapshot()
        val lifecycle=com.cyclecare.app.data.local.OwnerDataGate.snapshot(authPreferences.context,identity.operation.owner)
        val origin=com.cyclecare.app.security.PublicationOrigin.Context{
            authPreferences.publicationAdmission==null||
                (authPreferences.rustOperationSnapshot()==identity&&com.cyclecare.app.data.local.OwnerDataGate.snapshot(authPreferences.context,identity.operation.owner)==lifecycle)
        }
        return viewModelScope.launch(origin,block=block)
    }
    internal companion object {
        @Volatile internal var beforeOnboardingSubmissionLaunchForTests:((CycleCareViewModel)->Unit)?=null
        @Volatile internal var afterOnboardingChoiceForTests:(()->Unit)?=null
        @Volatile internal var afterPortRepositoryInstallForTests:(()->Unit)?=null
        @Volatile internal var beforeOnboardingInstallForTests:((kotlinx.coroutines.Job?)->Unit)?=null
        @Volatile internal var beforeDerivedPublicationForTests:(suspend (CycleCareViewModel)->Unit)?=null
        @Volatile internal var afterDerivedPublicationForTests:((CycleCareViewModel)->Unit)?=null
        @Volatile internal var beforeReloadPublicationForTests:(suspend (CycleCareViewModel)->Unit)?=null
        @Volatile internal var afterReloadPublicationForTests:((CycleCareViewModel)->Unit)?=null
        @Volatile internal var beforeCompletionPublicationForTests:(suspend (CycleCareViewModel)->Unit)?=null
        @Volatile internal var afterCompletionPublicationForTests:((CycleCareViewModel)->Unit)?=null
        @Volatile internal var beforeLogEnqueueForTests:(suspend (List<CycleDayLog>)->Unit)?=null
        @Volatile internal var afterLogPaintForTests:((List<CycleDayLog>)->Unit)?=null
        @Volatile internal var holdLogPublicationForTests:((List<CycleDayLog>,()->Unit)->Boolean)?=null
        /** RECONSTRUCTION (2026-11-13, labeled NEW WORK, not recovered bytes):
         * fires after each rust-revision observation in the init collector:
         * (previous stamp, observed revision, whether it invalidated). The
         * callback runs when the invalidation is QUEUED; the forced reset
         * itself re-routes through the admission's main scheduling. */
        @Volatile internal var afterRustRevisionObservationForTests:((Long,Long,Boolean)->Unit)?=null
        @Volatile internal var beforeStartupPublicationForTests:(suspend (CycleCareViewModel)->Unit)?=null
        @Volatile internal var afterStartupPublicationForTests:((CycleCareViewModel)->Unit)?=null
        @Volatile internal var beforeStartupCalculationForTests:((CycleCareViewModel)->Unit)?=null
        @Volatile internal var beforeCalculationStampForTests:((CycleCareViewModel,LocalDate)->Unit)?=null
        const val ONBOARDING_RETRY_MESSAGE = "Your answers couldn't be saved completely. Please try again."
    }

    /** Identifies the current onboarding save, so an older save's cleanup never clears a newer one's flag. */
    private var onboardingOperationId = 0L
    @Volatile internal var onboardingStatusFinalization:java.util.concurrent.CompletableFuture<Unit>?=null
        private set
    @Volatile internal var onboardingStatusFinalizationFailure:Throwable?=null
        private set

    private fun reportOnboardingPreflightFailure(){
        val admission=authPreferences.publicationAdmission
        if(admission==null){_uiState.value=_uiState.value.copy(onboardingCompletionMessage=ONBOARDING_RETRY_MESSAGE);return}
        val identity=authPreferences.rustOperationSnapshot()
        val lifecycle=com.cyclecare.app.data.local.OwnerDataGate.snapshot(authPreferences.context,identity.operation.owner)
        admission.transitionMain{
            if(authPreferences.rustOperationSnapshot()!=identity||com.cyclecare.app.data.local.OwnerDataGate.snapshot(authPreferences.context,identity.operation.owner)!=lifecycle)return@transitionMain
            if(onboardingStatus?.running==true||uncertainOnboardingChoice!=null||unpublishedOnboardingAttempt!=null)return@transitionMain
            val id=Math.addExact(onboardingOperationId,1L);onboardingOperationId=id
            onboardingStatus=OnboardingStatus(identity.operation.owner,identity.operation.session,lifecycle,id,false,ONBOARDING_RETRY_MESSAGE)
            com.cyclecare.app.security.PublicationOrigin.withOrigin({true}){_uiState.value=_uiState.value}
        }
    }
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
    /** RECONSTRUCTION (2026-11-13, labeled NEW WORK, not recovered bytes):
     * read-only test witnesses over existing private lifecycle state. */
    internal val identityResetEpochForTests: Long get() = identityResetEpoch
    internal val uiOwnerForTests: String? get() = activeUiOwner
    internal val uiSessionForTests: Long get() = activeUiSession

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
                // Selected startup publishes verified read success with its
                // coherent route. A queued old aggregate here can otherwise
                // restore default auth/routing after that startup paint.
                if(repository.currentRustPortForOnboarding()==null)_uiState.value = _uiState.value.copy(localReadFailed = false)
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
        launchIdentity {
            val result = runCatching { repository.pendingSyncConflicts() }
            if (!isCurrentConflictSnapshot(snapshot)) return@launchIdentity
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
        // Intent-time token after the conflict-owner snapshot (which may reset
        // identity), before isResolvingSyncConflict flips.
        val token=try{repository.beginOperation()}catch(_:com.cyclecare.app.data.repository.OwnerTransitionException){return}
        _uiState.value = _uiState.value.copy(isResolvingSyncConflict = true, syncConflictMessage = null)
        launchIdentity {
            val operation = runCatching {
                if (keepLocal) repository.retainLocalConflict(recordId, token)
                else repository.acceptRemoteConflict(recordId, token)
            }
            if (!isCurrentConflictSnapshot(snapshot)) return@launchIdentity
            val remaining = runCatching { repository.pendingSyncConflicts() }.getOrNull()
            if (!isCurrentConflictSnapshot(snapshot)) return@launchIdentity
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
        println("MERGE_DIAG reset pending="+(pendingCareProposal!=null)+" stack="+Thread.currentThread().stackTrace.take(12).joinToString(" | "))
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

    /** Candidate replacement requires an already opened/published owner port,
     * never account/key access here. One main admission updates repository,
     * sender and UI before new observations are published.
     */
    internal fun installPublishedRustPort(port:com.cyclecare.app.data.local.rust.RustRepositoryPort,stillCurrent:()->Boolean={true}):java.util.concurrent.CompletableFuture<Unit>{
        val admission=authPreferences.publicationAdmission?:error("admission_required")
        return admission.transitionMain{installRustPortAdmitted(port,stillCurrent)}
    }
    private fun installRustPortAdmitted(port:com.cyclecare.app.data.local.rust.RustRepositoryPort,stillCurrent:()->Boolean){
            check(stillCurrent()){"stale_port_install"}
            port.verifyPreferences(authPreferences)
            repository.replaceRustPort(port)
            afterPortRepositoryInstallForTests?.invoke()
            // The sync service reads the authoritative port from the bound
            // repository; no per-instance attach exists anymore.
            // Force fresh same-owner/token reload as well as owner switches.
            activeUiSession=Long.MIN_VALUE
            com.cyclecare.app.security.PublicationOrigin.withOrigin({true}){resetIdentityUiIfNeeded()}
    }
    private fun resetIdentityUiIfNeeded() {
        val admission=authPreferences.publicationAdmission
        if(admission!=null&&!admission.isTransitionAction()){
            admission.transitionMain{resetIdentityUiIfNeeded()};return
        }
        val owner = authPreferences.effectiveUserId
        val session = authPreferences.sessionGeneration
        if (activeUiOwner == owner && activeUiSession == session) return
        activeUiOwner = owner
        activeUiSession = session
        identityResetEpoch++
        invalidateRecovery()
        uiReadByPurpose.clear()
        walletRealtime.stop()
        accountRefreshJob?.cancel()
        walletRefreshJob?.cancel()
        lastReadyWalletOwner = null
        val ownerActivation=runCatching{repository.activateCurrentOwner()}
        _logsMap.value = emptyMap()
        resetPrivateConversation()
        // Local owner clearing must not depend on an optional worker service.
        // Revocation/identity guards still reject old work even if cancellation
        // infrastructure is unavailable. Cancellation failure is not UI success.
        val syncCancellation=runCatching{com.cyclecare.app.data.supabase.SupabaseSyncWorker.cancelSync(authPreferences.context)}
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
            localReadFailed = ownerActivation.isFailure, isCompletingOnboarding = false, onboardingCompletionMessage = null,
            todayPrediction = null, selectedDatePrediction = null, patterns = emptyList(),
            learnedObservations = emptyList(), clinicianReportText = "", explainMyBodyResult = null,
            isCloudSyncAllowed = authPreferences.isCloudSyncAllowed,
            isAiSharingAllowed = authPreferences.isAiSharingAllowed,
            aiProviderConsents = authPreferences.providerConsents().mapValues { it.value.granted },
            syncConflicts = emptyList(), isLoadingSyncConflicts = false,
            isResolvingSyncConflict = false, syncConflictMessage = null
            ,aiProcessorManifest = null, isLoadingAiProcessorManifest = false
        )
        ownerActivation.exceptionOrNull()?.let{android.util.Log.w("CycleCareIdentity","owner activation pending after local clear",it)}
        syncCancellation.exceptionOrNull()?.let{android.util.Log.w("CycleCareIdentity","sync cancellation unavailable after owner reset",it)}
        if(ownerActivation.isFailure)return
        // The wipe above protects against leaking the previous owner's data; now
        // load the NEW owner's own settings/profile so nothing downstream reads
        // factory defaults (which look like "never completed onboarding").
        val reloadEpoch=identityResetEpoch
        val reloadBase=_uiState.value
        launchIdentity {
            val reloaded = runCatching { readStableLocalSnapshot(resetFirst = false) }.getOrNull()
            if (reloaded != null && activeUiOwner == owner && activeUiSession == session) {
                beforeReloadPublicationForTests?.invoke(this@CycleCareViewModel)
                val reloadOrigin=com.cyclecare.app.security.PublicationOrigin.capture()
                val publishReload={
                    if(reloadOrigin()&&activeUiOwner==owner&&activeUiSession==session&&identityResetEpoch==reloadEpoch&&
                        repository.isCurrentContext(reloaded.context)&&
                        _uiState.value.settings==reloadBase.settings&&_uiState.value.onboardingProfile==reloadBase.onboardingProfile){
                        _uiState.value = _uiState.value.copy(settings = reloaded.settings, onboardingProfile = reloaded.profile)
                        afterReloadPublicationForTests?.invoke(this@CycleCareViewModel)
                    }
                }
                if(repository.currentRustPortForOnboarding()!=null){
                    publishCurrentRustRows("reload",{activeUiOwner==owner&&activeUiSession==session&&identityResetEpoch==reloadEpoch}){rows->
                        _logsMap.value=rows.logs.associateBy{it.dateString}
                        _uiState.value=_uiState.value.copy(settings=rows.settings,onboardingProfile=rows.profile)
                        updateCalculationsAdmitted(rows.settings,LocalDate.now(),_uiState.value.selectedDate,rows.logs)
                        afterReloadPublicationForTests?.invoke(this@CycleCareViewModel)
                    }
                }else if(admission==null)publishReload()
                else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){admission.transitionMain{publishReload()}.get()}
            }
        }
    }

    internal enum class UiReadStatus { PENDING, PAINTED, SUPERSEDED, UNINITIALIZED, STORAGE_FAILED, REJECTED, EXPIRED, CANCELLED_BEFORE_EFFECT, CANCELLED_WITH_EFFECT, PAINT_FAILED }
    internal enum class UiWaitEnd { ACTIVE, CANCELLED, EXPIRED, RETURNED }
    internal enum class UiEffect { NOT_STARTED, RUNNING, SUCCEEDED, FAILED }
    internal data class UiReadRecord(val id:Long,val effect:UiEffect=UiEffect.NOT_STARTED,
        val readCoverage:Long=0,val settledAt:Long=0,
        val effectFailure:Throwable?=null,val waitEnd:UiWaitEnd=UiWaitEnd.ACTIVE,
        val outcome:UiReadStatus?=null,val failure:Throwable?=null){
        val effectStarted get()=effect!=UiEffect.NOT_STARTED
        val effectCompleted get()=effect==UiEffect.SUCCEEDED
        val status:UiReadStatus get()=when{
            effect==UiEffect.FAILED->UiReadStatus.PAINT_FAILED
            effect==UiEffect.SUCCEEDED->UiReadStatus.PAINTED
            waitEnd==UiWaitEnd.EXPIRED->UiReadStatus.EXPIRED
            waitEnd==UiWaitEnd.CANCELLED->if(effectStarted)UiReadStatus.CANCELLED_WITH_EFFECT else UiReadStatus.CANCELLED_BEFORE_EFFECT
            else->outcome?:UiReadStatus.PENDING
        }
    }
    internal class UiReadOperation(val id:Long,val demandCoverage:Long=id,private val order:java.util.concurrent.atomic.AtomicLong=java.util.concurrent.atomic.AtomicLong()){
        fun capturedRead(){val sequence=order.incrementAndGet();change{old->old.copy(readCoverage=sequence)}}
        private val mutable=kotlinx.coroutines.flow.MutableStateFlow(UiReadRecord(id))
        val changes:kotlinx.coroutines.flow.StateFlow<UiReadRecord> = mutable.asStateFlow()
        val record get()=mutable.value
        val status get()=record.status
        val effectStarted get()=record.effectStarted
        val effectCompleted get()=record.effectCompleted
        val effectFailure get()=record.effectFailure
        val waitEnd get()=record.waitEnd
        val failure get()=record.failure
        // StateFlow CAS emits after its own internal lock. No receipt monitor is
        // held around callbacks. Transforms must be pure because CAS may retry.
        private fun change(transform:(UiReadRecord)->UiReadRecord){
            while(true){val old=record;val next=transform(old);if(next===old||mutable.compareAndSet(old,next))return}
        }
        fun startEffect():Boolean{
            while(true){
                val old=record
                if(old.effect!=UiEffect.NOT_STARTED||old.outcome!=null||old.waitEnd!=UiWaitEnd.ACTIVE)return false
                // RUNNING is irrevocable acquisition, not promise of completion.
                // A subscriber cancelling after this CAS cannot roll it back.
                if(mutable.compareAndSet(old,old.copy(effect=UiEffect.RUNNING)))return true
            }
        }
        fun finishEffect(){val sequence=order.incrementAndGet();change{old->check(old.effect==UiEffect.RUNNING){"effect_not_running"};old.copy(effect=UiEffect.SUCCEEDED,outcome=UiReadStatus.PAINTED,settledAt=sequence)}}
        fun failEffect(error:Throwable){val sequence=order.incrementAndGet();change{old->check(old.effect==UiEffect.RUNNING){"effect_not_running"};old.copy(effect=UiEffect.FAILED,effectFailure=error,failure=error,outcome=UiReadStatus.PAINT_FAILED,settledAt=sequence)}}
        fun complete(next:UiReadStatus,error:Throwable?=null){val sequence=order.incrementAndGet();change{old->
            if(old.outcome!=null||old.effect!=UiEffect.NOT_STARTED)old
            else{check(next!=UiReadStatus.PENDING&&next!=UiReadStatus.PAINTED&&next!=UiReadStatus.PAINT_FAILED){"effect_outcome_requires_effect"};old.copy(outcome=next,failure=error,settledAt=sequence)}
        }}
        fun endWait(end:UiWaitEnd,error:Throwable?=null){
            require(end!=UiWaitEnd.ACTIVE)
            change{old->if(old.waitEnd!=UiWaitEnd.ACTIVE)old else old.copy(waitEnd=end,failure=old.failure?:error)}
        }
        fun cancelWait()=endWait(UiWaitEnd.CANCELLED)
        fun expireWait(error:Throwable?=null)=endWait(UiWaitEnd.EXPIRED,error)
    }
    private val uiReadIds=java.util.concurrent.atomic.AtomicLong()
    private val uiReadOrder=java.util.concurrent.atomic.AtomicLong()
    internal data class UiRecovery(val purpose:String,val operationId:Long,val reason:UiReadStatus,val settledAt:Long=0)
    private val recoveryMutable=kotlinx.coroutines.flow.MutableStateFlow<Map<String,UiRecovery>>(emptyMap())
    internal val uiRecovery:kotlinx.coroutines.flow.StateFlow<Map<String,UiRecovery>> = recoveryMutable.asStateFlow()
    private data class RecoveryLedger(val epoch:Long=0,val revision:Long=0,
        val demands:Map<String,UiRecovery> = emptyMap(),val coverage:Map<String,Long> = emptyMap())
    private val recoveryLedger=java.util.concurrent.atomic.AtomicReference(RecoveryLedger())
    // This lock protects only job ownership and ledger mutations. Never invoke
    // an executor, future callback or StateFlow subscriber while holding it.
    private val recoveryPumpLock=Any()
    private data class RecoveryPump(val epoch:Long,val job:kotlinx.coroutines.Job)
    private var recoveryPump:RecoveryPump?=null
    internal data class RecoveryPresentation(val epoch:Long=0,val revision:Long=-1,val generation:Long=0)
    private val recoveryPresentedMutable=kotlinx.coroutines.flow.MutableStateFlow(RecoveryPresentation())
    internal val recoveryPresentedForTests=recoveryPresentedMutable.asStateFlow()
    @Volatile internal var beforeRecoveryCommitForTests:(()->Unit)?=null
    @Volatile internal var beforeRecoveryPumpExitForTests:(()->Unit)?=null
    @Volatile internal var beforeRecoveryPumpCancelForTests:(()->Unit)?=null
    internal fun pendingRecoveryForTests()=recoveryLedger.get().demands
    internal fun recoveryTargetForTests()=recoveryLedger.get().let{it.epoch to it.revision}
    internal fun invalidateRecoveryForTests():java.util.concurrent.CompletableFuture<Unit> =
        checkNotNull(authPreferences.publicationAdmission).transitionMain{invalidateRecovery()}
    private fun invalidateRecovery(){
        // Reset and presentation share MAIN admission. There is no epoch check
        // followed by an independently scheduled flow/UI write.
        val gate=authPreferences.publicationAdmission
        check(gate==null||gate.isTransitionAction()){"recovery_reset_requires_admission"}
        val oldPump=synchronized(recoveryPumpLock){
            val old=recoveryLedger.get()
            recoveryLedger.set(RecoveryLedger(epoch=old.epoch+1,revision=old.revision+1))
            recoveryPump.also{recoveryPump=null}
        }
        beforeRecoveryPumpCancelForTests?.invoke()
        oldPump?.job?.cancel()
        recoveryMutable.value=emptyMap()
    }
    internal fun recordReadRecovery(gate:com.cyclecare.app.security.IdentityPublicationAdmission,purpose:String,receipt:UiReadOperation,current:()->Boolean){
        recordReadRecoveryAtEpoch(gate,purpose,receipt,recoveryLedger.get().epoch,current)
    }
    private fun recordReadRecoveryAtEpoch(gate:com.cyclecare.app.security.IdentityPublicationAdmission,purpose:String,receipt:UiReadOperation,epoch:Long,current:()->Boolean){
        if(!current())return
        synchronized(recoveryPumpLock){
            val old=recoveryLedger.get();if(old.epoch!=epoch)return
            val record=receipt.record
            val actionable=record.status in setOf(UiReadStatus.UNINITIALIZED,UiReadStatus.STORAGE_FAILED,UiReadStatus.REJECTED,UiReadStatus.EXPIRED,UiReadStatus.PAINT_FAILED)
            val next=old.demands.toMutableMap();val covered=old.coverage.toMutableMap()
            if(record.effectCompleted){
                covered[purpose]=maxOf(covered[purpose]?:0,record.readCoverage)
                next.entries.removeAll{(_,demand)->
                    val ownProvisional=demand.purpose==purpose&&demand.operationId==receipt.id&&demand.settledAt==0L
                    val cut=maxOf(covered[demand.purpose]?:0,covered["startup"]?:0)
                    ownProvisional||(demand.settledAt>0&&demand.settledAt<=cut)
                }
            }else if(actionable){
                // An effect settling under existing covering read retires its
                // provisional wait demand even when no failure is re-added.
                if(record.settledAt>0)next.entries.removeAll{(_,d)->d.purpose==purpose&&d.operationId==receipt.id&&d.settledAt==0L}
                val cut=maxOf(covered[purpose]?:0,covered["startup"]?:0)
                if(record.settledAt==0L||record.settledAt>cut){
                    var key=if(uiReadByPurpose[purpose]===receipt)purpose else "$purpose:${receipt.id}"
                    // Settlement replaces the exact receipt's provisional entry
                    // even if its current-purpose pointer changed in the interim.
                    next.entries.removeAll{(_,d)->d.purpose==purpose&&d.operationId==receipt.id}
                    var demand=UiRecovery(purpose,receipt.id,record.status,record.settledAt)
                    if(record.settledAt>0){
                        // Same purpose AND reason share the exact same covering
                        // read predicate. Keep the largest settlement cut, not
                        // every receipt ID. Reasons remain separate so an older
                        // acquired partial effect is not hidden by rejection.
                        val same=next.filterValues{it.purpose==purpose&&it.reason==record.status&&it.settledAt>0}
                        val last=same.maxByOrNull{it.value.settledAt}
                        if(last!=null){
                            key=last.key
                            if(last.value.settledAt>record.settledAt)demand=last.value
                            same.keys.forEach{next.remove(it)}
                        }
                    }
                    // A purpose key may already hold a distinct reason's cut.
                    // Never overwrite it just because this is latest-purpose.
                    if(next.containsKey(key))key="$purpose:${demand.operationId}"
                    next[key]=demand
                }
            }
            recoveryLedger.set(old.copy(revision=old.revision+1,demands=next.toMap(),coverage=covered.toMap()))
        }
        ensureRecoveryPump(gate,epoch)
    }
    private fun ensureRecoveryPump(gate:com.cyclecare.app.security.IdentityPublicationAdmission,epoch:Long){
        var start:kotlinx.coroutines.Job?=null
        synchronized(recoveryPumpLock){
            if(recoveryLedger.get().epoch!=epoch)return
            val owner=recoveryPump
            if(owner==null||owner.epoch!=epoch||owner.job.isCompleted){
                val job=viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default,start=kotlinx.coroutines.CoroutineStart.LAZY){
                    val self=kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
                    try{
                        var retry=0
                        while(kotlinx.coroutines.currentCoroutineContext().isActive){
                            val target=recoveryLedger.get();if(target.epoch!=epoch)return@launch
                            try{
                                kotlinx.coroutines.suspendCancellableCoroutine<Unit>{continuation->
                                    val publish=gate.transitionMain{
                                        beforeRecoveryCommitForTests?.invoke()
                                        val latest=recoveryLedger.get()
                                        if(latest.epoch==epoch){
                                            recoveryMutable.value=latest.demands
                                            _uiState.value=_uiState.value.copy(localReadFailed=latest.demands.isNotEmpty())
                                            val prior=recoveryPresentedMutable.value
                                            recoveryPresentedMutable.value=RecoveryPresentation(epoch,latest.revision,prior.generation+1)
                                        }
                                    }
                                    continuation.invokeOnCancellation{publish.cancel(false)}
                                    publish.whenComplete{_,error->if(continuation.isActive){
                                        if(error==null)continuation.resumeWith(Result.success(Unit))else continuation.resumeWith(Result.failure(error))
                                    }}
                                }
                                retry=0
                                beforeRecoveryPumpExitForTests?.invoke()
                                val done=synchronized(recoveryPumpLock){
                                    if(recoveryLedger.get().epoch!=epoch)true
                                    else if(recoveryLedger.get().revision==target.revision){
                                        if(recoveryPump?.job===self)recoveryPump=null
                                        true
                                    }else false
                                }
                                if(done)return@launch
                            }catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
                            catch(error:Exception){
                                retry=(retry+1).coerceAtMost(5)
                                kotlinx.coroutines.delay((retry*100L).coerceAtMost(500))
                            }
                        }
                    }finally{
                        // A retiring epoch can never clear its successor. A
                        // live same-epoch producer either sees this owner and
                        // its next revision, or starts after this atomic detach.
                        synchronized(recoveryPumpLock){if(recoveryPump?.job===self)recoveryPump=null}
                    }
                }
                recoveryPump=RecoveryPump(epoch,job);start=job
            }
        }
        start?.start()
    }
    @Volatile internal var lastUiReadOperation:UiReadOperation?=null;private set
    // Retains exact handles by purpose, including after a waiter is cancelled.
    private val uiReadByPurpose=java.util.concurrent.ConcurrentHashMap<String,UiReadOperation>()
    internal fun uiReadForTests(purpose:String)=uiReadByPurpose[purpose]
    internal fun retainReadForTests(purpose:String,receipt:UiReadOperation){uiReadByPurpose[purpose]=receipt}
    @Volatile internal var startupPaintForTests:CycleUiState?=null;private set
    @Volatile internal var beforeUiReadEffectForTests:((UiReadOperation)->Unit)?=null

    private fun observeReadRecovery(gate:com.cyclecare.app.security.IdentityPublicationAdmission,purpose:String,receipt:UiReadOperation,current:()->Boolean){
        // Owned by VM lifetime, not the caller's cancelled waiter. RUNNING may
        // finish later; every terminal record re-evaluates presentation at drain.
        val epoch=recoveryLedger.get().epoch
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Unconfined){
            receipt.changes.first{state->
                val terminal=state.outcome!=null||state.effect==UiEffect.SUCCEEDED||state.effect==UiEffect.FAILED
                if(terminal||state.waitEnd==UiWaitEnd.EXPIRED)recordReadRecoveryAtEpoch(gate,purpose,receipt,epoch,current)
                terminal || (state.waitEnd==UiWaitEnd.CANCELLED&&state.effect==UiEffect.NOT_STARTED)
            }
        }
    }
    /** Pure read only. Bounded attempts keep the originating signal owned until
     * current paint, supersession, cancellation, or explicit recoverable failure.
     * No claim for writers outside this admission/namespace.
     */
    internal suspend fun publishCurrentRustRows(purpose:String="refresh",valid:()->Boolean,paint:(CycleRepository.UiRead.Ready)->Unit):UiReadOperation {
        val operationId=uiReadIds.incrementAndGet()
        val receipt=UiReadOperation(operationId,operationId,uiReadOrder)
        lastUiReadOperation=receipt;uiReadByPurpose[purpose]=receipt
        traceStartup(com.cyclecare.app.security.StartupRouteDiagnostics.Event.READ_BEGIN)
        receipt.changes.let { changes ->
            if(com.cyclecare.app.security.StartupRouteDiagnostics.isEnabled())viewModelScope.launch(kotlinx.coroutines.Dispatchers.Unconfined){
                changes.first{record->
                    if(record.outcome!=null||record.effect==UiEffect.SUCCEEDED||record.effect==UiEffect.FAILED){
                        com.cyclecare.app.security.StartupRouteDiagnostics.record(com.cyclecare.app.security.StartupRouteDiagnostics.Event.READ_RECEIPT,this@CycleCareViewModel,
                            operationId,record.status.ordinal.toLong(),record.effect.ordinal.toLong(),record.waitEnd.ordinal.toLong())
                        true
                    }else false
                }
            }
        }
        val gate=authPreferences.publicationAdmission?:run{receipt.complete(UiReadStatus.SUPERSEDED);return receipt}
        val origin=com.cyclecare.app.security.PublicationOrigin.capture()
        val port=repository.currentRustPortForOnboarding()?:run{receipt.complete(UiReadStatus.SUPERSEDED);return receipt}
        val current={
            val originOk=origin();val validOk=originOk&&valid();val samePort=validOk&&repository.currentRustPortForOnboarding()===port;val portOk=samePort&&port.isCurrent()
            com.cyclecare.app.security.StartupRouteDiagnostics.record(com.cyclecare.app.security.StartupRouteDiagnostics.Event.READ_CHECK,this,
                operationId,if(originOk)1 else 0,if(validOk)1 else 0,if(samePort)1 else 0,if(portOk)1 else 0)
            originOk&&validOk&&samePort&&portOk
        }
        observeReadRecovery(gate,purpose,receipt,current)
        // A pure-read attempt can fail before effect. Never replay a started paint.
        var lastError:Throwable?=null
        var finalStatus:UiReadStatus?=null
        try {
            repeat(3){attempt->
                if(finalStatus!=null)return@repeat
                if(!current()){finalStatus=UiReadStatus.SUPERSEDED;return@repeat}
                try{
                    var domainStatus:UiReadStatus?=null
                    val result=kotlinx.coroutines.withTimeoutOrNull(5000){
                        kotlinx.coroutines.suspendCancellableCoroutine<com.cyclecare.app.security.IdentityPublicationAdmission.ReadPublication>{continuation->
                            val operation=gate.readThenPublish(stillCurrent={continuation.isActive&&current()},
                                read={repository.readRustUiRowsAdmitted().also{receipt.capturedRead()}},paint={rows->
                                    if(!continuation.isActive||!current())domainStatus=UiReadStatus.SUPERSEDED
                                    else when(rows){
                                        is CycleRepository.UiRead.Ready->{
                                            if(receipt.startEffect()){
                                                try{beforeUiReadEffectForTests?.invoke(receipt);paint(rows);receipt.finishEffect()}
                                                catch(error:Throwable){receipt.failEffect(error);throw error}
                                            }
                                        }
                                        CycleRepository.UiRead.Superseded->domainStatus=UiReadStatus.SUPERSEDED
                                        CycleRepository.UiRead.Uninitialized->domainStatus=UiReadStatus.UNINITIALIZED
                                        is CycleRepository.UiRead.Failed->{domainStatus=UiReadStatus.STORAGE_FAILED;lastError=com.cyclecare.app.data.local.rust.RustRepositoryPort.Failure(rows.cause)}
                                    }
                                })
                            continuation.invokeOnCancellation{operation.cancel(false)}
                            operation.whenComplete{status,error->if(continuation.isActive){
                                if(error==null)continuation.resumeWith(Result.success(status))else continuation.resumeWith(Result.failure(error))
                            }}
                        }
                    }
                    when{
                        result==null->{receipt.expireWait();finalStatus=UiReadStatus.EXPIRED}
                        receipt.effectStarted->finalStatus=receipt.status
                        domainStatus!=null->finalStatus=domainStatus
                        result==com.cyclecare.app.security.IdentityPublicationAdmission.ReadPublication.SUPERSEDED->finalStatus=UiReadStatus.SUPERSEDED
                        result==com.cyclecare.app.security.IdentityPublicationAdmission.ReadPublication.EXPIRED->finalStatus=UiReadStatus.EXPIRED
                        else->finalStatus=UiReadStatus.SUPERSEDED
                    }
                }catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
                catch(error:Exception){
                    lastError=error
                    if(receipt.effectStarted)finalStatus=receipt.status
                    else if(attempt==2)finalStatus=UiReadStatus.REJECTED
                    else kotlinx.coroutines.delay(100L*(attempt+1))
                }
            }
            if(!receipt.effectStarted)receipt.complete(finalStatus?:UiReadStatus.REJECTED,lastError)
            receipt.endWait(UiWaitEnd.RETURNED)
        }catch(cancelled:kotlinx.coroutines.CancellationException){receipt.cancelWait();throw cancelled}
        return receipt
    }

    private val _logsMap = com.cyclecare.app.security.AdmissionStateFlow<Map<String, CycleDayLog>>(emptyMap(),{authPreferences.publicationAdmission})
    val logsMap: StateFlow<Map<String, CycleDayLog>> = _logsMap.asStateFlow()

    private val reconnectWorkState = com.cyclecare.app.domain.sync.ReconnectWorkPolicy.State()

    private var startupJob: kotlinx.coroutines.Job? = null

    /** Re-runs the startup read after a failed steady read. Real re-read, not a cloud refresh. */
    fun retryLocalRead() {
        // Explicit retry supersedes a failed start still in bounded backoff.
        // It must also recreate collectors that terminated on storage failure.
        startupJob?.cancel()
        // Keep failed state visible until a verified startup snapshot succeeds.
        startLogObservation()
        startDerivedObservation()
        runStartup()
    }

    private fun runStartup() {
        startupJob = launchIdentity {
            // One database/owner capture; a read that straddled an owner or
            // session switch is retried, never published.
            traceStartup(com.cyclecare.app.security.StartupRouteDiagnostics.Event.START_READ)
            val scope = readStartScope() ?: run{traceStartup(com.cyclecare.app.security.StartupRouteDiagnostics.Event.START_NO_SCOPE);return@launchIdentity}
            traceStartup(com.cyclecare.app.security.StartupRouteDiagnostics.Event.START_READY)
            com.cyclecare.app.security.StartupRouteDiagnostics.record(com.cyclecare.app.security.StartupRouteDiagnostics.Event.START_SCOPE,this@CycleCareViewModel,
                scope.startEpoch,scope.resetEpoch,scope.snapshot.context.epoch,scope.owner.session)
            val stored = scope.snapshot
            var settings = stored.settings
            var profile = stored.profile
            // Isolated spike wiring (RustLifecycleConfig.STARTUP_GATE_ENABLED=false):
            // when enabled, the Rust startup gate computes these flags with a
            // Kotlin fallback; when disabled, gate stays null and the original
            // expressions below run unchanged.
            var gate = if (com.cyclecare.app.rust.RustLifecycleConfig.STARTUP_GATE_ENABLED)
                com.cyclecare.app.rust.RustLifecycleAdapters.startupGate(
                    authPreferences, profile != null, settings.isOnboardingCompleted) else null
            var hasCompletedInitialAuth = gate?.hasCompletedInitialAuth ?: authPreferences.hasCompletedInitialAuth
            var needsCloudProfileCheck = gate?.needsCloudProfileCheck ?: (hasCompletedInitialAuth && !authPreferences.isLocalOnlyMode &&
                (profile == null || !settings.isOnboardingCompleted))
            val isPinSet = authPreferences.isPinEnabled && !authPreferences.pin.isNullOrBlank()
            var isLocked = gate?.isLocked ?: (hasCompletedInitialAuth && (isPinSet || authPreferences.isFingerprintEnabled))
            var showOnboarding = gate?.showOnboarding ?: (hasCompletedInitialAuth && !needsCloudProfileCheck &&
                (profile == null || !settings.isOnboardingCompleted))

            val isCachedBanned = authPreferences.accountStatus.equals("BANNED", ignoreCase = true)
            val isCachedSuspended = authPreferences.accountStatus.equals("SUSPENDED", ignoreCase = true)
            val cachedNotice = authPreferences.suspensionReason

            traceStartup(com.cyclecare.app.security.StartupRouteDiagnostics.Event.START_BEFORE)
            beforeStartupPublicationForTests?.invoke(this@CycleCareViewModel)
            val startupOrigin=com.cyclecare.app.security.PublicationOrigin.capture()
            val publishStartup={
                traceStartup(com.cyclecare.app.security.StartupRouteDiagnostics.Event.START_CHECK,scopeCurrent=isStartScopeCurrent(scope),originCurrent=startupOrigin())
                if(startupOrigin()&&isStartScopeCurrent(scope)){
                    gate=if(com.cyclecare.app.rust.RustLifecycleConfig.STARTUP_GATE_ENABLED)
                        com.cyclecare.app.rust.RustLifecycleAdapters.startupGate(authPreferences,profile!=null,settings.isOnboardingCompleted) else null
                    hasCompletedInitialAuth=gate?.hasCompletedInitialAuth?:authPreferences.hasCompletedInitialAuth
                    needsCloudProfileCheck=gate?.needsCloudProfileCheck?:(hasCompletedInitialAuth&&!authPreferences.isLocalOnlyMode&&(profile==null||!settings.isOnboardingCompleted))
                    isLocked=gate?.isLocked?:(hasCompletedInitialAuth&&(isPinSet||authPreferences.isFingerprintEnabled))
                    showOnboarding=gate?.showOnboarding?:(hasCompletedInitialAuth&&!needsCloudProfileCheck&&(profile==null||!settings.isOnboardingCompleted))
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
                    traceStartup(com.cyclecare.app.security.StartupRouteDiagnostics.Event.START_AFTER)
                }
            }
            val startupAdmission=authPreferences.publicationAdmission
            val selectedRust=repository.currentRustPortForOnboarding()!=null
            if(selectedRust){
                if(publishCurrentRustRows("startup",{isStartScopeCurrent(scope)}){rows->
                    settings=rows.settings;profile=rows.profile
                    publishStartup()
                    _logsMap.value=rows.logs.associateBy{it.dateString}
                    updateCalculationsAdmitted(rows.settings,LocalDate.now(),_uiState.value.selectedDate,rows.logs)
                    startupPaintForTests=_uiState.value
                    afterStartupPublicationForTests?.invoke(this@CycleCareViewModel)
                }.status!=UiReadStatus.PAINTED)return@launchIdentity
            }else {
                if(startupAdmission==null)publishStartup()
                else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){startupAdmission.transitionMain{publishStartup()}.get()}
                afterStartupPublicationForTests?.invoke(this@CycleCareViewModel)
            }

            if (hasCompletedInitialAuth && !authPreferences.isLocalOnlyMode && authPreferences.accountMode == "ANONYMOUS" &&
                com.cyclecare.app.util.NetworkHelper.isOnline(authPreferences.context)
            ) {
                val session = authService.continueAsGuest()
                // Suspended above: publish only if this start is still the current one.
                if (!isStartScopeCurrent(scope)) return@launchIdentity
                when (session) {
                    is com.cyclecare.app.data.supabase.AuthResult.Error -> _uiState.value = _uiState.value.copy(
                        lastSyncMessage = session.errorMessage,
                        lastSyncSuccess = false
                    )
                    else -> Unit
                }
            }

            if (!isStartScopeCurrent(scope)) return@launchIdentity
            beforeStartupCalculationForTests?.invoke(this@CycleCareViewModel)
            if(!selectedRust)updateCalculations(settings, LocalDate.now(), LocalDate.now())

            if (hasCompletedInitialAuth) runAutoSync(false, scope)
            val initOwner = authPreferences.effectiveUserId
            val initSession = authPreferences.sessionGeneration
            val fetchWallet = gate?.fetchWallet ?: (hasCompletedInitialAuth && !authPreferences.isLocalOnlyMode && authPreferences.userId != null)
            val verifiedEconomy = if (fetchWallet)
                economyService.fetchVerifiedProfile(initOwner) else null
            if (!isStartScopeCurrent(scope)) return@launchIdentity
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
                if (!isStartScopeCurrent(scope)) return@launchIdentity
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

    private var logObservationJob:kotlinx.coroutines.Job?=null
    private var derivedObservationJob:kotlinx.coroutines.Job?=null

    private fun startLogObservation(){
        logObservationJob?.cancel()
        logObservationJob=viewModelScope.launch {

            repository.scopedLogs.catch{error->
                if(error is kotlinx.coroutines.CancellationException)throw error
                _uiState.value=_uiState.value.copy(localReadFailed=true)
                android.util.Log.w("CycleCareLocalRead","log observation failed",error)
            }.collect { snapshot ->
                val admission=authPreferences.publicationAdmission
                if(admission==null){
                    _logsMap.value = if (repository.isCurrentSnapshot(snapshot)) snapshot.logs.associateBy { it.dateString } else emptyMap()
                }else{
                    val identity=authPreferences.rustOperationSnapshot()
                    val lifecycle=com.cyclecare.app.data.local.OwnerDataGate.snapshot(authPreferences.context,identity.operation.owner)
                    // Startup routing may transition while this Room emission waits.
                    // Check its identity/context at the actual publication, rather than
                    // dropping the only current row set on an unrelated epoch change.
                    beforeLogEnqueueForTests?.invoke(snapshot.logs)
                    val paintLogs={
                        if(authPreferences.rustOperationSnapshot()==identity&&
                            com.cyclecare.app.data.local.OwnerDataGate.snapshot(authPreferences.context,identity.operation.owner)==lifecycle&&repository.isCurrentSnapshot(snapshot)){
                            _logsMap.value=snapshot.logs.associateBy{it.dateString}
                            afterLogPaintForTests?.invoke(snapshot.logs)
                        }
                    }
                    if(holdLogPublicationForTests?.invoke(snapshot.logs,paintLogs)!=true){
                        if(repository.currentRustPortForOnboarding()!=null){
                            publishCurrentRustRows("logs",{authPreferences.rustOperationSnapshot()==identity&&repository.isCurrentSnapshot(snapshot)}){rows->
                                _logsMap.value=rows.logs.associateBy{it.dateString}
                                _uiState.value=_uiState.value.copy(settings=rows.settings,onboardingProfile=rows.profile)
                                updateCalculationsAdmitted(rows.settings,LocalDate.now(),_uiState.value.selectedDate,rows.logs)
                                afterLogPaintForTests?.invoke(snapshot.logs)
                            }
                        }else admission.transitionMain{paintLogs()}
                    }
                }
            }
        
        }
    }
    private fun startDerivedObservation(){
        derivedObservationJob?.cancel()
        derivedObservationJob=viewModelScope.launch {

            // Rust scoped observation already wakes for settings/profile/logs.
            // Do not add a second independent domain read that can fail during
            // cancellation before the coherent refresh even gets a chance.
            (if(repository.currentRustPortForOnboarding()!=null)repository.scopedLogs
             else combine(repository.settingsFlow, repository.scopedLogs) { _, scoped -> scoped })
                .catch{error->
                    if(error is kotlinx.coroutines.CancellationException)throw error
                    _uiState.value=_uiState.value.copy(localReadFailed=true)
                    android.util.Log.w("CycleCareLocalRead","derived observation failed",error)
                }.collect { scoped ->
                if (!repository.isCurrentSnapshot(scoped)) return@collect
                val identity=authPreferences.rustOperationSnapshot()
                val owner = authPreferences.effectiveUserId
                beforeDerivedPublicationForTests?.invoke(this@CycleCareViewModel)
                if(repository.currentRustPortForOnboarding()!=null){
                    publishCurrentRustRows("derived",{authPreferences.rustOperationSnapshot()==identity&&repository.isCurrentSnapshot(scoped)}){rows->
                        _logsMap.value=rows.logs.associateBy{it.dateString}
                        _uiState.value=_uiState.value.copy(settings=rows.settings,onboardingProfile=rows.profile)
                        updateCalculationsAdmitted(rows.settings,_uiState.value.currentDate,_uiState.value.selectedDate,rows.logs)
                        afterDerivedPublicationForTests?.invoke(this@CycleCareViewModel)
                    }
                    return@collect
                }
                val newSettings = try{repository.getSettings()}catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}catch(error:Exception){
                    _uiState.value=_uiState.value.copy(localReadFailed=true)
                    android.util.Log.w("CycleCareLocalRead","derived settings read failed",error)
                    return@collect
                }
                if (owner != authPreferences.effectiveUserId || !repository.isCurrentSnapshot(scoped)) return@collect
                val allLogs = scoped.logs
                val activeSettings = newSettings ?: _uiState.value.settings
                com.cyclecare.app.security.PublicationOrigin.withOrigin({authPreferences.rustOperationSnapshot()==identity&&repository.isCurrentSnapshot(scoped)}){
                updateCalculations(
                    settings = activeSettings,
                    today = _uiState.value.currentDate,
                    selectedDate = _uiState.value.selectedDate,
                    explicitLogs = allLogs
                )
                }
            }
        
        }
    }
    private fun updateCalculations(
        settings: CycleSettings,
        today: LocalDate,
        selectedDate: LocalDate,
        explicitLogs: List<CycleDayLog>? = null
    ) {
        val admission=authPreferences.publicationAdmission
        if(admission!=null){
            val identity=authPreferences.rustOperationSnapshot()
            val origin=com.cyclecare.app.security.PublicationOrigin.capture()
            beforeCalculationStampForTests?.invoke(this,selectedDate)
            admission.stamp().thenAccept{stamp->admission.publish(stamp,{origin()&&authPreferences.rustOperationSnapshot()==identity}){
                // Calculation refreshes are not date-selection intents. SelectDate
                // owns this field; resolve it only after admission, including startup.
                updateCalculationsAdmitted(settings,today,_uiState.value.selectedDate,explicitLogs)
            }};return
        }
        updateCalculationsAdmitted(settings,today,selectedDate,explicitLogs)
    }
    private fun updateCalculationsAdmitted(settings:CycleSettings,today:LocalDate,selectedDate:LocalDate,explicitLogs:List<CycleDayLog>?){
        val profile = _uiState.value.onboardingProfile
        // Native-first: core cycle_screen_state anchor-slice port of the
        // computation below. A null reply (native unavailable/malformed)
        // keeps the Kotlin computation, verbatim.
        val nativeAnchor = com.cyclecare.app.rust.CycleCareCoreBridge.cycleScreenAnchorState(
            settings, profile?.approximatePeriodChoice, profile?.cycleLengthOption, today, selectedDate)
        val hasCycleAnchor: Boolean
        val approximateChoice: Boolean
        val daysUntilPeriod: Int
        val daysUntilOvu: Int
        val todayPrediction: com.cyclecare.app.data.model.DayPrediction?
        val selectedPrediction: com.cyclecare.app.data.model.DayPrediction?
        if (nativeAnchor != null) {
            hasCycleAnchor = nativeAnchor.hasCycleAnchor
            approximateChoice = nativeAnchor.cycleStartIsApproximate
            daysUntilPeriod = nativeAnchor.daysUntilNextPeriod
            daysUntilOvu = nativeAnchor.daysUntilOvulation
            todayPrediction = if (hasCycleAnchor) repository.calculateDayPrediction(today, settings) else null
            selectedPrediction = if (hasCycleAnchor) repository.calculateDayPrediction(selectedDate, settings) else null
        } else {
            val dateChoice = profile?.approximatePeriodChoice?.trim()?.lowercase().orEmpty()
            val unknownDateChoice = dateChoice.isBlank() || dateChoice == "unknown" || dateChoice.contains("don't know") ||
                dateChoice.contains("don’t know") || dateChoice == "it varies"
            val cycleLengthChoice = profile?.cycleLengthOption?.trim()?.lowercase().orEmpty()
            val unknownCycleLengthChoice = cycleLengthChoice.isBlank() || cycleLengthChoice == "unknown" ||
                cycleLengthChoice.contains("don't know") || cycleLengthChoice.contains("don’t know") ||
                cycleLengthChoice.contains("changes")
            hasCycleAnchor = settings.lastPeriodStartDate.isNotBlank() && !unknownDateChoice && !unknownCycleLengthChoice
            approximateChoice = dateChoice.startsWith("approximate:") || dateChoice in setOf(
                "today", "a few days ago", "about a week ago", "about two weeks ago"
            )
            todayPrediction = if (hasCycleAnchor) repository.calculateDayPrediction(today, settings) else null
            selectedPrediction = if (hasCycleAnchor) repository.calculateDayPrediction(selectedDate, settings) else null
            val cycleLength = settings.averageCycleLength.coerceIn(21, 45)
            val nextPeriodDate = if (hasCycleAnchor) {
                runCatching { LocalDate.parse(settings.lastPeriodStartDate) }.getOrNull()?.let { lastStart ->
                    var nextStart = lastStart
                    while (!nextStart.isAfter(today)) nextStart = nextStart.plusDays(cycleLength.toLong())
                    nextStart
                }
            } else null
            daysUntilPeriod = nextPeriodDate?.let {
                java.time.temporal.ChronoUnit.DAYS.between(today, it).toInt().coerceAtLeast(0)
            } ?: 0

            val ovulationCycleDay = (cycleLength - settings.lutealPhaseDays).coerceAtLeast(settings.periodDuration + 2)
            daysUntilOvu = todayPrediction?.let { prediction ->
                if (prediction.dayOfCycle <= ovulationCycleDay) {
                    ovulationCycleDay - prediction.dayOfCycle
                } else {
                    cycleLength - prediction.dayOfCycle + ovulationCycleDay
                }
            } ?: 0
        }

        val allLogsList = explicitLogs ?: logsMap.value.values.toList()
        val currentLog = allLogsList.find { it.dateString == today.toString() } ?: logsMap.value[today.toString()]
        val safety = SafetyEngine.evaluate(currentLog)
        val baseline = if (com.cyclecare.bridge.RustBaselineConfig.ENABLED)
            com.cyclecare.bridge.RustBaseline.compute(settings, allLogsList)
        else PatternEngine.computeBaseline(settings, allLogsList)
        val detectedPatterns = PatternEngine.detectPatterns(settings, allLogsList)
        val attentionItem = AttentionEngine.evaluateTodayPriorityNonsafetyNativeFirst(todayPrediction, safety, settings, allLogsList)
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
        val admission=authPreferences.publicationAdmission
        if(admission!=null&&!admission.isTransitionAction()){admission.transitionMain{selectDate(date)};return}
        if(admission!=null)selectedDateIntent=date
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
        val token=try{repository.beginOperation()}catch(_:com.cyclecare.app.data.repository.OwnerTransitionException){return}
        launchIdentity {
            val update=repository.setPeriodAnchor(date,token)
            if(!repository.isCurrentContext(update.context))return@launchIdentity
            SoundFeedbackManager.playGentleRewardChime()
            _uiState.value=_uiState.value.copy(onboardingProfile=update.profile)
            updateCalculations(update.settings,_uiState.value.currentDate,_uiState.value.selectedDate)
        }
    }

    /**
     * 10-second micro check-in directly from the Home screen with appreciative reward feedback.
     */
    fun quickCheckIn(mood: String, energy: String? = null) {
        // Intent-time token; a stale mint leaves the action untouched.
        val token=try{repository.beginOperation()}catch(_:com.cyclecare.app.data.repository.OwnerTransitionException){return}
        launchIdentity {
            val todayStr = _uiState.value.currentDate.toString()
            val existing = repository.getLogForDate(todayStr) ?: CycleDayLog(dateString = todayStr)
            val updated = existing.copy(
                moodLevel = mood,
                energyLevel = energy ?: existing.energyLevel
            )
            try{repository.saveLog(updated,token)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:com.cyclecare.app.data.repository.OwnerTransitionException){return@launchIdentity}catch(_:com.cyclecare.app.data.local.rust.RustRepositoryPort.Failure){return@launchIdentity}
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
        // Intent-time token after the cheap interpretation/owner checks, before
        // pending state and UI are cleared; a stale mint leaves both intact.
        val token=try{repository.beginOperation()}catch(_:com.cyclecare.app.data.repository.OwnerTransitionException){return}
        pendingQuickLogOwner = null
        _uiState.value = _uiState.value.copy(currentInterpretation = null, isSomethingDifferentSheetOpen = false)
        launchIdentity {
            if (owner != authPreferences.effectiveUserId) return@launchIdentity
            val existing = repository.getLogForDate(date) ?: CycleDayLog(dateString = date)
            if (owner != authPreferences.effectiveUserId) return@launchIdentity
            try{repository.saveLog(com.cyclecare.app.domain.ai.CareConversationPolicy.merge(existing, interpretation),token)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:com.cyclecare.app.data.repository.OwnerTransitionException){return@launchIdentity}catch(_:com.cyclecare.app.data.local.rust.RustRepositoryPort.Failure){return@launchIdentity}
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

    @Volatile internal var beforeSaveRefreshForTests:(suspend ()->Unit)?=null
    @Volatile internal var lastSaveReadForTests:UiReadOperation?=null;private set
    @Volatile internal var afterSaveProjectionForTests:((CycleUiState,Map<String,CycleDayLog>)->Unit)?=null
    fun saveDayLog(log: CycleDayLog) {
        if(!log.hasRecordedObservations())return
        val token=try{repository.beginOperation()}catch(_:com.cyclecare.app.data.repository.OwnerTransitionException){return}
        launchIdentity {
            repository.saveDayLogBound(log,token)
            if(!repository.isCurrentContext(token.context))return@launchIdentity
            SoundFeedbackManager.playGentleRewardChime()
            val safety = SafetyEngine.evaluate(log)
            if(repository.currentRustPortForOnboarding()!=null){
                beforeSaveRefreshForTests?.invoke()
                lastSaveReadForTests=publishCurrentRustRows("save",{repository.isCurrentContext(token.context)}){rows->
                    _logsMap.value=rows.logs.associateBy{it.dateString}
                    _uiState.value=_uiState.value.copy(settings=rows.settings,onboardingProfile=rows.profile)
                    updateCalculationsAdmitted(rows.settings,_uiState.value.currentDate,_uiState.value.selectedDate,rows.logs)
                    afterSaveProjectionForTests?.invoke(_uiState.value,_logsMap.value)
                }
            }else _uiState.value = _uiState.value.copy(safetyEvaluation = safety)
            autoSyncSilently()
        }
    }

    fun deleteDayLog(dateString: String) {
        // Intent-time token; a stale mint leaves the action untouched.
        val token=try{repository.beginOperation()}catch(_:com.cyclecare.app.data.repository.OwnerTransitionException){return}
        launchIdentity {
            try{repository.deleteLog(dateString,token)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:com.cyclecare.app.data.repository.OwnerTransitionException){return@launchIdentity}catch(_:com.cyclecare.app.data.local.rust.RustRepositoryPort.Failure){return@launchIdentity}
            autoSyncSilently()
        }
    }

    /** Candidate editor is separate from aggregate CycleUiState. Its display is
     * the source-bound editing base, never a committed/current-domain assertion.
     * Existing production settings UI is not wired to this route yet. */
    internal enum class SettingsEditorStatus {
        CLOSED, EDITING, PREPARING, PREPARED, COMMITTING, SAVED, SAVED_REPLAY,
        CONFLICT, INVALID_REUSE, VALIDATION, STALE, STORAGE_UNCERTAIN, ROUTE_UNAVAILABLE, CANCELLED,
        PUBLICATION_FAILED
    }
    internal data class CandidateSettingsEditor(val displayed:CycleSettings?=null,val status:SettingsEditorStatus=SettingsEditorStatus.CLOSED)
    private val candidateEditorMutable=MutableStateFlow(CandidateSettingsEditor())
    internal val candidateSettingsEditor:StateFlow<CandidateSettingsEditor> = candidateEditorMutable.asStateFlow()
    private var candidateEditSession:CycleRepository.SettingsEditSession?=null
    private var candidatePreparedChange:CycleRepository.PreparedSettingsChange?=null
    private var candidateCommitAttempted=false
    private val candidateEditBusy=java.util.concurrent.atomic.AtomicBoolean(false)
    // Last known commit truth belongs to the exact prepared operation, not a
    // later retry attempt/route. Retain actual receipt, not only a status label.
    internal data class KnownSettingsCommit(val change:CycleRepository.PreparedSettingsChange,val outcome:com.cyclecare.app.data.local.rust.RustRoomBatch.Outcome.Committed)
    @Volatile internal var candidateKnownSettingsCommit:KnownSettingsCommit?=null
        private set

    // Execution truth survives failure to enqueue/paint. UI may report that paint
    // failed, but reopening may not discard a prepared uncertain transaction.
    @Volatile internal var candidateSettingsOutcome=SettingsEditorStatus.CLOSED
        private set
    @Volatile internal var candidateSettingsPublicationFailure:Throwable?=null
        private set
    internal var afterCandidateSettingsPrepareForTests:(suspend ()->Unit)?=null
    internal var afterCandidateSettingsCommitForTests:(suspend ()->Unit)?=null
    private fun editorHasUnresolvedPrepared():Boolean = candidatePreparedChange!=null&&candidateKnownSettingsCommit?.change!==candidatePreparedChange&&candidateSettingsOutcome !in setOf(SettingsEditorStatus.SAVED,SettingsEditorStatus.SAVED_REPLAY,SettingsEditorStatus.CONFLICT,SettingsEditorStatus.INVALID_REUSE)
    private val editorPublicationLock=Any()
    private var editorPublicationRunning=0
    @Volatile private var candidateEditorCleared=false
    override fun onCleared(){
        synchronized(editorPublicationLock){candidateEditorCleared=true}
        screenCommandMutable.value?.let{refreshEditorConsumer(it)}
        super.onCleared()
    }
    internal var afterEditorPublicationAcquiredForTests:(()->Unit)?=null
    private fun acquireCandidateEditor():Boolean = synchronized(editorPublicationLock){
        !candidateEditorCleared&&editorPublicationRunning==0&&candidateEditBusy.compareAndSet(false,true)
    }
    internal var candidateEditorPublicationTimeoutMillis=5000L
    internal enum class EditorPublicationState { NOT_STARTED, APPLIED, SKIPPED_STALE, SKIPPED_CLEARED, EXPIRED, FAILED, PENDING }
    /** An owned result survives a timeout of the waiting caller. PENDING is not
     * failure or abandonment: consult this same handle for its terminal result. */
    internal data class EditorCommandFacts(
        val session:CycleRepository.SettingsEditSession?,
        val prepared:CycleRepository.PreparedSettingsChange?,
        val attempt:SettingsEditorStatus,
        val known:KnownSettingsCommit?
    )
    private fun captureEditorFacts()=EditorCommandFacts(candidateEditSession,candidatePreparedChange,candidateSettingsOutcome,candidateKnownSettingsCommit?.takeIf{it.change===candidatePreparedChange})
    internal data class EditorPublicationSnapshot(val state:EditorPublicationState,val failure:Throwable?=null)
    internal class EditorPublication internal constructor(){
        private val mutableSnapshot=MutableStateFlow(EditorPublicationSnapshot(EditorPublicationState.PENDING))
        val snapshots:kotlinx.coroutines.flow.StateFlow<EditorPublicationSnapshot> = mutableSnapshot.asStateFlow()
        val state:EditorPublicationState get()=snapshots.value.state
        val failure:Throwable? get()=snapshots.value.failure
        @Volatile internal var facts:EditorCommandFacts?=null
        internal var afterTerminal:(()->Unit)?=null
        @Volatile internal var observerFailure:Throwable?=null
            private set
        internal fun finish(state:EditorPublicationState,error:Throwable?=null){
            val changed=synchronized(this){
                if(mutableSnapshot.value.state==EditorPublicationState.PENDING){mutableSnapshot.value=EditorPublicationSnapshot(state,error);true}else false
            }
            if(changed)try{afterTerminal?.invoke()}catch(error:Throwable){observerFailure=error}
        }
    }
    @Volatile internal var candidateLastEditorPublication:EditorPublication?=null
        private set
    internal var afterEditorMutationStartedForTests:(()->Unit)?=null
    private fun newEditorPublication():EditorPublication = EditorPublication().also{candidateLastEditorPublication=it}
    private suspend fun editorTransition(result:EditorPublication=newEditorPublication(),action:()->Boolean):EditorPublication{
        val admission=authPreferences.publicationAdmission
        if(admission==null){result.finish(EditorPublicationState.FAILED,IllegalStateException("candidate_editor_admission_required"));return result}
        val ticket=java.util.concurrent.atomic.AtomicInteger(0) // queued/acquired/expired/mutating
        fun expire(){
            while(true){val phase=ticket.get();if(phase>=2)return
                if(ticket.compareAndSet(phase,2)){result.finish(EditorPublicationState.EXPIRED);return}
            }
        }
        try{kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable){
            kotlinx.coroutines.withTimeout(candidateEditorPublicationTimeoutMillis){
                kotlinx.coroutines.suspendCancellableCoroutine<Unit>{continuation->
                    val future=admission.transitionMain{
                        val acquired=synchronized(editorPublicationLock){
                            if(ticket.compareAndSet(0,1)){editorPublicationRunning++;true}else false
                        }
                        if(acquired)try{
                            afterEditorPublicationAcquiredForTests?.invoke()
                            if(ticket.compareAndSet(1,3)){
                                try{
                                    afterEditorMutationStartedForTests?.invoke()
                                    // Closing the VM expires callbacks not yet in their
                                    // action. It does not interrupt an action already
                                    // executing; its owned result must still settle.
                                    if(candidateEditorCleared)result.finish(EditorPublicationState.SKIPPED_CLEARED)
                                    else {val applied=action();result.finish(if(applied)EditorPublicationState.APPLIED else EditorPublicationState.SKIPPED_STALE)}
                                }catch(t:Throwable){result.finish(EditorPublicationState.FAILED,t);throw t}
                            }
                        }finally{synchronized(editorPublicationLock){editorPublicationRunning--}}
                    }
                    continuation.invokeOnCancellation{expire();future.cancel(false)}
                    future.whenComplete{_,error->
                        if(error!=null&&ticket.get()!=2&&ticket.get()!=3)result.finish(EditorPublicationState.FAILED,error)
                        if(continuation.isActive){
                            if(error==null)continuation.resumeWith(Result.success(Unit))else continuation.resumeWith(Result.failure(error))
                        }
                    }
                }
            }
        }}catch(error:Exception){
            candidateSettingsPublicationFailure=error
            if(result.state==EditorPublicationState.PENDING&&ticket.get()!=3)result.finish(EditorPublicationState.FAILED,error)
        }finally{expire()}
        return result
    }
    private suspend fun paintEditorOutcome(session:CycleRepository.SettingsEditSession?,command:EditorPublication=newEditorPublication()):EditorPublication{
        command.facts=captureEditorFacts()
        return editorTransition(command){
            if(candidateEditSession===session){
                if(session==null||!repository.isSettingsSessionCurrent(session)){
                    candidateEditorMutable.value=CandidateSettingsEditor(null,SettingsEditorStatus.STALE)
                }else candidateEditorMutable.value=CandidateSettingsEditor(session.settings,candidateSettingsOutcome)
                candidateSettingsPublicationFailure=null
                true
            }else false
        }
    }
    private fun clearStaleCandidateEditor(){
        screenCommandMutable.value?.let{refreshEditorConsumer(it)}
        val session=candidateEditSession?:return
        if(!repository.isSettingsSessionCurrent(session)){
            // Keep private prepared authority for its exact outcome/retry record,
            // but never retain the old person's settings on the visible editor.
            // Route/display invalidity is NOT a settlement of a prepared write.
            // Preserve SAVED/uncertain/cancelled truth and exact retry capability.
            candidateEditorMutable.value=CandidateSettingsEditor(null,SettingsEditorStatus.STALE)
        }
    }
    internal suspend fun openCandidateSettingsEditor(onPublication:(EditorPublication)->Unit={}):EditorPublication{
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if(!acquireCandidateEditor())return EditorPublication().apply{finish(EditorPublicationState.NOT_STARTED)}
        val command=newEditorPublication()
        var publicationRequested=false
        try{
            onPublication(command)
            if(editorHasUnresolvedPrepared())return command.apply{finish(EditorPublicationState.NOT_STARTED)}
            val session=repository.settingsEditSession()
            publicationRequested=true
            val publication=editorTransition(command){
                if(repository.isSettingsSessionCurrent(session)){
                    candidateEditSession=session;candidatePreparedChange=null;candidateCommitAttempted=false
                    candidateSettingsOutcome=SettingsEditorStatus.EDITING
                    candidateEditorMutable.value=CandidateSettingsEditor(session.settings,SettingsEditorStatus.EDITING)
                    candidateSettingsPublicationFailure=null
                    command.facts=captureEditorFacts()
                    true
                }else {command.facts=EditorCommandFacts(session,null,SettingsEditorStatus.STALE,null);false}
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            return publication
        }catch(cancelled:kotlinx.coroutines.CancellationException){if(!publicationRequested)command.finish(EditorPublicationState.EXPIRED);throw cancelled}
        catch(error:Exception){candidateSettingsPublicationFailure=error;return command.apply{finish(EditorPublicationState.FAILED,error)}}
        finally{candidateEditBusy.set(false)}
    }
    internal suspend fun submitCandidateSettingsIntent(intent:com.cyclecare.app.data.local.rust.RustBoundRoomHost.SettingsIntent,onPublication:(EditorPublication)->Unit={}):EditorPublication{
        if(!acquireCandidateEditor())return EditorPublication().apply{finish(EditorPublicationState.NOT_STARTED)}
        val session=candidateEditSession
        if(session==null||candidatePreparedChange!=null){candidateEditBusy.set(false);return EditorPublication().apply{finish(EditorPublicationState.NOT_STARTED)}}
        val command=newEditorPublication()
        lateinit var publication:EditorPublication
        try{
            onPublication(command)
            candidateSettingsOutcome=SettingsEditorStatus.PREPARING
            try{
                val children=List(if(intent is com.cyclecare.app.data.local.rust.RustBoundRoomHost.SettingsIntent.DiscreetNotifications)1 else 2){java.util.UUID.randomUUID().toString()}
                // Complete ownership transfer even if caller cancellation races the
                // prepare return. Preparation only reads; it is not a durable save.
                candidatePreparedChange=kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable){repository.prepareSettingsChange(session,intent,java.util.UUID.randomUUID().toString(),children,System.currentTimeMillis())}
                candidateCommitAttempted=false
                candidateSettingsOutcome=SettingsEditorStatus.PREPARED
                afterCandidateSettingsPrepareForTests?.invoke()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                completeCandidateSettingsCommit(session,candidatePreparedChange!!)
            }catch(cancelled:kotlinx.coroutines.CancellationException){
                if(candidateSettingsOutcome==SettingsEditorStatus.PREPARING||candidateSettingsOutcome==SettingsEditorStatus.PREPARED)candidateSettingsOutcome=SettingsEditorStatus.CANCELLED
                throw cancelled
            }catch(error:Exception){classifySettingsFailure(error)}
        }finally{publication=paintEditorOutcome(session,command);candidateEditBusy.set(false)}
        return publication
    }
    private fun classifySettingsFailure(error:Exception){
        candidateSettingsOutcome=when(error){
            is com.cyclecare.app.data.repository.OwnerTransitionException->if(candidatePreparedChange!=null)SettingsEditorStatus.ROUTE_UNAVAILABLE else SettingsEditorStatus.STALE
            is com.cyclecare.app.data.local.rust.RustRepositoryPort.Failure->when(val result=error.result){
                is com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Stale->if(candidatePreparedChange!=null)SettingsEditorStatus.ROUTE_UNAVAILABLE else SettingsEditorStatus.STALE
                is com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Rejected->if(result.reason.startsWith("invalid_"))SettingsEditorStatus.VALIDATION else SettingsEditorStatus.CONFLICT
                else->SettingsEditorStatus.STORAGE_UNCERTAIN
            }
            else->SettingsEditorStatus.STORAGE_UNCERTAIN
        }
    }
    private suspend fun completeCandidateSettingsCommit(session:CycleRepository.SettingsEditSession,prepared:CycleRepository.PreparedSettingsChange){
        candidateCommitAttempted=true
        candidateSettingsOutcome=SettingsEditorStatus.COMMITTING
        // Do not let prompt cancellation discard a returned committed receipt.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable){
            val outcome=repository.commitSettingsChange(prepared)
            if(outcome is com.cyclecare.app.data.local.rust.RustRoomBatch.Outcome.Committed)candidateKnownSettingsCommit=KnownSettingsCommit(prepared,outcome)
            candidateSettingsOutcome=when(outcome){
                is com.cyclecare.app.data.local.rust.RustRoomBatch.Outcome.Committed->if(outcome.replay)SettingsEditorStatus.SAVED_REPLAY else SettingsEditorStatus.SAVED
                is com.cyclecare.app.data.local.rust.RustRoomBatch.Outcome.Conflict->SettingsEditorStatus.CONFLICT
                is com.cyclecare.app.data.local.rust.RustRoomBatch.Outcome.InvalidReuse->SettingsEditorStatus.INVALID_REUSE
            }
        }
        afterCandidateSettingsCommitForTests?.invoke()
    }
    /** Explicit abandonment is safe only before the FIRST commit attempt. No
     * route/timeout/cancellation inference may authorize dropping attempted work. */
    internal suspend fun abandonUnattemptedSettingsEdit(onPublication:(EditorPublication)->Unit={}):EditorPublication {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if(!acquireCandidateEditor())return EditorPublication().apply{finish(EditorPublicationState.NOT_STARTED)}
        val command=newEditorPublication()
        var publicationRequested=false
        try{
            onPublication(command)
            if(candidatePreparedChange==null||candidateCommitAttempted)return command.apply{finish(EditorPublicationState.NOT_STARTED)}
            publicationRequested=true
            val publication=editorTransition(command){
                candidatePreparedChange=null;candidateEditSession=null
                candidateSettingsOutcome=SettingsEditorStatus.CLOSED
                candidateEditorMutable.value=CandidateSettingsEditor()
                candidateSettingsPublicationFailure=null
                command.facts=captureEditorFacts()
                true
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            return publication
        }catch(cancelled:kotlinx.coroutines.CancellationException){if(!publicationRequested)command.finish(EditorPublicationState.EXPIRED);throw cancelled}
        catch(error:Exception){candidateSettingsPublicationFailure=error;return command.apply{finish(EditorPublicationState.FAILED,error)}}
        finally{candidateEditBusy.set(false)}
    }
    internal suspend fun retryCandidateSettingsCommit(onPublication:(EditorPublication)->Unit={}):EditorPublication{
        if(!acquireCandidateEditor())return EditorPublication().apply{finish(EditorPublicationState.NOT_STARTED)}
        val session=candidateEditSession
        val prepared=candidatePreparedChange
        if(session==null||prepared==null){candidateEditBusy.set(false);return EditorPublication().apply{finish(EditorPublicationState.NOT_STARTED)}}
        val command=newEditorPublication()
        lateinit var publication:EditorPublication
        try{
            onPublication(command)
            try{completeCandidateSettingsCommit(session,prepared)}
            catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
            catch(error:Exception){classifySettingsFailure(error)}
        }finally{publication=paintEditorOutcome(session,command);candidateEditBusy.set(false)}
        return publication
    }

    internal enum class EditorScreenAction { OPEN, RETRY, ABANDON }
    internal data class EditorConsumerSnapshot(
        val owner:String,val session:Long,val origin:CycleSettings?=null,
        val attempt:SettingsEditorStatus=SettingsEditorStatus.CLOSED,
        val knownSave:Boolean=false,val publication:EditorPublicationState?=null,
        val identityCurrent:Boolean=false,val editable:Boolean=false
    )
    internal class EditorScreenCommand internal constructor(val owner:String,val session:Long){
        internal val mutable=MutableStateFlow<EditorPublication?>(null)
        val publication:StateFlow<EditorPublication?> = mutable.asStateFlow()
        internal val consumerMutable=MutableStateFlow(EditorConsumerSnapshot(owner,session))
        val consumer:StateFlow<EditorConsumerSnapshot> = consumerMutable.asStateFlow()
        val attempt:SettingsEditorStatus get()=consumer.value.attempt
        val knownSave:Boolean get()=consumer.value.knownSave
    }
    internal fun refreshEditorConsumer(command:EditorScreenCommand){
        val valid=!candidateEditorCleared&&authPreferences.effectiveUserId==command.owner&&authPreferences.sessionGeneration==command.session
        val handle=command.publication.value
        val facts=handle?.facts
        val session=facts?.session
        val current=valid&&session!=null&&repository.isSettingsSessionCurrent(session)
        val publication=handle?.state
        val attempt=if(valid)facts?.attempt?:SettingsEditorStatus.CLOSED else SettingsEditorStatus.STALE
        val origin=if(current)session!!.settings else null
        // Historical exact-command receipt survives route replacement, but never
        // crosses a person/session boundary. It is not a current-row projection.
        val known=valid&&facts?.known!=null&&facts.known.change===facts.prepared
        val active=screenCommandMutable.value===command
        command.consumerMutable.value=EditorConsumerSnapshot(command.owner,command.session,origin,attempt,known,publication,valid,
            active&&current&&origin!=null&&attempt==SettingsEditorStatus.EDITING&&publication==EditorPublicationState.APPLIED)
    }

    private fun bindEditorConsumer(command:EditorScreenCommand,publication:EditorPublication){
        publication.afterTerminal={refreshEditorConsumer(command)}
        command.mutable.value=publication
        refreshEditorConsumer(command)
    }
    private val screenCommandMutable=MutableStateFlow<EditorScreenCommand?>(null)
    internal val candidateScreenCommand:StateFlow<EditorScreenCommand?> = screenCommandMutable.asStateFlow()
    private val screenNoticeMutable=MutableStateFlow<String?>(null)
    internal val candidateScreenNotice:StateFlow<String?> = screenNoticeMutable.asStateFlow()
    internal fun startCandidateScreenCommand(action:EditorScreenAction?=null,intent:com.cyclecare.app.data.local.rust.RustBoundRoomHost.SettingsIntent?=null):EditorScreenCommand{
        if(candidateEditorCleared||viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.isActive!=true){
            return EditorScreenCommand(authPreferences.effectiveUserId,authPreferences.sessionGeneration).also{command->bindEditorConsumer(command,EditorPublication().apply{finish(EditorPublicationState.SKIPPED_CLEARED)})}
        }
        val previous=screenCommandMutable.value
        if(previous!=null&&(previous.publication.value==null||previous.publication.value!!.state==EditorPublicationState.PENDING)){
            screenNoticeMutable.value="Another command is still running. This tap did not start a new change."
            return previous
        }
        screenNoticeMutable.value=null
        val command=EditorScreenCommand(authPreferences.effectiveUserId,authPreferences.sessionGeneration);screenCommandMutable.value=command
        val job=viewModelScope.launch{
            try{
                val bind:(EditorPublication)->Unit={bindEditorConsumer(command,it)}
                val publication=if(intent!=null)submitCandidateSettingsIntent(intent,bind) else when(action){
                    EditorScreenAction.OPEN->openCandidateSettingsEditor(bind)
                    EditorScreenAction.RETRY->retryCandidateSettingsCommit(bind)
                    EditorScreenAction.ABANDON->abandonUnattemptedSettingsEdit(bind)
                    null->EditorPublication().apply{finish(EditorPublicationState.NOT_STARTED)}
                }
                bindEditorConsumer(command,publication)
            }catch(cancelled:kotlinx.coroutines.CancellationException){
                if(command.mutable.value==null)bindEditorConsumer(command,EditorPublication().apply{finish(EditorPublicationState.FAILED,cancelled)})
                refreshEditorConsumer(command)
                throw cancelled
            }catch(error:Exception){if(command.mutable.value==null)bindEditorConsumer(command,EditorPublication().apply{finish(EditorPublicationState.FAILED,error)})
                refreshEditorConsumer(command)}
        }
        job.invokeOnCompletion{error->
            if(command.mutable.value==null)bindEditorConsumer(command,EditorPublication().apply{finish(if(candidateEditorCleared)EditorPublicationState.SKIPPED_CLEARED else EditorPublicationState.FAILED,error)})
            refreshEditorConsumer(command)
        }
        return command
    }

    fun updateSettings(newSettings: CycleSettings) {
        val owner = captureConflictOwnerSnapshot()
        // Intent-time token; during an owner transition nothing is saved (no message: the
        // settings screen has no failure surface, so the change is simply not applied).
        val token = try { repository.beginOperation() } catch (_: com.cyclecare.app.data.repository.OwnerTransitionException) { return }
        if (token.session != owner.session || !isCurrentConflictSnapshot(owner)) return
        val previousSettings = _uiState.value.settings
        launchIdentity {
            // Profile read and both writes use the token's database, owner and consent generation.
            val update = try { repository.updateSettingsBound(newSettings, previousSettings.averageCycleLength, token) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { return@launchIdentity }
            // The write stays with its own owner; only the current owner's UI is updated.
            if (!isCurrentConflictSnapshot(owner) || !repository.isCurrentContext(update.context)) return@launchIdentity
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
        launchIdentity {
            val token = com.cyclecare.app.data.supabase.SupabaseSessionManager.validAccessToken(authPreferences)
            if (authPreferences.effectiveUserId != owner || authPreferences.sessionGeneration != session) return@launchIdentity
            val deleted = com.cyclecare.app.domain.ai.CarePrivacyService.deleteCloudHealthCopy(token)
            if (authPreferences.effectiveUserId != owner || authPreferences.sessionGeneration != session) return@launchIdentity
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
            launchIdentity {
                val token = authPreferences.accessToken ?: return@launchIdentity
                val manifest = (processorConsentService.fetchManifest(token) as? com.cyclecare.app.domain.ai.ConsentResult.Success)?.manifest ?: return@launchIdentity
                if (owner == authPreferences.effectiveUserId) processorConsentService.setConsent(token, manifest, provider, false)
            }
            return
        }
        // Enabling is explicit but never optimistic: require a fresh eligible manifest and server receipt.
        launchIdentity {
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
                return@launchIdentity
            }
            val result = if (manifest != null) processorConsentService.setConsent(token!!, manifest, provider, true) else manifestResult
            if (owner != authPreferences.effectiveUserId) return@launchIdentity
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
        launchIdentity {
            val result = processorConsentService.fetchManifest(token)
            if (owner != authPreferences.effectiveUserId) return@launchIdentity
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
        println("MERGE_DIAG confirm pending="+(pendingCareProposal!=null)); val pending = pendingCareProposal ?: return
        // Intent-time token BEFORE the consent gate consumes its pending: a
        // stale mint leaves pendingCareProposal AND the gate's pending intact,
        // so the user can retry the identical confirmation.
        val token=try{repository.beginOperation()}catch(e:com.cyclecare.app.data.repository.OwnerTransitionException){println("MERGE_DIAG mint refused "+e.javaClass.simpleName);return}
        val confirmed = careLogConsentGate.confirm(pending.id, authPreferences.effectiveUserId) ?: run{println("MERGE_DIAG gate refused");return};println("MERGE_DIAG gate consumed")
        pendingCareProposal = null
        _uiState.value = _uiState.value.copy(pendingCareLog = null, pendingCareTurnId = null)
        launchIdentity {
            println("MERGE_DIAG launch entered ownerCurrent="+(confirmed.ownerId==authPreferences.effectiveUserId))
            if (confirmed.ownerId != authPreferences.effectiveUserId) return@launchIdentity
            val existing = repository.getLogForDate(confirmed.date) ?: CycleDayLog(dateString = confirmed.date)
            println("MERGE_DIAG read done ownerCurrent="+(confirmed.ownerId==authPreferences.effectiveUserId))
            if (confirmed.ownerId != authPreferences.effectiveUserId) return@launchIdentity
            try{repository.saveLog(com.cyclecare.app.domain.ai.CareConversationPolicy.merge(existing, confirmed.interpretation),token)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:com.cyclecare.app.data.repository.OwnerTransitionException){println("MERGE_DIAG save owner refusal "+e.javaClass.simpleName);return@launchIdentity}catch(e:com.cyclecare.app.data.local.rust.RustRepositoryPort.Failure){println("MERGE_DIAG save port failure "+e.toString());return@launchIdentity}
            println("MERGE_DIAG save returned")
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
        careJob = launchIdentity {
            try {
                val consentReady = remoteAllowed && com.cyclecare.app.domain.ai.CarePrivacyService.updateConsents(
                    authPreferences.accessToken, authPreferences.isAiSharingAllowed, authPreferences.isCloudSyncAllowed, false
                )
                if (!isCurrentCareRequest()) return@launchIdentity
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
                if (!isCurrentCareRequest()) return@launchIdentity
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
        imageJob = launchIdentity {
            try {
                val consentReady = com.cyclecare.app.domain.ai.CarePrivacyService.updateConsents(
                    accessToken, authPreferences.isAiSharingAllowed, authPreferences.isCloudSyncAllowed, true
                )
                if (!consentReady) throw CareImageGenerationException("Image creation isn't ready. Your privacy choice could not be verified.")
                if (owner != authPreferences.effectiveUserId) return@launchIdentity
                val bytes = CareImageGenerationService.generate(style, accessToken, customPrompt)
                if (owner != authPreferences.effectiveUserId) return@launchIdentity
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
        launchIdentity {
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
        launchIdentity {
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
        launchIdentity {
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
        launchIdentity {
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
        launchIdentity {
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
        launchIdentity {
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
        launchIdentity {
            when (val pollResult = paymentUseCase.pollPaymentStatus(extRef, timeoutSeconds, _uiState.value.economyProfile)) {
                is PaymentPollResult.Success -> {
                    // A poll that outlives its identity must never publish the
                    // old owner's wallet onto the new owner's card.
                    if (owner != authPreferences.effectiveUserId || session != authPreferences.sessionGeneration) return@launchIdentity
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
        launchIdentity {
            when (val pollResult = paymentUseCase.pollPaymentStatus(extRef, timeoutSeconds, _uiState.value.economyProfile)) {
                is PaymentPollResult.Success -> {
                    if (owner != authPreferences.effectiveUserId || session != authPreferences.sessionGeneration) return@launchIdentity
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
        launchIdentity {
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
        launchIdentity {
            // Same preservation gate as cold start: never upload with pending
            // conflicts or an unreadable local state.
            val conflictsPending = runCatching { repository.pendingSyncConflicts().isNotEmpty() }.getOrDefault(true)
            if (conflictsPending) {
                _uiState.value = _uiState.value.copy(
                    syncGatedForConflicts = true,
                    lastSyncSuccess = false,
                    lastSyncMessage = "Review the pending sync choices in Settings first. Nothing was uploaded."
                )
                return@launchIdentity
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
        walletRefreshJob = launchIdentity {
            val result = economyService.fetchVerifiedProfile(owner)
            if (owner != authPreferences.effectiveUserId || session != authPreferences.sessionGeneration) return@launchIdentity
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
        return launchIdentity {
            kotlinx.coroutines.delay(delayMs)
            if (ownerAtSchedule != authPreferences.effectiveUserId) return@launchIdentity
            val eligible = !authPreferences.isLocalOnlyMode && authPreferences.userId != null
            val online = com.cyclecare.app.util.NetworkHelper.isOnline(authPreferences.context)
            when (val decision = if (com.cyclecare.bridge.RustReconnectPolicyConfig.ENABLED)
                com.cyclecare.bridge.RustReconnectPolicy.decideTrailing(
                    reconnectWorkState, online, eligible,
                    authPreferences.effectiveUserId, System.currentTimeMillis()
                )
            else com.cyclecare.app.domain.sync.ReconnectWorkPolicy.decideTrailing(
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
            launchIdentity {
                val stable = readStartScope(supersede = false) ?: return@launchIdentity
                if (!isStartScopeCurrent(stable)) return@launchIdentity
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
        accountRefreshJob = launchIdentity {
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
            if (owner != authPreferences.effectiveUserId || session != authPreferences.sessionGeneration) return@launchIdentity
            if (!repository.isCurrentContext(syncContext) || (scope != null && !isStartScopeCurrent(scope))) return@launchIdentity
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
            if (destination != com.cyclecare.app.domain.sync.AccountStartDestination.HOME) return@launchIdentity
            if (localState == com.cyclecare.app.domain.sync.LocalDataState.CONFLICTED) return@launchIdentity
            val result = syncUseCase.executeSync()
            if (owner != authPreferences.effectiveUserId || session != authPreferences.sessionGeneration) return@launchIdentity
            val restoredSettings = repository.getSettings()
            val restoredProfile = repository.getOnboardingProfile()
            val isAdmin = runCatching { authService.isCycleCareAdmin() }.getOrDefault(false)
            if (owner != authPreferences.effectiveUserId || session != authPreferences.sessionGeneration) return@launchIdentity
            if (!repository.isCurrentContext(syncContext) || (scope != null && !isStartScopeCurrent(scope))) return@launchIdentity
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
        val admission=authPreferences.publicationAdmission
        if(admission!=null&&!admission.isTransitionAction()){admission.transitionMain{onAuthCompleted(isAnonymous)};return}
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
        val admission=authPreferences.publicationAdmission
        if(admission!=null&&!admission.isTransitionAction()){admission.transitionMain{startLocalOnly()};return}
        authPreferences.clearSession()
        authPreferences.isLocalOnlyMode = true
        authPreferences.hasCompletedInitialAuth = true
        authPreferences.isCloudSyncAllowed = false
        authPreferences.isAiSharingAllowed = false
        resetIdentityUiIfNeeded()
        launchIdentity {
            val scope = readStartScope() ?: return@launchIdentity
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
        val admission=authPreferences.publicationAdmission
        if(admission!=null&&!admission.isTransitionAction()){admission.transitionMain{completeInitialAuth(isAnonymous)};return}
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
        launchIdentity {
            when (val result = authService.completeEmailLink(uri)) {
                is com.cyclecare.app.data.supabase.AuthResult.Success -> {
                    authPreferences.hasCompletedInitialAuth = true
                    resetIdentityUiIfNeeded()
                    val scope = readStartScope() ?: return@launchIdentity
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
        launchIdentity {
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
        launchIdentity {
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
        launchIdentity {
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
        launchIdentity {
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
        launchIdentity {
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
        launchIdentity {
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
        launchIdentity {
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
        launchIdentity {
            val res = miningUseCase.reviveStreak(_uiState.value.economyProfile)
            if (res is com.cyclecare.app.data.economy.EconomyResult.Error) {
                _uiState.value = _uiState.value.copy(economyToastMessage = res.errorMessage)
            }
        }
    }

    fun refreshNotifications() {
        if (authPreferences.isLocalOnlyMode || authPreferences.userId == null) return
        launchIdentity {
            val userIdentifier = authPreferences.effectiveUserId
            val session = authPreferences.sessionGeneration
            val list = notificationService.fetchNotifications(userIdentifier)

            val verified = economyService.fetchVerifiedProfile(userIdentifier)
            if (userIdentifier != authPreferences.effectiveUserId || session != authPreferences.sessionGeneration) return@launchIdentity
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
        launchIdentity {
            notificationService.markAsRead(id)
            val updated = _uiState.value.notifications.map {
                if (it.id == id) it.copy(isRead = true) else it
            }
            _uiState.value = _uiState.value.copy(notifications = updated)
        }
    }

    fun markAllNotificationsAsRead() {
        launchIdentity {
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

    /** Admission before prefs monitor, preserving intent-time identity/lifecycle.
     * Preference choice is separate from the later Room completion transaction.
     */
    internal enum class OnboardingPortState{NONE,CHOICE_UNCERTAIN,UNPUBLISHED,PUBLISHED,STALE}
    private class OnboardingPortAttempt(val port:com.cyclecare.app.data.local.rust.RustRepositoryPort,val generation:Long,val operationId:Long)
    @Volatile internal var onboardingPortState=OnboardingPortState.NONE
        private set
    private class OnboardingChoiceAttempt(val original:com.cyclecare.app.data.local.rust.RustRepositoryPort,val generation:Long,val operationId:Long,val enabled:Boolean)
    @Volatile private var uncertainOnboardingChoice:OnboardingChoiceAttempt?=null
    @Volatile private var unpublishedOnboardingAttempt:OnboardingPortAttempt?=null
    private var onboardingAttemptGeneration=0L
    private class OnboardingRepair(val choice:OnboardingChoiceAttempt,val status:OnboardingStatus,val id:String=java.util.UUID.randomUUID().toString())
    private val onboardingRepairInFlight=java.util.concurrent.atomic.AtomicReference<OnboardingRepair?>(null)
    /** Compare the exact attempt INSIDE admission. Late completion can neither
     * clear nor publish a newer own-choice capability. Install failure retains
     * the exact capability for explicit recovery, including partial install.
     */
    private fun installOnboardingAttempt(attempt:OnboardingPortAttempt,stillCurrent:()->Boolean):java.util.concurrent.CompletableFuture<Unit>{
        val admission=authPreferences.publicationAdmission?:error("admission_required")
        return admission.transitionMain{
            check(unpublishedOnboardingAttempt===attempt){"superseded_onboarding_attempt"}
            if(!attempt.port.isCurrent()){onboardingPortState=OnboardingPortState.STALE;error("stale_onboarding_attempt")}
            installRustPortAdmitted(attempt.port,stillCurrent)
            check(unpublishedOnboardingAttempt===attempt)
            unpublishedOnboardingAttempt=null;onboardingPortState=OnboardingPortState.PUBLISHED
        }
    }
    internal fun recoverUnpublishedOnboardingPort():java.util.concurrent.CompletableFuture<Unit>{
        val attempt=unpublishedOnboardingAttempt?:return java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("no_unpublished_port"))
        return installOnboardingAttempt(attempt,{attempt.port.isCurrent()&&onboardingOperationId==attempt.operationId})
    }
    /** Explicit exact-host recovery. It cannot adopt a current preference
     * snapshot or reconstruct an attempt after restart. Failed retries retain
     * the capability, while successful persistence transfers it to install.
     */
    internal fun recoverUncertainOnboardingChoice():java.util.concurrent.CompletableFuture<Unit>{
        val choice=uncertainOnboardingChoice?:return java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("no_uncertain_choice"))
        val admission=authPreferences.publicationAdmission?:return java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("admission_required"))
        return admission.exclusiveIo{
            check(uncertainOnboardingChoice===choice){"superseded_choice_attempt"}
            check(onboardingOperationId==choice.operationId){"superseded_choice_operation"}
            val recovered=choice.original.host.recoverUncertainChoice(choice.original.token)
            val next=when(recovered){
                is com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Value->recovered.value
                com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Stale->{
                    val abandoned=choice.original.host.abandonUnchangedChoice(choice.original.token)
                    if(abandoned is com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Value&&abandoned.value){
                        check(uncertainOnboardingChoice===choice)
                        uncertainOnboardingChoice=null;onboardingPortState=OnboardingPortState.NONE
                    }
                    error("uncertain_choice_not_recovered")
                }
                else->error("uncertain_choice_not_recovered")
            }
            val attempt=OnboardingPortAttempt(com.cyclecare.app.data.local.rust.RustRepositoryPort(choice.original.host,next),choice.generation,choice.operationId)
            check(uncertainOnboardingChoice===choice)
            unpublishedOnboardingAttempt=attempt;uncertainOnboardingChoice=null;onboardingPortState=OnboardingPortState.UNPUBLISHED
            attempt
        }.thenCompose{attempt->installOnboardingAttempt(attempt,{attempt.port.isCurrent()&&onboardingOperationId==attempt.operationId})}
    }
    /** Repair only the saved privacy choice. Does not submit answers, retain
     * their old values, or retain PIN. A new Finish is a separate user action.
     * UI rendering of this affordance remains uninstalled candidate work.
     */
    fun repairOnboardingChoice():java.util.concurrent.CompletableFuture<Unit>{
        val choice=uncertainOnboardingChoice?:return java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("no_uncertain_choice"))
        val status=onboardingStatus?:return java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("no_onboarding_status"))
        val gate=authPreferences.publicationAdmission?:return java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("admission_required"))
        val repair=OnboardingRepair(choice,status)
        if(!onboardingRepairInFlight.compareAndSet(null,repair))return java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("choice_repair_inflight"))
        val owned=gate.transitionMain{
            check(onboardingRepairInFlight.get()===repair&&uncertainOnboardingChoice===choice&&onboardingOperationId==choice.operationId&&onboardingStatus===status){"superseded_choice_repair"}
            val op=authPreferences.rustOperationSnapshot().operation
            check(op.owner==status.owner&&op.session==status.session&&com.cyclecare.app.data.local.OwnerDataGate.snapshot(authPreferences.context,status.owner)==status.lifecycle){"stale_choice_repair"}
            onboardingStatus=status.copy(running=true,message="Repairing your privacy choice. Your answers are not being submitted.")
            com.cyclecare.app.security.PublicationOrigin.withOrigin({true}){_uiState.value=_uiState.value}
        }.thenCompose{recoverUncertainOnboardingChoice()}.handle{_,error->
            gate.transitionMain{
                if(onboardingRepairInFlight.get()===repair&&onboardingOperationId==choice.operationId&&onboardingStatus?.operationId==choice.operationId){
                    val message=when(onboardingPortState){
                        OnboardingPortState.NONE,OnboardingPortState.PUBLISHED->"Review your answers, then tap Finish to submit them."
                        OnboardingPortState.UNPUBLISHED->"Your privacy choice was saved, but setup could not finish. Your answers have not been submitted."
                        OnboardingPortState.STALE->"Setup changed before repair finished. Your answers have not been submitted."
                        else->"Your privacy choice could not be repaired. Your answers have not been submitted."
                    }
                    // Ready-to-submit must not paint before reservation release:
                    // a tap on that paint must be able to start a new submission.
                    check(onboardingRepairInFlight.compareAndSet(repair,null)){"superseded_repair_completion"}
                    onboardingStatus=onboardingStatus?.copy(running=false,message=message)
                    // Normalization keeps the status private to its owner/lifecycle.
                    com.cyclecare.app.security.PublicationOrigin.withOrigin({true}){_uiState.value=_uiState.value}
                }
                if(error!=null)throw java.util.concurrent.CompletionException(error)
            }
        }.thenCompose{it}
        // Caller cancellation is cancellation of its observation, not ownership
        // of already admitted local effects. Keep reservation until owned work
        // settles; a canceled view cannot start a concurrent repair.
        val observation=java.util.concurrent.CompletableFuture<Unit>()
        owned.whenComplete{_,error->
            onboardingRepairInFlight.compareAndSet(repair,null)
            if(error==null)observation.complete(Unit)else observation.completeExceptionally(error)
        }
        return observation
    }
    internal data class OnboardingPreferencesApplied(val first:String,val second:Long,val identity:com.cyclecare.app.security.AuthPreferences.RustOperationSnapshot,val port:com.cyclecare.app.data.local.rust.RustRepositoryPort?=null)
    internal suspend fun applyOnboardingPreferences(
        identity:com.cyclecare.app.security.AuthPreferences.RustOperationSnapshot,
        lifecycle:com.cyclecare.app.data.local.OwnerDataGate.Snapshot,
        owner:String?,session:Long,isCloudSyncAllowed:Boolean,
        dynamicAnswers:Map<String,Set<String>>,pin:String,enableLock:Boolean,operationId:Long=0L
    ):OnboardingPreferencesApplied? {
        val job=kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            var result:OnboardingPreferencesApplied?=null
            val admitted=authPreferences.mutatePublicationIdentityIfCurrent(identity,{
                job?.isActive!=false&&com.cyclecare.app.data.local.OwnerDataGate.snapshot(authPreferences.context,identity.operation.owner)==lifecycle
            }){
                val choices={authPreferences.runIfIdentityUnchanged(owner, session) {
                        if(repository.currentRustPortForOnboarding()==null)authPreferences.setCloudSyncChoiceWithoutCallouts(isCloudSyncAllowed,authPreferences.publicationAdmission!=null)
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
                        OnboardingPreferencesApplied(authPreferences.accountMode,authPreferences.operationSnapshot().consentGeneration,authPreferences.rustOperationSnapshot())
                    }
                }
                check(uncertainOnboardingChoice==null){"unrecovered_onboarding_choice"}
                val priorPort=repository.currentRustPortForOnboarding()
                if(priorPort==null)result=choices()
                else {
                    check(priorPort.token.preference==identity&&priorPort.token.lifecycle==lifecycle)
                    val nextAttemptGeneration=Math.addExact(onboardingAttemptGeneration,1L)
                    check(uncertainOnboardingChoice==null){"unrecovered_onboarding_choice"}
                    val choiceAttempt=OnboardingChoiceAttempt(priorPort,nextAttemptGeneration,operationId,isCloudSyncAllowed)
                    // Reserve exact original intent before any potentially writing host
                    // call. Cancellation/throw cannot leave only an unrouteable host state.
                    onboardingAttemptGeneration=nextAttemptGeneration
                    uncertainOnboardingChoice=choiceAttempt;onboardingPortState=OnboardingPortState.CHOICE_UNCERTAIN
                    when(val transitioned=priorPort.host.onboardingChoice(priorPort.token,isCloudSyncAllowed)){
                        is com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Value->{
                            val newToken=transitioned.value
                            val port=com.cyclecare.app.data.local.rust.RustRepositoryPort(priorPort.host,newToken)
                            onboardingAttemptGeneration=nextAttemptGeneration
                            unpublishedOnboardingAttempt=OnboardingPortAttempt(port,onboardingAttemptGeneration,operationId)
                            check(uncertainOnboardingChoice===choiceAttempt)
                            uncertainOnboardingChoice=null;onboardingPortState=OnboardingPortState.UNPUBLISHED
                            result=choices()?.copy(port=port)
                        }
                        com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Storage->{
                            onboardingAttemptGeneration=nextAttemptGeneration
                            uncertainOnboardingChoice=choiceAttempt;onboardingPortState=OnboardingPortState.CHOICE_UNCERTAIN
                            error("onboarding_port_choice_uncertain")
                        }
                        else->error("onboarding_port_transition_rejected")
                    }
                }
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if(admitted)result else null
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
        // Freeze mutable non-secret caller collections before admission.
        val submittedGoals=goals.toList();val submittedSymptoms=typicalSymptoms.toList()
        val submittedAnswers=dynamicAnswers.mapValues{it.value.toSet()}
        // A second submission is not approval to replace an unresolved choice.
        // Recovery remains a separate internal action until an owner-bound UI
        // repair flow exists. In particular do not advance its operationId.
        if (uncertainOnboardingChoice!=null||unpublishedOnboardingAttempt!=null||onboardingRepairInFlight.get()!=null) return
        if (_uiState.value.isCompletingOnboarding) return
        val onboardingOwner = captureConflictOwnerSnapshot()
        // Intent-time token: database, owner, session and that owner's consent generation,
        // captured when the user taps Finish. Everything below uses it, never a later lookup.
        val token = try { repository.beginOperation() } catch (_: com.cyclecare.app.data.repository.OwnerTransitionException) {
            reportOnboardingPreflightFailure()
            return
        }
        // The UI snapshot and the token are two captures; refuse unless they are the same
        // owner and session, so one tap can never pair owner A's intent with owner B's token.
        if (token.session != onboardingOwner.session || !isCurrentConflictSnapshot(onboardingOwner)) {
            reportOnboardingPreflightFailure()
            return
        }
        val onboardingIdentity=authPreferences.rustOperationSnapshot()
        val onboardingLifecycle=com.cyclecare.app.data.local.OwnerDataGate.snapshot(authPreferences.context,token.context.owner)
        if(onboardingIdentity.operation.owner!=token.context.owner||onboardingIdentity.operation.session!=token.session)return
        fun executeSubmission(operationId:Long){
        val entered=java.util.concurrent.atomic.AtomicBoolean(false)
        val reservedOrigin=com.cyclecare.app.security.PublicationOrigin.Context{
            authPreferences.publicationAdmission==null||(authPreferences.rustOperationSnapshot()==onboardingIdentity&&com.cyclecare.app.data.local.OwnerDataGate.snapshot(authPreferences.context,token.context.owner)==onboardingLifecycle)
        }
        fun cleanupUnstartedSubmission(){
                val cleanup={
                    val status=onboardingStatus
                    if(onboardingOperationId==operationId&&status?.operationId==operationId&&status.running&&onboardingRepairInFlight.get()==null){
                        onboardingStatus=status.copy(running=false,message=ONBOARDING_RETRY_MESSAGE)
                        com.cyclecare.app.security.PublicationOrigin.withOrigin({true}){_uiState.value=_uiState.value}
                    }
                }
                val gate=authPreferences.publicationAdmission
                if(gate==null){
                    if(onboardingOperationId==operationId&&authPreferences.rustOperationSnapshot()==onboardingIdentity&&com.cyclecare.app.data.local.OwnerDataGate.snapshot(authPreferences.context,token.context.owner)==onboardingLifecycle)_uiState.value=_uiState.value.copy(isCompletingOnboarding=false,onboardingCompletionMessage=ONBOARDING_RETRY_MESSAGE)
                }else {
                    val finalization=gate.transitionMain{cleanup()};onboardingStatusFinalization=finalization
                    finalization.whenComplete{_,failure->if(failure!=null){onboardingStatusFinalizationFailure=failure;android.util.Log.e("CycleCareOnboarding","unstarted submission cleanup failed",failure)}}
                }

        }
        val submissionJob=try{
        beforeOnboardingSubmissionLaunchForTests?.invoke(this)
        viewModelScope.launch(reservedOrigin) submission@{
            entered.set(true)
            val onboardingJob=kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            var onboardingFailed=false
            var acceptedIdentity=onboardingIdentity
            val acceptedIdentityCurrent={authPreferences.rustOperationSnapshot()==acceptedIdentity&&com.cyclecare.app.data.local.OwnerDataGate.snapshot(authPreferences.context,token.context.owner)==onboardingLifecycle}
            val acceptedCurrent={onboardingJob?.isActive!=false&&acceptedIdentityCurrent()}
            try {
                fun failAttempt() {
                    onboardingFailed=true
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
                    applyOnboardingPreferences(onboardingIdentity,onboardingLifecycle,token.context.owner,token.session,isCloudSyncAllowed,submittedAnswers,pin,enableLock,operationId)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (_: Exception) { failAttempt(); return@submission }
                if (applied == null) { failAttempt(); return@submission }
                afterOnboardingChoiceForTests?.invoke()
                acceptedIdentity=applied.identity
                kotlinx.coroutines.withContext(com.cyclecare.app.security.PublicationOrigin.Context(acceptedCurrent)) {
                if(!acceptedCurrent())return@withContext
                val accountMode = applied.first
                val writeToken = if(applied.port==null)token.withConsentGeneration(applied.second) else {
                    beforeOnboardingInstallForTests?.invoke(onboardingJob)
                    val attempt=unpublishedOnboardingAttempt?:error("missing_onboarding_attempt")
                    check(attempt.port===applied.port)
                    try{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){installOnboardingAttempt(attempt,acceptedCurrent).get()}}
                    catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
                    catch(error:Exception){
                        onboardingFailed=true
                        if(acceptedIdentityCurrent())_uiState.value=_uiState.value.copy(onboardingCompletionMessage=ONBOARDING_RETRY_MESSAGE)
                        return@withContext
                    }
                    repository.beginOperation().also{next->
                        check(next.context.database===token.context.database&&next.context.owner==token.context.owner&&next.session==token.session&&next.consentGeneration==applied.second)
                    }
                }

                val profile = com.cyclecare.app.domain.onboarding.OnboardingCompletionMapping.profile(
                    com.cyclecare.app.domain.onboarding.OnboardingCompletionMapping.Input(accountMode,System.currentTimeMillis(),submittedGoals,lastPeriodDate,lastPeriodEndDate,periodStartChoice,cycleLengthOption,cycleLength,periodDuration,remindFertile,submittedSymptoms,typicalEnergy,typicalSleep,hasCompletedDeepBaseline))
                // The write uses the intent-time token (post-choice consent generation), never a later lookup.
                val completion = try {
                    repository.completeOnboardingWithProfile(profile = profile, pin = pin, enableLock = enableLock, token = writeToken)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (_: Exception) { failAttempt(); return@withContext }
                SoundFeedbackManager.playGentleRewardChime()
                // The write above is bound to the database/owner captured at its start.
                // Publish only if that owner, session and repository context are still
                // current; otherwise the completed record stays with its own owner and
                // the current owner's state is left to its own load.
                if (!isCurrentConflictSnapshot(onboardingOwner) || !repository.isCurrentContext(completion.context)) return@withContext
                val updated = completion.settings
                beforeCompletionPublicationForTests?.invoke(this@CycleCareViewModel)
                val publishCompletion={
                    if(acceptedCurrent()&&isCurrentConflictSnapshot(onboardingOwner)&&repository.isCurrentContext(completion.context)){
                        // A completed destination supersedes any suspended start decision.
                        localReadEpoch++
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
                        updateCalculationsAdmitted(updated, LocalDate.now(), _uiState.value.selectedDate, null)
                        afterCompletionPublicationForTests?.invoke(this@CycleCareViewModel)
                    }
                }
                val completionAdmission=authPreferences.publicationAdmission
                if(repository.currentRustPortForOnboarding()!=null){
                    val completionRead=publishCurrentRustRows("finish",{acceptedCurrent()&&isCurrentConflictSnapshot(onboardingOwner)&&repository.isCurrentContext(completion.context)}){rows->
                        localReadEpoch++
                        _logsMap.value=rows.logs.associateBy{it.dateString}
                        _uiState.value=_uiState.value.copy(settings=rows.settings,onboardingProfile=rows.profile,
                            showOnboarding=rows.profile==null||!rows.settings.isOnboardingCompleted,isAppLocked=false,
                            isCompletingOnboarding=false,onboardingCompletionMessage=null,
                            showQuickUnlockDialog=!authPreferences.isQuickUnlockAsked&&pin.isBlank())
                        updateCalculationsAdmitted(rows.settings,LocalDate.now(),_uiState.value.selectedDate,rows.logs)
                        afterCompletionPublicationForTests?.invoke(this@CycleCareViewModel)
                    }
                    if(completionRead.status!=UiReadStatus.PAINTED)return@withContext
                }else if(completionAdmission==null)publishCompletion()
                else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){completionAdmission.transitionMain{publishCompletion()}.get()}
                autoSyncSilently()
                }
            } finally {
                val ownAttempt=unpublishedOnboardingAttempt
                if(ownAttempt!=null&&ownAttempt.operationId==operationId&&ownAttempt.port.token.preference.operation.owner==onboardingIdentity.operation.owner&&ownAttempt.port.token.preference.operation.session==onboardingIdentity.operation.session&&ownAttempt.port.token.lifecycle==onboardingLifecycle){
                    acceptedIdentity=ownAttempt.port.token.preference
                }
                // Cleared on every exit (including cancellation) by the operation that set it,
                // and only if no newer save has started since.
                val finalizeStatus={
                if (operationId == onboardingOperationId) {
                    val status=onboardingStatus
                    if(status?.operationId==operationId&&status.running&&status.message==null&&onboardingRepairInFlight.get()==null){
                        // Operation-local status is independent of stale aggregate UI
                        // copies and consent-generation drift caused by its own choice.
                        onboardingStatus=status.copy(running=false,message=if(onboardingFailed||uncertainOnboardingChoice?.operationId==operationId)ONBOARDING_RETRY_MESSAGE else null)
                    }
                    val statusCurrent={authPreferences.rustOperationSnapshot().operation.let{it.owner==onboardingIdentity.operation.owner&&it.session==onboardingIdentity.operation.session}&&com.cyclecare.app.data.local.OwnerDataGate.snapshot(authPreferences.context,token.context.owner)==onboardingLifecycle&&onboardingOperationId==operationId}
                    com.cyclecare.app.security.PublicationOrigin.withOrigin(statusCurrent){
                        _uiState.value = _uiState.value.copy(isCompletingOnboarding = false)
                    }
                }
                }
                val statusAdmission=authPreferences.publicationAdmission
                if(statusAdmission==null)finalizeStatus()else {
                    val finalization=statusAdmission.transitionMain{finalizeStatus()}
                    onboardingStatusFinalization=finalization
                    finalization.whenComplete{_,error->if(error!=null){onboardingStatusFinalizationFailure=error;android.util.Log.e("CycleCareOnboarding","status finalization failed",error)}}
                }

            }
        }
        }catch(error:Exception){cleanupUnstartedSubmission();android.util.Log.e("CycleCareOnboarding","submission launch failed",error);return}
        submissionJob.invokeOnCompletion{_->if(!entered.get())cleanupUnstartedSubmission()}
    }
        val admission=authPreferences.publicationAdmission
        val reserve={
            if(authPreferences.rustOperationSnapshot()!=onboardingIdentity||!repository.isCurrentContext(token.context)||!isCurrentConflictSnapshot(onboardingOwner)||com.cyclecare.app.data.local.OwnerDataGate.snapshot(authPreferences.context,token.context.owner)!=onboardingLifecycle||uncertainOnboardingChoice!=null||unpublishedOnboardingAttempt!=null||onboardingRepairInFlight.get()!=null||onboardingStatus?.running==true)null
            else {
                val id=Math.addExact(onboardingOperationId,1L);onboardingOperationId=id
                onboardingStatus=OnboardingStatus(token.context.owner,token.session,onboardingLifecycle,id,true,null)
                com.cyclecare.app.security.PublicationOrigin.withOrigin({true}){_uiState.value=_uiState.value.copy(isCompletingOnboarding=true,onboardingCompletionMessage=null)}
                id
            }
        }
        if(admission==null){val id=++onboardingOperationId;_uiState.value=_uiState.value.copy(isCompletingOnboarding=true,onboardingCompletionMessage=null);executeSubmission(id)}
        else admission.transitionMain{reserve()}.whenComplete{operationId,error->
            if(error!=null)android.util.Log.e("CycleCareOnboarding","submission reservation failed",error)
            else if(operationId!=null)executeSubmission(operationId)
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

    /**
     * NEW IMPLEMENTATION (2026-11-13): native-first via data_export_json_v1.
     * The source reads _uiState.value, logsMap.value and
     * System.currentTimeMillis() once each; each is captured once here in
     * the source's original read order (state, state, clock) and the
     * captures are handed to both routes (single-capture). The clock read
     * stays in Kotlin at this original execution point; only its value
     * crosses JNI. A null reply runs the verbatim pre-wiring assembly, kept
     * as the private twin [kotlinBuildDataExportJson].
     */
    fun buildDataExportJson(): String {
        val current = _uiState.value
        val sortedLogs = logsMap.value.values.sortedBy { it.dateString }
        val epochMs = System.currentTimeMillis()
        return com.cyclecare.app.rust.CycleCareCoreBridge.dataExportJson(
            current.settings, current.onboardingProfile, sortedLogs, epochMs,
        ) ?: kotlinBuildDataExportJson(current.settings, current.onboardingProfile, sortedLogs, epochMs)
    }

    /**
     * Verbatim pre-wiring body of [buildDataExportJson], operating on the
     * caller's single captures. A NaN/Infinity float field throws org.json's
     * JSONException here, exactly as the source did before wiring.
     */
    private fun kotlinBuildDataExportJson(
        settings: com.cyclecare.app.data.local.CycleSettings,
        profile: com.cyclecare.app.data.local.OnboardingProfile?,
        sortedLogs: List<com.cyclecare.app.data.local.CycleDayLog>,
        epochMs: Long,
    ): String {
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
            sortedLogs.forEach { log ->
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
            put("exported_at_epoch_ms", epochMs)
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
        launchIdentity {
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

        launchIdentity {
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

    // Install listeners and launch Main.immediate collectors only AFTER every
    // backing field in this ViewModel has been initialized. Production main
    // dispatch can execute callbacks inline during construction; the previous
    // mid-class init reached screenCommandMutable before it existed.
    init {
        traceStartup(com.cyclecare.app.security.StartupRouteDiagnostics.Event.VM_CAPTURE)
        authPreferences.publicationAdmission?.onFailure{error->
            _uiState.value=_uiState.value.copy(localReadFailed=true)
            android.util.Log.w("CycleCareLocalRead","admission operation failed",error)
        }?.let{addCloseable(it)}
        authPreferences.publicationAdmission?.onTransition{com.cyclecare.app.security.PublicationOrigin.withOrigin({true}){resetIdentityUiIfNeeded();clearStaleCandidateEditor()}}?.let{addCloseable(it)}
        // RECONSTRUCTION (2026-11-13, labeled NEW WORK, not recovered bytes):
        // composition-driven (MainActivity-path) installs bypass this
        // ViewModel's admitted install path, so their read-source move is
        // visible here only as a rustPortRevisions bump. Any bump forces the
        // same fresh same-owner reload as installRustPortAdmitted: MIN_VALUE
        // session, then an origin-forced resetIdentityUiIfNeeded (which
        // re-routes through the admission's main scheduling off-transition).
        viewModelScope.launch {
            repository.rustPortRevisions.collect { revision ->
                val previous = observedRustRevision
                val didInvalidate = revision != previous
                observedRustRevision = revision
                afterRustRevisionObservationForTests?.invoke(previous, revision, didInvalidate)
                if (didInvalidate) {
                    activeUiSession = Long.MIN_VALUE
                    com.cyclecare.app.security.PublicationOrigin.withOrigin({ true }) { resetIdentityUiIfNeeded() }
                }
            }
        }
        startLogObservation()
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
                when (val decision = if (com.cyclecare.bridge.RustReconnectPolicyConfig.ENABLED)
                    com.cyclecare.bridge.RustReconnectPolicy.decide(
                        reconnectWorkState, isConnected, eligible,
                        authPreferences.effectiveUserId, System.currentTimeMillis()
                    )
                else com.cyclecare.app.domain.sync.ReconnectWorkPolicy.decide(
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
        startDerivedObservation()
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


# CycleCareViewModel.kt — full method inventory (ref 31b88f5, 2172 lines, 101 fun declarations (100 members + 1 local))

Classification:
- DECISION-PORTED: the method's decision logic is ported and JVM/source-locked in the named Rust module.
- ORCHESTRATION: verified by direct source read to be a thin delegate (state copy + use-case/service call); the decision cores live in the delegate files (EarnCoinsUseCase, MiningUseCase, AuthService, repositories — tracked in their own inventories).
- DECISION-UNPORTED: contains real decision logic not yet ported; named for upcoming bundles.
- STATE-SETTER: UI-state copy only, no decision logic (ported value is the state machine itself — a Compose/host concern, stays a labeled seam).
- MONEY-INERT: payment orchestration; per the standing money boundary only its gates/messages are portable, execution stays with the host, and with the production release policy the whole path stays closed.
- ACCOUNT-MUTATION (consent/data): real account/data mutations with their own consent, authorization and wipe semantics; never folded into the payment release policy. Only gates/messages/ordering are portable; the mutation itself stays host-executed and owner-authorized.

Method counts (mechanically generated: one row per fun declaration, 103 rows): DECISION-PORTED 37, ORCHESTRATION 26 (incl. Factory.create), STATE-SETTER 25, DECISION-UNPORTED 8, MONEY-INERT 6, ACCOUNT-MUTATION 0, LOCAL 1 (isCurrentCareRequest inside consultCare; ported within care_consult.rs). Breakdown: 101 named member functions + Factory.create (generic override, listed separately) + 1 local (isCurrentCareRequest) = 103 declarations; 102 excluding Factory.create, matching the reviewer regex count. Reviewer line offsets reconciled: reviewer cited :1789/:915 from spike af04874 (+8 lines from a disabled startup-gate init); this table's lines are grep-verified against main 31b88f5 (:1781/:907).

| :line | method | class | evidence / remaining |
|---|---|---|---|
| :207 | captureConflictOwnerSnapshot | DECISION-PORTED | conflict_resolve.rs |
| :212 | isCurrentConflictSnapshot | DECISION-PORTED | conflict_resolve.rs |
| :217 | loadSyncConflicts | DECISION-PORTED | conflict_resolve.rs |
| :231 | resolveSyncConflict | DECISION-PORTED | conflict_resolve.rs |
| :257 | resetPrivateConversation | ORCHESTRATION | state/care-session reset; glue |
| :272 | resetIdentityUiIfNeeded | ORCHESTRATION | identity fan-out; lifecycle.rs covers generation checks |
| :469 | updateCalculations | DECISION-PORTED | home_anchor.rs anchor chain + compute_countdowns (i32 wrap) |
| :547 | selectTab | STATE-SETTER | UI-state copy; Compose/host seam |
| :551 | selectDate | STATE-SETTER | UI-state copy; Compose/host seam |
| :562 | openTrackForDate | STATE-SETTER | UI-state copy; Compose/host seam |
| :568 | closeDailyLog | STATE-SETTER | UI-state copy; Compose/host seam |
| :572 | openAsk | STATE-SETTER | UI-state copy; Compose/host seam |
| :576 | closeAsk | STATE-SETTER | UI-state copy; Compose/host seam |
| :580 | logPeriodStart | ORCHESTRATION | repository write + recalc |
| :603 | quickCheckIn | ORCHESTRATION | day-log write; mood/energy pass through |
| :619 | openSomethingDifferentSheet | STATE-SETTER | UI-state copy; Compose/host seam |
| :626 | closeSomethingDifferentSheet | STATE-SETTER | UI-state copy; Compose/host seam |
| :633 | parseNaturalLanguageInput | DECISION-PORTED | something_sheet.rs should_parse/append; parser delegate |
| :639 | confirmParsedLog | ORCHESTRATION | log write from interpretation |
| :655 | updateTonePreference | ORCHESTRATION | settings write |
| :659 | setSoundEnabled | ORCHESTRATION | settings write |
| :668 | saveDayLog | ORCHESTRATION | repository write |
| :693 | deleteDayLog | ORCHESTRATION | repository delete |
| :700 | updateSettings | DECISION-PORTED | settings_core.rs gates; VM write glue |
| :719 | deleteCloudHealthCopy | DECISION-PORTED | cloud_health_delete.rs TYPE-STATE sequence shell (v95): RpcPermit unconstructable before persisted pause; checks wired at :741/:743; RPC host-executed |
| :750 | setAiSharingAllowed | DECISION-PORTED | ai_consent_tap.rs |
| :773 | setAiProviderSharingAllowed | DECISION-PORTED | ai_consent_tap.rs snapshot equality |
| :822 | enableDisplayedAiProviders | DECISION-PORTED | ai_consent_tap.rs |
| :834 | refreshAiProcessorManifest | ORCHESTRATION | manifest fetch delegate |
| :849 | previewCareContext | ORCHESTRATION | care context assembly delegate |
| :856 | editCareLog | DECISION-UNPORTED | care log edit draft machine (small) |
| :862 | cancelCareLog | DECISION-UNPORTED | draft machine |
| :868 | confirmCareLog | DECISION-UNPORTED | draft machine |
| :884 | consultCare | DECISION-PORTED | care_consult.rs + 46-case JVM corpus (v93) |
| :907 | isCurrentCareRequest | LOCAL | LOCAL inside consultCare (:907); ported as part of its surface |
| :948 | selectTiedDownChoice | STATE-SETTER | UI-state copy; Compose/host seam |
| :952 | generateCareImage | DECISION-PORTED | care_image_gen.rs care_image_entry_gate (busy/premium/token/prompt-length chain, v94) |
| :1003 | clearGeneratedCareImage | STATE-SETTER | UI-state copy; Compose/host seam |
| :1007 | saveThoughtForCareReview | ORCHESTRATION | note write |
| :1016 | openMpesaDialog | STATE-SETTER | UI-state copy; Compose/host seam |
| :1020 | openUnifiedCheckout | STATE-SETTER | UI-state copy; Compose/host seam |
| :1029 | dismissUnifiedCheckout | STATE-SETTER | UI-state copy; Compose/host seam |
| :1038 | toggleLocationRegion | STATE-SETTER | UI-state copy; Compose/host seam |
| :1042 | processUnifiedMpesa | MONEY-INERT | offline gate + status literals portable; STK execution host; closed under production policy |
| :1074 | processUnifiedCard | MONEY-INERT | card path not released in source |
| :1103 | processUnifiedPaypal | MONEY-INERT | same boundary |
| :1132 | processUnifiedCrypto | MONEY-INERT | crypto path not released in source |
| :1161 | dismissMpesaDialog | STATE-SETTER | UI-state copy; Compose/host seam |
| :1170 | initiateMpesaStkPush | MONEY-INERT | delegates SupabasePaymentService; closed under production policy |
| :1194 | pollAndCompletePayment | DECISION-PORTED | payment_poll.rs |
| :1230 | pollMpesaStatus | DECISION-PORTED | payment_poll.rs timeout/row-lock/ladder |
| :1264 | cancelActivePayment | DECISION-PORTED | vm_sync.rs literals (v95) |
| :1276 | triggerManualSync | DECISION-PORTED | sync_gate.rs manual_sync_preaction |
| :1320 | refreshWalletNow | DECISION-PORTED | wallet.rs begin_refresh/apply_result (NO_SESSION gate :1320-1326 verified) |
| :1376 | runReconnectWorkOnce | DECISION-PORTED | vm_sync.rs reconnect_success conjunction (v95) |
| :1402 | scheduleTrailingReconnectWork | DECISION-PORTED | vm_sync.rs coalescing (v95) |
| :1407 | launchTrailingReconnectWork | DECISION-PORTED | vm_sync.rs trailing dispatch + owner capture (v95) |
| :1431 | onForeground | ORCHESTRATION | lifecycle trigger |
| :1437 | onBackground | ORCHESTRATION | wallet realtime stop glue |
| :1439 | autoSyncSilently | DECISION-PORTED | vm_sync.rs composition helpers over sync_gate/account_start (v95) |
| :1496 | unlockApp | DECISION-PORTED | app_lock.rs + pin_verify.rs |
| :1508 | unlockViaBiometric | DECISION-PORTED | app_lock.rs (fail-open parity, security-review hold, labeled) |
| :1512 | saveQuickUnlockPin | DECISION-PORTED | pin.rs + pin_verify.rs PBKDF2+legacy |
| :1525 | enableFingerprintUnlock | DECISION-PORTED | app_lock.rs |
| :1535 | setPinProtectionEnabled | DECISION-PORTED | app_lock.rs |
| :1542 | setFingerprintProtectionEnabled | DECISION-PORTED | app_lock.rs |
| :1547 | markQuickUnlockPromptDismissed | STATE-SETTER | UI-state copy; Compose/host seam |
| :1555 | setShowAuthSheet | STATE-SETTER | UI-state copy; Compose/host seam |
| :1559 | setShowQuickUnlockDialog | STATE-SETTER | UI-state copy; Compose/host seam |
| :1563 | onAuthCompleted | DECISION-PORTED | lifecycle.rs has_completed_initial_auth |
| :1582 | startLocalOnly | DECISION-PORTED | lifecycle.rs |
| :1599 | completeInitialAuth | DECISION-PORTED | lifecycle.rs |
| :1618 | handleAuthDeepLink | ORCHESTRATION | delegates authService.completeEmailLink (verified read); link parse in auth_link.rs/auth_uri.rs |
| :1657 | completePasswordSetup | DECISION-UNPORTED | password validation gates |
| :1674 | dismissPasswordSetup | STATE-SETTER | UI-state copy; Compose/host seam |
| :1678 | setVaultOpen | STATE-SETTER | UI-state copy; Compose/host seam |
| :1683 | claimCoinsManual | ORCHESTRATION | delegates earnCoinsUseCase.claimDailyAttunement (verified read); claim gates in economy_rules.rs |
| :1698 | mineCoinsManual | ORCHESTRATION | verbatim delegate to claimCoinsManual (verified read) |
| :1702 | transferCoins | DECISION-PORTED | economy_rules.rs transfer_may_proceed + toast literal (v94) |
| :1723 | unlockArtifact | ORCHESTRATION | delegates miningUseCase.purchaseArtifact (verified read); missing-coins math in economy_rules.rs |
| :1738 | equipItem | ORCHESTRATION | economy delegate |
| :1753 | purchasePackage | MONEY-INERT | routes to checkout; closed under production policy |
| :1765 | upgradeEconomyTier | ORCHESTRATION | economy delegate; tier tables in economy_rules.rs |
| :1777 | clearEconomyToast | STATE-SETTER | UI-state copy; Compose/host seam |
| :1781 | clearCareChatHistory | ORCHESTRATION | care history clear write |
| :1785 | setNotificationCenterOpen | STATE-SETTER | UI-state copy; Compose/host seam |
| :1790 | dismissGiftModal | STATE-SETTER | UI-state copy; Compose/host seam |
| :1797 | dismissFloatingBanner | STATE-SETTER | UI-state copy; Compose/host seam |
| :1801 | reviveInactivityStreak | DECISION-UNPORTED | streak-revive conditions |
| :1810 | refreshNotifications | DECISION-UNPORTED | assembly/ordering beyond notifications.rs parse helpers |
| :1853 | markNotificationAsRead | ORCHESTRATION | read-state write |
| :1863 | markAllNotificationsAsRead | ORCHESTRATION | read-state write |
| :1872 | signOut | ORCHESTRATION | pref resets + worker cancel + authService.signOut (verified read); auth_flows.rs sign_out |
| :1890 | lockApp | DECISION-PORTED | app_lock.rs |
| :1897 | completeOnboarding | DECISION-PORTED | onboarding_complete.rs + onboarding_vm.rs |
| :1972 | refreshExplainMyBody | ORCHESTRATION | care_knowledge delegate |
| :1984 | openOnboarding | STATE-SETTER | UI-state copy; Compose/host seam |
| :1988 | buildDataExportJson | DECISION-UNPORTED | export JSON assembly; codec in sync_codec.rs |
| :2049 | buildDataExportPdf | DECISION-PORTED | data_export_pdf.rs layout core; PDF bytes host glue, labeled |
| :2055 | wipeAllData | DECISION-PORTED | owner_data_gate.rs namespaces/marker/resume; deletion host |
| :2085 | eraseAllDataUnified | DECISION-PORTED | owner_data_gate.rs; deletion host |
| :2153 | getPredictionForDate | DECISION-UNPORTED | prediction lookup; cycle_math has engine |
| :2166 | create | ORCHESTRATION | DI wiring (Factory) |

## Remaining port candidates, priority order
1. consultCare entry gates + history window (:884-933) — safety-adjacent, locks the >1200 UTF-16 boundary.
3. generateCareImage premium+busy gates (:952).
4. transferCoins offline gate + toast literal (:1702).
2. deleteCloudHealthCopy privacy sequence (:719-747) — HIGH PRIORITY, privacy-critical ACCOUNT-MUTATION: reentry/local-only guards, persist-pause-BEFORE-RPC with abort on save failure, force AI sharing off + conversation reset, owner+session checks before AND after token fetch and delete RPC, uncertain-outcome message pair. owner_data_gate alone does not establish sequence coverage; portable shell only, no live mutation.
5. runReconnectWorkOnce + trailing-job coalescing (:1376-1407) — owner/session recheck pair.
6. autoSyncSilently gate composition (:1439).
7. cancelActivePayment race gate (:1264).
8. reviveInactivityStreak (:1801), refreshNotifications assembly (:1810).
9. completePasswordSetup validation (:1657).
10. getPredictionForDate (:2153).
11. buildDataExportJson assembly (:1988).
12. editCareLog/cancelCareLog/confirmCareLog draft machine (:856-868).
13. processUnified* gate/message layer only (:1042-1132), money-inert, after 1-12.

DECISION-UNPORTED methods (15): editCareLog, cancelCareLog, confirmCareLog, consultCare,
generateCareImage, cancelActivePayment, runReconnectWorkOnce, scheduleTrailingReconnectWork,
launchTrailingReconnectWork, autoSyncSilently, completePasswordSetup, transferCoins,
reviveInactivityStreak, refreshNotifications, buildDataExportJson, getPredictionForDate.
ACCOUNT-MUTATION (1): deleteCloudHealthCopy.

Platform seams that stay in this inventory (not closure): SharedPreferences/repository IO, Supabase service calls, SoundFeedbackManager synthesis, NetworkHelper, coroutine dispatch, all Compose state.

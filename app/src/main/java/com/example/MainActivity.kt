package com.example

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.example.data.local.CycleCareDatabase
import com.example.data.repository.CycleRepository
import com.example.data.supabase.SupabaseAuthService
import com.example.data.supabase.SupabaseSyncService
import com.example.data.supabase.SupabaseSyncWorker
import com.example.security.AuthPreferences
import com.example.security.BiometricHelper
import com.example.ui.auth.AuthSheet
import com.example.ui.auth.QuickUnlockSetupDialog
import com.example.ui.auth.WelcomeAuthScreen
import com.example.ui.screens.AppLockScreen
import com.example.ui.screens.CareScreen
import com.example.ui.screens.CycleScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.InsightsScreen
import com.example.ui.screens.MpesaStkPushDialog
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SomethingDifferentSheet
import com.example.ui.screens.TrackScreen
import com.example.ui.screens.VaultEconomyScreen
import com.example.ui.theme.CycleCareTheme
import com.example.ui.theme.rememberSystemMotionEnabled
import com.example.ui.viewmodel.AppNavTab
import com.example.ui.viewmodel.CycleCareViewModel
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : FragmentActivity() {
    private val pendingAuthLink = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingAuthLink.value = intent?.data

        val authPreferences = AuthPreferences(applicationContext)
        val database = CycleCareDatabase.getDatabase(applicationContext, authPreferences)
        val repository = CycleRepository(applicationContext, authPreferences)
        val syncService = SupabaseSyncService(database, authPreferences)
        val authService = SupabaseAuthService(authPreferences)
        val viewModelFactory = CycleCareViewModel.Factory(repository, authPreferences, syncService, authService)

        // Schedule periodic 24-hour daily background sync with Supabase
        SupabaseSyncWorker.scheduleDailySync(applicationContext)

        setContent {
            val viewModel: CycleCareViewModel by viewModels { viewModelFactory }
            val authLink by pendingAuthLink.collectAsStateWithLifecycle()
            LaunchedEffect(authLink) {
                authLink?.let {
                    viewModel.handleAuthDeepLink(it)
                    pendingAuthLink.value = null
                }
            }
            CycleCareApp(viewModel = viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAuthLink.value = intent.data
    }
}

@Composable
fun CycleCareApp(viewModel: CycleCareViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val logsMap by viewModel.logsMap.collectAsStateWithLifecycle()
    val systemMotionEnabled = rememberSystemMotionEnabled()
    var isSettingsOpen by remember(uiState.localDataOwnerKey) { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    CycleCareTheme {
        when {
            // First Launch Auth Flow (Account Creation / Sign In / Guest) BEFORE Onboarding
            !uiState.hasCompletedInitialAuth -> {
                WelcomeAuthScreen(
                    authService = viewModel.authService,
                    onAuthSuccess = { isAnonymous ->
                        viewModel.completeInitialAuth(isAnonymous)
                    },
                    onLocalOnly = { viewModel.startLocalOnly() }
                )
            }

            uiState.isCheckingCloudProfile -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(14.dp))
                        Text("Checking for your saved profile…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Private App Lock Overlay (Prompted on launch for repeat users if PIN or Fingerprint enabled)
            uiState.isAppLocked -> {
                AppLockScreen(
                    isFingerprintEnabled = uiState.isFingerprintEnabled,
                    onUnlock = { pin -> viewModel.unlockApp(pin) },
                    onTriggerBiometric = {
                        activity?.let { act ->
                            BiometricHelper.showBiometricPrompt(
                                activity = act,
                                onSuccess = { viewModel.unlockViaBiometric() },
                                onError = { /* Handled gracefully in prompt */ }
                            )
                        }
                    },
                    onForgotCredentials = {
                        viewModel.setShowAuthSheet(true)
                    }
                )
            }

            // Onboarding Flow
            uiState.showOnboarding -> {
                OnboardingScreen(
                    onComplete = { goals, lastPeriod, periodStartChoice, cycleLength, cycleLengthOption, periodDuration, pin, enableLock, remindFertile, typicalSymptoms, typicalEnergy, typicalSleep, hasCompletedDeepBaseline, isCloudSyncAllowed ->
                        viewModel.completeOnboarding(
                            goals = goals,
                            lastPeriodDate = lastPeriod,
                            periodStartChoice = periodStartChoice,
                            cycleLength = cycleLength,
                            cycleLengthOption = cycleLengthOption,
                            periodDuration = periodDuration,
                            pin = pin,
                            enableLock = enableLock,
                            remindFertile = remindFertile,
                            typicalSymptoms = typicalSymptoms,
                            typicalEnergy = typicalEnergy,
                            typicalSleep = typicalSleep,
                            hasCompletedDeepBaseline = hasCompletedDeepBaseline,
                            isCloudSyncAllowed = isCloudSyncAllowed
                        )
                    }
                )
            }

            // Account restrictions apply to cloud privileges, never the private health shell.

            // Global Settings Overlay
            isSettingsOpen -> {
                BackHandler { isSettingsOpen = false }
                SettingsScreen(
                    viewModel = viewModel,
                    uiState = uiState,
                    onNavigateBack = { isSettingsOpen = false }
                )
            }

            // Vault / Economy Screen Overlay
            uiState.isVaultOpen && !uiState.isBanned && !uiState.isSuspended -> {
                BackHandler { viewModel.setVaultOpen(false) }
                VaultEconomyScreen(
                    profile = uiState.economyProfile,
                    onMineCoins = { viewModel.mineCoinsManual() },
                    onTransferCoins = { recipient, amount -> viewModel.transferCoins(recipient, amount) },
                    onUnlockItem = { item -> viewModel.unlockArtifact(item) },
                    onEquipItem = { item -> viewModel.equipItem(item) },
                    onPurchasePackage = { pkg -> viewModel.openMpesaDialog(pkg) },
                    onUpgradeTier = { tier -> viewModel.upgradeEconomyTier(tier) },
                    guidedChatsRepository = viewModel.guidedChatsRepo,
                    onNavigateBack = { viewModel.setVaultOpen(false) },
                    isAdmin = uiState.isAdmin
                )
            }

            // Main Core App Shell (Home / Cycle / Track / Insights / Care)
            else -> {
                BackHandler(enabled = uiState.activeTab != AppNavTab.HOME) {
                    viewModel.selectTab(AppNavTab.HOME)
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        CycleCareBottomBar(
                            currentTab = uiState.activeTab,
                            onSelectTab = { viewModel.selectTab(it) }
                        )
                    }
                ) { innerPadding ->
                    AnimatedContent(
                        targetState = uiState.activeTab,
                        transitionSpec = {
                            val duration = if (systemMotionEnabled) 180 else 0
                            fadeIn(animationSpec = tween(duration)) togetherWith fadeOut(animationSpec = tween(duration))
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        label = "tab_transition"
                    ) { targetTab ->
                        when (targetTab) {
                            AppNavTab.HOME -> HomeScreen(
                                viewModel = viewModel,
                                uiState = uiState,
                                todayLog = logsMap[uiState.currentDate.toString()],
                                onOpenSettings = { isSettingsOpen = true }
                            )
                            AppNavTab.CYCLE -> CycleScreen(
                                viewModel = viewModel,
                                uiState = uiState,
                                logsMap = logsMap
                            )
                            AppNavTab.TRACK -> TrackScreen(
                                viewModel = viewModel,
                                uiState = uiState,
                                currentLog = logsMap[uiState.selectedDate.toString()]
                            )
                            AppNavTab.INSIGHTS -> InsightsScreen(
                                viewModel = viewModel,
                                uiState = uiState,
                                logsMap = logsMap
                            )
                            AppNavTab.CARE -> CareScreen(
                                viewModel = viewModel,
                                uiState = uiState
                            )
                        }
                    }

                    if (uiState.isSomethingDifferentSheetOpen) {
                        SomethingDifferentSheet(
                            viewModel = viewModel,
                            uiState = uiState
                        )
                    }
                }
            }
        }

        if (uiState.showAuthSheet) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { viewModel.setShowAuthSheet(false) },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = androidx.compose.ui.Alignment.BottomCenter
                ) {
                    AuthSheet(
                        authService = viewModel.authService,
                        onDismiss = { viewModel.setShowAuthSheet(false) },
                        onAuthSuccess = { isAnonymous -> viewModel.onAuthCompleted(isAnonymous) }
                    )
                }
            }
        }

        if (uiState.isPasswordSetupRequired) {
            var newPassword by remember { mutableStateOf("") }
            var confirmPassword by remember { mutableStateOf("") }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { viewModel.dismissPasswordSetup() },
                title = { Text("Secure your CycleCare account") },
                text = {
                    androidx.compose.foundation.layout.Column {
                        Text("Your guest data is linked to your verified email. Set a password now, or dismiss and use email sign-in links.")
                        androidx.compose.material3.OutlinedTextField(
                            value = newPassword,
                            onValueChange = { newPassword = it },
                            label = { Text("New password") },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            label = { Text("Confirm password") },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                        )
                        uiState.passwordSetupMessage?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.Button(
                        enabled = newPassword.length >= 8 && newPassword == confirmPassword,
                        onClick = { viewModel.completePasswordSetup(newPassword) }
                    ) { Text("Set password") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { viewModel.dismissPasswordSetup() }) {
                        Text("Use email link")
                    }
                }
            )
        }

        if (uiState.showQuickUnlockDialog) {
            QuickUnlockSetupDialog(
                isBiometricSupported = BiometricHelper.isBiometricAvailable(context),
                onDismiss = { viewModel.markQuickUnlockPromptDismissed() },
                onPinConfigured = { pin -> viewModel.saveQuickUnlockPin(pin) },
                onFingerprintEnabled = { viewModel.enableFingerprintUnlock() }
            )
        }

        if (uiState.isNotificationCenterOpen) {
            com.example.ui.components.NotificationCenterDialog(
                notifications = uiState.notifications,
                onDismiss = { viewModel.setNotificationCenterOpen(false) },
                onMarkAsRead = { id -> viewModel.markNotificationAsRead(id) },
                onMarkAllAsRead = { viewModel.markAllNotificationsAsRead() },
                onRefresh = { viewModel.refreshNotifications() }
            )
        }

        if (uiState.activeCheckoutPackage != null) {
            com.example.ui.screens.UnifiedGamifiedCheckoutDialog(
                pkg = uiState.activeCheckoutPackage!!,
                isEastAfricaRegion = uiState.isEastAfricaRegion,
                isProcessing = uiState.isPaymentProcessing,
                statusMessage = uiState.paymentStatusMessage,
                isSuccess = uiState.isPaymentSuccess,
                onDismiss = { viewModel.dismissUnifiedCheckout() },
                onProcessMpesa = { phone, amountKes -> viewModel.processUnifiedMpesa(phone, amountKes) },
                onProcessCard = { card, exp, cvv -> viewModel.processUnifiedCard(card, exp, cvv) },
                onProcessPaypal = { viewModel.processUnifiedPaypal() },
                onProcessCrypto = { currency -> viewModel.processUnifiedCrypto(currency) }
            )
        }

        if (uiState.activeGiftDialogItem != null) {
            com.example.ui.components.GiftReceivedDialog(
                item = uiState.activeGiftDialogItem,
                onDismiss = { viewModel.dismissGiftModal() },
                onViewInVault = { viewModel.setVaultOpen(true) }
            )
        }
    }
}

data class NavItem(val tab: AppNavTab, val label: String, val icon: ImageVector)

@Composable
fun CycleCareBottomBar(
    currentTab: AppNavTab,
    onSelectTab: (AppNavTab) -> Unit
) {
    val items = listOf(
        NavItem(AppNavTab.HOME, "Today", Icons.Default.Home),
        NavItem(AppNavTab.CYCLE, "Cycle", Icons.Default.CalendarMonth),
        NavItem(AppNavTab.TRACK, "Log", Icons.Default.Edit),
        NavItem(AppNavTab.INSIGHTS, "Insights", Icons.AutoMirrored.Filled.TrendingUp),
        NavItem(AppNavTab.CARE, "Care", Icons.Default.Spa)
    )

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("cyclecare_bottom_bar")
    ) {
        items.forEach { item ->
            val selected = currentTab == item.tab
            NavigationBarItem(
                selected = selected,
                onClick = { onSelectTab(item.tab) },
                icon = {
                    Icon(imageVector = item.icon, contentDescription = item.label)
                },
                label = {
                    Text(text = item.label, style = MaterialTheme.typography.labelSmall)
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.testTag("nav_${item.tab.name.lowercase()}")
            )
        }
    }
}

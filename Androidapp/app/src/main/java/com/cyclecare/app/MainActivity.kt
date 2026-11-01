package com.cyclecare.app

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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.supabase.SupabaseAuthService
import com.cyclecare.app.data.supabase.SupabaseSyncService
import com.cyclecare.app.data.supabase.SupabaseSyncWorker
import com.cyclecare.app.security.AuthPreferences
import com.cyclecare.app.security.BiometricHelper
import com.cyclecare.app.ui.auth.AuthSheet
import com.cyclecare.app.ui.auth.QuickUnlockSetupDialog
import com.cyclecare.app.ui.auth.WelcomeAuthScreen
import com.cyclecare.app.ui.screens.AppLockScreen
import com.cyclecare.app.ui.screens.AtelierScreen
import com.cyclecare.app.ui.screens.CareScreen
import com.cyclecare.app.ui.screens.CycleScreen
import com.cyclecare.app.ui.screens.HomeScreen
import com.cyclecare.app.ui.screens.MpesaStkPushDialog
import com.cyclecare.app.ui.screens.OnboardingScreen
import com.cyclecare.app.ui.screens.SettingsScreen
import com.cyclecare.app.ui.screens.SomethingDifferentSheet
import com.cyclecare.app.ui.screens.SpaceScreen
import com.cyclecare.app.ui.screens.TrackScreen
import com.cyclecare.app.ui.screens.VaultEconomyScreen
import com.cyclecare.app.ui.theme.CycleCareTheme
import com.cyclecare.app.ui.theme.rememberSystemMotionEnabled
import com.cyclecare.app.ui.viewmodel.AppNavTab
import com.cyclecare.app.ui.viewmodel.CycleCareViewModel
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : FragmentActivity() {
    private val pendingAuthLink = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Health data must never leak into screenshots, screen recordings, or
        // the recent-apps thumbnail. Release keeps FLAG_SECURE always on; debug
        // and slim sharing builds allow captures so issues can be documented.
        if (!com.cyclecare.app.BuildConfig.DEBUG) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
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

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, uiState.localDataOwnerKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onForeground()
            if (event == Lifecycle.Event.ON_PAUSE) viewModel.onBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); viewModel.onBackground() }
    }

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

            uiState.cloudProfileCheckFailed -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Your saved account could not be checked yet.")
                        Text("Reconnect and try again. Your account has not been reset.")
                        TextButton(onClick = { viewModel.autoSyncSilently(forceWalletRefresh = true) }) { Text("Try again") }
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
                    onComplete = { goals, lastPeriod, periodEndDate, periodStartChoice, cycleLength, cycleLengthOption, periodDuration, pin, enableLock, remindFertile, typicalSymptoms, typicalEnergy, typicalSleep, hasCompletedDeepBaseline, isCloudSyncAllowed, dynamicAnswers ->
                        viewModel.completeOnboarding(
                            goals = goals,
                            lastPeriodDate = lastPeriod,
                            lastPeriodEndDate = periodEndDate,
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
                            isCloudSyncAllowed = isCloudSyncAllowed,
                            dynamicAnswers = dynamicAnswers
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

            // Daily log overlay - opened from Home, or from a date action on Cycle/Calendar
            uiState.isDailyLogOpen -> {
                BackHandler { viewModel.closeDailyLog() }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { viewModel.closeDailyLog() },
                            modifier = Modifier.testTag("daily_log_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                    TrackScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        currentLog = logsMap[uiState.selectedDate.toString()]
                    )
                }
            }

            // Ask CycleCare overlay - opened from the floating bubble
            uiState.isAskOpen -> {
                BackHandler { viewModel.closeAsk() }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { viewModel.closeAsk() },
                            modifier = Modifier.testTag("ask_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Text(
                            text = "Ask CycleCare",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        )
                    }
                    CareScreen(
                        viewModel = viewModel,
                        uiState = uiState
                    )
                }
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
                    isAdmin = uiState.isAdmin,
                    communityGuidesService = viewModel.communityGuidesService
                )
            }

            // Main Core App Shell (Today / Cycle / Hub / Space; Daily log opens from Home, Ask is a floating bubble, You is blended into Settings)
            else -> {
                BackHandler(enabled = uiState.activeTab != AppNavTab.TODAY) {
                    viewModel.selectTab(AppNavTab.TODAY)
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
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    AnimatedContent(
                        targetState = uiState.activeTab,
                        transitionSpec = {
                            val duration = if (systemMotionEnabled) 180 else 0
                            fadeIn(animationSpec = tween(duration)) togetherWith fadeOut(animationSpec = tween(duration))
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "tab_transition"
                    ) { targetTab ->
                        when (targetTab) {
                            AppNavTab.TODAY -> HomeScreen(
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
                            AppNavTab.ATELIER -> SpaceScreen(
                                viewModel = viewModel,
                                uiState = uiState,
                                initialTab = 1
                            )
                            AppNavTab.SPACE -> SpaceScreen(
                                viewModel = viewModel,
                                uiState = uiState
                            )
                        }
                    }

                    AskFloatingBubble(onClick = { viewModel.openAsk() })
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
            BackHandler { viewModel.setNotificationCenterOpen(false) }
            com.cyclecare.app.ui.components.NotificationCenterScreen(
                notifications = uiState.notifications,
                onDismiss = { viewModel.setNotificationCenterOpen(false) },
                onMarkAsRead = { id -> viewModel.markNotificationAsRead(id) },
                onMarkAllAsRead = { viewModel.markAllNotificationsAsRead() },
                onRefresh = { viewModel.refreshNotifications() }
            )
        }

        if (uiState.activeCheckoutPackage != null) {
            com.cyclecare.app.ui.screens.UnifiedGamifiedCheckoutDialog(
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
            com.cyclecare.app.ui.components.GiftReceivedDialog(
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
        NavItem(AppNavTab.TODAY, "Today", Icons.Default.Home),
        NavItem(AppNavTab.CYCLE, "Cycle", Icons.Default.CalendarMonth),
        NavItem(AppNavTab.ATELIER, "Hub", Icons.Default.Star),
        NavItem(AppNavTab.SPACE, "Space", Icons.Default.Spa)
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



/**
 * Floating Ask CycleCare entry. Draggable anywhere on screen, position is
 * remembered across launches, tap opens the chat overlay. Defaults to the
 * bottom-end corner inside the safe content area.
 */
@Composable
private fun AskFloatingBubble(onClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ask_bubble_position", android.content.Context.MODE_PRIVATE) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val bubblePx = with(density) { 56.dp.toPx() }
        val marginPx = with(density) { 16.dp.toPx() }
        val maxX = (constraints.maxWidth.toFloat() - bubblePx - marginPx).coerceAtLeast(marginPx)
        // Keep the bubble below the status bar: this box starts at the top of the
        // screen under edge-to-edge, so an unclamped drag can park it under the clock.
        val statusTopPx = WindowInsets.statusBars.getTop(density).toFloat()
        val minY = marginPx + statusTopPx
        val maxY = (constraints.maxHeight.toFloat() - bubblePx - marginPx).coerceAtLeast(minY)
        var offsetX by remember { mutableFloatStateOf(prefs.getFloat("x", Float.NaN)) }
        var offsetY by remember { mutableFloatStateOf(prefs.getFloat("y", Float.NaN)) }
        val x = if (offsetX.isNaN()) maxX else offsetX.coerceIn(marginPx, maxX)
        val y = if (offsetY.isNaN()) maxY else offsetY.coerceIn(minY, maxY)

        Surface(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(56.dp)
                .pointerInput(maxX, maxY) {
                    detectTapGestures(onTap = { onClick() })
                }
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragEnd = {
                            if (!offsetX.isNaN() && !offsetY.isNaN()) {
                                prefs.edit()
                                    .putFloat("x", offsetX.coerceIn(marginPx, maxX))
                                    .putFloat("y", offsetY.coerceIn(minY, maxY))
                                    .apply()
                            }
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        // Read the state values, not the composed x/y: the
                        // gesture lambda outlives the composition that
                        // produced them, so captured x/y would be stale.
                        val curX = if (offsetX.isNaN()) maxX else offsetX
                        val curY = if (offsetY.isNaN()) maxY else offsetY
                        offsetX = (curX + dragAmount.x).coerceIn(marginPx, maxX)
                        offsetY = (curY + dragAmount.y).coerceIn(minY, maxY)
                    }
                }
                .semantics(mergeDescendants = true) {
                    onClick(label = "Open Ask CycleCare") {
                        onClick()
                        true
                    }
                }
                .testTag("ask_floating_bubble"),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 6.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "Ask CycleCare",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

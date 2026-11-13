package com.cyclecare.app.ui.screens

import com.cyclecare.app.util.CoinFormatter
import com.cyclecare.app.R

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclecare.app.data.model.UserTonePreference
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.local.HealthSyncConflictEntity
import com.cyclecare.app.ui.viewmodel.CycleCareViewModel
import com.cyclecare.app.ui.viewmodel.CycleUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: CycleCareViewModel,
    uiState: CycleUiState,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsScreenContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onLoadSyncConflicts = { viewModel.loadSyncConflicts() },
        onBuildDataExportPdf = { viewModel.buildDataExportPdf() },
        onShowAuthSheet = { viewModel.setShowAuthSheet(true) },
        onSignOut = { viewModel.signOut() },
        onOpenVault = { viewModel.setVaultOpen(true) },
        onUpdateSettings = { viewModel.updateSettings(it) },
        onUpdateTonePreference = { viewModel.updateTonePreference(it) },
        onSetSoundEnabled = { viewModel.setSoundEnabled(it) },
        onSetPinProtectionEnabled = { viewModel.setPinProtectionEnabled(it) },
        onSaveQuickUnlockPin = { viewModel.saveQuickUnlockPin(it) },
        onSetFingerprintProtectionEnabled = { viewModel.setFingerprintProtectionEnabled(it) },
        onSetAiSharingAllowed = { viewModel.setAiSharingAllowed(it) },
        onEraseAllData = { viewModel.eraseAllDataUnified() },
        onResolveSyncConflict = { recordId, keepLocal -> viewModel.resolveSyncConflict(recordId, keepLocal) },
        onTriggerManualSync = viewModel::triggerManualSync,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    uiState: CycleUiState,
    listState: LazyListState = rememberLazyListState(),
    onNavigateBack: () -> Unit,
    onLoadSyncConflicts: () -> Unit,
    onBuildDataExportPdf: () -> ByteArray,
    onShowAuthSheet: () -> Unit,
    onSignOut: () -> Unit,
    onOpenVault: () -> Unit,
    onUpdateSettings: (CycleSettings) -> Unit,
    onUpdateTonePreference: (UserTonePreference) -> Unit,
    onSetSoundEnabled: (Boolean) -> Unit,
    onSetPinProtectionEnabled: (Boolean) -> Unit,
    onSaveQuickUnlockPin: (String) -> Unit,
    onSetFingerprintProtectionEnabled: (Boolean) -> Unit,
    onSetAiSharingAllowed: (Boolean) -> Unit,
    onEraseAllData: () -> Unit,
    onResolveSyncConflict: (String, Boolean) -> Unit,
    onTriggerManualSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = uiState.settings
    // NEW IMPLEMENTATION (recovery rebuild): native-first via the
    // settings_steppers_v1 bridge (cycle_length_is_set op). The verbatim
    // Kotlin twin runs on any null reply; the profile option is captured
    // once at the original read point and passed to both.
    val cycleLengthOptionRaw = uiState.onboardingProfile?.cycleLengthOption
    val cycleLengthIsSet = com.cyclecare.app.rust.CycleCareCoreBridge.settingsCycleLengthIsSet(cycleLengthOptionRaw)
        ?: kotlinCycleLengthIsSet(cycleLengthOptionRaw)
    var showUnifiedEraseDialog by remember { mutableStateOf(false) }
    var showAllObservations by remember { mutableStateOf(false) }
    var showAllPatterns by remember { mutableStateOf(false) }
    var showAllTimeline by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var tempPin by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedConflict by remember { mutableStateOf<HealthSyncConflictEntity?>(null) }
    var selectedResolution by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(uiState.localDataOwnerKey) {
        selectedConflict = null
        selectedResolution = null
        onLoadSyncConflicts()
    }
    val exportScope = androidx.compose.runtime.rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            // Build and write the PDF off the UI thread: years of daily logs make
            // page rendering expensive enough to freeze the screen.
            exportScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                val exported = runCatching {
                    val bytes = onBuildDataExportPdf()
                    val stream = context.contentResolver.openOutputStream(uri) ?: error("No file stream")
                    stream.use { it.write(bytes) }
                }
                val failure = exported.exceptionOrNull()
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                exportMessage = when {
                    exported.isSuccess -> "Your cycle data was exported."
                    // A failure mid-write can leave an incomplete file at the chosen
                    // location; say so instead of implying all-or-nothing.
                    else -> "Could not save the export. If a file was created, it may be incomplete - please try again."
                }
            }
        }
    }

    // Surface provides LocalContentColor so text without an explicit color
    // stays readable on the themed background in dark and light mode.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "You & Settings",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            navigationIcon = {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.testTag("settings_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Section 0: Cloud Sync / Backup Card (Supabase)
            item {
                Spacer(modifier = Modifier.height(10.dp))
                if (uiState.authMode != "EMAIL") {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("anonymous_upsell_card")
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Using CycleCare as Guest",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "Keep your profile when you register",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "Create a new account to keep this guest profile and its entries. Linking Google works when it isn't tied to another profile; signing into an existing account won't move entries from this device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            RankCoinsRow(uiState)

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = { onShowAuthSheet() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("register_account_cta_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text(
                                    text = "Create or sign in",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                } else {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("registered_account_card")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Account Protected",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = uiState.userEmail ?: "Signed in",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    RankCoinsRow(uiState)
                                }
                            }

                            TextButton(
                                onClick = { onSignOut() },
                                modifier = Modifier.testTag("sign_out_button")
                            ) {
                                Text(
                                    text = "Sign Out",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth().testTag("sync_conflict_status_card")
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("Backup check", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        // NEW IMPLEMENTATION (recovery rebuild): native-first
                        // "Backup check" chain via the settings_backup_v1
                        // bridge; verbatim Kotlin twins run on any null reply.
                        Text(
                            com.cyclecare.app.rust.CycleCareCoreBridge.settingsBackupStatusMessage(uiState.isOnlineBackupActive, uiState.lastSyncSuccess)
                                ?: kotlinBackupStatusMessage(uiState.isOnlineBackupActive, uiState.lastSyncSuccess),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Hide a stale past result while backup is off in this
                        // version; the current state line above is the truth.
                        if (com.cyclecare.app.rust.CycleCareCoreBridge.settingsBackupShow("show_last_sync", uiState.isOnlineBackupActive, uiState.lastSyncSuccess)
                            ?: kotlinShowLastSyncMessage(uiState.isOnlineBackupActive)) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(uiState.lastSyncMessage, style = MaterialTheme.typography.bodySmall)
                        }
                        if (com.cyclecare.app.rust.CycleCareCoreBridge.settingsBackupShow("show_check_again", uiState.isOnlineBackupActive, uiState.lastSyncSuccess)
                            ?: kotlinShowCheckAgainButton(uiState.isOnlineBackupActive, uiState.lastSyncSuccess)) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = onTriggerManualSync, modifier = Modifier.testTag("review_sync_button")) {
                                Text("Check again")
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Section: owner-bound local conflict review. Cloud sync remains disabled pending release.
            // You page blended into Settings: patterns, cycle education,
            // saved-log timeline and the clinician visit summary.
            insightsItems(
                uiState = uiState,
                showAllObservations = showAllObservations,
                onToggleObservations = { showAllObservations = !showAllObservations },
                showAllPatterns = showAllPatterns,
                onTogglePatterns = { showAllPatterns = !showAllPatterns },
                showAllTimeline = showAllTimeline,
                onToggleTimeline = { showAllTimeline = !showAllTimeline }
            )

            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth().testTag("sync_conflict_review_card")
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("Records that need your choice", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(
                            "Online backup is off for now. These saved records disagree with their backed-up copies, and you can review them here on this device. Nothing is merged or overwritten automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        uiState.syncConflictMessage?.let {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onLoadSyncConflicts,
                            enabled = !uiState.isLoadingSyncConflicts && !uiState.isResolvingSyncConflict,
                            modifier = Modifier.testTag("refresh_sync_conflicts_button")
                        ) { Text(if (uiState.isLoadingSyncConflicts) "Checking…" else "Check again") }
                        if (uiState.syncConflicts.isEmpty() && !uiState.isLoadingSyncConflicts) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Nothing needs your choice right now.", style = MaterialTheme.typography.bodySmall)
                        }
                        uiState.syncConflicts.forEach { conflict ->
                            SyncConflictReviewRow(
                                conflict = conflict,
                                busy = uiState.isResolvingSyncConflict,
                                onChoose = { keepLocal -> selectedConflict = conflict; selectedResolution = keepLocal }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Section: Vault & Sacred Collectibles Shortcut
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onNavigateBack()
                            onOpenVault()
                        }
                        .testTag("open_vault_from_settings_card")
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.cycle_coin),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "CycleCare Vault & Collectibles",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "${CoinFormatter.format(uiState.economyProfile.coinBalance)} Coins • ${uiState.economyProfile.rank.title} Rank • 3D Talismans",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Section 1: Cycle Settings
            item {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Cycle settings", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        SettingStepperRow(
                            label = "Average cycle length",
                            subtitle = "An estimate is fine; cycles can vary.",
                            valueText = if (cycleLengthIsSet) "${settings.averageCycleLength} days" else "Not set",
                            onDecrease = {
                                // NEW IMPLEMENTATION (recovery rebuild): native-first
                                // stepper guard via settings_steppers_v1; the value is
                                // captured once and persistence stays at this original
                                // onUpdateSettings point.
                                val v = settings.averageCycleLength
                                val next = com.cyclecare.app.rust.CycleCareCoreBridge.settingsStepper("decrease_cycle_length", v) ?: kotlinDecreaseCycleLength(v)
                                if (next != null) {
                                    onUpdateSettings(settings.copy(averageCycleLength = next))
                                }
                            },
                            onIncrease = {
                                val v = settings.averageCycleLength
                                val next = com.cyclecare.app.rust.CycleCareCoreBridge.settingsStepper("increase_cycle_length", v) ?: kotlinIncreaseCycleLength(v)
                                if (next != null) {
                                    onUpdateSettings(settings.copy(averageCycleLength = next))
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        SettingStepperRow(
                            label = "Period duration",
                            subtitle = "Typical number of bleeding days",
                            valueText = "${settings.periodDuration} days",
                            onDecrease = {
                                val v = settings.periodDuration
                                val next = com.cyclecare.app.rust.CycleCareCoreBridge.settingsStepper("decrease_period_duration", v) ?: kotlinDecreasePeriodDuration(v)
                                if (next != null) {
                                    onUpdateSettings(settings.copy(periodDuration = next))
                                }
                            },
                            onIncrease = {
                                val v = settings.periodDuration
                                val next = com.cyclecare.app.rust.CycleCareCoreBridge.settingsStepper("increase_period_duration", v) ?: kotlinIncreasePeriodDuration(v)
                                if (next != null) {
                                    onUpdateSettings(settings.copy(periodDuration = next))
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Tone Preference & Crystal Sound Feedback Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("tone_preference_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Communication tone and app sounds", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "How CycleCare speaks and responds to your touch",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        UserTonePreference.entries.forEach { tone ->
                            val isSelected = uiState.userTonePreference == tone
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    .then(if (isSelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
                                    .clickable { onUpdateTonePreference(tone) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = tone.displayName,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        )
                                        if (isSelected) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "“${tone.sampleMessage}”",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "App sounds",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "Optional confirmation sounds. Off by default.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.isSoundEnabled,
                                onCheckedChange = { enabled ->
                                    onSetSoundEnabled(enabled)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Section 2: App Lock & Security
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "App Lock & PIN Security", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        }
                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "Enable PIN Protection", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                Text(text = "Locks app when leaving foreground", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = uiState.isPinEnabled,
                                onCheckedChange = { enabled ->
                                    onSetPinProtectionEnabled(enabled)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary)
                            )
                        }

                        if (uiState.isPinEnabled) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = tempPin,
                                onValueChange = { raw ->
                                    // Draft only: typing never changes the active passcode.
                                    // NEW IMPLEMENTATION (recovery rebuild): native-first
                                    // draft filter via settings_pin_draft_v1; the verbatim
                                    // Kotlin twin runs on any null reply.
                                    tempPin = com.cyclecare.app.rust.CycleCareCoreBridge.settingsPinDraftFilter(raw) ?: kotlinPinDraftFilter(raw)
                                },
                                label = { Text("New 4-Digit Passcode") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    if (com.cyclecare.app.rust.CycleCareCoreBridge.isCompletePin(tempPin)) {
                                        onSaveQuickUnlockPin(tempPin)
                                        tempPin = ""
                                    }
                                },
                                enabled = com.cyclecare.app.rust.CycleCareCoreBridge.isCompletePin(tempPin),
                                modifier = Modifier.testTag("save_pin_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Save New Passcode")
                            }
                            Text(
                                text = "Your current passcode stays active until you save a complete new one. Use the switch above to remove passcode lock.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "Fingerprint / Biometric Unlock", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                Text(text = "Unlock instantly using your biometric sensor", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = uiState.isFingerprintEnabled,
                                onCheckedChange = { enabled ->
                                    onSetFingerprintProtectionEnabled(enabled)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Section 3: Notification Privacy & Discreet Alerts
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Private notification text", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        }
                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "Hide cycle details on your lock screen", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                Text(
                                    text = if (settings.isDiscreetNotificationMode) "Notifications use neutral wording." else "Notifications may include cycle details.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.isDiscreetNotificationMode,
                                onCheckedChange = { isDiscreet ->
                                    onUpdateSettings(settings.copy(isDiscreetNotificationMode = isDiscreet))
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Section: AI Processing (chat consent lives here, not on the chat screen)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(text = "Assistant", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Allow Assistant answers",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "Off by default. You can turn it on or off anytime.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.isAiSharingAllowed,
                                onCheckedChange = { enabled -> onSetAiSharingAllowed(enabled) },
                                modifier = Modifier.testTag("settings_ai_processing_switch"),
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Section 4: Data Export & Destruction
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(text = "Your data", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Export or erase.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = { exportLauncher.launch("cyclecare-data-${java.time.LocalDate.now()}.pdf") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Export my data (PDF)")
                        }
                        exportMessage?.let { message ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = { showUnifiedEraseDialog = true },
                            enabled = !uiState.isDeletingCloudHealth,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("erase_data_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (uiState.isDeletingCloudHealth) "Erasing your data…" else "Erase your data")
                        }
                        uiState.cloudHealthDeletionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Section 5: Privacy Declaration
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Privacy & Security", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Private by design. Assistant answers are optional: when you allow them, your question, recent cycle context and chat history leave this device to produce the answer. Off by default - revoke anytime.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(30.dp))
            }


        }
    }


    // Unified erase: wipes this device AND the synced cloud copy in parallel.
    if (showUnifiedEraseDialog) {
        AlertDialog(
            onDismissRequest = { showUnifiedEraseDialog = false },
            title = { Text("Erase your data?") },
            text = { Text("This permanently deletes your cycle records, symptoms, notes and personal patterns from this device AND your online copy, at the same time. Your account, purchases and coins are kept. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showUnifiedEraseDialog = false
                        onNavigateBack()
                        onEraseAllData()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Erase everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnifiedEraseDialog = false }) { Text("Cancel") }
            }
        )
    }

    val conflict = selectedConflict
    val keepLocal = selectedResolution
    if (conflict != null && keepLocal != null) {
        val localPreview = conflict.localPayloadJson?.let(::boundedConflictPreview) ?: "(nothing saved on this device)"
        val remotePreview = if (conflict.remoteTombstone) "(the backed-up copy was erased — that can't be undone)"
        else conflict.remotePayloadJson?.let(::boundedConflictPreview) ?: "(no backed-up copy available)"
        AlertDialog(
            onDismissRequest = { if (!uiState.isResolvingSyncConflict) { selectedConflict = null; selectedResolution = null } },
            title = { Text(if (keepLocal) "Keep this device\u2019s version?" else "Use the backed-up version?") },
            text = {
                Column {
                    Text("Review both versions before choosing. Nothing is merged automatically.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("On this device", fontWeight = FontWeight.Bold)
                    Text(localPreview, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Backed-up copy", fontWeight = FontWeight.Bold)
                    Text(remotePreview, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onResolveSyncConflict(conflict.recordId, keepLocal)
                        selectedConflict = null; selectedResolution = null
                    },
                    enabled = !uiState.isResolvingSyncConflict
                ) { Text(if (keepLocal) "Keep mine" else "Use backed-up copy") }
            },
            dismissButton = { TextButton(onClick = { selectedConflict = null; selectedResolution = null }, enabled = !uiState.isResolvingSyncConflict) { Text("Cancel") } }
        )
    }
    }
}

@Composable
private fun SettingStepperRow(
    label: String,
    subtitle: String,
    valueText: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onDecrease,
                modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Decrease $label", modifier = Modifier.size(18.dp))
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            IconButton(
                onClick = onIncrease,
                modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Increase $label", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }


}

private val conflictPreviewSkipKeys = setOf("owner", "owner_id", "user_id", "revision", "server_revision", "hash", "payload_hash", "updated_at", "created_at", "device_id")

/** Rounds a saved health entry into plain-language lines; never raw JSON. */
private fun boundedConflictPreview(payload: String): String = runCatching {
    val json = org.json.JSONObject(payload)
    val lines = mutableListOf<String>()
    val keys = json.keys()
    while (keys.hasNext() && lines.size < 12) {
        val key = keys.next()
        if (key in conflictPreviewSkipKeys) continue
        val label = key.replace('_', ' ').replaceFirstChar { it.uppercase() }
        val value = json.opt(key)?.toString()?.replace('_', ' ')?.lowercase()?.take(120).orEmpty()
        if (value.isNotBlank() && value != "null") lines += "$label: $value"
    }
    lines.joinToString("\n").ifBlank { "(no readable details)" }.take(1000)
}.getOrElse { "(details can\u2019t be shown safely)" }

@Composable
private fun SyncConflictReviewRow(
    conflict: HealthSyncConflictEntity,
    busy: Boolean,
    onChoose: (Boolean) -> Unit
) {
    val localMissing = conflict.localKind.equals("UPSERT", ignoreCase = true) && conflict.localPayloadJson.isNullOrBlank()
    val keepDisabled = conflict.remoteTombstone || localMissing || conflict.localKind.equals("REMOTE", ignoreCase = true)
    val remoteValid = conflict.remoteRevision > 0L && (conflict.remoteTombstone || runCatching {
        val json = org.json.JSONObject(conflict.remotePayloadJson ?: return@runCatching false)
        json.length() > 0
    }.getOrDefault(false))
    Card(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(if (conflict.recordId == "settings") "Cycle settings" else if (conflict.recordId == "profile") "Your profile" else "Saved day · ${conflict.recordId}", fontWeight = FontWeight.Bold)
            Text("Saved ${java.time.Instant.ofEpochMilli(conflict.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}", style = MaterialTheme.typography.bodySmall)
            if (conflict.remoteTombstone) Text("The backed-up copy was erased, and that can't be undone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (localMissing) Text("Keep mine isn't available: the copy on this device is missing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (conflict.localKind.equals("REMOTE", ignoreCase = true)) Text("Keep mine isn't available: this entry only exists in the backup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (!remoteValid) Text("The backed-up copy can't be used: it looks incomplete.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { onChoose(true) }, enabled = !busy && !keepDisabled, modifier = Modifier.testTag("keep_local_${conflict.recordId}")) { Text("Keep mine") }
                Button(onClick = { onChoose(false) }, enabled = !busy && remoteValid, modifier = Modifier.testTag("use_server_${conflict.recordId}")) { Text("Use backed-up copy") }
            }
        }
    }
}



@Composable
private fun RankCoinsRow(uiState: CycleUiState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0xFFC89D54).copy(alpha = 0.2f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = Color(0xFFC89D54),
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = uiState.economyProfile.rank.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFC89D54),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Text(
            text = "${CoinFormatter.format(uiState.economyProfile.coinBalance)} coins",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A complete passcode is exactly 4 digits. Partial drafts are never valid. */
internal fun isCompletePin(pin: String): Boolean = pin.length == 4 && pin.all { it.isDigit() }

// NEW IMPLEMENTATION (recovery rebuild): verbatim Kotlin twins for the
// settings_steppers_v1 native-first wiring above. Each runs on any null
// reply; bodies are the original SettingsScreen expressions verbatim.

private fun kotlinCycleLengthIsSet(cycleLengthOption: String?): Boolean {
    val cycleLengthChoice = cycleLengthOption?.trim()?.lowercase().orEmpty()
    return cycleLengthChoice.isNotBlank() && cycleLengthChoice != "unknown" &&
        !cycleLengthChoice.contains("don't know") && !cycleLengthChoice.contains("don’t know") &&
        !cycleLengthChoice.contains("changes")
}

private fun kotlinDecreaseCycleLength(v: Int): Int? = if (v > 21) v - 1 else null

private fun kotlinIncreaseCycleLength(v: Int): Int? = if (v < 45) v + 1 else null

private fun kotlinDecreasePeriodDuration(v: Int): Int? = if (v > 2) v - 1 else null

private fun kotlinIncreasePeriodDuration(v: Int): Int? = if (v < 10) v + 1 else null

private fun kotlinBackupStatusMessage(isOnlineBackupActive: Boolean, lastSyncSuccess: Boolean): String =
    if (!isOnlineBackupActive) "Online backup is off in this version. Your records stay on this device."
    else if (lastSyncSuccess) "Everything looks good."
    else "Backup is paused or needs attention. CycleCare never overwrites one of your records without asking you first."

private fun kotlinShowLastSyncMessage(isOnlineBackupActive: Boolean): Boolean = isOnlineBackupActive

private fun kotlinShowCheckAgainButton(isOnlineBackupActive: Boolean, lastSyncSuccess: Boolean): Boolean =
    isOnlineBackupActive && !lastSyncSuccess

private fun kotlinPinDraftFilter(raw: String): String = raw.filter { ch -> ch.isDigit() }.take(4)

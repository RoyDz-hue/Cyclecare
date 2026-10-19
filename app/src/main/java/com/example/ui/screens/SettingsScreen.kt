package com.example.ui.screens

import com.example.R

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import com.example.data.model.UserTonePreference
import com.example.data.local.HealthSyncConflictEntity
import com.example.ui.viewmodel.CycleCareViewModel
import com.example.ui.viewmodel.CycleUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: CycleCareViewModel,
    uiState: CycleUiState,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = uiState.settings
    val cycleLengthChoice = uiState.onboardingProfile?.cycleLengthOption?.trim()?.lowercase().orEmpty()
    val cycleLengthIsSet = cycleLengthChoice.isNotBlank() && cycleLengthChoice != "unknown" &&
        !cycleLengthChoice.contains("don't know") && !cycleLengthChoice.contains("don’t know") &&
        !cycleLengthChoice.contains("changes")
    var showWipeDialog by remember { mutableStateOf(false) }
    var showCloudDeleteDialog by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var tempPin by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedConflict by remember { mutableStateOf<HealthSyncConflictEntity?>(null) }
    var selectedResolution by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(uiState.localDataOwnerKey) {
        selectedConflict = null
        selectedResolution = null
        viewModel.loadSyncConflicts()
    }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val exported = runCatching {
                val stream = context.contentResolver.openOutputStream(uri) ?: error("No file stream")
                stream.use { it.write(viewModel.buildDataExportJson().toByteArray(Charsets.UTF_8)) }
            }
            exportMessage = if (exported.isSuccess) "Your cycle data was exported." else "Could not save the export. Please try again."
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Security, Privacy & Settings",
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
                                text = "Create or sign in with email to link this guest profile. Google linking is not available for guests yet; using Google would open a different account, and these entries would not be merged.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = { viewModel.setShowAuthSheet(true) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("register_account_cta_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text(
                                    text = "Create or sign in with email",
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
                                }
                            }

                            TextButton(
                                onClick = { viewModel.signOut() },
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
                        Text("Cloud sync review", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(
                            if (uiState.lastSyncSuccess) "No unresolved sync error is reported." else "Sync is paused or needs attention. CycleCare never silently overwrites a conflicting health record.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(uiState.lastSyncMessage, style = MaterialTheme.typography.bodySmall)
                        if (!uiState.lastSyncSuccess) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = viewModel::triggerManualSync, modifier = Modifier.testTag("review_sync_button")) {
                                Text("Review / retry")
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Section: owner-bound local conflict review. Cloud sync remains disabled pending release.
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth().testTag("sync_conflict_review_card")
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("Review local sync conflicts", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(
                            "Cloud health sync remains disabled pending release. These locally retained conflicts can still be reviewed offline. There is no automatic merge.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        uiState.syncConflictMessage?.let {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = viewModel::loadSyncConflicts,
                            enabled = !uiState.isLoadingSyncConflicts && !uiState.isResolvingSyncConflict,
                            modifier = Modifier.testTag("refresh_sync_conflicts_button")
                        ) { Text(if (uiState.isLoadingSyncConflicts) "Refreshing…" else "Refresh conflicts") }
                        if (uiState.syncConflicts.isEmpty() && !uiState.isLoadingSyncConflicts) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("No unresolved local conflicts.", style = MaterialTheme.typography.bodySmall)
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
                            viewModel.setVaultOpen(true)
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
                                text = "${uiState.economyProfile.coinBalance} Coins • ${uiState.economyProfile.rank.title} Rank • 3D Talismans",
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
                                if (settings.averageCycleLength > 21) {
                                    viewModel.updateSettings(settings.copy(averageCycleLength = settings.averageCycleLength - 1))
                                }
                            },
                            onIncrease = {
                                if (settings.averageCycleLength < 45) {
                                    viewModel.updateSettings(settings.copy(averageCycleLength = settings.averageCycleLength + 1))
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        SettingStepperRow(
                            label = "Period duration",
                            subtitle = "Typical number of bleeding days",
                            valueText = "${settings.periodDuration} days",
                            onDecrease = {
                                if (settings.periodDuration > 2) {
                                    viewModel.updateSettings(settings.copy(periodDuration = settings.periodDuration - 1))
                                }
                            },
                            onIncrease = {
                                if (settings.periodDuration < 10) {
                                    viewModel.updateSettings(settings.copy(periodDuration = settings.periodDuration + 1))
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
                                    .clickable { viewModel.updateTonePreference(tone) }
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
                                    viewModel.setSoundEnabled(enabled)
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
                                    viewModel.setPinProtectionEnabled(enabled)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary)
                            )
                        }

                        if (uiState.isPinEnabled) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = tempPin,
                                onValueChange = {
                                    if (it.length <= 4) {
                                        tempPin = it
                                        viewModel.saveQuickUnlockPin(it)
                                    }
                                },
                                label = { Text("Set 4-Digit Passcode") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
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
                                    viewModel.setFingerprintProtectionEnabled(enabled)
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
                                    viewModel.updateSettings(settings.copy(isDiscreetNotificationMode = isDiscreet))
                                },
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
                            text = "Export or erase health data on this device. Synced copies are not deleted from this screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = { exportLauncher.launch("cyclecare-data-${java.time.LocalDate.now()}.json") },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Export my data (JSON)")
                        }
                        exportMessage?.let { message ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = {
                                onNavigateBack()
                                viewModel.openOnboarding()
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("restart_onboarding_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Replay Onboarding Journey")
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = { showWipeDialog = true },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("wipe_data_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Erase data on this device")
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(onClick = { showCloudDeleteDialog = true }, enabled = !uiState.isDeletingCloudHealth,
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("delete_cloud_health_button"), shape = RoundedCornerShape(12.dp)) {
                            Text(if (uiState.isDeletingCloudHealth) "Deleting cloud health copy…" else "Delete cloud health copy")
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
                            text = "Your cycle and health records are strictly private and never sold or shared with third-party advertisers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(30.dp))
            }


        }
    }

    if (showCloudDeleteDialog) {
        AlertDialog(onDismissRequest = { showCloudDeleteDialog = false }, title = { Text("Delete cloud health copy?") },
            text = { Text("This deletes the connected account’s cloud health logs, cycle records and health-profile fields, and turns sync and AI sharing off. Local records remain. Account identity, payment, economy and audit records are retained; provider-side AI records are not erased. Success requires the new backend deletion service.") },
            confirmButton = { TextButton(onClick = { showCloudDeleteDialog = false; viewModel.deleteCloudHealthCopy() }) { Text("Delete cloud health copy") } },
            dismissButton = { TextButton(onClick = { showCloudDeleteDialog = false }) { Text("Cancel") } })
    }

    // Wipe Confirmation Dialog
    if (showWipeDialog) {
        AlertDialog(
            onDismissRequest = { showWipeDialog = false },
            title = { Text("Erase this device’s health records?") },
            text = { Text("This permanently deletes cycle records, symptoms, notes, and personal patterns from this device. It does not delete a synced cloud copy. This action cannot be undone on this device.") },
            confirmButton = {
                Button(
                    onClick = {
                        onNavigateBack()
                        viewModel.wipeAllData()
                        showWipeDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Erase local records")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeDialog = false }) { Text("Cancel") }
            }
        )
    }

    val conflict = selectedConflict
    val keepLocal = selectedResolution
    if (conflict != null && keepLocal != null) {
        val localPreview = conflict.localPayloadJson?.let(::boundedConflictPreview) ?: "(local payload unavailable)"
        val remotePreview = if (conflict.remoteTombstone) "(server deletion — this deletion is final)"
        else conflict.remotePayloadJson?.let(::boundedConflictPreview) ?: "(server payload unavailable)"
        AlertDialog(
            onDismissRequest = { if (!uiState.isResolvingSyncConflict) { selectedConflict = null; selectedResolution = null } },
            title = { Text(if (keepLocal) "Keep this local version?" else "Use this server version?") },
            text = {
                Column {
                    Text("Review the bounded previews before resolving. This action does not merge values.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Local revision ${conflict.localRevision}", fontWeight = FontWeight.Bold)
                    Text(localPreview, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Server revision ${conflict.remoteRevision}", fontWeight = FontWeight.Bold)
                    Text(remotePreview, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resolveSyncConflict(conflict.recordId, keepLocal)
                        selectedConflict = null; selectedResolution = null
                    },
                    enabled = !uiState.isResolvingSyncConflict
                ) { Text(if (keepLocal) "Keep mine" else "Use server version") }
            },
            dismissButton = { TextButton(onClick = { selectedConflict = null; selectedResolution = null }, enabled = !uiState.isResolvingSyncConflict) { Text("Cancel") } }
        )
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

private fun boundedConflictPreview(payload: String): String = runCatching {
    org.json.JSONObject(payload).toString(2).take(2000)
}.getOrElse { "(malformed payload; unavailable for safe preview)" }

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
            Text("Record ${conflict.recordId}", fontWeight = FontWeight.Bold)
            Text("Created ${java.time.Instant.ofEpochMilli(conflict.createdAt)}", style = MaterialTheme.typography.bodySmall)
            Text("Local revision ${conflict.localRevision} • Server revision ${conflict.remoteRevision}", style = MaterialTheme.typography.bodySmall)
            if (conflict.remoteTombstone) Text("The server version is a deletion; deletions are final.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (localMissing) Text("Keep mine unavailable: local payload is missing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (conflict.localKind.equals("REMOTE", ignoreCase = true)) Text("Keep mine unavailable for a server-originated local record.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (!remoteValid) Text("Use server version unavailable: server revision or metadata is malformed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { onChoose(true) }, enabled = !busy && !keepDisabled, modifier = Modifier.testTag("keep_local_${conflict.recordId}")) { Text("Keep mine") }
                Button(onClick = { onChoose(false) }, enabled = !busy && remoteValid, modifier = Modifier.testTag("use_server_${conflict.recordId}")) { Text("Use server version") }
            }
        }
    }
}

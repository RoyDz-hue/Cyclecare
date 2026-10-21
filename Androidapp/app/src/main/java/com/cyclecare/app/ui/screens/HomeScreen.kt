package com.cyclecare.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyclecare.app.R
import com.cyclecare.app.data.economy.EconomyCatalog
import com.cyclecare.app.data.economy.UserEconomyTier
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.model.MoodLevel
import com.cyclecare.app.data.model.SafetyLevel
import com.cyclecare.app.domain.safety.SafetyEngine
import com.cyclecare.app.ui.components.CycleDial
import com.cyclecare.app.ui.components.InteractiveArtifact3D
import com.cyclecare.app.ui.viewmodel.AppNavTab
import com.cyclecare.app.ui.viewmodel.CycleCareViewModel
import com.cyclecare.app.ui.viewmodel.CycleUiState
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    viewModel: CycleCareViewModel,
    uiState: CycleUiState,
    todayLog: CycleDayLog?,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val prediction = uiState.todayPrediction
    val phase = prediction?.phase
    val unreadCount = uiState.notifications.count { !it.isRead }
    val safety = SafetyEngine.evaluate(todayLog)
    val selectedMood = todayLog?.moodLevel ?: ""
    val canUseDirectAi = uiState.isAdmin || uiState.economyProfile.tier == UserEconomyTier.PREMIUM
    var showPremiumChatDialog by remember { mutableStateOf(false) }
    var premiumChatPrompt by remember { mutableStateOf("") }
    var showPlansDialog by remember { mutableStateOf(false) }
    var showCommunityCirclesDialog by remember { mutableStateOf(false) }
    val currentDateText = uiState.currentDate.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
    val nextPeriodDateText = uiState.currentDate
        .plusDays(uiState.daysUntilNextPeriod.toLong())
        .format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    val activeArtifactItem = remember(uiState.economyProfile.activeArtifact) {
        EconomyCatalog.findItem(uiState.economyProfile.activeArtifact) ?: EconomyCatalog.ALL_ITEMS.first()
    }
    val urgentNotification = remember(uiState.notifications, uiState.dismissedFloatingBannerId) {
        uiState.notifications.firstOrNull {
            !it.isRead && it.id != uiState.dismissedFloatingBannerId &&
                (it.priority.equals("URGENT", true) || it.priority.equals("HIGH", true) ||
                    it.type.equals("OFFICIAL_DISPATCH", true) || it.type.equals("WARNING", true))
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home_screen_content")
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        if (urgentNotification != null) {
            item {
                com.cyclecare.app.ui.components.FloatingDispatchBanner(
                    notification = urgentNotification,
                    onOpenInbox = { viewModel.setNotificationCenterOpen(true) },
                    onDismiss = { viewModel.dismissFloatingBanner(urgentNotification.id) }
                )
            }
        }

        if (uiState.sessionNeedsReauthentication) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().testTag("session_reauth_banner")
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Reconnect your account",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Your secure cloud session needs attention. Your health entries remain on this device until you sign in again.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { viewModel.setShowAuthSheet(true) }) {
                                Text("Sign in again")
                            }
                        }
                    }
                }
            }
        }

        // Top Bar with Refined Vector Brand Mark
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_cyclecare_logo),
                        contentDescription = "CycleCare",
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    )
                    Column {
                        Text(
                            text = "CycleCare",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = currentDateText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BadgedBox(badge = {
                        if (unreadCount > 0) {
                            Badge {
                                Text(if (unreadCount > 9) "9+" else "$unreadCount")
                            }
                        }
                    }) {
                        IconButton(
                            onClick = { viewModel.setNotificationCenterOpen(true) },
                            modifier = Modifier.size(48.dp).testTag("notification_bell_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = if (unreadCount > 0) "Notifications, $unreadCount unread" else "Notifications",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(48.dp).testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Editorial Greeting
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "TODAY, AT YOUR PACE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "A little space to check in.",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Notice what feels right for you today.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (safety.level != SafetyLevel.LOW) {
            item {
                val safetyColor = if (safety.isRedFlag) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("today_safety_notice"),
                    shape = RoundedCornerShape(20.dp),
                    color = if (safety.isRedFlag) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(safety.title, style = MaterialTheme.typography.titleSmall, color = safetyColor)
                        Text(safety.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(safety.actionAdvice, style = MaterialTheme.typography.labelMedium, color = safetyColor)
                    }
                }
            }
        }

        // Primary Cycle Rhythm Dial Card
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .clickable(
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClickLabel = "Open cycle calendar"
                    ) { viewModel.selectTab(AppNavTab.CYCLE) }
                    .testTag("today_cycle_snapshot"),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "YOUR CYCLE RHYTHM",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = phase?.title ?: "Not set",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                    CycleDial(
                        prediction = prediction,
                        cycleLength = uiState.settings.averageCycleLength,
                        periodDuration = uiState.settings.periodDuration,
                        daysUntilPeriod = uiState.daysUntilNextPeriod,
                        modifier = Modifier.size(228.dp),
                        onClick = { viewModel.selectTab(AppNavTab.CYCLE) }
                    )
                    Text(
                        text = when {
                            !uiState.hasCycleAnchor -> "Add an approximate start date and cycle length to see estimates"
                            uiState.cycleStartIsApproximate -> "Approximate calendar estimate · tap to open calendar"
                            else -> "Calendar estimate from your entered date · tap to open calendar"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (uiState.hasCycleAnchor && uiState.daysUntilNextPeriod > 0) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("next_period_estimate_notice"),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Your period may be approaching around $nextPeriodDateText",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "A calendar estimate based on the cycle information you chose; timing can vary.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        // 1-Tap Reactive Micro Check-In Card
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("micro_check_in_card"),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("A quick check-in", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (todayLog != null) "Saved in your daily log." else "Optional · choose a quick check-in to add to your log.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (todayLog != null) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = "✓ Saved",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    Text("How are you feeling?", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MoodLevel.entries.forEach { mood ->
                            FilterChip(
                                selected = selectedMood == mood.name,
                                onClick = {
                                    viewModel.quickCheckIn(mood.name, todayLog?.energyLevel)
                                },
                                label = { Text("${mood.emoji} ${mood.displayName}") },
                                modifier = Modifier.height(48.dp)
                            )
                        }
                    }
                    AnimatedVisibility(visible = uiState.economyToastMessage != null) {
                        uiState.economyToastMessage?.let { rewardMsg ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFC89D54).copy(alpha = 0.14f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "✨ $rewardMsg",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color(0xFF8C6222)
                                )
                                Text(
                                    text = "Dismiss",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { viewModel.clearEconomyToast() }
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { viewModel.openSomethingDifferentSheet() }) {
                            Text("Something feels different?")
                        }
                        TextButton(onClick = {
                            viewModel.selectDate(uiState.currentDate)
                            viewModel.selectTab(AppNavTab.ASK)
                        }) {
                            Text("Add details")
                        }
                    }
                }
            }
        }

        // Priority Insight Note Card
        item {
            val attention = uiState.todayAttentionItem
            val headline = attention?.headline ?: phase?.subtitle ?: "Your own rhythm"
            val message = attention?.message ?: phase?.description ?: "Add a few cycle dates to make your estimates more personal."
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("today_priority_card"),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A NOTE FOR TODAY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    Text(headline, style = MaterialTheme.typography.titleMedium)
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { viewModel.selectTab(AppNavTab.ASK) }) {
                        Text("Explore in Ask")
                    }
                }
            }
        }

        // Interactive 3D Artifact & Rewards Sanctuary Card + Subtle Premium Discovery
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { viewModel.setVaultOpen(true) }
                    .testTag("open_vault_coin_pill"),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFC89D54).copy(alpha = 0.35f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFFC89D54).copy(alpha = 0.10f),
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                )
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            InteractiveArtifact3D(
                                drawableRes = activeArtifactItem.drawableRes,
                                size = 56.dp,
                                auraColor = Color(activeArtifactItem.auraColorHex),
                                isInteractive = true,
                                enableIdleRotation = false,
                                harmonicPitchHz = activeArtifactItem.harmonicPitchHz
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "CycleCare rewards",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(0xFFC89D54).copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            text = uiState.economyProfile.tier.title,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF8C6222),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "${uiState.economyProfile.coinBalance} coins · ${activeArtifactItem.name} active",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (uiState.economyProfile.tier == UserEconomyTier.FREE && !uiState.isAdmin) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = "Supporter options add 2× coins and Circle topic previews",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                        TextButton(onClick = { viewModel.selectTab(AppNavTab.SPACE) }) {
                            Text("View Atelier")
                        }
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TextButton(
                    onClick = { showCommunityCirclesDialog = true },
                    modifier = Modifier.fillMaxWidth().testTag("home_community_circles_popup_button")
                ) {
                    Text(if (uiState.economyProfile.tier == UserEconomyTier.FREE && !uiState.isAdmin) "Learn about Community Circles" else "Open Community Circles")
                }
                if (!uiState.isAdmin) {
                    TextButton(
                        onClick = { showPlansDialog = true },
                        modifier = Modifier.fillMaxWidth().testTag("home_plans_popup_button")
                    ) {
                        Text(if (uiState.economyProfile.tier == UserEconomyTier.FREE) "View plans" else "Review plan benefits")
                    }
                }
            }
        }
    }

    if (canUseDirectAi) {
        FloatingActionButton(
            onClick = { showPremiumChatDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("premium_direct_ai_fab"),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = "Chat with your CycleCare Expert", tint = MaterialTheme.colorScheme.onPrimary)
        }
    }

    if (showCommunityCirclesDialog) {
        AlertDialog(
            onDismissRequest = { showCommunityCirclesDialog = false },
            title = { Text("Community Circles", modifier = Modifier.testTag("community_circles_popup_title")) },
            text = {
                val accessCopy = when {
                    uiState.isAdmin -> "This account has access. Only topic previews are currently connected; live group chat is not available."
                    uiState.economyProfile.tier == UserEconomyTier.FREE -> "Supporter and Premium plan details include Circle access. Only topic previews are currently connected; live group chat is not available."
                    else -> "Your ${uiState.economyProfile.tier.title} plan includes Circle access. Only topic previews are currently connected; live group chat is not available."
                }
                Text("Community Circles are guided discussion topics. $accessCopy This popup shows no member counts or activity and does not join or post anything.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showCommunityCirclesDialog = false
                    viewModel.setVaultOpen(true)
                }) {
                    Text(if (uiState.economyProfile.tier == UserEconomyTier.FREE && !uiState.isAdmin) "See access options" else "Open Rewards")
                }
            },
            dismissButton = { TextButton(onClick = { showCommunityCirclesDialog = false }) { Text("Not now") } }
        )
    }

    if (showPlansDialog) {
        AlertDialog(
            onDismissRequest = { showPlansDialog = false },
            title = { Text(if (uiState.economyProfile.tier == UserEconomyTier.FREE) "CycleCare plans" else "Your CycleCare plan") },
            text = {
                val planCopy = when (uiState.economyProfile.tier) {
                    UserEconomyTier.FREE -> "Cycle tracking and daily check-ins remain available on the free plan. Supporter and Premium options add the benefits listed in Rewards. Opening plan details does not start a payment."
                    UserEconomyTier.SUPPORTER -> "Your Supporter plan includes its listed benefits. Compare current options in Rewards; opening them does not start a payment."
                    UserEconomyTier.PREMIUM -> "Your Premium plan includes its listed benefits. Review your plan and current options in Rewards; opening them does not start a payment."
                }
                Text(planCopy)
            },
            confirmButton = {
                TextButton(onClick = {
                    showPlansDialog = false
                    viewModel.setVaultOpen(true)
                }) { Text("Open Rewards") }
            },
            dismissButton = { TextButton(onClick = { showPlansDialog = false }) { Text("Not now") } }
        )
    }

    if (showPremiumChatDialog) {
        AlertDialog(
            onDismissRequest = { showPremiumChatDialog = false },
            title = { Text("CycleCare Expert") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Your question is sent with your saved cycle preferences and recent entries to tailor the guidance. This is educational support, not a diagnosis.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = premiumChatPrompt,
                        onValueChange = { premiumChatPrompt = it.take(600) },
                        modifier = Modifier.fillMaxWidth().testTag("premium_direct_ai_prompt"),
                        label = { Text("What would you like help with?") },
                        minLines = 3
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = premiumChatPrompt.isNotBlank(),
                    onClick = {
                        val question = premiumChatPrompt.trim()
                        premiumChatPrompt = ""
                        showPremiumChatDialog = false
                        viewModel.selectTab(AppNavTab.ASK)
                        viewModel.consultCare(question, includeFullContext = true)
                    }
                ) { Text("Ask CycleCare") }
            },
            dismissButton = { TextButton(onClick = { showPremiumChatDialog = false }) { Text("Cancel") } }
        )
    }
    }
}


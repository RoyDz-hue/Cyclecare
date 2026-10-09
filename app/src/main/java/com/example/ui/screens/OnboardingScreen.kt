package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ui.viewmodel.OnboardingViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = viewModel(),
    onComplete: (
        goals: List<String>,
        lastPeriod: LocalDate,
        cycleLength: Int,
        periodDuration: Int,
        pin: String,
        enableLock: Boolean,
        remindFertile: Boolean
    ) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .testTag("onboarding_screen"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Nav Indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (uiState.step in 2..4) {
                IconButton(
                    onClick = { viewModel.previousStep() },
                    modifier = Modifier.testTag("onboarding_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            } else {
                Spacer(modifier = Modifier.size(48.dp))
            }

            Text(
                text = "Step ${uiState.step} of ${uiState.totalSteps}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.size(48.dp))
        }

        LinearProgressIndicator(
            progress = { uiState.step.toFloat() / uiState.totalSteps.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Step Content Carousel
        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = uiState.step,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "onboarding_step_transition"
            ) { currentStep ->
                when (currentStep) {
                    1 -> Step1Welcome()
                    2 -> Step2PrivacyFirst(
                        isAgeVerified = uiState.isAgeVerified,
                        onAgeVerifiedChange = { viewModel.setAgeVerified(it) },
                        isTermsAgreed = uiState.isTermsAgreed,
                        onTermsAgreedChange = { viewModel.setTermsAgreed(it) },
                        onOpenTerms = { viewModel.setShowTermsDialog(true) },
                        onOpenPrivacy = { viewModel.setShowPrivacyDialog(true) }
                    )
                    3 -> Step3SoftGoalSelection(
                        selectedGoals = uiState.selectedGoals,
                        onToggle = { goal -> viewModel.toggleGoal(goal) }
                    )
                    4 -> Step4FirstDataLowFriction(
                        selectedExactDate = uiState.selectedExactDate,
                        onPickExactDateClick = { viewModel.setDatePickerDialogVisible(true) },
                        isNotSureSelected = uiState.isNotSureSelected,
                        onNotSureClick = { viewModel.setNotSureSelected(true) },
                        selectedApproximateChip = uiState.selectedApproximateChip,
                        onSelectApproximateChip = { viewModel.selectApproximateChip(it) },
                        lastPeriodAnswered = uiState.lastPeriodAnswered,
                        selectedCycleOption = uiState.selectedCycleOption,
                        onSelectCycleOption = { label, days -> viewModel.selectCycleOption(label, days) }
                    )
                    5 -> Step5ImmediateValue(
                        dayOfCycle = uiState.dayOfCycle,
                        remindFertileWindow = uiState.remindFertileWindow,
                        onToggleRemindFertile = { viewModel.setRemindFertile(it) }
                    )
                }
            }
        }

        // Bottom CTA Section
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            val isButtonEnabled = when (uiState.step) {
                2 -> uiState.canProceedFromPrivacy
                else -> true
            }

            Button(
                onClick = {
                    if (uiState.step < uiState.totalSteps) {
                        viewModel.nextStep()
                    } else {
                        onComplete(
                            uiState.selectedGoals.toList(),
                            uiState.effectiveLastPeriodDate,
                            uiState.cycleLengthDays,
                            5,
                            "",
                            false,
                            uiState.remindFertileWindow
                        )
                    }
                },
                enabled = isButtonEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("onboarding_cta_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = when (uiState.step) {
                        1 -> "Let’s begin"
                        2 -> "Continue privately"
                        3 -> "Continue"
                        4 -> "Continue"
                        5 -> "Start using CycleCare"
                        else -> "Continue"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = if (uiState.step == 5) Icons.Default.Check else Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null
                )
            }

            if (uiState.step == 2) {
                if (!uiState.canProceedFromPrivacy) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Please confirm your age (18+) and accept terms to continue",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                TextButton(
                    onClick = {
                        if (uiState.canProceedFromPrivacy) {
                            viewModel.nextStep()
                        }
                    },
                    enabled = uiState.canProceedFromPrivacy,
                    modifier = Modifier.padding(top = 2.dp).testTag("account_sync_button")
                ) {
                    Text(
                        text = "I want an account for sync & recovery",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (uiState.canProceedFromPrivacy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }

    // Material 3 Date Picker Dialog
    if (uiState.showDatePickerDialog) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { viewModel.setDatePickerDialogVisible(false) },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val localDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                            viewModel.setExactDate(localDate)
                        } ?: viewModel.setDatePickerDialogVisible(false)
                    }
                ) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setDatePickerDialogVisible(false) }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Terms & Conditions Dialog
    if (uiState.showTermsDialog) {
        PolicySummaryDialog(
            title = "Terms of Service",
            summaryPoints = listOf(
                "Personal Health Companion" to "CycleCare is designed as an informative wellness and cycle observation tool. It does not provide medical diagnosis, treatment, or clinical advice.",
                "Adult Use Only" to "By using CycleCare, you confirm you are 18 years of age or older.",
                "Ownership of Content" to "You retain complete ownership of all notes, symptoms, and entries you record in the application.",
                "Voluntary Tracking" to "You decide what to log and when. You can erase or export your stored cycle logs whenever you wish."
            ),
            onDismiss = { viewModel.setShowTermsDialog(false) }
        )
    }

    // Privacy Policy Dialog
    if (uiState.showPrivacyDialog) {
        PolicySummaryDialog(
            title = "Privacy Policy",
            summaryPoints = listOf(
                "Private by Default" to "Your cycle, symptom, and mood data are stored securely on your device. We do not transmit or sell personal health records to data brokers or third-party advertisers.",
                "No Creepy Tracking" to "We never track you across other applications or monetize your private biological cycles.",
                "Optional Cloud Sync" to "If you choose to enable cloud recovery in the future, your data is securely encrypted in transit and at rest under strict access controls.",
                "Complete Data Control" to "You can clear your local database at any time directly in CycleCare settings."
            ),
            onDismiss = { viewModel.setShowPrivacyDialog(false) }
        )
    }
}

/**
 * Screen 1 — Welcome
 */
@Composable
private fun Step1Welcome() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_cyclecare_logo),
            contentDescription = "CycleCare Logo",
            modifier = Modifier
                .size(112.dp)
                .clip(CircleShape)
        )
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = "Your body has patterns.\n\nWe’ll help you see them clearly.",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                lineHeight = 34.sp
            ),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "CycleCare helps you notice what changes, what repeats, and what may be worth paying attention to.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

/**
 * Screen 2 — Privacy First with Age Verification & Agreement
 */
@Composable
private fun Step2PrivacyFirst(
    isAgeVerified: Boolean,
    onAgeVerifiedChange: (Boolean) -> Unit,
    isTermsAgreed: Boolean,
    onTermsAgreedChange: (Boolean) -> Unit,
    onOpenTerms: () -> Unit,
    onOpenPrivacy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0xFFE8F5E9),
            modifier = Modifier.size(68.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(34.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "This is your space.",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Your health information is personal.\nCycleCare is designed to keep it private, secure, and under your control.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 14.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                PrivacyBulletPoint(title = "Private by default", desc = "Stored on this device. Never sold or shared.")
                Spacer(modifier = Modifier.height(12.dp))
                PrivacyBulletPoint(title = "You control your data", desc = "Export, review, or clear your history anytime.")
                Spacer(modifier = Modifier.height(12.dp))
                PrivacyBulletPoint(title = "You can change your mind anytime", desc = "Adjust permissions and features whenever you like.")
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Legal Consent & Age Verification Section
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Age Verification & Consent",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Checkbox 1: Age Verification (18+)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAgeVerifiedChange(!isAgeVerified) }
                        .padding(vertical = 4.dp)
                        .testTag("checkbox_age_18_row")
                ) {
                    Checkbox(
                        checked = isAgeVerified,
                        onCheckedChange = { onAgeVerifiedChange(it) },
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("checkbox_age_18")
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "I confirm that I am 18 years of age or older",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Checkbox 2: User Agreement (Terms & Privacy)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .testTag("checkbox_terms_agreement_row")
                ) {
                    Checkbox(
                        checked = isTermsAgreed,
                        onCheckedChange = { onTermsAgreedChange(it) },
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("checkbox_terms_agreement")
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "I agree to the Terms of Service and Privacy Policy",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.clickable { onTermsAgreedChange(!isTermsAgreed) }
                        )
                        Row(modifier = Modifier.padding(top = 2.dp)) {
                            Text(
                                text = "View Terms",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { onOpenTerms() }
                                    .testTag("link_view_terms")
                            )
                            Text(
                                text = "  •  ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "View Privacy Policy",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { onOpenPrivacy() }
                                    .testTag("link_view_privacy")
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dedicated In-App Policy Summary Dialog
 */
@Composable
private fun PolicySummaryDialog(
    title: String,
    summaryPoints: List<Pair<String, String>>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                summaryPoints.forEach { (pointTitle, pointDesc) ->
                    Text(
                        text = pointTitle,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = pointDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("dialog_policy_confirm_button")
            ) {
                Text("Understood", fontWeight = FontWeight.Bold)
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun PrivacyBulletPoint(title: String, desc: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = "• $title",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Screen 3 — Soft Goal Selection
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Step3SoftGoalSelection(
    selectedGoals: Set<String>,
    onToggle: (String) -> Unit
) {
    val chips = listOf(
        "🌸 Understand my cycle",
        "🩷 Track my periods",
        "🔎 Understand changes in my body",
        "🌱 Learn about my fertility",
        "💭 Understand symptoms",
        "✨ Feel more in tune with myself",
        "📖 Just exploring"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "What would you like help with?",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Choose any that feel right. You can change this later.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            chips.forEach { chip ->
                val isSelected = selectedGoals.contains(chip)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                        .then(
                            if (isSelected) Modifier.border(
                                1.5.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(16.dp)
                            )
                            else Modifier.border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(16.dp)
                            )
                        )
                        .clickable { onToggle(chip) }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = chip,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        }
    }
}

/**
 * Screen 4 — First Data (Low Friction)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Step4FirstDataLowFriction(
    selectedExactDate: LocalDate?,
    onPickExactDateClick: () -> Unit,
    isNotSureSelected: Boolean,
    onNotSureClick: () -> Unit,
    selectedApproximateChip: String?,
    onSelectApproximateChip: (String) -> Unit,
    lastPeriodAnswered: Boolean,
    selectedCycleOption: String,
    onSelectCycleOption: (String, Int) -> Unit
) {
    val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

    val approximateChips = listOf(
        "Today",
        "A few days ago",
        "About a week ago",
        "About two weeks ago",
        "It varies / I don’t know"
    )

    val cycleOptions = listOf(
        "21–24 days" to 23,
        "25–28 days" to 28,
        "29–32 days" to 30,
        "33–35 days" to 34,
        "It changes" to 29,
        "I don’t know" to 28
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Question 1
        Text(
            text = "When did your last period start?",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(14.dp))

        // Primary action: Pick exact date (opens calendar)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (selectedExactDate != null) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            ),
            border = androidx.compose.foundation.BorderStroke(
                width = if (selectedExactDate != null) 1.5.dp else 1.dp,
                color = if (selectedExactDate != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPickExactDateClick() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = "Calendar",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (selectedExactDate != null) "Selected: ${selectedExactDate.format(dateFormatter)}"
                            else "Pick exact date",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = if (selectedExactDate != null) FontWeight.Bold else FontWeight.Medium,
                                color = if (selectedExactDate != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        )
                        Text(
                            text = if (selectedExactDate != null) "Tap to change date" else "Opens calendar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (selectedExactDate != null) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Soft secondary option: I’m not sure of the exact day
        if (!isNotSureSelected && selectedExactDate == null) {
            OutlinedButton(
                onClick = onNotSureClick,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "I’m not sure of the exact day",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Only if she chooses "I'm not sure...", show these chips:
        AnimatedVisibility(visible = isNotSureSelected) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                Text(
                    text = "Roughly when was it?",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    approximateChips.forEach { chip ->
                        val isSelected = selectedApproximateChip == chip
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                                .then(
                                    if (isSelected) Modifier.border(
                                        1.5.dp,
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(14.dp)
                                    )
                                    else Modifier.border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(14.dp)
                                    )
                                )
                                .clickable { onSelectApproximateChip(chip) }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = chip,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Question 2 (appears after the first answer)
        AnimatedVisibility(visible = lastPeriodAnswered) {
            Column {
                Text(
                    text = "And about how long does your cycle usually take?",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(12.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    cycleOptions.forEach { (label, days) ->
                        val isSelected = selectedCycleOption == label
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                                .then(
                                    if (isSelected) Modifier.border(
                                        1.5.dp,
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(14.dp)
                                    )
                                    else Modifier.border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(14.dp)
                                    )
                                )
                                .clickable { onSelectCycleOption(label, days) }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Screen 5 — Immediate Value
 */
@Composable
private fun Step5ImmediateValue(
    dayOfCycle: Int,
    remindFertileWindow: Boolean,
    onToggleRemindFertile: (Boolean) -> Unit
) {
    val phaseInsight = when {
        dayOfCycle in 10..16 -> "Your fertile window may be approaching soon."
        dayOfCycle <= 5 -> "Your body is in its natural menstrual reset phase."
        dayOfCycle in 6..9 -> "You are in your follicular phase with energy rising."
        else -> "You are moving through your luteal phase."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(76.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Headline
        Text(
            text = "That’s enough to get started.",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Dynamic insight card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "You’re roughly around Day $dayOfCycle of your cycle.",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    ),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = phaseInsight,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Reassurance
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(14.dp)
        ) {
            Text(
                text = "🌿 As you use CycleCare, we’ll gently learn your personal pattern — without asking for everything upfront.",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Optional soft question
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Would you like a gentle reminder when your fertile window may be near?",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (remindFertileWindow) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .then(
                                if (remindFertileWindow) Modifier.border(
                                    1.5.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(12.dp)
                                )
                                else Modifier.border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(12.dp)
                                )
                            )
                            .clickable { onToggleRemindFertile(true) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Yes, please",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (remindFertileWindow) FontWeight.Bold else FontWeight.Normal,
                                color = if (remindFertileWindow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (!remindFertileWindow) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .then(
                                if (!remindFertileWindow) Modifier.border(
                                    1.5.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(12.dp)
                                )
                                else Modifier.border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(12.dp)
                                )
                            )
                            .clickable { onToggleRemindFertile(false) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Not now",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (!remindFertileWindow) FontWeight.Bold else FontWeight.Normal,
                                color = if (!remindFertileWindow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }
        }
    }
}

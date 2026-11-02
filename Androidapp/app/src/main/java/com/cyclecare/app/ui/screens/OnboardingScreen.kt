package com.cyclecare.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.runtime.LaunchedEffect
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
import com.cyclecare.app.R
import com.cyclecare.app.ui.viewmodel.OnboardingViewModel
import com.cyclecare.app.ui.theme.rememberSystemMotionEnabled
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = viewModel(),
    onComplete: (
        goals: List<String>,
        lastPeriod: LocalDate,
        periodEndDate: LocalDate?,
        periodStartChoice: String,
        cycleLength: Int,
        cycleLengthOption: String,
        periodDuration: Int,
        pin: String,
        enableLock: Boolean,
        remindFertile: Boolean,
        typicalSymptoms: List<String>,
        typicalEnergy: String,
        typicalSleep: String,
        hasCompletedDeepBaseline: Boolean,
        isCloudSyncAllowed: Boolean,
        dynamicAnswers: Map<String, Set<String>>
    ) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val systemMotionEnabled = rememberSystemMotionEnabled()

    // Wire the AI question service once (local adaptive bank covers offline/guest).
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        if (viewModel.questionService == null) {
            viewModel.questionService = com.cyclecare.app.data.supabase.OnboardingQuestionService(
                com.cyclecare.app.security.AuthPreferences(context.applicationContext)
            )
        }
    }

    // Generate/adapt the dynamic questions whenever a steps 4-6 is entered.
    LaunchedEffect(uiState.step) {
        if (uiState.step in 4..6) viewModel.loadDynamicQuestionsForCurrentStep()
    }

    // Surface provides LocalContentColor so text without an explicit color
    // stays readable on the themed background in dark and light mode.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .testTag("onboarding_screen"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Nav Indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (uiState.step in 2..6) {
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
                transitionSpec = {
                    val duration = if (systemMotionEnabled) 180 else 0
                    fadeIn(animationSpec = tween(duration)) togetherWith fadeOut(animationSpec = tween(duration))
                },
                label = "onboarding_step_transition"
            ) { currentStep ->
                when (currentStep) {
                    1 -> Step1Welcome()
                    2 -> Step2PrivacyFirst(
                        isAgeVerified = uiState.isAgeVerified,
                        onAgeVerifiedChange = { viewModel.setAgeVerified(it) },
                        isTermsAgreed = uiState.isTermsAgreed,
                        onTermsAgreedChange = { viewModel.setTermsAgreed(it) },
                        isCloudSyncAllowed = uiState.isCloudSyncAllowed,
                        onCloudSyncAllowedChange = { viewModel.setCloudSyncAllowed(it) },
                        onOpenTerms = { viewModel.setShowTermsDialog(true) },
                        onOpenPrivacy = { viewModel.setShowPrivacyDialog(true) }
                    )
                    3 -> Step3SoftGoalSelection(
                        selectedGoals = uiState.selectedGoals,
                        onToggle = { goal -> viewModel.toggleGoal(goal) }
                    )
                    4 -> Step4InteractiveRhythmQuest(
                        selectedGoals = uiState.selectedGoals,
                        showCycleQuestions = uiState.needsCycleFollowup,
                        showPeriodQuestion = uiState.needsPeriodFollowup,
                        showSymptomQuestion = uiState.needsSymptomFollowup,
                        selectedSymptoms = uiState.selectedTypicalSymptoms,
                        onToggleSymptom = { viewModel.toggleTypicalSymptom(it) },
                        selectedExactStartDate = uiState.selectedExactDate,
                        onPickExactStartDateClick = { viewModel.setDatePickerDialogVisible(true) },
                        selectedApproximateChip = uiState.selectedApproximateChip,
                        onSelectApproximateChip = { viewModel.selectApproximateChip(it) },
                        selectedExactEndDate = uiState.selectedPeriodEndDate,
                        onPickExactEndDateClick = { viewModel.setEndDatePickerDialogVisible(true) },
                        selectedPeriodDurationOption = uiState.selectedPeriodDurationOption,
                        periodDurationDays = uiState.periodDurationDays,
                        isPeriodOngoing = uiState.isPeriodOngoing,
                        onSelectPeriodDuration = { option, days, isOngoing -> viewModel.selectPeriodDurationOption(option, days, isOngoing) },
                        selectedCycleOption = uiState.selectedCycleOption,
                        cycleLengthDays = uiState.cycleLengthDays,
                        onSelectCycleOption = { label, days -> viewModel.selectCycleOption(label, days) },
                        hasSelectedCycleLength = uiState.selectedCycleOption.isNotBlank(),
                        hasCycleStartEstimate = uiState.hasCycleStartEstimate,
                        effectiveStartDate = uiState.effectiveLastPeriodDate,
                        effectiveEndDate = uiState.effectivePeriodEndDate,
                        dynamicSection = {
                            DynamicQuestionSection(
                                step = uiState.step,
                                questions = uiState.dynamicQuestions[uiState.step].orEmpty(),
                                answers = uiState.dynamicAnswers,
                                isLoading = uiState.isLoadingDynamicQuestions,
                                onToggle = { id, option, multi -> viewModel.toggleDynamicAnswer(id, option, multi) }
                            )
                        }
                    )
                    5 -> Step5ImmediateValue(
                        remindFertileWindow = uiState.remindFertileWindow,
                        showFertilityReminder = uiState.needsFertilityFollowup,
                        onToggleRemindFertile = { viewModel.setRemindFertile(it) },
                        dynamicSection = {
                            DynamicQuestionSection(
                                step = uiState.step,
                                questions = uiState.dynamicQuestions[uiState.step].orEmpty(),
                                answers = uiState.dynamicAnswers,
                                isLoading = uiState.isLoadingDynamicQuestions,
                                onToggle = { id, option, multi -> viewModel.toggleDynamicAnswer(id, option, multi) }
                            )
                        }
                    )
                    6 -> Step6LearnAboutMe(
                        selectedSymptoms = uiState.selectedTypicalSymptoms,
                        onToggleSymptom = { viewModel.toggleTypicalSymptom(it) },
                        showSymptomSection = !uiState.needsSymptomFollowup,
                        selectedEnergy = uiState.selectedTypicalEnergy,
                        onSelectEnergy = { viewModel.selectTypicalEnergy(it) },
                        selectedSleep = uiState.selectedTypicalSleep,
                        onSelectSleep = { viewModel.selectTypicalSleep(it) },
                        dynamicSection = {
                            DynamicQuestionSection(
                                step = uiState.step,
                                questions = uiState.dynamicQuestions[uiState.step].orEmpty(),
                                answers = uiState.dynamicAnswers,
                                isLoading = uiState.isLoadingDynamicQuestions,
                                onToggle = { id, option, multi -> viewModel.toggleDynamicAnswer(id, option, multi) }
                            )
                        }
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
                            viewModel.confirmedPeriodEndDate(),
                            uiState.periodStartChoice,
                            uiState.cycleLengthDays,
                            uiState.selectedCycleOption,
                            uiState.periodDurationDays,
                            "",
                            false,
                            uiState.remindFertileWindow == true,
                            uiState.selectedTypicalSymptoms.toList(),
                            uiState.selectedTypicalEnergy,
                            uiState.selectedTypicalSleep,
                            uiState.hasCompletedDeepBaseline,
                            uiState.isCloudSyncAllowed,
                            uiState.dynamicAnswers
                        )
                    }
                },
                enabled = isButtonEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
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
                        5 -> "Calibrate my pattern"
                        6 -> "Start using CycleCare"
                        else -> "Continue"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = if (uiState.step == 6) Icons.Default.Check else Icons.AutoMirrored.Filled.ArrowForward,
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

    // Unusual period-length confirmation popup
    uiState.periodDateDialogMessage?.let { message ->
        PeriodDateConfirmDialog(
            message = message,
            onConfirm = { viewModel.confirmPeriodEndDate() },
            onEdit = { viewModel.editPeriodEndDate() }
        )
    }

    // Material 3 Date Picker Dialog for Period End Date
    if (uiState.showEndDatePickerDialog) {
        val endDatePickerState = rememberDatePickerState(
            initialSelectedDateMillis = System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { viewModel.setEndDatePickerDialogVisible(false) },
            confirmButton = {
                TextButton(
                    onClick = {
                        endDatePickerState.selectedDateMillis?.let { millis ->
                            val localDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                            viewModel.setExactEndDate(localDate)
                        } ?: viewModel.setEndDatePickerDialogVisible(false)
                    }
                ) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setEndDatePickerDialogVisible(false) }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = endDatePickerState)
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
                "Private by Default" to "Your cycle, symptom, and mood data are stored safely on your device. We never sell personal health records to third-party advertisers.",
                "Zero Ad Tracking" to "We never track you across other applications or monetize your private health data.",
                "Secure Protection" to "Your personal cycle information remains strictly confidential under your control.",
                "Complete Data Control" to "You can erase all your records at any time directly in CycleCare settings."
            ),
            onDismiss = { viewModel.setShowPrivacyDialog(false) }
        )
    }
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
    isCloudSyncAllowed: Boolean,
    onCloudSyncAllowedChange: (Boolean) -> Unit,
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
            color = Color(0xFFD5EBDD),
            modifier = Modifier.size(68.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = Color(0xFF4D6B58),
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

                Spacer(modifier = Modifier.height(12.dp))

                // Cloud Backup & Device Sync Preference (Asked once during onboarding)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .testTag("checkbox_cloud_sync_row")
                ) {
                    Checkbox(
                        checked = isCloudSyncAllowed,
                        onCheckedChange = { onCloudSyncAllowedChange(it) },
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("checkbox_cloud_sync")
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "Online backup",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.clickable { onCloudSyncAllowedChange(!isCloudSyncAllowed) }
                        )
                        Text(
                            text = "Online backup is off in this version, so your records stay on this device. Your choice is saved for when backup becomes available.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Unusual period-length confirmation: keeps the user's real entry on confirm,
 * or lets them fix the dates. The record is only kept after save succeeds.
 */
@Composable
private fun PeriodDateConfirmDialog(
    message: String,
    onConfirm: () -> Unit,
    onEdit: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* A choice is required: keep or edit. */ },
        title = {
            Text(
                text = "Please check your dates",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("period_dates_confirm_button")
            ) {
                Text("Dates are correct", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onEdit,
                modifier = Modifier.testTag("period_dates_edit_button")
            ) {
                Text("Edit dates")
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
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
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            // Explicit full-strength color: the default inherited content color was
            // rendering this heading nearly invisible on the dark onboarding backdrop.
            color = MaterialTheme.colorScheme.onSurface
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
 * Screen 4 — Interactive Rhythm Quest (Game-like, Tactile, Zero Text Clutter)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Step4InteractiveRhythmQuest(
    selectedGoals: Set<String>,
    showCycleQuestions: Boolean,
    showPeriodQuestion: Boolean,
    showSymptomQuestion: Boolean,
    selectedSymptoms: Set<String>,
    onToggleSymptom: (String) -> Unit,
    selectedExactStartDate: LocalDate?,
    onPickExactStartDateClick: () -> Unit,
    selectedApproximateChip: String?,
    onSelectApproximateChip: (String) -> Unit,
    selectedExactEndDate: LocalDate?,
    onPickExactEndDateClick: () -> Unit,
    selectedPeriodDurationOption: String,
    periodDurationDays: Int,
    isPeriodOngoing: Boolean,
    onSelectPeriodDuration: (String, Int, Boolean) -> Unit,
    selectedCycleOption: String,
    cycleLengthDays: Int,
    onSelectCycleOption: (String, Int) -> Unit,
    hasSelectedCycleLength: Boolean,
    hasCycleStartEstimate: Boolean,
    effectiveStartDate: LocalDate,
    effectiveEndDate: LocalDate,
    dynamicSection: (@Composable () -> Unit)? = null
) {
    val shortDateFormatter = DateTimeFormatter.ofPattern("MMM d")

    val startChips = listOf(
        "Today" to "Today",
        "A few days ago" to "3–5 days ago",
        "About a week ago" to "6–8 days ago",
        "About two weeks ago" to "13–15 days ago",
        "I don’t know" to "Not sure yet"
    )

    val durationChips = listOf(
        "3 days" to 3,
        "4 days" to 4,
        "5 days" to 5,
        "6 days" to 6,
        "7+ days" to 7
    )

    val cycleOptions = listOf(
        "24 days" to 24,
        "26 days" to 26,
        "28 days" to 28,
        "30 days" to 30,
        "32 days" to 32,
        "35+ days" to 35,
        "It changes" to 28,
        "I don’t know" to 28
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("step_4_interactive_quest")
    ) {
        if (dynamicSection != null) {
            dynamicSection()
            Spacer(modifier = Modifier.height(18.dp))
        }
        // Quest Header
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 6.dp)
        ) {
            Text(
                text = "Step 4 of 6 • Your Rhythm Baseline",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        Text(
            text = "A few optional details",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (showCycleQuestions || showPeriodQuestion || showSymptomQuestion) {
                "These questions follow the goals you selected. Estimates are welcome; exact dates are optional, and you can leave anything blank."
            } else {
                "No extra details are needed for the goals you chose. You can continue and update your preferences later."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Preview only after the user has chosen the values it would display.
        if (hasCycleStartEstimate && hasSelectedCycleLength && selectedPeriodDurationOption.isNotBlank()) {
            Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Interactive dynamic multi-segment track
                val bleedWeight = periodDurationDays.toFloat().coerceAtLeast(1f)
                val follicularWeight = ((cycleLengthDays - 14 - 4) - periodDurationDays).coerceAtLeast(3).toFloat()
                val fertileWeight = 6f
                val lutealWeight = (cycleLengthDays - (cycleLengthDays - 14 + 2)).coerceAtLeast(5).toFloat()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .weight(bleedWeight)
                            .fillMaxSize()
                            .background(Color(0xFF803A46))
                    )
                    Box(
                        modifier = Modifier
                            .weight(follicularWeight)
                            .fillMaxSize()
                            .background(Color(0xFF4D6B58))
                    )
                    Box(
                        modifier = Modifier
                            .weight(fertileWeight)
                            .fillMaxSize()
                            .background(Color(0xFFC89D54))
                    )
                    Box(
                        modifier = Modifier
                            .weight(lutealWeight)
                            .fillMaxSize()
                            .background(Color(0xFF6E2E3B))
                    )
                }

                Text(
                    text = if (hasCycleStartEstimate) {
                        "Calendar-only estimate from your selected dates and settings. It does not confirm ovulation or create past health logs."
                    } else {
                        "This diagram is illustrative. No personal cycle estimate or past health logs will be created until you add information."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Phase Legend & Live Equalizer Values
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = Color(0xFF803A46), modifier = Modifier.size(8.dp)) {}
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Period ${periodDurationDays}d",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = Color(0xFFC89D54), modifier = Modifier.size(8.dp)) {}
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Estimated fertile timing ~Day ${cycleLengthDays - 14}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = Color(0xFF6E2E3B), modifier = Modifier.size(8.dp)) {}
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Cycle ${cycleLengthDays}d",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Cycle-date questions appear only for cycle, period, or fertility goals.
        if (showCycleQuestions) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "1. About when did your last period start?",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Text(
                    text = "Choose the range closest to when you remember it. Calendar estimates use a representative day; an exact date is optional. Choose Not sure yet if you prefer no estimate.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    startChips.forEach { (chipKey, chipLabel) ->
                        val isSelected = (selectedExactStartDate == null && selectedApproximateChip == chipKey)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { onSelectApproximateChip(chipKey) }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = chipLabel,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Calendar exact pick pill
                    val isExactSelected = (selectedExactStartDate != null)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isExactSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .border(
                                width = if (isExactSelected) 1.5.dp else 1.dp,
                                color = if (isExactSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { onPickExactStartDateClick() }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = "Calendar",
                                tint = if (isExactSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (selectedExactStartDate != null) selectedExactStartDate.format(shortDateFormatter) else "Calendar",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isExactSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isExactSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            )
                            if (isExactSelected) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quest 2: End Date / Duration
        if (showPeriodQuestion) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "2. About when did your last period end?",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Ongoing pill
                    val isOngoingSelected = isPeriodOngoing
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isOngoingSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .border(
                                width = if (isOngoingSelected) 1.5.dp else 1.dp,
                                color = if (isOngoingSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                val currentDay = (ChronoUnit.DAYS.between(effectiveStartDate, LocalDate.now()) + 1).toInt().coerceIn(1, 10)
                                onSelectPeriodDuration("Still ongoing", currentDay, true)
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "🔴 Bleeding today",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isOngoingSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isOngoingSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            )
                            if (isOngoingSelected) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // Duration pills
                    durationChips.forEach { (label, days) ->
                        val isSelected = !isPeriodOngoing && selectedExactEndDate == null && selectedPeriodDurationOption == label
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { onSelectPeriodDuration(label, days, false) }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // End Date Calendar pick pill
                    val isEndCalendarSelected = (selectedExactEndDate != null && !isPeriodOngoing)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isEndCalendarSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .border(
                                width = if (isEndCalendarSelected) 1.5.dp else 1.dp,
                                color = if (isEndCalendarSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { onPickExactEndDateClick() }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = "Calendar",
                                tint = if (isEndCalendarSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (selectedExactEndDate != null) selectedExactEndDate.format(shortDateFormatter) else "Pick end date",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isEndCalendarSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isEndCalendarSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            )
                            if (isEndCalendarSelected) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        }

        if (showSymptomQuestion) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Which experiences do you usually notice?",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Choose any that fit, or leave this blank. You can change these later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "Cramps", "Headaches", "Tiredness", "Mood changes", "Bloating",
                            "Breast tenderness", "Back pain", "Acne", "Appetite changes",
                            "Nothing noticeable", "I'm not sure"
                        ).forEach { experience ->
                            val isSelected = selectedSymptoms.contains(experience)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                    .border(
                                        if (isSelected) 1.5.dp else 1.dp,
                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(14.dp)
                                    )
                                    .clickable { onToggleSymptom(experience) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = experience,
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

        // Quest 3: Cycle Length
        if (showCycleQuestions) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "3. About how long is your cycle?",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

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
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { onSelectCycleOption(label, days) }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

/**
 * Screen 5 — Immediate Value
 */
@Composable
private fun Step5ImmediateValue(
    remindFertileWindow: Boolean?,
    showFertilityReminder: Boolean,
    onToggleRemindFertile: (Boolean) -> Unit,
    dynamicSection: (@Composable () -> Unit)? = null
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
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        if (dynamicSection != null) {
            dynamicSection()
            Spacer(modifier = Modifier.height(18.dp))
        }
        Text(
            text = "Your profile is ready to grow with you.",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "CycleCare will use the preferences you chose and learn from entries you decide to make. You do not need to guess dates or fill in anything you are unsure about.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (showFertilityReminder) {
            Spacer(modifier = Modifier.height(20.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Would you like a reminder when your estimated fertile window may be near?",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf("Yes, please" to true, "No, thanks" to false).forEach { (label, value) ->
                            val selected = remindFertileWindow == value
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                    .border(
                                        if (selected) 1.5.dp else 1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { onToggleRemindFertile(value) }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Screen 6 — "Learn About Me" Progressive Calibration
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Step6LearnAboutMe(
    selectedSymptoms: Set<String>,
    onToggleSymptom: (String) -> Unit,
    showSymptomSection: Boolean,
    selectedEnergy: String,
    onSelectEnergy: (String) -> Unit,
    selectedSleep: String,
    onSelectSleep: (String) -> Unit,
    dynamicSection: (@Composable () -> Unit)? = null
) {
    val symptomOptions = listOf(
        "Cramps",
        "Headaches",
        "Tiredness",
        "Mood changes",
        "Bloating",
        "Breast tenderness",
        "Back pain",
        "Acne",
        "Appetite changes",
        "Nothing noticeable",
        "I'm not sure"
    )

    val energyOptions = listOf(
        "Steady & High" to "HIGH",
        "Moderate / Balanced" to "NORMAL",
        "Dips pre-period" to "LOW",
        "Fluctuates often" to "VARIABLE"
    )

    val sleepOptions = listOf(
        "Generally deep & restful" to "GOOD",
        "Restless before period" to "FAIR",
        "Hard to fall asleep" to "POOR",
        "Variable" to "VARIABLE"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("step_6_learn_about_me")
    ) {
        if (dynamicSection != null) {
            dynamicSection()
            Spacer(modifier = Modifier.height(18.dp))
        }
        Text(
            text = "Your personal baseline",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Choose only what feels useful. Leaving anything blank is fine.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))

        // Repeated only when no symptom follow-up was shown in Step 4.
        if (showSymptomSection) {
        Text(
            text = "What do you usually notice around your period?",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(10.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            symptomOptions.forEach { symptom ->
                val isSelected = selectedSymptoms.contains(symptom)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                        .then(
                            if (isSelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                            else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                        )
                        .clickable { onToggleSymptom(symptom) }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = symptom,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        }

        }

        Spacer(modifier = Modifier.height(22.dp))

        // Section 2: Energy rhythm
        Text(
            text = "How about your typical energy rhythm?",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(10.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            energyOptions.forEach { (label, key) ->
                val isSelected = selectedEnergy == key
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                        .then(
                            if (isSelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                            else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                        )
                        .clickable { onSelectEnergy(key) }
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

        Spacer(modifier = Modifier.height(22.dp))

        // Section 3: Sleep quality
        Text(
            text = "And your usual sleep pattern?",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(10.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sleepOptions.forEach { (label, key) ->
                val isSelected = selectedSleep == key
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                        .then(
                            if (isSelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                            else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                        )
                        .clickable { onSelectSleep(key) }
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

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Dynamically generated questions for steps 4-6 (AI-backed, local fallback).
 * Questions adapt to step-3 goals and every answer given so far.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DynamicQuestionSection(
    step: Int,
    questions: List<com.cyclecare.app.domain.onboarding.OnboardingQuestion>,
    answers: Map<String, Set<String>>,
    isLoading: Boolean,
    onToggle: (String, String, Boolean) -> Unit
) {
    if (questions.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().testTag("dynamic_questions_step_$step")) {
        questions.forEach { question ->
            Text(
                text = question.prompt,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                question.options.forEach { option ->
                    val selected = answers[question.id]?.contains(option) == true
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                            .clickable { onToggle(question.id, option, question.multiSelect) }
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                            .testTag("dynamic_option_${question.id}")
                    ) {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        if (isLoading) {
            Text(
                text = "Personalizing your questions…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

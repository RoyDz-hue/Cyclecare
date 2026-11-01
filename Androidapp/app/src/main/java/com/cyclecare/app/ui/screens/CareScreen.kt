package com.cyclecare.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import coil.compose.AsyncImage
import com.cyclecare.app.domain.ai.CareImageStyle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyclecare.app.data.model.SafetyLevel
import com.cyclecare.app.ui.components.CareConversationMessage
import com.cyclecare.app.ui.viewmodel.CareChatTurn
import com.cyclecare.app.ui.viewmodel.CycleCareViewModel
import com.cyclecare.app.ui.viewmodel.CycleUiState

internal const val SAFETY_LIMITATION_NOTICE = "Automated checks can miss important symptoms, especially in languages other than English. No warning does not mean you are safe. Seek urgent local medical help for severe or worsening symptoms; do not wait for an app or online response."

@Composable
fun CareScreen(
    viewModel: CycleCareViewModel,
    uiState: CycleUiState,
    modifier: Modifier = Modifier
) {
    CareScreenContent(
        uiState = uiState,
        onConsultCare = viewModel::consultCare,
        onEditCareLog = viewModel::editCareLog,
        onEnableAiProcessing = viewModel::enableAiProcessingForAllProviders,
        onRefreshAiManifest = viewModel::refreshAiProcessorManifest,
        onGenerateCareImage = viewModel::generateCareImage,
        onOpenVault = { onOpenVault() },
        modifier = modifier
    )
}

/** Pure state-and-events Care screen: renderable without a ViewModel. */
@Composable
fun CareScreenContent(
    uiState: CycleUiState,
    onConsultCare: (String) -> Unit,
    onEditCareLog: (String) -> Unit,
    onEnableAiProcessing: () -> Unit,
    onRefreshAiManifest: () -> Unit,
    onGenerateCareImage: (CareImageStyle, String) -> Unit,
    onOpenVault: () -> Unit,
    modifier: Modifier = Modifier
) {
    var queryText by remember(uiState.localDataOwnerKey) { mutableStateOf("") }
    var aiConsentDismissed by remember(uiState.localDataOwnerKey) { mutableStateOf(false) }
    var showImagePaywall by remember(uiState.localDataOwnerKey) { mutableStateOf(false) }
    var editingDraft by remember(uiState.localDataOwnerKey) { mutableStateOf(false) }
    var editRawText by remember(uiState.localDataOwnerKey) { mutableStateOf("") }
    var submittedQuestion by remember(uiState.localDataOwnerKey) { mutableStateOf<String?>(null) }
    var showImageDialog by remember(uiState.localDataOwnerKey) { mutableStateOf(false) }
    var customPrompt by remember(uiState.localDataOwnerKey) { mutableStateOf("") }
    var imageStyle by remember(uiState.localDataOwnerKey) { mutableStateOf(CareImageStyle.BOTANICAL) }
    val listState = rememberLazyListState()
    val turns = uiState.careChatHistory
    val premiumOrAdmin = uiState.isAdmin || uiState.economyProfile.tier == com.cyclecare.app.data.economy.UserEconomyTier.PREMIUM
    val canSend = queryText.trim().isNotEmpty() && !uiState.isCareLoading && queryText.length <= 1200

    LaunchedEffect(turns.size, uiState.isCareLoading) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1)
        if (submittedQuestion != null && turns.lastOrNull()?.userQuery == submittedQuestion) {
            queryText = ""
            submittedQuestion = null
        }
    }

    Column(
        modifier = modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().testTag("care_screen_content")
    ) {
        CareHeader(
            uiState = uiState,
            onClear = viewModel::clearCareChatHistory,
            onCreateImage = { showImageDialog = true }
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 92.dp)
        ) {
            if (!uiState.isAiSharingAllowed && !aiConsentDismissed) {
                item(key = "ai_consent") {
                    AiConsentCard(
                        onAllow = {
                            onRefreshAiManifest()
                            onEnableAiProcessing()
                        },
                        onNotNow = { aiConsentDismissed = true }
                    )
                }
            }
            if (uiState.safetyEvaluation.level != SafetyLevel.LOW) {
                item(key = "safety") { SafetyNotice(uiState) }
            }
            if (turns.isEmpty() && !uiState.isCareLoading) {
                item(key = "welcome") { EmptyCareState(onPrompt = { queryText = it }) }
            }
            items(turns, key = { it.id }) { turn ->
                CareConversationMessage(
                    userText = turn.userQuery,
                    assistantSummary = turn.response.summary,
                    assistantDetail = listOf(turn.response.whatText, turn.response.understandText,
                        turn.response.careText, turn.response.watchText, turn.response.escalateText)
                        .filter { it.isNotBlank() }.distinct().joinToString("\n\n"),
                    source = turn.response.source,
                    modifier = Modifier.testTag("care_turn_${turn.id}"),
                    onClick = { queryText = turn.userQuery }
                )
            }
            if (uiState.isCareLoading) {
                item(key = "loading") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("CycleCare is thinking…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
            uiState.pendingCareLog?.let { draft ->
                item(key = "pending-${uiState.pendingCareTurnId ?: "draft"}") {
                    PendingLogReview(
                        rawText = if (editingDraft) editRawText else draft.rawText,
                        itemsText = draft.items.joinToString(" · ") { "${it.label}: ${it.detail}" },
                        editing = editingDraft,
                        onStartEdit = { editRawText = draft.rawText; editingDraft = true },
                        onEditChange = { editRawText = it.take(1200) },
                        onCancelEdit = { editingDraft = false },
                        onApplyEdit = { onEditCareLog(editRawText.trim()); editingDraft = false },
                        onCancel = viewModel::cancelCareLog,
                        onConfirm = viewModel::confirmCareLog
                    )
                }
            }
            uiState.careSavedReviewMessage?.let { message ->
                item(key = "saved-review") { NoticeCard(message) }
            }
        }

        Composer(
            queryText = queryText,
            onQueryChange = { queryText = it.take(1200) },
            canSend = canSend,
            isLoading = uiState.isCareLoading,
            onSend = {
                val question = queryText.trim()
                if (question.isNotEmpty() && !uiState.isCareLoading && question.length <= 1200) {
                    onConsultCare(question)
                    submittedQuestion = question
                }
            }
        )
    }
    if (showImageDialog) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { if (!uiState.isGeneratingCareImage) showImageDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 600.dp),
                shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Create a quiet illustration", style = MaterialTheme.typography.titleLarge)
                    Column(modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Decorative art, not a health insight. Only this prompt and style are sent — never your chat or health records.", style = MaterialTheme.typography.bodySmall)
                    CareImageStyle.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pair.forEach { style ->
                                TextButton(onClick = { imageStyle = style }, enabled = !uiState.isGeneratingCareImage) {
                                    Text(if (imageStyle == style) "• ${style.title}" else style.title)
                                }
                            }
                        }
                    }
                    OutlinedTextField(value = customPrompt, onValueChange = { customPrompt = it.take(500) },
                        label = { Text("Your custom prompt (optional)") }, placeholder = { Text("Misty mountains with soft green and gold") },
                        minLines = 2, maxLines = 4, enabled = !uiState.isGeneratingCareImage,
                        supportingText = { Text("${customPrompt.length}/500 · No personal or health details") },
                        modifier = Modifier.fillMaxWidth().testTag("care_custom_image_prompt"))
                    if (uiState.isGeneratingCareImage) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Creating your illustration…", modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    uiState.careImageError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    uiState.generatedCareImage?.let { bytes ->
                        AsyncImage(model = bytes, contentDescription = "Your generated decorative illustration", modifier = Modifier.fillMaxWidth().height(220.dp))
                    }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showImageDialog = false }, enabled = !uiState.isGeneratingCareImage) { Text("Close") }
                        Button(onClick = {
                            if (premiumOrAdmin) onGenerateCareImage(imageStyle, customPrompt.trim())
                            else showImagePaywall = true
                        },
                            enabled = !uiState.isGeneratingCareImage,
                            modifier = Modifier.weight(1f).testTag("care_generate_image")) { Text("Generate image") }
                    }
                }
            }
        }
    }

    if (showImagePaywall) {
        AlertDialog(
            onDismissRequest = { showImagePaywall = false },
            title = { Text("CycleCare Premium") },
            text = { Text("Image creation is part of Premium. Opening plan details does not start a payment.") },
            confirmButton = {
                TextButton(onClick = {
                    showImagePaywall = false
                    showImageDialog = false
                    onOpenVault()
                }) { Text("View plans") }
            },
            dismissButton = { TextButton(onClick = { showImagePaywall = false }) { Text("Not now") } }
        )
    }

}

@Composable
private fun AiConsentCard(
    onAllow: () -> Unit,
    onNotNow: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("care_ai_consent_card")
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enable expert answers", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            Text(
                "CycleCare can answer with expert guidance online. Your question and relevant cycle context are sent securely, only when you chat. It's tied to this account and you can turn it off anytime in Settings.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onNotNow, modifier = Modifier.testTag("care_ai_consent_dismiss")) { Text("Not now") }
                Button(onClick = onAllow, modifier = Modifier.testTag("care_ai_consent_allow")) { Text("Allow expert answers") }
            }
        }
    }
}

@Composable
private fun CareHeader(
    uiState: CycleUiState,
    onClear: () -> Unit,
    onCreateImage: () -> Unit
) {
    // Backend mechanics (providers, routing, consent detail) deliberately live
    // in Settings, not on the chat surface.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Spa, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text("Ask CycleCare", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            Text("Private, calm guidance—not a diagnosis", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onCreateImage, modifier = Modifier.testTag("care_open_image_creator")) { Text("Create") }
        if (uiState.careChatHistory.isNotEmpty()) {
            IconButton(onClick = onClear, modifier = Modifier.testTag("care_clear_history")) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Clear conversation")
            }
        }
    }
}

@Composable
private fun EmptyCareState(onPrompt: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("What would you like to understand?", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
        Text("Start with a question in your own words.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        listOf("Why am I tired before my period?", "How can I ease period cramps?", "Is spotting between periods normal?").forEach { prompt ->
            TextButton(onClick = { onPrompt(prompt) }, modifier = Modifier.testTag("care_suggestion")) { Text(prompt) }
        }
    }
}

@Composable
private fun Composer(
    queryText: String,
    onQueryChange: (String) -> Unit,
    canSend: Boolean,
    isLoading: Boolean,
    onSend: () -> Unit
) {
    // Persistent bottom input bar. Relevant cycle context is attached
    // automatically by the ViewModel - no user-facing routing controls.
    Surface(shadowElevation = 4.dp, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = queryText,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("care_query_input"),
            placeholder = { Text("Message CycleCare") },
            minLines = 1,
            maxLines = 6,
            supportingText = { Text("${queryText.length}/1200") },
            trailingIcon = {
                IconButton(onClick = onSend, enabled = canSend, modifier = Modifier.testTag("care_submit_button")) {
                    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
private fun PendingLogReview(
    rawText: String,
    itemsText: String,
    editing: Boolean,
    onStartEdit: () -> Unit,
    onEditChange: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onApplyEdit: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("care_pending_log_review"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Review before saving", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            Text("Nothing has been added to your health log yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            if (editing) {
                OutlinedTextField(value = rawText, onValueChange = onEditChange, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("care_edit_raw_text"), minLines = 2, maxLines = 6, label = { Text("Edit your note") })
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { TextButton(onClick = onCancelEdit) { Text("Cancel") }; Button(onClick = onApplyEdit, enabled = rawText.trim().isNotEmpty()) { Text("Apply edit") } }
            } else {
                Text(itemsText.ifBlank { rawText }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { TextButton(onClick = onCancel) { Text("Dismiss") }; TextButton(onClick = onStartEdit) { Text("Edit") }; Button(onClick = onConfirm) { Text("Save to log") } }
            }
        }
    }
}

@Composable
private fun SafetyNotice(uiState: CycleUiState) {
    val safety = uiState.safetyEvaluation
    Card(colors = CardDefaults.cardColors(containerColor = if (safety.isRedFlag) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Warning, contentDescription = "Safety notice", tint = if (safety.isRedFlag) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(safety.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                Text(safety.message, style = MaterialTheme.typography.bodySmall)
                Text(safety.actionAdvice, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun NoticeCard(message: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
    }
}


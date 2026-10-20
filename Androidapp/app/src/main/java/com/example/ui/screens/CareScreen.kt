package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import coil.compose.AsyncImage
import com.example.domain.ai.CareImageStyle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import com.example.data.model.SafetyLevel
import com.example.ui.components.CareConversationMessage
import com.example.ui.viewmodel.CareChatTurn
import com.example.ui.viewmodel.CycleCareViewModel
import com.example.ui.viewmodel.CycleUiState

internal const val SAFETY_LIMITATION_NOTICE = "Automated checks can miss important symptoms, especially in languages other than English. No warning does not mean you are safe. Seek urgent local medical help for severe or worsening symptoms; do not wait for an app or AI response."

@Composable
fun CareScreen(
    viewModel: CycleCareViewModel,
    uiState: CycleUiState,
    modifier: Modifier = Modifier
) {
    var queryText by remember(uiState.localDataOwnerKey) { mutableStateOf("") }
    var includeFullContext by remember(uiState.localDataOwnerKey) { mutableStateOf(false) }
    var showAiSharingDetails by remember(uiState.localDataOwnerKey) { mutableStateOf(false) }
    var editingDraft by remember(uiState.localDataOwnerKey) { mutableStateOf(false) }
    var editRawText by remember(uiState.localDataOwnerKey) { mutableStateOf("") }
    var submittedQuestion by remember(uiState.localDataOwnerKey) { mutableStateOf<String?>(null) }
    var showImageDialog by remember(uiState.localDataOwnerKey) { mutableStateOf(false) }
    var customPrompt by remember(uiState.localDataOwnerKey) { mutableStateOf("") }
    var imageStyle by remember(uiState.localDataOwnerKey) { mutableStateOf(CareImageStyle.BOTANICAL) }
    val listState = rememberLazyListState()
    val turns = uiState.careChatHistory
    val premiumOrAdmin = uiState.isAdmin || uiState.economyProfile.tier == com.example.data.economy.UserEconomyTier.PREMIUM
    val canSend = queryText.trim().isNotEmpty() && !uiState.isCareLoading && queryText.length <= 1200

    // Preview is deliberately driven by the exact current question, not by consent.
    LaunchedEffect(queryText, includeFullContext, premiumOrAdmin) {
        if (includeFullContext && premiumOrAdmin && queryText.isNotBlank()) {
            viewModel.previewCareContext(queryText.trim())
        }
    }
    LaunchedEffect(turns.size, uiState.isCareLoading) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1)
        if (submittedQuestion != null && turns.lastOrNull()?.userQuery == submittedQuestion) {
            queryText = ""
            submittedQuestion = null
        }
    }

    Column(
        modifier = modifier.fillMaxSize().imePadding().testTag("care_screen_content")
    ) {
        CareHeader(
            uiState = uiState,
            aiSharingExpanded = showAiSharingDetails,
            onAiSharingExpanded = { showAiSharingDetails = !showAiSharingDetails },
            onAiProviderSharingChanged = viewModel::setAiProviderSharingAllowed,
            onClear = viewModel::clearCareChatHistory,
            onCreateImage = { showImageDialog = true }
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 12.dp)
        ) {
            item(key = "safety_limitations") {
                Text(
                    SAFETY_LIMITATION_NOTICE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("care_safety_limitations")
                )
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
                        onApplyEdit = { viewModel.editCareLog(editRawText.trim()); editingDraft = false },
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
            includeFullContext = includeFullContext,
            contextAllowed = premiumOrAdmin,
            contextPreview = uiState.careContextPreview,
            onContextChange = { includeFullContext = it },
            onSend = {
                val question = queryText.trim()
                if (question.isNotEmpty() && !uiState.isCareLoading && question.length <= 1200) {
                    viewModel.consultCare(question, includeFullContext && premiumOrAdmin)
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
                    Text("Decorative art, not a health insight. Generating sends only this prompt and style to the image provider—not your chat or health records.", style = MaterialTheme.typography.bodySmall)
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
                    if (!premiumOrAdmin) Text("Image creation requires Premium. Tracking, safety, privacy, export and deletion stay free.", style = MaterialTheme.typography.bodySmall)
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
                        Button(onClick = { viewModel.generateCareImage(imageStyle, customPrompt.trim()) },
                            enabled = premiumOrAdmin && !uiState.isGeneratingCareImage,
                            modifier = Modifier.weight(1f).testTag("care_generate_image")) { Text("Generate image") }
                    }
                }
            }
        }
    }

}

@Composable
private fun CareHeader(
    uiState: CycleUiState,
    aiSharingExpanded: Boolean,
    onAiSharingExpanded: () -> Unit,
    onAiProviderSharingChanged: (String, Boolean) -> Unit,
    onClear: () -> Unit,
    onCreateImage: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("Allow AI processing", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onAiSharingExpanded) { Text(if (aiSharingExpanded) "Hide details" else "What is shared?") }
        }
        if (aiSharingExpanded) {
            Text(
                "Choose each processor separately. Consent is off by default, tied to this account, and can be revoked at any time. CycleCare does not promise provider-side retention or deletion.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            val manifest = uiState.aiProcessorManifest
            if (manifest == null) Text("Provider availability is unknown until the authenticated server manifest loads. Online AI stays off.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            val entries = manifest?.providers ?: listOf("COHERE" to "Cohere", "GEMINI" to "Google Gemini", "OPENROUTER" to "OpenRouter").map { (id, name) ->
                com.example.domain.ai.AiProcessor(id, name, "Unknown", "", "", emptyList(), false, "Manifest unavailable", false, 0)
            }
            entries.forEach { entry ->
                val provider = entry.id
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${entry.displayName} · ${entry.model}", style = MaterialTheme.typography.bodySmall)
                        if (!entry.eligible) Text(entry.unavailableReason.ifBlank { "Unavailable" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        entry.processors.forEach { processor -> Text("${processor.name}: ${processor.policyUrl}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Switch(
                        checked = uiState.aiProviderConsents[provider] == true,
                        onCheckedChange = { onAiProviderSharingChanged(provider, it) },
                        enabled = entry.eligible && manifest?.version == 3,
                        modifier = Modifier.testTag("care_ai_consent_$provider")
                    )
                }
            }
        }
        if (uiState.authMode == "ANONYMOUS") {
            Text("Limited account mode: conversation is local to this screen until the end of this session; some features may be unavailable.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
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
    includeFullContext: Boolean,
    contextAllowed: Boolean,
    contextPreview: String?,
    onContextChange: (Boolean) -> Unit,
    onSend: () -> Unit
) {
    Surface(shadowElevation = 4.dp, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = queryText,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().testTag("care_query_input"),
                placeholder = { Text("Message CycleCare") },
                minLines = 1,
                maxLines = 5,
                supportingText = { Text("${queryText.length}/1200") },
                trailingIcon = {
                    IconButton(onClick = onSend, enabled = canSend, modifier = Modifier.testTag("care_submit_button")) {
                        if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Include relevant cycle context", style = MaterialTheme.typography.labelMedium)
                    Text(if (contextAllowed) "Off by default; selected structured context only." else "Premium or admin access required.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = includeFullContext, enabled = contextAllowed && !isLoading, onCheckedChange = onContextChange, modifier = Modifier.testTag("care_context_switch"))
            }
            if (includeFullContext && contextAllowed && !contextPreview.isNullOrBlank()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("care_context_preview")) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Preview sent with this question", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                        Text(contextPreview, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 3.dp).heightIn(max = 120.dp).verticalScroll(rememberScrollState()))
                    }
                }
            }
        }
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

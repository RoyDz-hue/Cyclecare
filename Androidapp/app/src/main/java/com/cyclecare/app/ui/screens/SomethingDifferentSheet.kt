package com.cyclecare.app.ui.screens

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclecare.app.ui.viewmodel.CycleCareViewModel
import com.cyclecare.app.ui.viewmodel.CycleUiState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SomethingDifferentSheet(
    viewModel: CycleCareViewModel,
    uiState: CycleUiState
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    var textInput by remember { mutableStateOf("") }
    val interpretation = uiState.currentInterpretation

    // NEW IMPLEMENTATION (recovery rebuild): native-first quick chips via
    // something_sheet_v1 (op quick_chips); the verbatim Kotlin literal is
    // the twin on any null reply.
    val quickChips = com.cyclecare.app.rust.CycleCareCoreBridge.somethingSheetQuickChips() ?: kotlinSheetQuickChips()

    ModalBottomSheet(
        onDismissRequest = { viewModel.closeSomethingDifferentSheet() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("something_different_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Something feels different?",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Tell me in your own words. We'll handle the rest.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = { viewModel.closeSomethingDifferentSheet() },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Freeform Input Field
            OutlinedTextField(
                value = textInput,
                onValueChange = {
                    textInput = it
                    // NEW IMPLEMENTATION (recovery rebuild): native-first
                    // parse gate (op should_parse); twin on null.
                    if (com.cyclecare.app.rust.CycleCareCoreBridge.somethingSheetShouldParse(it) ?: kotlinSheetShouldParse(it)) {
                        viewModel.parseNaturalLanguageInput(it)
                    }
                },
                placeholder = { Text("e.g. \"My stomach is killing me and I'm exhausted\"") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("freeform_feel_input"),
                shape = RoundedCornerShape(16.dp),
                maxLines = 3,
                trailingIcon = {
                    // NEW IMPLEMENTATION (recovery rebuild): native-first
                    // parse gate (op should_parse); twin on null.
                    if (com.cyclecare.app.rust.CycleCareCoreBridge.somethingSheetShouldParse(textInput) ?: kotlinSheetShouldParse(textInput)) {
                        IconButton(onClick = { viewModel.parseNaturalLanguageInput(textInput) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Parse",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Conversational Unpacking: "Ask Without Knowing What to Ask"
            var activeUnpackDomain by remember { mutableStateOf<String?>(null) }
            Text(
                text = "Unpack what feels different:",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // NEW IMPLEMENTATION (recovery rebuild): native-first domain
                // chips (op domain_chips); the verbatim literal is the twin.
                (com.cyclecare.app.rust.CycleCareCoreBridge.somethingSheetDomainChips() ?: kotlinSheetDomainChips()).forEach { (domain, _) ->
                    val isDomainActive = activeUnpackDomain == domain
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isDomainActive) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                            .border(
                                1.dp,
                                if (isDomainActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                activeUnpackDomain = if (isDomainActive) null else domain
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = domain,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = if (isDomainActive) FontWeight.Bold else FontWeight.Normal,
                                color = if (isDomainActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }

            // Sub-items for active domain
            if (activeUnpackDomain != null) {
                // NEW IMPLEMENTATION (recovery rebuild): native-first
                // sub-option table (op sub_options); the verbatim when-chain
                // is the twin. Both keep the QUIRK: this table is a second,
                // separate literal from the domain-chips table.
                val subOptions = com.cyclecare.app.rust.CycleCareCoreBridge.somethingSheetSubOptions(activeUnpackDomain) ?: kotlinSheetSubOptions(activeUnpackDomain)
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    subOptions.forEach { subItem ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                .clickable {
                                    // NEW IMPLEMENTATION (recovery rebuild):
                                    // native-first sensation append (op
                                    // append_sensation); twin on null.
                                    textInput = com.cyclecare.app.rust.CycleCareCoreBridge.somethingSheetAppendSensation(textInput, subItem) ?: kotlinSheetAppendSensation(textInput, subItem)
                                    viewModel.parseNaturalLanguageInput(textInput)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            // NEW IMPLEMENTATION (recovery rebuild): native-first label (op sub_item_label); twin on null.
                            Text(text = com.cyclecare.app.rust.CycleCareCoreBridge.somethingSheetSubItemLabel(subItem) ?: kotlinSheetSubItemLabel(subItem), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Shortcut Chips
            Text(
                text = "Or quick common sensations:",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickChips.forEach { chip ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .clickable {
                                // NEW IMPLEMENTATION (recovery rebuild):
                                // native-first sensation append (op
                                // append_sensation); twin on null.
                                textInput = com.cyclecare.app.rust.CycleCareCoreBridge.somethingSheetAppendSensation(textInput, chip) ?: kotlinSheetAppendSensation(textInput, chip)
                                viewModel.parseNaturalLanguageInput(textInput)
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(text = chip, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Interpretation Card (Instant Preview)
            // NEW IMPLEMENTATION (recovery rebuild): native-first card gate
            // (op card_visible); the original expression is the twin. The
            // val interpretation is stable; both sides read it in the
            // original order (null check, then items).
            if (com.cyclecare.app.rust.CycleCareCoreBridge.somethingSheetCardVisible(interpretation != null, interpretation?.items?.size ?: 0) ?: kotlinSheetCardVisible(interpretation != null, interpretation?.items?.size ?: 0)) {
                Spacer(modifier = Modifier.height(20.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("interpretation_preview_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Psychology,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "I understood:",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        interpretation.items.forEach { item ->
                            Row(
                                modifier = Modifier.padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = item.iconEmoji, fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = com.cyclecare.app.rust.CycleCareCoreBridge.somethingSheetItemLine(item.label, item.detail) ?: kotlinSheetItemLine(item.label, item.detail), // NEW IMPLEMENTATION (recovery rebuild): native-first (op item_line); twin on null.
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        if (interpretation.followUpQuestion != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = com.cyclecare.app.rust.CycleCareCoreBridge.somethingSheetFollowUp(interpretation.followUpQuestion) ?: kotlinSheetFollowUp(interpretation.followUpQuestion), // NEW IMPLEMENTATION (recovery rebuild): native-first (op follow_up); twin on null. Smart cast: interpretation is a local val, followUpQuestion a data-class val.
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { viewModel.confirmParsedLog(interpretation) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("confirm_parsed_log_button"),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save Both", fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { textInput = "" },
                                modifier = Modifier.height(48.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Clear")
                            }
                        }
                    }
                }
            }
        }
    }
}


// NEW IMPLEMENTATION (recovery rebuild): verbatim Kotlin twins for the
// something_sheet_v1 wiring above. Each body is the ORIGINAL expression
// from this file (main@31b88f5 lineage), moved unchanged so the native
// path and the fallback can never drift apart silently. Private; the
// fallback-parity test reaches them by reflection.
private fun kotlinSheetQuickChips(): List<String> = listOf(
    "Cramps",
    "Fatigue",
    "Headache",
    "Mood shift",
    "Stomach pain",
    "Bloating",
    "Nausea",
    "Restless sleep",
    "Spotting",
    "Just feel off"
)

private fun kotlinSheetDomainChips(): List<Pair<String, List<String>>> = listOf(
    "Physical" to listOf("Cramps", "Headache", "Back pain", "Breast tenderness"),
    "Mood" to listOf("Anxious", "Irritable", "Tearful", "Low motivation"),
    "Energy & Sleep" to listOf("Heavy fatigue", "Brain fog", "Restless sleep"),
    "Pelvic" to listOf("Discharge change", "Spotting", "Deep pressure"),
    "Stomach" to listOf("Bloating", "Nausea", "Appetite surge")
)

private fun kotlinSheetSubOptions(domain: String?): List<String> = when (domain) {
    "Physical" -> listOf("Cramps", "Headache", "Back pain", "Breast tenderness")
    "Mood" -> listOf("Anxious", "Irritable", "Tearful", "Low motivation")
    "Energy & Sleep" -> listOf("Heavy fatigue", "Brain fog", "Restless sleep")
    "Pelvic" -> listOf("Discharge change", "Spotting", "Deep pressure")
    "Stomach" -> listOf("Bloating", "Nausea", "Appetite surge")
    else -> emptyList()
}

private fun kotlinSheetShouldParse(text: String): Boolean = text.isNotBlank()

private fun kotlinSheetAppendSensation(textInput: String, item: String): String =
    if (textInput.isBlank()) item else "$textInput, $item"

private fun kotlinSheetCardVisible(interpretationPresent: Boolean, itemsCount: Int): Boolean =
    interpretationPresent && itemsCount > 0

private fun kotlinSheetSubItemLabel(subItem: String): String = "• $subItem"

private fun kotlinSheetItemLine(label: String, detail: String): String = "• $label ($detail)"

private fun kotlinSheetFollowUp(question: String): String = "💭 $question"

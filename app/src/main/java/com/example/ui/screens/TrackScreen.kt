package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.CycleDayLog
import com.example.data.model.EnergyLevel
import com.example.data.model.FlowLevel
import com.example.data.model.MasterHealthOptions
import com.example.data.model.MoodLevel
import com.example.data.model.OvulationTestResult
import com.example.data.model.SafetyLevel
import com.example.data.model.SleepQuality
import com.example.domain.safety.SafetyEngine
import com.example.ui.components.HorizontalWeekDateStrip
import com.example.ui.components.SculptedDropletFlowSelector
import com.example.ui.viewmodel.CycleCareViewModel
import com.example.ui.viewmodel.CycleUiState
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackScreen(
    viewModel: CycleCareViewModel,
    uiState: CycleUiState,
    currentLog: CycleDayLog?,
    modifier: Modifier = Modifier
) {
    val selectedDate = uiState.selectedDate
    val dateText = selectedDate.format(DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy"))
    var selectedFlow by remember { mutableStateOf(currentLog?.flowLevel.orEmpty()) }
    var selectedEnergy by remember { mutableStateOf(currentLog?.energyLevel.orEmpty()) }
    var selectedMood by remember { mutableStateOf(currentLog?.moodLevel.orEmpty()) }
    var selectedSymptoms by remember { mutableStateOf(currentLog?.symptomsCsv?.split(",")?.filter(String::isNotBlank)?.toSet() ?: emptySet()) }
    var selectedVaginal by remember { mutableStateOf(currentLog?.vaginalHealthCsv?.split(",")?.filter(String::isNotBlank)?.toSet() ?: emptySet()) }
    var selectedUrinary by remember { mutableStateOf(currentLog?.urinaryHealthCsv?.split(",")?.filter(String::isNotBlank)?.toSet() ?: emptySet()) }
    var selectedMucus by remember { mutableStateOf(currentLog?.cervicalMucus ?: "") }
    var selectedOvulationTest by remember { mutableStateOf(currentLog?.ovulationTest ?: "NONE") }
    var painRating by remember { mutableIntStateOf(currentLog?.painIntensity ?: 0) }
    var painRecorded by remember { mutableStateOf(currentLog?.painIntensity?.let { it > 0 } ?: false) }
    var sleepHours by remember { mutableFloatStateOf(currentLog?.sleepHours?.takeIf { it > 0f } ?: 7.5f) }
    var sleepHoursRecorded by remember { mutableStateOf(currentLog?.sleepHours?.let { it > 0f } ?: false) }
    var selectedSleepQuality by remember { mutableStateOf(currentLog?.sleepQuality.orEmpty()) }
    var waterCount by remember { mutableIntStateOf(currentLog?.waterGlasses ?: 0) }
    var waterRecorded by remember { mutableStateOf(currentLog?.waterGlasses?.let { it > 0 } ?: false) }
    var notesText by remember { mutableStateOf(currentLog?.notes ?: "") }
    var savedFeedback by remember { mutableStateOf(false) }
    var symptomsExpanded by remember { mutableStateOf(false) }
    var observationsExpanded by remember { mutableStateOf(false) }
    var comfortExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(selectedDate, currentLog) {
        selectedFlow = currentLog?.flowLevel.orEmpty()
        selectedEnergy = currentLog?.energyLevel.orEmpty()
        selectedMood = currentLog?.moodLevel.orEmpty()
        selectedSymptoms = currentLog?.symptomsCsv?.split(",")?.filter(String::isNotBlank)?.toSet() ?: emptySet()
        selectedVaginal = currentLog?.vaginalHealthCsv?.split(",")?.filter(String::isNotBlank)?.toSet() ?: emptySet()
        selectedUrinary = currentLog?.urinaryHealthCsv?.split(",")?.filter(String::isNotBlank)?.toSet() ?: emptySet()
        selectedMucus = currentLog?.cervicalMucus ?: ""
        selectedOvulationTest = currentLog?.ovulationTest ?: "NONE"
        painRating = currentLog?.painIntensity ?: 0
        painRecorded = currentLog?.painIntensity?.let { it > 0 } ?: false
        sleepHours = currentLog?.sleepHours?.takeIf { it > 0f } ?: 7.5f
        sleepHoursRecorded = currentLog?.sleepHours?.let { it > 0f } ?: false
        selectedSleepQuality = currentLog?.sleepQuality.orEmpty()
        waterCount = currentLog?.waterGlasses ?: 0
        waterRecorded = currentLog?.waterGlasses?.let { it > 0 } ?: false
        notesText = currentLog?.notes ?: ""
        savedFeedback = false
    }

    val candidateLog = CycleDayLog(
        dateString = selectedDate.toString(),
        flowLevel = selectedFlow,
        energyLevel = selectedEnergy,
        moodLevel = selectedMood,
        symptomsCsv = selectedSymptoms.joinToString(","),
        vaginalHealthCsv = selectedVaginal.joinToString(","),
        urinaryHealthCsv = selectedUrinary.joinToString(","),
        cervicalMucus = selectedMucus,
        ovulationTest = selectedOvulationTest,
        sleepHours = if (sleepHoursRecorded) sleepHours else 0f,
        sleepQuality = selectedSleepQuality,
        waterGlasses = if (waterRecorded) waterCount else 0,
        notes = notesText,
        painIntensity = if (painRecorded) painRating else 0
    )
    val hasAnyEntry = candidateLog.hasRecordedObservations()
    val safety = SafetyEngine.evaluate(candidateLog)
    val observationCount = selectedVaginal.size + selectedUrinary.size +
        (if (selectedMucus.isNotBlank()) 1 else 0) +
        (if (selectedOvulationTest != "NONE") 1 else 0)

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f).testTag("track_screen_content"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Daily log", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = "Start with the basics. Everything else unfolds only when you need it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = { viewModel.selectDate(selectedDate.minusDays(1)) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous day")
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(dateText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            if (selectedDate == uiState.currentDate) {
                                Text("Today", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        IconButton(onClick = { viewModel.selectDate(selectedDate.plusDays(1)) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next day")
                        }
                    }
                }
            }

            item {
                HorizontalWeekDateStrip(
                    selectedDate = selectedDate,
                    todayDate = uiState.currentDate,
                    onSelectDate = viewModel::selectDate
                )
            }

            if (safety.level != SafetyLevel.LOW) {
                item {
                    val safetyColor = if (safety.isRedFlag) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                    Surface(
                        modifier = Modifier.fillMaxWidth().testTag("track_safety_notice"),
                        shape = RoundedCornerShape(20.dp),
                        color = if (safety.isRedFlag) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(safety.title, style = MaterialTheme.typography.titleSmall, color = safetyColor)
                            Text(safety.message, style = MaterialTheme.typography.bodySmall)
                            Text(safety.actionAdvice, style = MaterialTheme.typography.labelMedium, color = safetyColor)
                        }
                    }
                }
            }

            item {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Quick check-in", style = MaterialTheme.typography.titleMedium)
                            Text("Only selections or changes you make are saved. Untouched fields stay unreported.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        SelectionGroup("Flow", vertical = true) {
                            Text(
                                text = if (selectedFlow.isBlank()) "Not reported until you choose an option." else "Selected: ${FlowLevel.entries.firstOrNull { it.name == selectedFlow }?.displayName ?: "Not reported"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            SculptedDropletFlowSelector(
                                selectedFlow = selectedFlow,
                                onSelectFlow = { selectedFlow = it }
                            )
                        }
                        SelectionGroup("Energy", vertical = true) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                EnergyLevel.entries.forEach { energy ->
                                    QuietChoiceChip(energy.displayName, selectedEnergy == energy.name) { selectedEnergy = energy.name }
                                }
                            }
                        }
                        SelectionGroup("Mood", vertical = true) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                MoodLevel.entries.forEach { mood ->
                                    QuietChoiceChip("${mood.emoji} ${mood.displayName}", selectedMood == mood.name) { selectedMood = mood.name }
                                }
                            }
                        }
                    }
                }
            }

            item {
                TrackAccordion(
                    title = "Symptoms",
                    summary = if (selectedSymptoms.isEmpty()) "Optional · nothing selected" else "${selectedSymptoms.size} selected",
                    badgeCount = selectedSymptoms.size,
                    expanded = symptomsExpanded,
                    onToggle = { symptomsExpanded = !symptomsExpanded }
                ) {
                    Text("Physical symptoms", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MasterHealthOptions.symptoms.forEach { symptom ->
                            QuietChoiceChip(symptom.label, selectedSymptoms.contains(symptom.id)) {
                                selectedSymptoms = if (selectedSymptoms.contains(symptom.id)) selectedSymptoms - symptom.id else selectedSymptoms + symptom.id
                            }
                        }
                    }
                }
            }

            item {
                TrackAccordion(
                    title = "Body observations",
                    summary = if (observationCount == 0) "Discharge, urinary comfort and optional test results" else "$observationCount noted today",
                    badgeCount = observationCount,
                    expanded = observationsExpanded,
                    onToggle = { observationsExpanded = !observationsExpanded }
                ) {
                    SelectionGroup("Vaginal health & fluid", vertical = true) {
                        Text("Changes in texture, colour, sensation or odour", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            MasterHealthOptions.vaginalHealth.forEach { item ->
                                QuietChoiceChip(item.label, selectedVaginal.contains(item.id)) {
                                    selectedVaginal = if (selectedVaginal.contains(item.id)) selectedVaginal - item.id else selectedVaginal + item.id
                                }
                            }
                        }
                    }
                    SelectionGroup("Urinary comfort", vertical = true) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            MasterHealthOptions.urinaryHealth.forEach { item ->
                                QuietChoiceChip(item.label, selectedUrinary.contains(item.id)) {
                                    selectedUrinary = if (selectedUrinary.contains(item.id)) selectedUrinary - item.id else selectedUrinary + item.id
                                }
                            }
                        }
                    }
                    SelectionGroup("Cervical mucus", vertical = true) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("Dry", "Sticky", "Creamy", "Egg White (Fertile)", "Watery").forEach { option ->
                                QuietChoiceChip(option, selectedMucus == option) { selectedMucus = if (selectedMucus == option) "" else option }
                            }
                        }
                    }
                    SelectionGroup("Ovulation test", vertical = true) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OvulationTestResult.entries.forEach { result ->
                                QuietChoiceChip(result.displayName, selectedOvulationTest == result.name) { selectedOvulationTest = result.name }
                            }
                        }
                    }
                }
            }

            item {
                val comfortCount = (if (painRating > 0) 1 else 0) +
                    (if (sleepHoursRecorded) 1 else 0) +
                    (if (selectedSleepQuality.isNotBlank()) 1 else 0) +
                    (if (waterCount > 0) 1 else 0) +
                    (if (notesText.isNotBlank()) 1 else 0)
                TrackAccordion(
                    title = "Comfort & notes",
                    summary = "Pain, sleep, hydration and a private note",
                    badgeCount = comfortCount,
                    expanded = comfortExpanded,
                    onToggle = { comfortExpanded = !comfortExpanded }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Pain", style = MaterialTheme.typography.titleSmall)
                            Text(if (painRecorded) "$painRating / 10" else "Not recorded", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = painRating.toFloat(),
                            onValueChange = { painRating = it.toInt(); painRecorded = true },
                            valueRange = 0f..10f,
                            steps = 9,
                            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Sleep", style = MaterialTheme.typography.titleSmall)
                            Text(if (sleepHoursRecorded) String.format(java.util.Locale.getDefault(), "%.1f hours", sleepHours) else "Not recorded", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = sleepHours,
                            onValueChange = { sleepHours = it; sleepHoursRecorded = true },
                            valueRange = 4f..12f,
                            steps = 15,
                            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
                        )
                    }
                    SelectionGroup("Sleep quality", vertical = true) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SleepQuality.entries.forEach { quality ->
                                QuietChoiceChip(quality.displayName, selectedSleepQuality == quality.name) { selectedSleepQuality = quality.name }
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Water", style = MaterialTheme.typography.titleSmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { waterCount = (waterCount - 1).coerceAtLeast(0); waterRecorded = true }, modifier = Modifier.size(48.dp)) {
                                    Text("−", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                                }
                                Text(if (waterRecorded) "$waterCount glasses" else "Not recorded", style = MaterialTheme.typography.bodyMedium)
                                IconButton(onClick = { waterCount++; waterRecorded = true }, modifier = Modifier.size(48.dp)) {
                                    Text("+", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = notesText,
                        onValueChange = { notesText = it },
                        placeholder = { Text("Add a note in your own words…") },
                        label = { Text("Private note · optional") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("notes_field"),
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = {
                    viewModel.saveDayLog(candidateLog)
                    savedFeedback = true
                },
                enabled = hasAnyEntry,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("save_track_button"),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save check-in", style = MaterialTheme.typography.labelLarge)
            }
            if (savedFeedback) {
                Text(
                    text = "Check-in submitted. Reward status appears only when confirmed.",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            if (!hasAnyEntry) {
                Text(
                    text = "Select or enter at least one observation to save.",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun QuietChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
    )
}

@Composable
private fun SelectionGroup(
    title: String,
    vertical: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        content()
    }
}

@Composable
private fun TrackAccordion(
    title: String,
    summary: String,
    badgeCount: Int = 0,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 68.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(role = Role.Button, onClick = onToggle)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        if (badgeCount > 0) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "$badgeCount",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = content
                )
            }
        }
    }
}

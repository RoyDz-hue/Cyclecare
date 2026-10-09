package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.CycleDayLog
import com.example.data.model.EnergyLevel
import com.example.data.model.FlowLevel
import com.example.data.model.MasterHealthOptions
import com.example.data.model.MoodLevel
import com.example.data.model.OvulationTestResult
import com.example.data.model.SafetyLevel
import com.example.data.model.SleepQuality
import com.example.domain.safety.SafetyEngine
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

    var selectedFlow by remember { mutableStateOf(currentLog?.flowLevel ?: "NONE") }
    var selectedEnergy by remember { mutableStateOf(currentLog?.energyLevel ?: "NORMAL") }
    var selectedMood by remember { mutableStateOf(currentLog?.moodLevel ?: "GOOD") }
    var selectedSymptoms by remember {
        mutableStateOf(currentLog?.symptomsCsv?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet())
    }
    var selectedVaginal by remember {
        mutableStateOf(currentLog?.vaginalHealthCsv?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet())
    }
    var selectedUrinary by remember {
        mutableStateOf(currentLog?.urinaryHealthCsv?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet())
    }
    var selectedMucus by remember { mutableStateOf(currentLog?.cervicalMucus ?: "") }
    var selectedOvulationTest by remember { mutableStateOf(currentLog?.ovulationTest ?: "NONE") }
    var painRating by remember { mutableIntStateOf(currentLog?.painIntensity ?: 0) }
    var sleepHours by remember { mutableFloatStateOf(currentLog?.sleepHours ?: 7.5f) }
    var selectedSleepQuality by remember { mutableStateOf(currentLog?.sleepQuality ?: "GOOD") }
    var waterCount by remember { mutableIntStateOf(currentLog?.waterGlasses ?: 0) }
    var notesText by remember { mutableStateOf(currentLog?.notes ?: "") }
    var savedFeedback by remember { mutableStateOf(false) }

    // Sync on date change
    LaunchedEffect(selectedDate, currentLog) {
        selectedFlow = currentLog?.flowLevel ?: "NONE"
        selectedEnergy = currentLog?.energyLevel ?: "NORMAL"
        selectedMood = currentLog?.moodLevel ?: "GOOD"
        selectedSymptoms = currentLog?.symptomsCsv?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        selectedVaginal = currentLog?.vaginalHealthCsv?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        selectedUrinary = currentLog?.urinaryHealthCsv?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        selectedMucus = currentLog?.cervicalMucus ?: ""
        selectedOvulationTest = currentLog?.ovulationTest ?: "NONE"
        painRating = currentLog?.painIntensity ?: 0
        sleepHours = currentLog?.sleepHours ?: 7.5f
        selectedSleepQuality = currentLog?.sleepQuality ?: "GOOD"
        waterCount = currentLog?.waterGlasses ?: 0
        notesText = currentLog?.notes ?: ""
        savedFeedback = false
    }

    // Dynamic Live Safety Preview
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
        sleepHours = sleepHours,
        sleepQuality = selectedSleepQuality,
        waterGlasses = waterCount,
        notes = notesText,
        painIntensity = painRating
    )
    val safetyEval = SafetyEngine.evaluate(candidateLog)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("track_screen_content")
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Date Navigation Header
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.selectDate(selectedDate.minusDays(1)) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous Day")
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = dateText, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    if (selectedDate == uiState.currentDate) {
                        Text(text = "Today", style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary))
                    }
                }

                IconButton(onClick = { viewModel.selectDate(selectedDate.plusDays(1)) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next Day")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Live Deterministic Safety Notice (if triggered by user selection)
        if (safetyEval.level != SafetyLevel.LOW) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = if (safetyEval.isRedFlag) Color(0xFFFFEBEE) else Color(0xFFFFF8E1))
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = if (safetyEval.isRedFlag) Color(0xFFC62828) else Color(0xFFF57F17))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(text = safetyEval.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                            Text(text = safetyEval.message, style = MaterialTheme.typography.bodySmall)
                            Text(text = "Action: ${safetyEval.actionAdvice}", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }
        }

        // 1. Flow & Bleeding
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "🩸 Menstrual Flow & Spotting", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowLevel.entries.forEach { flow ->
                            val isSelected = selectedFlow == flow.name
                            val isUnusuallyHeavy = flow == FlowLevel.UNUSUALLY_HEAVY
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        when {
                                            isSelected && isUnusuallyHeavy -> Color(0xFFFFCDD2)
                                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        }
                                    )
                                    .then(if (isSelected) Modifier.border(1.5.dp, if (isUnusuallyHeavy) Color(0xFFC62828) else MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
                                    .clickable { selectedFlow = flow.name }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = flow.displayName,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected && isUnusuallyHeavy) Color(0xFFC62828) else if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // 2. Energy Levels
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "⚡ Energy Level", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EnergyLevel.entries.forEach { energy ->
                            val isSelected = selectedEnergy == energy.name
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) Color(0xFFFFF3E0) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .then(if (isSelected) Modifier.border(1.5.dp, Color(0xFFE65100), RoundedCornerShape(10.dp)) else Modifier)
                                    .clickable { selectedEnergy = energy.name }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = energy.displayName,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Color(0xFFE65100) else MaterialTheme.colorScheme.onSurface
                                    ),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // 3. Mood Levels
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "✨ Mood", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MoodLevel.entries.forEach { mood ->
                            val isSelected = selectedMood == mood.name
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) Color(0xFFF3E5F5) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .then(if (isSelected) Modifier.border(1.5.dp, Color(0xFF7B1FA2), RoundedCornerShape(10.dp)) else Modifier)
                                    .clickable { selectedMood = mood.name }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(text = mood.emoji, fontSize = 20.sp)
                                    Text(
                                        text = mood.displayName,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) Color(0xFF7B1FA2) else MaterialTheme.colorScheme.onSurface
                                        ),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // 4. Physical Symptoms
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "🩺 Physical Symptoms", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MasterHealthOptions.symptoms.forEach { symptom ->
                            val isSelected = selectedSymptoms.contains(symptom.id)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .then(if (isSelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
                                    .clickable {
                                        selectedSymptoms = if (isSelected) selectedSymptoms - symptom.id else selectedSymptoms + symptom.id
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = symptom.label,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // 5. Vaginal Health & Infection Monitoring
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "🌸 Vaginal Health & Fluid", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text(text = "Changes in texture, color, sensation, or odor", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MasterHealthOptions.vaginalHealth.forEach { v ->
                            val isSelected = selectedVaginal.contains(v.id)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) Color(0xFFFCE4EC) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .then(if (isSelected) Modifier.border(1.5.dp, Color(0xFFC2185B), RoundedCornerShape(12.dp)) else Modifier)
                                    .clickable {
                                        selectedVaginal = if (isSelected) selectedVaginal - v.id else selectedVaginal + v.id
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = v.label,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // 6. Urinary Symptoms
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "💧 Urinary Comfort", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MasterHealthOptions.urinaryHealth.forEach { u ->
                            val isSelected = selectedUrinary.contains(u.id)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) Color(0xFFE0F7FA) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .then(if (isSelected) Modifier.border(1.5.dp, Color(0xFF0097A7), RoundedCornerShape(12.dp)) else Modifier)
                                    .clickable {
                                        selectedUrinary = if (isSelected) selectedUrinary - u.id else selectedUrinary + u.id
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = u.label,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // 7. Pain Intensity & Sleep
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "Pain Intensity (0 - 10)", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        Text(text = "$painRating / 10", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                    }
                    Slider(
                        value = painRating.toFloat(),
                        onValueChange = { painRating = it.toInt() },
                        valueRange = 0f..10f,
                        steps = 9,
                        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "Sleep Duration", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        Text(text = String.format("%.1f hours", sleepHours), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                    }
                    Slider(
                        value = sleepHours,
                        onValueChange = { sleepHours = it },
                        valueRange = 4f..12f,
                        steps = 15,
                        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // 8. Personal Diary Notes
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "📝 Private Journal Notes", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = notesText,
                        onValueChange = { notesText = it },
                        placeholder = { Text("What did you experience today? Thoughts, bodily sensations, food...") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("notes_field"),
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Save & Feedback
        item {
            Button(
                onClick = {
                    viewModel.saveDayLog(candidateLog)
                    savedFeedback = true
                },
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("save_track_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save Today's Log", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            if (savedFeedback) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "✓ Health log saved privately on device", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
            }

            if (currentLog != null) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { viewModel.deleteDayLog(selectedDate.toString()) },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Delete Log", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

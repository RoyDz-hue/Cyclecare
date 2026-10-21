package com.cyclecare.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.model.CyclePhase
import com.cyclecare.app.data.model.HealthPattern
import com.cyclecare.app.ui.viewmodel.CycleCareViewModel
import com.cyclecare.app.ui.viewmodel.CycleUiState

@Composable
fun InsightsScreen(
    viewModel: CycleCareViewModel,
    uiState: CycleUiState,
    logsMap: Map<String, CycleDayLog>,
    modifier: Modifier = Modifier
) {
    val baseline = uiState.baselineMetrics
    val patterns = uiState.patterns
    var showAllObservations by remember { mutableStateOf(false) }
    var showAllPatterns by remember { mutableStateOf(false) }
    var showAllTimeline by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("insights_screen_content")
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Screen Header
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Your patterns",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "A clearer view of your own recorded history",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
        }

        // Statistical Baseline Card (Mean, Median, Range, Variability) with harmonized Quiet Depth palette
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("you_vs_you_baseline_card"),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Cycle settings & observed baseline",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Your settings are not measured averages. Observed intervals come only from saved period starts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        BaselineStatTile(
                            icon = Icons.Default.Schedule,
                            label = if (baseline.observedCycleIntervals == 0) "Setting" else if (baseline.observedCycleIntervals == 1) "Interval" else "Average",
                            value = if (baseline.observedCycleIntervals == 0) "${uiState.settings.averageCycleLength}d" else "${baseline.averageCycleLength.toInt()}d",
                            color = MaterialTheme.colorScheme.primary
                        )
                        BaselineStatTile(
                            icon = Icons.Default.CalendarMonth,
                            label = "Median",
                            value = if (baseline.observedCycleIntervals == 0) "—" else "${baseline.medianCycleLength}d",
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        BaselineStatTile(
                            icon = Icons.Default.CheckCircle,
                            label = "Variability",
                            value = if (baseline.observedCycleIntervals < 2) "Need 2+" else "±${baseline.variabilityDays}d",
                            color = MaterialTheme.colorScheme.secondary
                        )
                        BaselineStatTile(
                            icon = Icons.Default.AutoAwesome,
                            label = "Data Points",
                            value = "${baseline.totalLoggedDays} logs",
                            color = Color(0xFFC89D54)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Section: "What I've learned about you"
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "What I've Learned About You",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        items(if (showAllObservations) uiState.learnedObservations.size else minOf(uiState.learnedObservations.size, 1)) { index ->
            val obs = uiState.learnedObservations[index]
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = obs.category.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        )
                        Text(
                            text = obs.confidence,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = obs.headline, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = obs.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (uiState.learnedObservations.size > 1) {
            item {
                TextButton(onClick = { showAllObservations = !showAllObservations }) {
                    Text(if (showAllObservations) "Show less" else "See all ${uiState.learnedObservations.size} observations")
                }
            }
        }

        // Detected Patterns Section
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Repeated entries",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        items(if (showAllPatterns) patterns.size else minOf(patterns.size, 1)) { index ->
            val pattern = patterns[index]
            PatternCardItem(pattern = pattern)
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (patterns.size > 1) {
            item {
                TextButton(onClick = { showAllPatterns = !showAllPatterns }) {
                    Text(if (showAllPatterns) "Show fewer patterns" else "See all ${patterns.size} patterns")
                }
            }
        }

        if (patterns.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("No repeated entries yet", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        Text(
                            "A repeated entry appears only after the same symptom is saved on at least two dates. No missing days are filled in and no cause is inferred.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 4 Phases Hormonal Dynamics
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "General cycle education",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Educational information only; it does not describe measured hormone levels or your personal cycle phase.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        item {
            PhaseInsightAccordion(
                phase = CyclePhase.MENSTRUAL,
                hormone = "Estrogen & Progesterone reset to lowest baseline. Prostaglandins facilitate uterine shedding.",
                care = "Warm broths, dark leafy greens, gentle hydration, and heat compresses."
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        item {
            PhaseInsightAccordion(
                phase = CyclePhase.FOLLICULAR,
                hormone = "FSH stimulates egg maturation. Estrogen steadily rises, thickening the endometrium.",
                care = "Fresh sprouted seeds, creative endeavors, active endurance workouts."
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        item {
            PhaseInsightAccordion(
                phase = CyclePhase.OVULATION,
                hormone = "Luteinizing Hormone (LH) surges, prompting the mature follicle to release an ovum.",
                care = "Antioxidant berries, fiber, high water intake, and peak strength training."
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        item {
            PhaseInsightAccordion(
                phase = CyclePhase.LUTEAL,
                hormone = "Corpus luteum secretes progesterone. Progesterone peaks then withdraws if unfertilized.",
                care = "Magnesium, sweet potatoes, root vegetables, dark chocolate, and mindful pacing."
            )
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Longitudinal Personal Health Timeline
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(28.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("📅", fontSize = 14.sp)
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Saved-log timeline",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Saved entries grouped by month. Unlogged months are left blank.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        items(if (showAllTimeline) uiState.monthlyTimeline.size else minOf(uiState.monthlyTimeline.size, 1)) { index ->
            val entry = uiState.monthlyTimeline[index]
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = entry.monthTitle,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        )
                        Text(
                            text = if (entry.cycleLengthDays > 0) "${entry.cycleLengthDays}d observed interval" else "${entry.periodBleedingDays} logged bleeding days",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Most logged symptom: ${entry.dominantSymptom}",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Non-default energy entries: ${entry.dominantEnergy}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (entry.patternNoticed != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "✨ Pattern: ${entry.patternNoticed}",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (uiState.monthlyTimeline.size > 1) {
            item {
                TextButton(onClick = { showAllTimeline = !showAllTimeline }) {
                    Text(if (showAllTimeline) "Show less history" else "See all ${uiState.monthlyTimeline.size} months")
                }
            }
        }

        if (uiState.monthlyTimeline.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("No saved timeline entries yet", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        Text(
                            "Only months with actual saved logs appear here. No previous cycles or symptoms are generated.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Clinician Visit Report CTA Card
        item {
            var showDoctorReportDialog by remember { mutableStateOf(false) }

            Spacer(modifier = Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth().testTag("clinician_report_cta_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.MedicalServices, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Clinician Visit Summary",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            )
                            Text(
                                text = "A summary of saved settings and logs. No diagnosis or inferred history.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { showDoctorReportDialog = true },
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("open_clinician_report_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("View saved-information summary", fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (showDoctorReportDialog) {
                AlertDialog(
                    onDismissRequest = { showDoctorReportDialog = false },
                    title = {
                        Text(
                            text = "Clinician Visit Summary",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(380.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = uiState.clinicianReportText,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { showDoctorReportDialog = false },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Done")
                        }
                    },
                    shape = RoundedCornerShape(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
private fun BaselineStatTile(icon: ImageVector, label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = color.copy(alpha = 0.14f), modifier = Modifier.size(38.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = color))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PatternCardItem(pattern: HealthPattern) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = pattern.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(text = "Evidence: ${pattern.confidence}", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Cycle timing: ${pattern.cyclePhase}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))

            Text(text = pattern.explanation, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(10.dp)
            ) {
                Text(text = "💡 Care Action: ${pattern.actionableCare}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
            }
        }
    }
}

@Composable
private fun PhaseInsightAccordion(phase: CyclePhase, hormone: String, care: String) {
    var expanded by remember { mutableStateOf(false) }
    val phaseColor = Color(phase.colorHex)

    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(phaseColor))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = phase.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = phaseColor))
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Text(text = "🧬 Physiology: $hormone", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "🥗 Phase Care: $care", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

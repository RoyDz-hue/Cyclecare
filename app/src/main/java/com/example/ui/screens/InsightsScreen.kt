package com.example.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.example.data.local.CycleDayLog
import com.example.data.model.CyclePhase
import com.example.data.model.HealthPattern
import com.example.ui.viewmodel.CycleCareViewModel
import com.example.ui.viewmodel.CycleUiState

@Composable
fun InsightsScreen(
    viewModel: CycleCareViewModel,
    uiState: CycleUiState,
    logsMap: Map<String, CycleDayLog>,
    modifier: Modifier = Modifier
) {
    val baseline = uiState.baselineMetrics
    val patterns = uiState.patterns

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
                        Icon(Icons.Default.TrendingUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Patterns & Personal Baseline",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Statistically derived from your recorded history",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
        }

        // Statistical Baseline Card (Mean, Median, Range, Variability)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Personal Physiological Baseline",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        BaselineStatTile(
                            icon = Icons.Default.Schedule,
                            label = "Average",
                            value = "${baseline.averageCycleLength.toInt()}d",
                            color = MaterialTheme.colorScheme.primary
                        )
                        BaselineStatTile(
                            icon = Icons.Default.CalendarMonth,
                            label = "Median",
                            value = "${baseline.medianCycleLength}d",
                            color = Color(0xFF7B1FA2)
                        )
                        BaselineStatTile(
                            icon = Icons.Default.CheckCircle,
                            label = "Variability",
                            value = "±${baseline.variabilityDays}d",
                            color = Color(0xFF388E3C)
                        )
                        BaselineStatTile(
                            icon = Icons.Default.AutoAwesome,
                            label = "Data Points",
                            value = "${baseline.totalLoggedDays} logs",
                            color = Color(0xFFE65100)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
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

        items(uiState.learnedObservations.size) { index ->
            val obs = uiState.learnedObservations[index]
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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

        // Detected Patterns Section
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Detected Body Patterns",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        items(patterns.size) { index ->
            val pattern = patterns[index]
            PatternCardItem(pattern = pattern)
            Spacer(modifier = Modifier.height(12.dp))
        }

        // 4 Phases Hormonal Dynamics
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Hormonal Dynamics by Phase",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
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
            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
private fun BaselineStatTile(icon: ImageVector, label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = color.copy(alpha = 0.12f), modifier = Modifier.size(36.dp)) {
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                    Text(text = "${pattern.confidence} Confidence", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Window: ${pattern.cyclePhase}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

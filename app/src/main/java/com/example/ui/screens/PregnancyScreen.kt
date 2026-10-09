package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.ui.viewmodel.CycleCareViewModel
import com.example.ui.viewmodel.CycleUiState
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class WeekMilestone(
    val week: Int,
    val fruit: String,
    val emoji: String,
    val length: String,
    val weight: String,
    val description: String
)

val milestones = listOf(
    WeekMilestone(4, "Poppy Seed", "🌱", "1 mm", "< 1 g", "Blastocyst implants into the uterine lining."),
    WeekMilestone(8, "Raspberry", "🍇", "1.6 cm", "1 g", "Tiny fingers and toes begin developing."),
    WeekMilestone(12, "Lime", "🍋", "5.4 cm", "14 g", "All major organs and reflexes are formed."),
    WeekMilestone(16, "Avocado", "🥑", "11.6 cm", "100 g", "Baby can hear your voice and make facial expressions."),
    WeekMilestone(20, "Banana", "🍌", "25.6 cm", "300 g", "Halfway mark! Baby kicks may now be felt clearly."),
    WeekMilestone(24, "Corn", "🌽", "30 cm", "600 g", "Lungs develop surfactant and taste buds form."),
    WeekMilestone(28, "Eggplant", "🍆", "37 cm", "1.0 kg", "Eyes can open and sense changing light."),
    WeekMilestone(32, "Pineapple", "🍍", "42 cm", "1.7 kg", "Bones continue hardening and fingernails grow."),
    WeekMilestone(36, "Papaya", "🍈", "47 cm", "2.6 kg", "Rapid brain growth and fat layer accumulation."),
    WeekMilestone(40, "Watermelon", "🍉", "51 cm", "3.4 kg", "Full term! Ready to welcome your little one into the world.")
)

@Composable
fun PregnancyScreen(
    viewModel: CycleCareViewModel,
    uiState: CycleUiState,
    modifier: Modifier = Modifier
) {
    val settings = uiState.settings
    val isPregnancyActive = settings.isPregnancyMode

    var kickCount by remember { mutableIntStateOf(0) }

    // If no due date set, default to 6 months from now
    val dueDate = try {
        if (settings.pregnancyDueDate.isNotBlank()) LocalDate.parse(settings.pregnancyDueDate)
        else LocalDate.now().plusMonths(6)
    } catch (e: Exception) {
        LocalDate.now().plusMonths(6)
    }

    val daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(), dueDate).coerceAtLeast(0L)
    val totalPregnancyDays = 280L
    val daysCompleted = (totalPregnancyDays - daysRemaining).coerceIn(0L, totalPregnancyDays)
    val currentWeek = (daysCompleted / 7L).coerceIn(1L, 40L).toInt()
    val currentTrimester = when {
        currentWeek <= 13 -> 1
        currentWeek <= 26 -> 2
        else -> 3
    }

    val milestone = milestones.findLast { it.week <= currentWeek } ?: milestones.first()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("pregnancy_screen_content")
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFFCE4EC),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.ChildCare,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Pregnancy Companion",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Baby growth & maternity tracking",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
        }

        // Toggle Switch Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Pregnancy Tracking Mode",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (isPregnancyActive) "Active • Week $currentWeek" else "Inactive • Tap to activate pregnancy mode",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = isPregnancyActive,
                        onCheckedChange = { active ->
                            viewModel.updateSettings(
                                settings.copy(
                                    isPregnancyMode = active,
                                    pregnancyDueDate = if (settings.pregnancyDueDate.isBlank()) LocalDate.now().plusMonths(6).toString() else settings.pregnancyDueDate
                                )
                            )
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("pregnancy_mode_switch")
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (isPregnancyActive) {
            // Baby Size & Week Milestone Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "WEEK $currentWeek",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 1.5.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = milestone.emoji,
                            fontSize = 64.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Size of a ${milestone.fruit}",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                        )

                        Text(
                            text = "${milestone.length} • ${milestone.weight}",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = milestone.description,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Trimester Progress Bar & Due Date
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Trimester $currentTrimester Progress",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "$daysRemaining days left",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        LinearProgressIndicator(
                            progress = { (daysCompleted.toFloat() / totalPregnancyDays.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Due Date: ${dueDate.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy"))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$currentWeek / 40 Weeks",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Baby Kick Counter Tool
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "👣 Baby Kick Counter",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            IconButton(onClick = { kickCount = 0 }) {
                                Icon(Icons.Default.RestartAlt, contentDescription = "Reset Kicks")
                            }
                        }

                        Text(
                            text = "Track your baby's movement session (aim for 10 kicks within 2 hours)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "$kickCount",
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                        Text(
                            text = "Kicks recorded",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = { kickCount++ },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("record_kick_button"),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Record a Kick", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(30.dp))
            }
        } else {
            // Educational teaser when pregnancy mode is off
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Planning for Pregnancy?",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "CycleCare monitors your fertile window and ovulation peak to help you identify your best conception days. If you conceive, switch on Pregnancy Mode above to track weekly baby milestones, sizes, and maternity tips!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }
}

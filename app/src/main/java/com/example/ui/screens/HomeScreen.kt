package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.CycleDayLog
import com.example.data.model.CyclePhase
import com.example.data.model.SafetyLevel
import com.example.domain.language.LanguageEngine
import com.example.ui.components.CycleDial
import com.example.ui.viewmodel.AppNavTab
import com.example.ui.viewmodel.CycleCareViewModel
import com.example.ui.viewmodel.CycleUiState
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    viewModel: CycleCareViewModel,
    uiState: CycleUiState,
    todayLog: CycleDayLog?,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")
    val dateText = uiState.currentDate.format(dateFormatter)
    val todayPrediction = uiState.todayPrediction
    val phaseColor = Color(todayPrediction?.phase?.colorHex ?: 0xFFD81B60)
    val safety = uiState.safetyEvaluation
    val attentionItem = uiState.todayAttentionItem

    var showWhyAppeared by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen_content")
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top App Bar
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_cyclecare_logo),
                        contentDescription = "CycleCare Logo",
                        modifier = Modifier.size(42.dp).clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "CycleCare",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                        Text(
                            text = dateText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("settings_button")
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Empathetic Personalized Greeting
        item {
            val phase = todayPrediction?.phase ?: CyclePhase.FOLLICULAR
            val greeting = LanguageEngine.formatPhaseGreeting(
                phase = phase,
                dayOfCycle = todayPrediction?.dayOfCycle ?: 1,
                daysUntilPeriod = uiState.daysUntilNextPeriod,
                tone = uiState.userTonePreference
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Text(text = "🌸", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = greeting,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Hero Cycle Dial
        item {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(phaseColor.copy(alpha = 0.15f), Color.Transparent)))
                )

                CycleDial(
                    prediction = todayPrediction,
                    cycleLength = uiState.settings.averageCycleLength,
                    periodDuration = uiState.settings.periodDuration,
                    daysUntilPeriod = uiState.daysUntilNextPeriod,
                    onClick = { viewModel.selectTab(AppNavTab.CYCLE) }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Hero Trigger: "Something feels different?"
        item {
            Button(
                onClick = { viewModel.openSomethingDifferentSheet() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("something_feels_different_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Something feels different?", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Today's Care Priority Card (From Attention Engine)
        item {
            if (attentionItem != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("today_priority_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (attentionItem.isUrgent) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (attentionItem.isUrgent) Color(0xFFC62828) else MaterialTheme.colorScheme.primary)
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = attentionItem.category.uppercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = Color.White)
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { showWhyAppeared = !showWhyAppeared }
                            ) {
                                Icon(Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = "Why this appeared", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(text = attentionItem.headline, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = attentionItem.message, style = MaterialTheme.typography.bodyMedium)

                        if (showWhyAppeared) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = "💡 ${attentionItem.whyThisAppeared}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Care: ${attentionItem.careSuggestion}",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            } else {
                // "You're all caught up" Peaceful State
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("all_caught_up_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "🌿", fontSize = 24.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(text = "You're all caught up", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                            Text(
                                text = "Nothing needs your attention right now. Your recent rhythm looks steady and consistent.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }
        }

        // 10-Second Micro Check-In Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("micro_check_in_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "10-Second Check-In",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "How are you feeling right now?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf(
                            "🙂 Good" to "GOOD",
                            "😐 Okay" to "OKAY",
                            "😣 Not great" to "LOW",
                            "🤔 Off" to "UNCOMFORTABLE"
                        ).forEach { (label, moodVal) ->
                            val isSelected = todayLog?.moodLevel == moodVal
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 3.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .then(if (isSelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
                                    .clickable {
                                        viewModel.quickCheckIn(mood = moodVal, energy = todayLog?.energyLevel ?: "NORMAL")
                                    }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    ),
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Energy?", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "Low" to "LOW",
                            "Normal" to "NORMAL",
                            "Good" to "GOOD"
                        ).forEach { (label, energyVal) ->
                            val isSelected = todayLog?.energyLevel == energyVal
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) Color(0xFFFFF3E0) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .then(if (isSelected) Modifier.border(1.5.dp, Color(0xFFE65100), RoundedCornerShape(10.dp)) else Modifier)
                                    .clickable {
                                        viewModel.quickCheckIn(mood = todayLog?.moodLevel ?: "GOOD", energy = energyVal)
                                    }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Color(0xFFE65100) else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Predictive Phase Quick Actions
        item {
            val phase = todayPrediction?.phase ?: CyclePhase.FOLLICULAR
            Text(
                text = "Predictive Quick Actions",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (phase) {
                    CyclePhase.OVULATION -> {
                        QuickActionChip(modifier = Modifier.weight(1f), text = "Log Fertile Fluid") { viewModel.openTrackForDate(uiState.currentDate) }
                        QuickActionChip(modifier = Modifier.weight(1f), text = "Log Ovulation LH") { viewModel.openTrackForDate(uiState.currentDate) }
                    }
                    CyclePhase.MENSTRUAL -> {
                        QuickActionChip(modifier = Modifier.weight(1f), text = "Log Period Flow") { viewModel.openTrackForDate(uiState.currentDate) }
                        QuickActionChip(modifier = Modifier.weight(1f), text = "Pelvic Comfort Care") { viewModel.selectTab(AppNavTab.CARE) }
                    }
                    CyclePhase.LUTEAL -> {
                        QuickActionChip(modifier = Modifier.weight(1f), text = "Log Cramps / Fatigue") { viewModel.openTrackForDate(uiState.currentDate) }
                        QuickActionChip(modifier = Modifier.weight(1f), text = "Evening Rest Guide") { viewModel.selectTab(AppNavTab.CARE) }
                    }
                    CyclePhase.FOLLICULAR -> {
                        QuickActionChip(modifier = Modifier.weight(1f), text = "Log Rising Energy") { viewModel.openTrackForDate(uiState.currentDate) }
                        QuickActionChip(modifier = Modifier.weight(1f), text = "Creative Peak Tips") { viewModel.selectTab(AppNavTab.CARE) }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Personalized "Worth Knowing" Insight
        item {
            val phase = todayPrediction?.phase ?: CyclePhase.FOLLICULAR
            val (worthKnowingTitle, worthKnowingBody) = when (phase) {
                CyclePhase.MENSTRUAL -> Pair(
                    "Worth Knowing Today",
                    "Your resting metabolic rate drops slightly as uterine muscles contract. Natural prostaglandins are highest during the first 48 hours; heat therapy and anti-inflammatory foods can noticeably calm cellular tension."
                )
                CyclePhase.FOLLICULAR -> Pair(
                    "Worth Knowing Today",
                    "Estrogen is steadily rising, boosting neuroplasticity, memory retention, and muscle glycogen storage. It's often the easiest phase to learn new skills or tackle cognitively demanding tasks."
                )
                CyclePhase.OVULATION -> Pair(
                    "Worth Knowing Today",
                    "A surging LH surge triggers ovulation, elevating baseline testosterone briefly. You may experience sharper reflexes, higher social energy, and clearer, stretchy cervical fluid."
                )
                CyclePhase.LUTEAL -> Pair(
                    "Worth Knowing Today",
                    "Progesterone dominance increases core body temperature by ~0.3°C and shifts neurotransmitter sensitivity. If you feel more introspective or crave complex carbohydrates, it's physiological, not lack of willpower."
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth().testTag("worth_knowing_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("💡", fontSize = 16.sp)
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = worthKnowingTitle,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = worthKnowingBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Gentle Self-Care Suggestion
        item {
            val phase = todayPrediction?.phase ?: CyclePhase.FOLLICULAR
            val selfCareSuggestion = when (phase) {
                CyclePhase.MENSTRUAL -> "Warm pelvic heat pack, chamomile infusion, and restorative floor stretching."
                CyclePhase.FOLLICULAR -> "Fresh leafy greens, high-protein breakfast, and trying a new outdoor route."
                CyclePhase.OVULATION -> "Plentiful hydration, electrolyte balance, and taking advantage of peak stamina."
                CyclePhase.LUTEAL -> "Magnesium-rich foods (dark chocolate, pumpkin seeds), warm bath, and an earlier bedtime."
            }

            Card(
                modifier = Modifier.fillMaxWidth().testTag("gentle_self_care_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F8E9))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "🌱", fontSize = 22.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Gentle Self-Care Suggestion",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = selfCareSuggestion,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF1B5E20)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Water Hydration Counter
        item {
            val waterCount = todayLog?.waterGlasses ?: 0
            Card(
                modifier = Modifier.fillMaxWidth().testTag("water_widget"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = Color(0xFFE1F5FE), modifier = Modifier.size(38.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Opacity, contentDescription = "Water", tint = Color(0xFF0288D1), modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(text = "Hydration", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                            Text(text = "$waterCount of 8 glasses", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                val current = todayLog ?: CycleDayLog(dateString = uiState.currentDate.toString())
                                viewModel.saveDayLog(current.copy(waterGlasses = (current.waterGlasses - 1).coerceAtLeast(0)))
                            },
                            modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Text(text = "$waterCount", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(horizontal = 10.dp))
                        IconButton(
                            onClick = {
                                val current = todayLog ?: CycleDayLog(dateString = uiState.currentDate.toString())
                                viewModel.saveDayLog(current.copy(waterGlasses = current.waterGlasses + 1))
                            },
                            modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
private fun QuickActionChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

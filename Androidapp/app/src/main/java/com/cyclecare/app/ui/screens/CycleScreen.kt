package com.cyclecare.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.ui.viewmodel.CycleCareViewModel
import com.cyclecare.app.ui.viewmodel.CycleUiState
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun CycleScreen(
    viewModel: CycleCareViewModel,
    uiState: CycleUiState,
    logsMap: Map<String, CycleDayLog>,
    modifier: Modifier = Modifier
) {
    var displayedMonth by remember { mutableStateOf(YearMonth.from(uiState.selectedDate)) }
    val selectedDate = uiState.selectedDate
    val today = uiState.currentDate
    val selectedPrediction = uiState.selectedDatePrediction
    val selectedLog = logsMap[selectedDate.toString()]
    val daysUntilPeriodFromSelected: Int? = selectedPrediction?.let { prediction ->
        if (prediction.isPeriod) 0 else (uiState.settings.averageCycleLength - prediction.dayOfCycle).coerceAtLeast(0)
    }
    val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .testTag("cycle_screen_content")
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    Text("Your cycle", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = "A personal calendar, guided by the dates you record.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (selectedDate != today) {
                    TextButton(onClick = {
                        viewModel.selectDate(today)
                        displayedMonth = YearMonth.from(today)
                    }) {
                        Text("Jump to today")
                    }
                }
            }
        }

        // Selected Day Summary Banner (aligned cleanly with 20dp outer margin)
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "${selectedPrediction?.dayOfCycle ?: "—"}",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("SELECTED DAY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = selectedPrediction?.phase?.title ?: if (uiState.hasCycleAnchor) "No estimate for this date" else "Add a start date for estimates",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = if (selectedDate == today) "Today" else selectedDate.format(DateTimeFormatter.ofPattern("EEE, MMM d")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Calendar estimate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = when {
                                daysUntilPeriodFromSelected == null -> "Not available"
                                daysUntilPeriodFromSelected == 0 -> "around today"
                                else -> "about $daysUntilPeriodFromSelected days"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Interactive Month Calendar Grid
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { displayedMonth = displayedMonth.minusMonths(1) },
                            modifier = Modifier.size(48.dp).testTag("prev_month_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous month")
                        }
                        Text(displayedMonth.format(monthFormatter), style = MaterialTheme.typography.titleMedium)
                        IconButton(
                            onClick = { displayedMonth = displayedMonth.plusMonths(1) },
                            modifier = Modifier.size(48.dp).testTag("next_month_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next month")
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth()) {
                        listOf("S", "M", "T", "W", "T", "F", "S").forEach { day ->
                            Text(
                                text = day,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    val firstDay = displayedMonth.atDay(1)
                    val startOffset = firstDay.dayOfWeek.value % 7
                    val daysInMonth = displayedMonth.lengthOfMonth()
                    val cellCount = ((startOffset + daysInMonth + 6) / 7) * 7
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(cellCount / 7) { week ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                repeat(7) { column ->
                                    val cellIndex = week * 7 + column
                                    val dayNumber = cellIndex - startOffset + 1
                                    if (dayNumber in 1..daysInMonth) {
                                        val date = displayedMonth.atDay(dayNumber)
                                        val prediction = viewModel.getPredictionForDate(date)
                                        val hasLog = logsMap.containsKey(date.toString())
                                        val isSelected = date == selectedDate
                                        val isToday = date == today
                                        val isPeriodEstimate = prediction?.isPeriod == true
                                        val isFertileEstimate = prediction?.isFertile == true || prediction?.isOvulation == true
                                        val previousPrediction = if (prediction != null && column > 0 && dayNumber > 1) {
                                            viewModel.getPredictionForDate(date.minusDays(1))
                                        } else null
                                        val nextPrediction = if (prediction != null && column < 6 && dayNumber < daysInMonth) {
                                            viewModel.getPredictionForDate(date.plusDays(1))
                                        } else null
                                        val samePeriodOnLeft = isPeriodEstimate && previousPrediction?.isPeriod == true
                                        val sameFertileWindowOnLeft = isFertileEstimate &&
                                            (previousPrediction?.isFertile == true || previousPrediction?.isOvulation == true)
                                        val samePeriodOnRight = isPeriodEstimate && nextPrediction?.isPeriod == true
                                        val sameFertileWindowOnRight = isFertileEstimate &&
                                            (nextPrediction?.isFertile == true || nextPrediction?.isOvulation == true)
                                        val sameSpanLeft = samePeriodOnLeft || sameFertileWindowOnLeft
                                        val sameSpanRight = samePeriodOnRight || sameFertileWindowOnRight
                                        val cellShape = if (isSelected) {
                                            RoundedCornerShape(14.dp)
                                        } else {
                                            RoundedCornerShape(
                                                topStart = if (sameSpanLeft) 5.dp else 14.dp,
                                                bottomStart = if (sameSpanLeft) 5.dp else 14.dp,
                                                topEnd = if (sameSpanRight) 5.dp else 14.dp,
                                                bottomEnd = if (sameSpanRight) 5.dp else 14.dp
                                            )
                                        }
                                        val label = buildString {
                                            append(date.format(DateTimeFormatter.ofPattern("MMMM d")))
                                            if (prediction?.isPeriod == true) append(", period estimate")
                                            else if (prediction?.isOvulation == true) append(", ovulation estimate")
                                            else if (prediction?.isFertile == true) append(", fertile window estimate")
                                            if (hasLog) append(", has a saved log")
                                            if (isToday) append(", today")
                                        }
                                        val cellBg = when {
                                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                                            isPeriodEstimate -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f)
                                            isFertileEstimate -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.40f)
                                            else -> Color.Transparent
                                        }
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .aspectRatio(0.92f)
                                                .clip(cellShape)
                                                .background(cellBg)
                                                .then(
                                                    if (prediction?.isOvulation == true && !isSelected) {
                                                        Modifier.border(1.5.dp, Color(0xFFD4A857), cellShape)
                                                    } else if (isToday && !isSelected) {
                                                        Modifier.border(
                                                            1.dp,
                                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                                            cellShape
                                                        )
                                                    } else Modifier
                                                )
                                                .clickable(
                                                    role = Role.Button,
                                                    onClickLabel = "Select ${date.format(DateTimeFormatter.ofPattern("MMMM d"))}"
                                                ) { viewModel.selectDate(date) }
                                                .semantics { contentDescription = label }
                                                .padding(2.dp)
                                                .testTag("calendar_day_$dayNumber"),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = "$dayNumber",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                fontWeight = if (isSelected || isToday) FontWeight.SemiBold else FontWeight.Normal
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                                modifier = Modifier.padding(top = 3.dp)
                                            ) {
                                                if (prediction?.isPeriod == true) Marker(MaterialTheme.colorScheme.primary)
                                                else if (prediction?.isOvulation == true) Marker(MaterialTheme.colorScheme.tertiary)
                                                else if (prediction?.isFertile == true) Marker(MaterialTheme.colorScheme.secondary)
                                                if (hasLog) Marker(MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    } else {
                                        Spacer(Modifier.weight(1f).aspectRatio(0.92f))
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (uiState.hasCycleAnchor) {
                            LegendItem(MaterialTheme.colorScheme.primary, "Period estimate")
                            LegendItem(MaterialTheme.colorScheme.secondary, "Fertile estimate")
                        }
                        LegendItem(MaterialTheme.colorScheme.onSurfaceVariant, "Logged")
                    }
                }
            }
        }

        // Selected Cycle Day Action Card (fixed double horizontal padding)
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("selected_cycle_day_card"),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(selectedDate.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), style = MaterialTheme.typography.titleMedium)
                    if (selectedPrediction != null) {
                        Text(
                            text = "Calendar-estimated day ${selectedPrediction.dayOfCycle} · ${selectedPrediction.phase.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = selectedPrediction.phase.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Add a period start date to begin a personal cycle view.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (selectedLog != null) {
                        Text(
                            text = "Saved log · ${selectedLog.flowLevel.lowercase().replace('_', ' ')} flow · ${selectedLog.moodLevel.lowercase().replace('_', ' ')} mood",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { viewModel.openTrackForDate(selectedDate) },
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("edit_selected_day_button"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (selectedLog == null) "Log this day" else "Edit this day")
                    }
                }
            }
        }

        // Cycle Averages Card (fixed double horizontal padding)
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Cycle settings & history", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric("Cycle length setting", "${uiState.settings.averageCycleLength} days", Modifier.weight(1f))
                        Metric("Bleeding setting", "${uiState.settings.periodDuration} days", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric(
                            "Observed interval",
                            if (uiState.baselineMetrics.observedCycleIntervals > 0) "${uiState.baselineMetrics.minCycleLength}–${uiState.baselineMetrics.maxCycleLength} days" else "Not measured",
                            Modifier.weight(1f)
                        )
                        Metric("Logged days", "${logsMap.size}", Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (!uiState.hasCycleAnchor) {
                                "No cycle start date is saved, so calendar predictions are unavailable. Settings below are not measured history."
                            } else if (uiState.cycleStartIsApproximate) {
                                "These are approximate calendar estimates from a date range you selected. They can shift, do not confirm ovulation, and should not be used as contraception."
                            } else {
                                "Calendar dates are estimates from the date you entered and can shift. They do not confirm ovulation and should not be used as contraception."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Marker(color: Color) {
    Box(Modifier.size(5.dp).clip(CircleShape).background(color))
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Marker(color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

package com.cyclecare.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclecare.app.data.model.FlowLevel
import com.cyclecare.app.ui.theme.rememberSystemMotionEnabled
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** A tactile selector that records only the flow value the user explicitly chooses. */
@Composable
fun SculptedDropletFlowSelector(
    selectedFlow: String,
    onSelectFlow: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val motionEnabled = rememberSystemMotionEnabled()
    val rows = remember { FlowLevel.entries.chunked(3) }

    Column(
        modifier = modifier.fillMaxWidth().testTag("sculpted_droplet_flow_selector"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        rows.forEach { rowFlows ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                rowFlows.forEach { flow ->
                    val isSelected = selectedFlow == flow.name
                    val scale by animateFloatAsState(
                        targetValue = if (isSelected && motionEnabled) 1.025f else 1f,
                        animationSpec = tween(durationMillis = if (motionEnabled) 140 else 0),
                        label = "flow_droplet_scale_${flow.name}"
                    )
                    val fillFraction = when (flow) {
                        FlowLevel.NONE -> 0f
                        FlowLevel.SPOTTING -> 0.22f
                        FlowLevel.LIGHT -> 0.42f
                        FlowLevel.MODERATE -> 0.64f
                        FlowLevel.HEAVY -> 0.84f
                        FlowLevel.UNUSUALLY_HEAVY -> 1f
                    }
                    val highlightColor = MaterialTheme.colorScheme.surface
                    val dropletColor = MaterialTheme.colorScheme.primary
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .scale(scale)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable(
                                role = Role.RadioButton,
                                onClickLabel = "Select ${flow.displayName} flow"
                            ) { onSelectFlow(flow.name) }
                            .semantics {
                                selected = isSelected
                                contentDescription = "${flow.displayName} flow${if (isSelected) ", selected" else ""}"
                            }
                            .testTag("flow_droplet_${flow.name.lowercase()}"),
                        shape = RoundedCornerShape(18.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                        ),
                        shadowElevation = if (isSelected) 2.dp else 0.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Canvas(modifier = Modifier.size(26.dp)) {
                                drawFlowDroplet(
                                    fillFraction = fillFraction,
                                    color = dropletColor,
                                    selected = isSelected,
                                    highlightColor = highlightColor
                                )
                            }
                            Text(
                                text = flow.displayName,
                                modifier = Modifier.heightIn(min = 30.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                maxLines = 2
                            )
                        }
                    }
                }
                repeat(3 - rowFlows.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

private fun DrawScope.drawFlowDroplet(
    fillFraction: Float,
    color: Color,
    selected: Boolean,
    highlightColor: Color
) {
    val width = size.width
    val height = size.height
    val path = Path().apply {
        moveTo(width * 0.5f, height * 0.08f)
        cubicTo(width * 0.88f, height * 0.48f, width * 0.86f, height * 0.88f, width * 0.5f, height * 0.92f)
        cubicTo(width * 0.14f, height * 0.88f, width * 0.12f, height * 0.48f, width * 0.5f, height * 0.08f)
        close()
    }
    if (fillFraction <= 0f) {
        drawPath(
            path = path,
            color = color.copy(alpha = 0.8f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    } else {
        drawPath(
            path = path,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.5f + fillFraction * 0.4f), color),
                startY = height * 0.08f,
                endY = height * 0.92f
            )
        )
        drawCircle(
            color = highlightColor.copy(alpha = if (selected) 0.82f else 0.55f),
            radius = width * 0.09f,
            center = Offset(width * 0.38f, height * 0.62f)
        )
    }
}

/** Five nearby dates for quick navigation; all dates remain available as in the existing day controls. */
@Composable
fun HorizontalWeekDateStrip(
    selectedDate: LocalDate,
    todayDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val dayFormatter = remember { DateTimeFormatter.ofPattern("EEE") }
    val dates = remember(selectedDate) { (-2..2).map { selectedDate.plusDays(it.toLong()) } }

    Row(
        modifier = modifier.fillMaxWidth().testTag("horizontal_week_date_strip"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        dates.forEach { date ->
            val isSelected = date == selectedDate
            val isToday = date == todayDate
            val accessibleDate = date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")) +
                if (isToday) ", today" else ""
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 58.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Select $accessibleDate"
                    ) { onSelectDate(date) }
                    .semantics {
                        selected = isSelected
                        contentDescription = accessibleDate
                    }
                    .testTag("track_date_$date"),
                shape = RoundedCornerShape(18.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                border = if (isToday && !isSelected) {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
                } else null
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = date.format(dayFormatter),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = date.dayOfMonth.toString(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface
                        )
                        if (isToday) {
                            Box(Modifier.size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            }
        }
    }
}

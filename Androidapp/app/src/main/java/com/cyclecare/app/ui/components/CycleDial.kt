package com.cyclecare.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclecare.app.data.model.DayPrediction
import com.cyclecare.app.ui.theme.rememberSystemMotionEnabled
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CycleDial(
    prediction: DayPrediction?,
    cycleLength: Int,
    periodDuration: Int,
    daysUntilPeriod: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val motionEnabled = rememberSystemMotionEnabled()
    val currentDay = prediction?.dayOfCycle
    val safeCycleLength = cycleLength.coerceAtLeast(1)
    val progress = currentDay?.let { (it.toFloat() / safeCycleLength.toFloat()).coerceIn(0.01f, 1f) } ?: 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = if (motionEnabled) 700 else 0),
        label = "cycleProgress"
    )
    val phaseColor = Color(prediction?.phase?.colorHex ?: 0xFF803A46)
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val periodColor = MaterialTheme.colorScheme.primary
    val fertileColor = MaterialTheme.colorScheme.tertiary
    val markerSurface = MaterialTheme.colorScheme.surface
    val description = buildString {
        if (prediction == null) {
            append("No cycle date saved. Tap to open the cycle calendar.")
        } else {
            append("Calendar estimate, cycle day $currentDay")
            prediction.phase.title.let { append(", $it") }
            append(if (daysUntilPeriod == 0) ", period estimate around today" else ", period estimate in $daysUntilPeriod days")
        }
    }

    Box(
        modifier = modifier
            .size(200.dp)
            .testTag("cycle_dial_component")
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Open cycle calendar", onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(13.dp)) {
            val strokeWidth = 15.dp.toPx()
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = (size.width - strokeWidth) / 2f

            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            if (prediction != null) {
                val periodSweep = (periodDuration.coerceAtMost(safeCycleLength).toFloat() / safeCycleLength.toFloat()) * 360f
                drawArc(
                    color = periodColor.copy(alpha = 0.62f),
                    startAngle = -90f,
                    sweepAngle = periodSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth)
                )

                val ovulationDay = (safeCycleLength - 14).coerceAtLeast(periodDuration + 2)
                val fertileStart = (ovulationDay - 4).coerceAtLeast(periodDuration + 1)
                val fertileSweep = (6f / safeCycleLength.toFloat()) * 360f
                drawArc(
                    color = fertileColor.copy(alpha = 0.72f),
                    startAngle = -90f + (fertileStart.toFloat() / safeCycleLength.toFloat()) * 360f,
                    sweepAngle = fertileSweep.coerceAtMost(90f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                val todaySweep = animatedProgress * 360f
                drawArc(
                    brush = Brush.sweepGradient(listOf(phaseColor.copy(alpha = 0.72f), phaseColor, phaseColor.copy(alpha = 0.82f))),
                    startAngle = -90f,
                    sweepAngle = todaySweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth + 1.dp.toPx(), cap = StrokeCap.Round)
                )

                val angleRad = Math.toRadians((-90.0 + todaySweep).toDouble())
                val markerX = center.x + radius * cos(angleRad).toFloat()
                val markerY = center.y + radius * sin(angleRad).toFloat()
                drawCircle(color = markerSurface, radius = 9.dp.toPx(), center = Offset(markerX, markerY))
                drawCircle(color = phaseColor, radius = 5.5.dp.toPx(), center = Offset(markerX, markerY))
            }
        }

        Surface(
            modifier = Modifier.size(144.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Text(if (prediction == null) "CYCLE ESTIMATE" else "CYCLE DAY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
                Text(
                    currentDay?.toString() ?: "—",
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Normal, color = phaseColor)
                )
                Text(
                    if (prediction == null) "Add a start date" else prediction.phase.title.replace(" Phase", ""),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface),
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (prediction == null) "No estimate yet" else "Next period",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                if (prediction != null) {
                    Text(
                        if (daysUntilPeriod == 0) "around today" else "in $daysUntilPeriod days",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

package com.example.ui.components

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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DayPrediction
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
    val currentDay = prediction?.dayOfCycle ?: 1
    val progress = (currentDay.toFloat() / cycleLength.toFloat()).coerceIn(0.01f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 800),
        label = "cycleProgress"
    )

    val phase = prediction?.phase
    val phaseColor = Color(phase?.colorHex ?: 0xFFD81B60)

    Box(
        modifier = modifier
            .size(270.dp)
            .testTag("cycle_dial_component")
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // Draw the circular segmented track and glowing indicator
        Canvas(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            val strokeWidth = 18.dp.toPx()
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
            val center = Offset(size.width / 2, size.height / 2)
            val radius = (size.width - strokeWidth) / 2

            // Base track background ring
            drawArc(
                color = Color(0xFFF3E5E9),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Period arc (Red)
            val periodSweep = (periodDuration.toFloat() / cycleLength.toFloat()) * 360f
            drawArc(
                color = Color(0xFFE53935).copy(alpha = 0.85f),
                startAngle = -90f,
                sweepAngle = periodSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth)
            )

            // Ovulation window arc (Gold)
            val ovulationDay = (cycleLength - 14).coerceAtLeast(periodDuration + 2)
            val fertileStart = (ovulationDay - 4).coerceAtLeast(periodDuration + 1)
            val fertileDuration = 6
            val fertileStartAngle = -90f + (fertileStart.toFloat() / cycleLength.toFloat()) * 360f
            val fertileSweep = (fertileDuration.toFloat() / cycleLength.toFloat()) * 360f
            drawArc(
                color = Color(0xFFFFB300).copy(alpha = 0.85f),
                startAngle = fertileStartAngle,
                sweepAngle = fertileSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth)
            )

            // Dynamic progress arc up to today
            val todaySweep = animatedProgress * 360f
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(
                        phaseColor.copy(alpha = 0.7f),
                        phaseColor,
                        Color(0xFFFF4081)
                    )
                ),
                startAngle = -90f,
                sweepAngle = todaySweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth + 2.dp.toPx(), cap = StrokeCap.Round)
            )

            // Current day marker head
            val angleRad = Math.toRadians((-90.0 + todaySweep))
            val markerX = center.x + radius * cos(angleRad).toFloat()
            val markerY = center.y + radius * sin(angleRad).toFloat()
            drawCircle(
                color = Color.White,
                radius = 12.dp.toPx(),
                center = Offset(markerX, markerY)
            )
            drawCircle(
                color = phaseColor,
                radius = 7.dp.toPx(),
                center = Offset(markerX, markerY)
            )
        }

        // Dial Center Card with details
        Surface(
            modifier = Modifier
                .size(190.dp)
                .shadow(elevation = 6.dp, shape = CircleShape),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Text(
                    text = if (prediction?.isPeriod == true) "Period" else "Cycle",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.2.sp
                )

                Text(
                    text = "Day $currentDay",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = phaseColor
                    )
                )

                Spacer(modifier = Modifier.height(2.dp))

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(phaseColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = phase?.title?.replace(" Phase", "") ?: "Active",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = phaseColor
                        ),
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (daysUntilPeriod == 0) "Period today" else "Period in $daysUntilPeriod days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

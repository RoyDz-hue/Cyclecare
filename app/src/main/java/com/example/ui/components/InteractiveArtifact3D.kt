package com.example.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.util.SoundFeedbackManager
import com.example.ui.theme.rememberSystemMotionEnabled
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Memory-Optimized, Appreciative 3D Interactive Artifact Renderer.
 *
 * Performance & UX Architecture:
 * - Zero-Overhead Thumbnail Mode: When `isInteractive = false && !enableIdleRotation`,
 *   skips `rememberInfiniteTransition` and offscreen `.blur()` buffers completely so
 *   carousels and badges consume zero background GPU/CPU frames.
 * - True 3D Dancing & Appreciative Physics:
 *   - Touch-drag 3D perspective rotation (`rotationY`, `rotationX`, `rotationZ`) with
 *     bouncy spring-back equilibrium when released.
 *   - Tap-to-Dance micro-interaction: tapping the 3D asset triggers an appreciative
 *     360° celebratory twirl, scale pulse, stardust shimmer, and plays the artifact's
 *     signature solfeggio crystal chime via [SoundFeedbackManager].
 *   - Unlock/Acquisition Dance: triggers an ascending harmonic chord and spring unveiling.
 */
@Composable
fun InteractiveArtifact3D(
    @DrawableRes drawableRes: Int,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    auraColor: Color = Color(0xFFF5B82E),
    isInteractive: Boolean = true,
    enableIdleRotation: Boolean = true,
    triggerUnlockAnimation: Boolean = false,
    harmonicPitchHz: Float = 528f,
    contentDescription: String? = null
) {
    val systemMotionEnabled = rememberSystemMotionEnabled()

    // Static/reduced-motion path avoids infinite transitions while preserving tap feedback.
    if (!systemMotionEnabled || (!isInteractive && !enableIdleRotation && !triggerUnlockAnimation)) {
        if (!systemMotionEnabled && triggerUnlockAnimation) {
            LaunchedEffect(triggerUnlockAnimation) {
                SoundFeedbackManager.playArtifactAcquired(harmonicPitchHz)
            }
        }
        Box(
            modifier = modifier
                .size(size)
                .then(
                    if (isInteractive && !systemMotionEnabled) {
                        Modifier.clickable { SoundFeedbackManager.playArtifactInteraction(harmonicPitchHz) }
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            auraColor.copy(alpha = 0.28f),
                            auraColor.copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    ),
                    radius = this.size.minDimension * 0.48f
                )
            }
            Image(
                painter = painterResource(id = drawableRes),
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(size * 0.84f)
                    .graphicsLayer {
                        rotationY = -8f
                        rotationX = 4f
                        cameraDistance = 12f * density
                    }
            )
        }
        return
    }

    val scope = rememberCoroutineScope()
    var targetDragYaw by remember { mutableFloatStateOf(0f) }
    var targetDragPitch by remember { mutableFloatStateOf(0f) }
    var danceCount by remember { mutableIntStateOf(0) }

    val smoothDragYaw by animateFloatAsState(
        targetValue = targetDragYaw,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "smooth_drag_yaw"
    )
    val smoothDragPitch by animateFloatAsState(
        targetValue = targetDragPitch,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "smooth_drag_pitch"
    )

    val danceSpinAnim = remember { Animatable(0f) }
    val danceBounceAnim = remember { Animatable(1f) }

    val infiniteTransition = rememberInfiniteTransition(label = "artifact_3d_loop")

    val floatPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283185f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "floating_hover"
    )

    val idleYaw by infiniteTransition.animateFloat(
        initialValue = -14f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_yaw_rotation"
    )

    val pulseGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.38f,
        targetValue = 0.82f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_pulse"
    )

    val scaleAnim = remember { Animatable(if (triggerUnlockAnimation) 0.25f else 1f) }
    LaunchedEffect(triggerUnlockAnimation) {
        if (triggerUnlockAnimation) {
            SoundFeedbackManager.playArtifactAcquired(harmonicPitchHz)
            scaleAnim.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
    }

    val hoverOffsetDp = (sin(floatPhase) * 6.5f).dp
    val gentleRoll = cos(floatPhase) * 2.5f

    Box(
        modifier = modifier
            .size(size)
            .offset { IntOffset(x = 0, y = hoverOffsetDp.roundToPx()) }
            .then(
                if (isInteractive) {
                    Modifier
                        .clickable {
                            danceCount++
                            SoundFeedbackManager.playArtifactInteraction(harmonicPitchHz)
                            scope.launch {
                                danceBounceAnim.snapTo(1.14f)
                                danceBounceAnim.animateTo(
                                    targetValue = 1f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioHighBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            }
                            scope.launch {
                                val nextTarget = danceSpinAnim.value + 360f
                                danceSpinAnim.animateTo(
                                    targetValue = nextTarget,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessLow
                                    )
                                )
                            }
                        }
                        .pointerInput(harmonicPitchHz) {
                            var soundTriggeredOnDrag = false
                            detectDragGestures(
                                onDragStart = {
                                    soundTriggeredOnDrag = false
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (!soundTriggeredOnDrag && (kotlin.math.abs(dragAmount.x) > 4f || kotlin.math.abs(dragAmount.y) > 4f)) {
                                        soundTriggeredOnDrag = true
                                        SoundFeedbackManager.playArtifactInteraction(harmonicPitchHz)
                                    }
                                    targetDragYaw = (targetDragYaw + dragAmount.x * 0.45f).coerceIn(-55f, 55f)
                                    targetDragPitch = (targetDragPitch - dragAmount.y * 0.35f).coerceIn(-30f, 30f)
                                },
                                onDragEnd = {
                                    targetDragYaw = 0f
                                    targetDragPitch = 0f
                                },
                                onDragCancel = {
                                    targetDragYaw = 0f
                                    targetDragPitch = 0f
                                }
                            )
                        }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        // Direct GPU Canvas for multi-stop radial aura & orbiting stardust (zero offscreen blur buffer!)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerOffset = Offset(this.size.width / 2f, this.size.height / 2f)
            val maxR = (this.size.minDimension / 2f) * (0.88f + pulseGlowAlpha * 0.12f)

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.28f * pulseGlowAlpha),
                        auraColor.copy(alpha = 0.45f * pulseGlowAlpha),
                        auraColor.copy(alpha = 0.14f * pulseGlowAlpha),
                        Color.Transparent
                    ),
                    center = centerOffset,
                    radius = maxR
                ),
                radius = maxR,
                center = centerOffset
            )

            // Subtle orbiting luminous motes that react to 3D rotation
            val orbitRadius = this.size.minDimension * 0.40f
            for (i in 0 until 6) {
                val angle = floatPhase + (i * 1.0472f) + (smoothDragYaw * 0.02f)
                val px = centerOffset.x + cos(angle) * orbitRadius
                val py = centerOffset.y + sin(angle) * (orbitRadius * 0.65f)
                val dotAlpha = (0.25f + 0.45f * ((sin(floatPhase + i) + 1f) * 0.5f))
                drawCircle(
                    color = auraColor.copy(alpha = dotAlpha),
                    radius = (if (i % 2 == 0) 3.dp else 2.dp).toPx(),
                    center = Offset(px, py)
                )
            }
        }

        val effectiveYaw = (if (enableIdleRotation) idleYaw else 0f) + smoothDragYaw + danceSpinAnim.value
        val effectivePitch = smoothDragPitch + (sin(floatPhase) * 4f)

        Image(
            painter = painterResource(id = drawableRes),
            contentDescription = contentDescription,
            modifier = Modifier
                .size(size * 0.84f)
                .scale(scaleAnim.value * danceBounceAnim.value)
                .graphicsLayer {
                    rotationY = effectiveYaw
                    rotationX = effectivePitch
                    rotationZ = gentleRoll + (smoothDragYaw * 0.08f)
                    cameraDistance = 15f * density
                }
        )
    }
}

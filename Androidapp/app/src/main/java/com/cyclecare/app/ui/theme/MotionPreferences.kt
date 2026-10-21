package com.cyclecare.app.ui.theme

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Uses the platform animation setting as the source of truth for nonessential motion. */
@Composable
fun rememberSystemMotionEnabled(): Boolean = remember {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
}

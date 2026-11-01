package com.cyclecare.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.cyclecare.app.R
import com.cyclecare.app.ui.components.InteractiveArtifact3D
import org.junit.Rule
import org.junit.Test

/**
 * Render-level verification for the UI audit fixes (layoutlib, no emulator).
 * Scoped to leaf composables that need no ViewModel; screen-level shots that
 * require repository wiring are added only where a fake state path exists.
 */
class ChromeScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    @Test
    fun artifactDefaultSize() {
        paparazzi.snapshot {
            InteractiveArtifact3D(drawableRes = R.drawable.cycle_coin, isInteractive = false, enableIdleRotation = false)
        }
    }
}

package com.cyclecare.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.cyclecare.app.data.economy.DefaultCoinPackages
import com.cyclecare.app.ui.screens.UnifiedGamifiedCheckoutDialog
import com.cyclecare.app.ui.theme.CycleCareTheme
import org.junit.Rule
import org.junit.Test

/** Checkout under the current release policy: only the honest unavailable notice. */
class CheckoutScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    @Test
    fun checkoutUnavailableUnderCurrentPolicy() {
        paparazzi.snapshot {
            CycleCareTheme(darkTheme = false, dynamicColor = false) {
                UnifiedGamifiedCheckoutDialog(
                    pkg = DefaultCoinPackages.PACKAGES.first(),
                    onDismiss = {},
                    onProcessMpesa = { _, _ -> },
                    onProcessCard = { _, _, _ -> },
                    onProcessPaypal = {},
                    onProcessCrypto = {}
                )
            }
        }
    }
}

package com.cyclecare.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.cyclecare.app.data.economy.EconomyProfile
import com.cyclecare.app.ui.screens.WalletBalanceBlock
import com.cyclecare.app.ui.theme.CycleCareTheme
import com.cyclecare.app.ui.viewmodel.WalletStatus
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal

/**
 * Pixel proof for the identity-bound wallet card: each status renders its own
 * honest state, and a failed or missing fetch never looks like a real zero
 * balance. Layoutlib rendering, no emulator.
 */
class WalletStatusScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    private val royProfile = EconomyProfile(
        userId = "7c2dbf1b-1b5f-4b70-80d6-2aa737b3a325",
        coinBalance = BigDecimal("363"),
        lifetimeEarned = BigDecimal("400"),
        lifetimeSpent = BigDecimal("37")
    )

    private fun shot(status: WalletStatus, profile: EconomyProfile = royProfile) {
        paparazzi.snapshot {
            CycleCareTheme(darkTheme = true, dynamicColor = false) {
                Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface).padding(20.dp)) {
                    WalletBalanceBlock(profile = profile, walletStatus = status, onRetryWallet = {}, enableCoinIdleRotation = false)
                }
            }
        }
    }

    @Test fun walletReady() = shot(WalletStatus.READY)

    @Test fun walletStaleLastKnown() = shot(WalletStatus.STALE)

    @Test fun walletLoading() = shot(WalletStatus.LOADING)

    @Test fun walletNoSession() = shot(WalletStatus.NO_SESSION, EconomyProfile())

    @Test fun walletUnavailable() = shot(WalletStatus.UNAVAILABLE, EconomyProfile())
}

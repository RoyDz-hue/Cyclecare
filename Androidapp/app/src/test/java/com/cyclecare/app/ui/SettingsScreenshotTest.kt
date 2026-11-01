package com.cyclecare.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.cyclecare.app.data.economy.UserEconomyTier
import com.cyclecare.app.ui.screens.SettingsScreenContent
import com.cyclecare.app.ui.theme.CycleCareTheme
import org.junit.Rule
import org.junit.Test

/** Renders Settings through its content seam; callbacks are inert stubs. */
class SettingsScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    @Test
    fun settingsDefaultState() {
        paparazzi.snapshot {
            CycleCareTheme(darkTheme = false, dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    SettingsScreenContent(
                        uiState = homeBaseState(UserEconomyTier.FREE),
                        onNavigateBack = {},
                        onLoadSyncConflicts = {},
                        onBuildDataExportPdf = { ByteArray(0) },
                        onShowAuthSheet = {},
                        onSignOut = {},
                        onOpenVault = {},
                        onUpdateSettings = {},
                        onUpdateTonePreference = {},
                        onSetSoundEnabled = {},
                        onSetPinProtectionEnabled = {},
                        onSaveQuickUnlockPin = {},
                        onSetFingerprintProtectionEnabled = {},
                        onSetAiSharingAllowed = {},
                        onEraseAllData = {},
                        onResolveSyncConflict = { _, _ -> },
                        onTriggerManualSync = {}
                    )
                }
            }
        }
    }
}

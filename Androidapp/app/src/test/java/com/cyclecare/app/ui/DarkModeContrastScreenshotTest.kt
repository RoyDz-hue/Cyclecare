package com.cyclecare.app.ui

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityOptionsCompat
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.cyclecare.app.data.economy.UserEconomyTier
import com.cyclecare.app.ui.screens.AppLockScreen
import com.cyclecare.app.ui.screens.SettingsScreenContent
import com.cyclecare.app.ui.theme.CycleCareTheme
import org.junit.Rule
import org.junit.Test

/**
 * Both-mode contrast proof for screens whose root paints the themed
 * background through a Surface (so text without an explicit color inherits a
 * readable content color in dark AND light mode).
 */
class DarkModeContrastScreenshotTest {

    private val stubRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I : Any?, O : Any?> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?
            ) = Unit
        }
    }

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    @Test
    fun appLockDark() {
        paparazzi.snapshot {
            CycleCareTheme(darkTheme = true, dynamicColor = false) {
                AppLockScreen(onUnlock = { true })
            }
        }
    }

    @Test
    fun appLockLight() {
        paparazzi.snapshot {
            CycleCareTheme(darkTheme = false, dynamicColor = false) {
                AppLockScreen(onUnlock = { true })
            }
        }
    }

    @Test
    fun settingsDark() {
        paparazzi.snapshot {
            CycleCareTheme(darkTheme = true, dynamicColor = false) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides stubRegistryOwner) {
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
}

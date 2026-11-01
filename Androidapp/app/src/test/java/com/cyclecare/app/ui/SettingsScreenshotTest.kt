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
import com.cyclecare.app.ui.screens.SettingsScreenContent
import com.cyclecare.app.ui.theme.CycleCareTheme
import org.junit.Rule
import org.junit.Test

/** Renders Settings through its content seam; callbacks are inert stubs. */
class SettingsScreenshotTest {

    /** Export launcher registry stub; never launched while a screenshot renders. */
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
    fun settingsDefaultState() {
        paparazzi.snapshot {
            CycleCareTheme(darkTheme = false, dynamicColor = false) {
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

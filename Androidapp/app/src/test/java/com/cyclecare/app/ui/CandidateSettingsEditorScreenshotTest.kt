package com.cyclecare.app.ui
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.ui.screens.CandidateSettingsEditorContent
import com.cyclecare.app.ui.theme.CycleCareTheme
import com.cyclecare.app.ui.viewmodel.CycleCareViewModel
import org.junit.Rule
import org.junit.Test
class CandidateSettingsEditorScreenshotTest{
 @get:Rule val paparazzi=Paparazzi(deviceConfig=DeviceConfig.PIXEL_6)
 private fun render(state:CycleCareViewModel.EditorPublicationState,attempt:CycleCareViewModel.SettingsEditorStatus,known:Boolean=false){
  paparazzi.snapshot{CycleCareTheme(darkTheme=false,dynamicColor=false){CandidateSettingsEditorContent(CycleSettings(averageCycleLength=28,periodDuration=5),attempt,state,false,known,{},{},{},{})}}
 }
 @Test fun editingOrigin(){render(CycleCareViewModel.EditorPublicationState.APPLIED,CycleCareViewModel.SettingsEditorStatus.EDITING)}
 @Test fun savedReceiptIsNotCurrentRows(){render(CycleCareViewModel.EditorPublicationState.APPLIED,CycleCareViewModel.SettingsEditorStatus.SAVED,true)}
 @Test fun pendingOwnedCommand(){render(CycleCareViewModel.EditorPublicationState.PENDING,CycleCareViewModel.SettingsEditorStatus.SAVED,true)}
 @Test fun failedPublication(){render(CycleCareViewModel.EditorPublicationState.FAILED,CycleCareViewModel.SettingsEditorStatus.STORAGE_UNCERTAIN)}
}

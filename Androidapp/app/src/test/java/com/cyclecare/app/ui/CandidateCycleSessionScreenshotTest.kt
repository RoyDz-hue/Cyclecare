package com.cyclecare.app.ui
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.cyclecare.bridge.*
import com.cyclecare.app.ui.screens.CandidateCycleSessionContent
import com.cyclecare.app.ui.theme.CycleCareTheme
import org.junit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.LocalDate
class CandidateCycleSessionScreenshotTest{
 @get:Rule val paparazzi=Paparazzi(deviceConfig=DeviceConfig.PIXEL_6)
 @Test fun nativeSavedProjectionThenClosedReattach(){runBlocking{
  val file=File.createTempFile("cycle-render-synthetic-",".json").also{it.delete();CandidateCycleFixtureSource.writeSynthetic(it)}
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
  val day=LocalDate.of(2024,4,3)
  val session=CandidateCycleSession("synthetic-pixels",Any(),71,CandidateCycleFixtureSource(file),scope,today=day,main={it()},selected=day)
  try{
   session.start();val exact=withTimeout(3000){session.state.first{it.projection!=null||(!it.loading&&it.recovery!=null)}}
   Assert.assertNull("native required source failure",exact.recovery)
   Assert.assertEquals(1,exact.generation);Assert.assertEquals(3,exact.projection!!.native.selected!!.dayOfCycle)
   paparazzi.snapshot(name="native-generation1"){CycleCareTheme(darkTheme=false,dynamicColor=false){CandidateCycleSessionContent(session)}}
   session.close()
   paparazzi.snapshot(name="closed-reattach"){CycleCareTheme(darkTheme=false,dynamicColor=false){CandidateCycleSessionContent(session)}}
   Assert.assertTrue(session.state.value.closed);Assert.assertNull(session.state.value.projection)
  }finally{session.close();scope.cancel();file.delete()}
 }}
}

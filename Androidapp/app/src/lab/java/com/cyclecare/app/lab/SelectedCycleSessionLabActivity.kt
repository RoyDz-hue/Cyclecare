package com.cyclecare.app.lab
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.cyclecare.bridge.*
import com.cyclecare.app.ui.screens.CandidateCycleSessionContent
import com.cyclecare.app.ui.theme.CycleCareTheme
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.time.LocalDate

/** Nonlauncher/nonexported lab only. New synthetic file per instance, never
 * production provider. Close route before disposal; recreation new session.
 * No external build/device grant is implied by this source. */
class SelectedCycleSessionLabActivity:ComponentActivity(){
 private var session:CandidateCycleSession?=null
 private var fixture:File?=null
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  val file=File.createTempFile("cycle-session-synthetic-",".json",cacheDir).also{it.delete()}
  CandidateCycleFixtureSource.writeSynthetic(file);fixture=file
  val route=CandidateCycleSession("synthetic-calendar",Any(),System.nanoTime(),CandidateCycleFixtureSource(file),lifecycleScope,
   today=LocalDate.of(2024,4,3),selected=LocalDate.of(2024,4,3))
  session=route
  setContent{CycleCareTheme(darkTheme=false,dynamicColor=false){CandidateCycleSessionContent(route)}}
  route.start()
 }
 override fun onDestroy(){session?.close();fixture?.delete();super.onDestroy()}
}

package com.cyclecare.app.ui.screens
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.cyclecare.bridge.CandidateCycleSession
import com.cyclecare.app.ui.viewmodel.CycleUiState

/** OFF-by-default selected lab route. Sole subscription is tagged envelope.
 * Legacy uiState/logsMap/VM callbacks are not accepted. Adapter constructs a
 * display-only value for the existing real calendar content, never a VM sink.
 */
@Composable
internal fun CandidateCycleSessionContent(session:CandidateCycleSession){
 val envelope by session.state.collectAsState()
 DisposableEffect(session){onDispose{session.close()}}
 val e=envelope
 Surface(modifier=Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background,contentColor=MaterialTheme.colorScheme.onBackground){
 if(e.closed){Text("Calendar session closed",Modifier.testTag("cycle_session_closed"));return@Surface}
 Column(Modifier.testTag("cycle_session_${e.epoch}_${e.generation}")){
  Text("Synthetic calendar | session ${e.epoch} | generation ${e.generation}")
  Text("Read-only lab. Track editing is not enabled.")
  e.recovery?.let{Text(it);TextButton(onClick={session.retry(e.epoch)}){Text("Try again")}}
  val p=e.projection
  if(p==null){Text(if(e.loading)"Loading calendar estimates" else "Calendar unavailable");return@Column}
  val display=CycleUiState(settings=p.settings,selectedDate=p.selected,currentDate=p.today,
   hasCycleAnchor=p.native.hasAnchor,cycleStartIsApproximate=p.native.approximate,
   selectedDatePrediction=p.native.selected,todayPrediction=p.native.today,baselineMetrics=p.baseline)
  CycleScreenContent(display,p.logs,{date->p.native.month[date.toString()]},
   onSelectDate={session.selectDate(e.epoch,it)},onOpenTrackForDate={},
   controlledMonth=p.month,onDisplayedMonthChange={session.month(e.epoch,it)},trackEnabled=false)
 }
}
}

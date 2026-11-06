package com.cyclecare.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.local.rust.RustBoundRoomHost
import com.cyclecare.app.ui.viewmodel.CycleCareViewModel

/** Local candidate only. Not selected by production navigation or config. */
@Composable
internal fun CandidateSettingsEditorScreen(model:CycleCareViewModel){
    val exactCommand by model.candidateScreenCommand.collectAsStateWithLifecycle()
    val notice by model.candidateScreenNotice.collectAsStateWithLifecycle()
    val command=exactCommand
    val state=if(command==null)null else command.consumer.collectAsStateWithLifecycle().value
    CandidateSettingsEditorContent(
        origin=state?.origin,
        notice=notice,
        attempt=state?.attempt?:CycleCareViewModel.SettingsEditorStatus.CLOSED,
        publication=state?.publication,
        loading=command!=null&&state?.publication==null,
        knownSave=state?.knownSave==true,
        identityAllowsEdit=state?.editable==true,
        onOpen={model.startCandidateScreenCommand(CycleCareViewModel.EditorScreenAction.OPEN)},
        onRetry={model.startCandidateScreenCommand(CycleCareViewModel.EditorScreenAction.RETRY)},
        onAbandon={model.startCandidateScreenCommand(CycleCareViewModel.EditorScreenAction.ABANDON)},
        onIntent={model.startCandidateScreenCommand(intent=it)}
    )
}

@Composable
internal fun CandidateSettingsEditorContent(
    origin:CycleSettings?,attempt:CycleCareViewModel.SettingsEditorStatus,
    publication:CycleCareViewModel.EditorPublicationState?,loading:Boolean,knownSave:Boolean,
    onOpen:()->Unit,onRetry:()->Unit,onAbandon:()->Unit,
    onIntent:(RustBoundRoomHost.SettingsIntent)->Unit,notice:String?=null,identityAllowsEdit:Boolean=true
){
    val busy=loading||publication==CycleCareViewModel.EditorPublicationState.PENDING
    val editable=identityAllowsEdit&&!busy&&origin!=null&&attempt==CycleCareViewModel.SettingsEditorStatus.EDITING&&publication==CycleCareViewModel.EditorPublicationState.APPLIED
    Surface{
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("Cycle settings review",style=MaterialTheme.typography.headlineSmall)
            Text("Local test flow. Not the active Settings screen.",style=MaterialTheme.typography.bodySmall)
            if(notice!=null)Text(notice,color=MaterialTheme.colorScheme.error)
            Text("Editing origin",style=MaterialTheme.typography.titleMedium)
            Text("These values were read when editing opened. They are not a live view of current saved rows.")
            Text(if(origin==null)"No editing values available." else "Cycle: ${origin.averageCycleLength} days\nPeriod: ${origin.periodDuration} days\nPrivate notification text: ${if(origin.isDiscreetNotificationMode)"on" else "off"}")
            Text(when{
                loading->"Opening or saving. This command is still running."
                publication==CycleCareViewModel.EditorPublicationState.PENDING->"The display update is still pending. Wait before another change."
                publication==CycleCareViewModel.EditorPublicationState.SKIPPED_CLEARED->"This editor was closed. Open a fresh editor to continue."
                publication==CycleCareViewModel.EditorPublicationState.SKIPPED_STALE->"The session changed. These values cannot be used. Open a fresh editor."
                publication==CycleCareViewModel.EditorPublicationState.EXPIRED->"The display update expired before it ran. The save result is separate. Retry the exact save or reopen when allowed."
                publication==CycleCareViewModel.EditorPublicationState.FAILED->"The command or display update failed. A save may still have happened. Retry the exact save before changing it."
                publication==CycleCareViewModel.EditorPublicationState.NOT_STARTED->"The command was not started. Another command or unresolved save may block it."
                knownSave->"This exact change has a saved receipt. The values above remain the earlier editing origin. Reopen to read a fresh origin."
                editable->"Choose one change below. It saves separately; reopen before the next change."
                else->"Open an editor before changing values. Each change saves separately."
            })
            Text("Latest save attempt: ${attempt.name.lowercase().replace('_',' ')}. A display update is not a save receipt.",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(onClick=onOpen,enabled=!busy){Text("Open fresh")}
                OutlinedButton(onClick=onRetry,enabled=!busy){Text("Retry exact save")}
            }
            OutlinedButton(onClick=onAbandon,enabled=!busy){Text("Discard only if never attempted")}
            HorizontalDivider()
            Text("Save one change",style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(onClick={origin?.let{onIntent(RustBoundRoomHost.SettingsIntent.CycleLength(it.averageCycleLength-1))}},enabled=editable&&(origin?.averageCycleLength?:0)>21){Text("Cycle -1")}
                OutlinedButton(onClick={origin?.let{onIntent(RustBoundRoomHost.SettingsIntent.CycleLength(it.averageCycleLength+1))}},enabled=editable&&(origin?.averageCycleLength?:99)<45){Text("Cycle +1")}
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(onClick={origin?.let{onIntent(RustBoundRoomHost.SettingsIntent.PeriodDuration(it.periodDuration-1))}},enabled=editable&&(origin?.periodDuration?:0)>2){Text("Period -1")}
                OutlinedButton(onClick={origin?.let{onIntent(RustBoundRoomHost.SettingsIntent.PeriodDuration(it.periodDuration+1))}},enabled=editable&&(origin?.periodDuration?:99)<10){Text("Period +1")}
            }
            OutlinedButton(onClick={origin?.let{onIntent(RustBoundRoomHost.SettingsIntent.DiscreetNotifications(!it.isDiscreetNotificationMode))}},enabled=editable){Text("Toggle private notification text")}
        }
    }
}

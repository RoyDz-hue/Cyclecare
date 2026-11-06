package com.cyclecare.app.data.local.rust
import com.cyclecare.app.data.local.CycleDayLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
/** Narrow UI sink: no user-supplied callback can reenter or do IO under guard.
 * The only explicit paint is a StateFlow value assignment on main. Do not use
 * Unconfined/reentrant collectors: StateFlow can resume a collector immediately.
 * This is candidate sink only; actual UI integration remains unverified. Consumers must
 * replace this sink on identity change rather than relabel old rendered rows.
 */
internal class RustLogPublication(private val host:RustBoundRoomHost){
 private val mutable=MutableStateFlow<List<CycleDayLog>>(emptyList())
 val rows:StateFlow<List<CycleDayLog>> = mutable.asStateFlow()
 fun submit(snapshot:RustBoundRoomHost.Snapshot,complete:(RustBoundRoomHost.Result<Unit>)->Unit={}){
  host.publishSnapshot(snapshot,{mutable.value=it},complete)
 }
}

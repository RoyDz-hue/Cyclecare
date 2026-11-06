package com.cyclecare.app.data.local.rust
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
/** Explicit injection only, no production constructor or auto-enable flag. All
 * unsupported writes must fail closed in repository while this port is selected.
 * Lifecycle replacement creates a new port/token, never relabels old snapshots.
 */
internal class RustRepositoryPort(val host:RustBoundRoomHost,val token:RustBoundRoomHost.Token) {
 class Failure(val result:RustBoundRoomHost.Result<*>):IllegalStateException("rust_repository_${result.javaClass.simpleName}")
 private fun <T> value(result:RustBoundRoomHost.Result<T>):T=when(result){is RustBoundRoomHost.Result.Value->result.value;else->throw Failure(result)}
 suspend fun settingsSnapshot()=withContext(Dispatchers.IO){value(host.ensureSettings(token,java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString(),System.currentTimeMillis()))}
 suspend fun logs():List<CycleDayLog> = withContext(Dispatchers.IO){value(host.read(token)).rows}
 suspend fun domain(date:String?=null)=withContext(Dispatchers.IO){value(host.readDomain(token,date))}
 val observations:Flow<RustBoundRoomHost.Snapshot> get()=host.observe(token).map{value(it)}.catch{error->if(error !is Failure||error.result !is RustBoundRoomHost.Result.Stale)throw error}
 val settingsObservations get()=observations.map{domain().settings}.catch{error->if(error !is Failure||error.result !is RustBoundRoomHost.Result.Stale)throw error}
 val profileObservations get()=observations.map{domain().profile}.catch{error->if(error !is Failure||error.result !is RustBoundRoomHost.Result.Stale)throw error}
 suspend fun save(log:CycleDayLog,lease:RustBoundRoomHost.CommitLease?=null) = withContext(Dispatchers.IO){host.withCommitLease(lease){
  val operation=java.util.UUID.randomUUID().toString();val children=listOf(java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString())
  // Host determines child count from Rust-selected anchor/missing settings.
  commit(value(host.prepareTrackAuto(token,operation,log.dateString,log,System.currentTimeMillis(),children)))
 }}
 suspend fun delete(date:String,lease:RustBoundRoomHost.CommitLease?=null)=withContext(Dispatchers.IO){host.withCommitLease(lease){val operation=java.util.UUID.randomUUID().toString();commit(value(host.prepareTrack(token,operation,date,null,System.currentTimeMillis(),listOf(java.util.UUID.randomUUID().toString()))))}}
 suspend fun saveRows(rows:List<RustDomainRows.Row>,lease:RustBoundRoomHost.CommitLease?=null)=withContext(Dispatchers.IO){host.withCommitLease(lease){val operation=java.util.UUID.randomUUID().toString();commit(value(host.prepareRows(token,operation,System.currentTimeMillis(),rows,rows.map{java.util.UUID.randomUUID().toString()})))}}
 suspend fun settingsEditBase(lease:RustBoundRoomHost.CommitLease)=withContext(Dispatchers.IO){host.withCommitLease(lease){value(host.settingsEditBase(token))}}
 suspend fun prepareSettingsIntent(base:RustBoundRoomHost.SettingsEditBase,intent:RustBoundRoomHost.SettingsIntent,operation:String,children:List<String>,now:Long,lease:RustBoundRoomHost.CommitLease)=withContext(Dispatchers.IO){host.withCommitLease(lease){value(host.prepareSettingsIntent(base,intent,operation,children,now))}}
 suspend fun commitSettingsIntent(prepared:RustBoundRoomHost.PreparedSettingsEdit,lease:RustBoundRoomHost.CommitLease)=withContext(Dispatchers.IO){host.withCommitLease(lease){value(host.commitSettingsIntent(prepared))}}
 suspend fun updateSettings(settings:com.cyclecare.app.data.local.CycleSettings,previousAverage:Int,lease:RustBoundRoomHost.CommitLease?=null)=withContext(Dispatchers.IO){host.withCommitLease(lease){value(host.updateSettings(token,java.util.UUID.randomUUID().toString(),List(2){java.util.UUID.randomUUID().toString()},System.currentTimeMillis(),settings,previousAverage))}}
 suspend fun setPeriodAnchor(date:String,lease:RustBoundRoomHost.CommitLease?=null)=withContext(Dispatchers.IO){host.withCommitLease(lease){value(host.setPeriodAnchor(token,java.util.UUID.randomUUID().toString(),List(2){java.util.UUID.randomUUID().toString()},System.currentTimeMillis(),date))}}
 suspend fun completeOnboarding(profile:com.cyclecare.app.data.local.OnboardingProfile,enableLock:Boolean,lease:RustBoundRoomHost.CommitLease?=null)=withContext(Dispatchers.IO){host.withCommitLease(lease){value(host.completeOnboarding(token,java.util.UUID.randomUUID().toString(),List(2){java.util.UUID.randomUUID().toString()},System.currentTimeMillis(),profile,enableLock))}}
 private fun commit(prepared:RustBoundRoomHost.PreparedTrack){when(value(host.commitTrack(prepared))){is RustRoomBatch.Outcome.Committed->Unit;else->throw IllegalStateException("rust_commit_conflict")}}
 suspend fun pendingConflicts()=withContext(Dispatchers.IO){value(host.pendingConflicts(token))}
 suspend fun resolveConflict(record:String,retain:Boolean,lease:RustBoundRoomHost.CommitLease?=null)=withContext(Dispatchers.IO){host.withCommitLease(lease){value(host.resolveConflict(token,record,retain,java.util.UUID.randomUUID().toString(),System.currentTimeMillis()))}}
 suspend fun wipe()=withContext(Dispatchers.IO){value(host.wipe(token))}
 fun isCurrent():Boolean=host.isCurrent(token)
 fun verifyPreferences(preferences:AuthPreferences){check(token.preference==preferences.rustOperationSnapshot()){"stale repository port"}}
 internal fun isBoundToPreferences(preferences:AuthPreferences):Boolean=host.ownsPreferences(preferences)
 fun unsupported():Nothing=throw IllegalStateException("rust_writer_not_routed")
}

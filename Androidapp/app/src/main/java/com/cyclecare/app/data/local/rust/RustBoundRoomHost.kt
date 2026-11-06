package com.cyclecare.app.data.local.rust

import com.cyclecare.app.security.AuthPreferences
import com.cyclecare.app.data.local.OwnerDataGate
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.CycleDayLog
import androidx.room.InvalidationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Inert integration candidate: no constructor/caller in production wiring.
 * Same captured prefs object is locked through synchronous Room transaction and
 * publication. Never hold a JVM monitor across suspend/withTransaction. Caller
 * invokes operations on IO, not UI. Existing keys/provider unchanged.
 */
internal class RustBoundRoomHost(private val preferences:AuthPreferences,private val nativePlan:(String)->String=RustTrackNative::plan) {
 class Token internal constructor(val preference:AuthPreferences.RustOperationSnapshot,val database:CycleCareDatabase,val epoch:Long,val instance:String=java.util.UUID.randomUUID().toString(),val lifecycle:OwnerDataGate.Snapshot)
 class Snapshot internal constructor(val token:Token,val rows:List<CycleDayLog>)
 sealed class Result<out T> {data class Value<T>(val value:T):Result<T>();object Stale:Result<Nothing>();object Overflow:Result<Nothing>();object Storage:Result<Nothing>();object Corrupt:Result<Nothing>();data class Rejected(val reason:String):Result<Nothing>()}
 private val lock=Any()
 /** Synchronous routing lease, acquired AFTER prefs/lifecycle/host and held
  * through Room commit. Never retained across coroutine suspension. */
 internal interface CommitLease { fun <T> admit(action:()->T):T? }
 private val operationLease=ThreadLocal<CommitLease?>()
 internal fun <T> withCommitLease(lease:CommitLease?,action:()->T):T {
  val previous=operationLease.get();operationLease.set(lease)
  return try{action()}finally{operationLease.set(previous)}
 }

 private var active:Token?=null
 private class UncertainChoice(val original:Token,val expected:AuthPreferences.RustOperationSnapshot,val enabled:Boolean,val epoch:Long,val previous:Boolean,val previousPending:Boolean)
 private var uncertainChoice:UncertainChoice?=null
 private val issuedDispatches=java.util.Collections.newSetFromMap(java.util.WeakHashMap<HistoryDispatch,Boolean>())
 /** Must be preopened/validated by existing key-preserving provider, never here.
  * No profile/owner inference, no new key generation or DB migration on publish.
  */
 fun publish(expected:Token?,database:CycleCareDatabase,captured:AuthPreferences.RustOperationSnapshot):Result<Token> =
  preferences.runIfRustOperationUnchanged(captured) {
   val lifecycle=OwnerDataGate.snapshot(preferences.context,captured.operation.owner)
   OwnerDataGate.runIfUnchanged(preferences.context,captured.operation.owner,lifecycle){
    synchronized(lock) {
     if(active!=expected)return@synchronized Result.Stale
     val epoch=try{Math.addExact(active?.epoch?:0L,1L)}catch(_:ArithmeticException){return@synchronized Result.Overflow}
     val next=Token(captured,database,epoch,lifecycle=lifecycle);active=next;Result.Value(next)
    }
   }?:Result.Stale
  }?:Result.Stale
 /** Own onboarding choice only. Old token must still own this host before
  * mutation; replacement keeps the exact opened DB and lifecycle. No caller
  * can relabel an arbitrary stale token with a newer preference snapshot.
  */
 fun onboardingChoice(token:Token,enabled:Boolean):Result<Token> = guarded(token){
  val before=token.preference
  val previous=preferences.isCloudSyncAllowed
  val previousPending=preferences.hasPendingHealthSyncRevoke
  val generation=if(previous==enabled)before.operation.consentGeneration else Math.addExact(before.operation.consentGeneration,1L)
  val nextEpoch=if(previous==enabled)token.epoch else Math.addExact(token.epoch,1L)
  val expected=before.copy(operation=before.operation.copy(consentGeneration=generation))
  try{preferences.setCloudSyncChoiceWithoutCallouts(enabled,true)}catch(error:Exception){
   uncertainChoice=UncertainChoice(token,expected,enabled,nextEpoch,previous,previousPending)
   throw error
  }
  try{
  val after=preferences.rustOperationSnapshot()
  check(after==before.copy(operation=before.operation.copy(consentGeneration=generation))) {"onboarding_choice_changed_identity"}
  check(preferences.isCloudSyncAllowed==enabled)
  val next=if(after==before)token else Token(after,token.database,nextEpoch,lifecycle=token.lifecycle)
  active=next;uncertainChoice=null
  next
  }catch(error:Exception){uncertainChoice=UncertainChoice(token,expected,enabled,nextEpoch,previous,previousPending);throw error}
 }
 /** Explicit recovery of a failed write issued by this host, not a later
  * snapshot adopted as authority. A new commit must succeed first. */
 fun recoverUncertainChoice(original:Token):Result<Token>{
  val gate=preferences.publicationAdmission?:return Result.Rejected("admission_required")
  if(!gate.isTransitionAction()){
   if(gate.isOnDrain()||android.os.Looper.myLooper()==android.os.Looper.getMainLooper())return Result.Rejected("io_thread_required")
   return gate.exclusiveIo{recoverUncertainChoice(original)}.join()
  }
  return try{
   val recovery=synchronized(lock){uncertainChoice?.takeIf{it.original===original&&active===original}}?:return Result.Stale
   preferences.runIfRustOperationUnchanged(recovery.expected){
    OwnerDataGate.runIfUnchanged(preferences.context,original.preference.operation.owner,original.lifecycle){synchronized(lock){
     if(uncertainChoice!==recovery||active!==original||original.lifecycle.wiped)return@synchronized Result.Stale
     var persisted=false
     if(!recovery.enabled)gate.afterCurrentAction{if(persisted)preferences.cancelOwnDisabledSyncChoice(recovery.expected)}
     if(!preferences.persistExactCloudChoice(recovery.expected,recovery.enabled))return@synchronized Result.Storage
     persisted=true
     val next=Token(recovery.expected,original.database,recovery.epoch,lifecycle=original.lifecycle)
     active=next;uncertainChoice=null;Result.Value(next)
    }}?:Result.Stale
   }?:Result.Stale
  }catch(_:Exception){Result.Storage}
 }

 /** A failed-before-write attempt can be abandoned only while the exact old
  * token, preferences and lifecycle are still current. No changed prefs are
  * rebound and no new capability is issued. Caller may then submit anew.
  */
 fun abandonUnchangedChoice(original:Token):Result<Boolean> = guarded(original){
  val attempt=uncertainChoice
  if(attempt==null||attempt.original!==original)false
  else if(preferences.rustOperationSnapshot()!=original.preference||preferences.isCloudSyncAllowed!=attempt.previous||preferences.hasPendingHealthSyncRevoke!=attempt.previousPending)false
  else {uncertainChoice=null;true}
 }

 fun isCurrent(token:Token):Boolean=preferences.runIfRustOperationUnchanged(token.preference){OwnerDataGate.runIfUnchanged(preferences.context,token.preference.operation.owner,token.lifecycle){synchronized(lock){active==token&&!token.lifecycle.wiped}}?:false}?:false
 /** Narrow INSTANCE identity: the attach-scoped preferences must BE this
  * host's preferences; equal values on another instance are not identity. */
 internal fun ownsPreferences(candidate:AuthPreferences):Boolean=preferences===candidate
 /** Owner-run timing probe only, optional and never invoked under locks. */
 internal var transactionTiming:((Long)->Unit)?=null
 /** Test-only probe inside the guarded lock chain (preferences monitor + owner
  * lifecycle + host monitor held), immediately before the Room transaction is
  * attempted. Used by the bounded lock-order witness fixture. */
 internal var beforeGuardedTransactionForTests:(()->Unit)?=null
 /** This adapter accepts only a synchronous transaction function, e.g. JNI plan
  * validation plus bit-aware SQL writes using the SAME SupportSQLiteDatabase.
  * No returned raw connection or suspending DAO call may escape block.
  */
 private fun <T> transaction(token:Token,expectedServerGeneration:Long?=null,block:(androidx.sqlite.db.SupportSQLiteDatabase)->T):Result<T> {
  val start=System.nanoTime();val result=guarded(token,expectedServerGeneration) {
  fun runTransaction():Result<T> {
   var value:T?=null
   beforeGuardedTransactionForTests?.invoke()
   token.database.runInTransaction {value=block(token.database.openHelper.writableDatabase)}
   @Suppress("UNCHECKED_CAST") return Result.Value(value as T)
  }
  val lease=operationLease.get()
  if(lease==null)runTransaction() else lease.admit{runTransaction()}?:Result.Stale
  };transactionTiming?.invoke(System.nanoTime()-start)
  return when(result){is Result.Value->result.value;Result.Stale->Result.Stale;Result.Storage->Result.Storage;Result.Corrupt->Result.Corrupt;Result.Overflow->Result.Overflow;is Result.Rejected->result}
 }
 private fun <T> guarded(token:Token,expectedServerGeneration:Long?=null,block:()->T):Result<T> {return try {
  val admission=preferences.publicationAdmission
  if(admission!=null&&!admission.isTransitionAction()){
   if(admission.isOnDrain())return Result.Rejected("use_async_host_from_publication")
   if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())return Result.Rejected("io_thread_required")
   val lease=operationLease.get()
   return admission.exclusiveIo{withCommitLease(lease){guarded(token,expectedServerGeneration,block)}}.join()
  }
  if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())return Result.Rejected("io_thread_required")
  fun gated():Result<T> = OwnerDataGate.runIfUnchanged(preferences.context,token.preference.operation.owner,token.lifecycle){
   synchronized(lock) {if(active!=token||token.lifecycle.wiped)Result.Stale else Result.Value(block())}
  }?:Result.Stale
  // Sync dispatch also binds the SERVER consent generation inside the same
  // prefs lock, before any SQL: a receipt/withdrawal landing mid-flight is
  // typed Stale, never a storage-looking failure. Pending-revoke writes
  // remain outside this lock (stated gap; in-SQL checks are retained).
  if(expectedServerGeneration==null)preferences.runIfRustOperationUnchanged(token.preference){gated()}?:Result.Stale
  else preferences.runIfRustSyncUnchanged(token.preference,expectedServerGeneration){gated()}?:Result.Stale
 }catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
 catch(joined:java.util.concurrent.CompletionException){
  val cause=joined.cause
  if(cause is kotlinx.coroutines.CancellationException)throw cause
  if(cause is ArithmeticException)Result.Overflow else Result.Storage
 }catch(e:ConflictRejected){Result.Rejected(e.reason)}catch(_:LinkageError){Result.Storage}catch(_:ArithmeticException){Result.Overflow}catch(_:CorruptRustFloat){Result.Corrupt}catch(_:Exception){Result.Storage}
 }
 /** Owner key and generation derive only from guarded actual preference token,
  * never an unbound external intent. Guest sync owner remains legacy_guest.
  */
 fun commitLog(token:Token,date:String,intent:RustRoomCommit.LogIntent):Result<RustRoomCommit.Outcome> = transaction(token) {sql->
  val op=token.preference.operation
  val owner=op.owner?.takeIf{it.isNotBlank()}?:"legacy_guest"
  require(intent.namespace==(op.owner?.takeIf{it.isNotBlank()}?.let(com.cyclecare.app.data.local.CycleCareDatabase::namespaceFor)?:"cycle_care_database"))
  require(intent.generation==op.consentGeneration)
  val binding=org.json.JSONArray().put(op.owner?:org.json.JSONObject.NULL).put(op.effectiveUserId).put(op.session).put(op.consentGeneration).put(token.preference.localOnly).put(token.preference.accountMode).put(token.epoch).put(token.instance).put(token.lifecycle.generation).toString()
  RustRoomCommit.apply(sql,owner,date,intent,binding)
 }
 class PreparedTrack internal constructor(val token:Token,val intent:RustRoomBatch.Intent,val nativeDigest:String)
 /** Captures exact physical bases, derives missing settings from same profile,
  * invokes Rust and validates its immutable result inside guarded read tx.
  * Commit is a second guarded write tx with receipt-before-OCC of these bases.
  */
 fun prepareTrack(token:Token,operation:String,date:String,log:CycleDayLog?,now:Long,children:List<String>):Result<PreparedTrack> = prepareTrackInner(token,operation,date,log,now,children,false)
 fun prepareTrackAuto(token:Token,operation:String,date:String,log:CycleDayLog?,now:Long,children:List<String>):Result<PreparedTrack> = prepareTrackInner(token,operation,date,log,now,children,true)
 private data class TrackCapture(val logBase:RustRoomBatch.Base,val settingsBase:RustRoomBatch.Base,val profileBase:RustRoomBatch.Base,val settings:com.cyclecare.app.data.local.CycleSettings,val missing:Boolean)
 private fun prepareTrackInner(token:Token,operation:String,date:String,log:CycleDayLog?,now:Long,children:List<String>,autoChildren:Boolean):Result<PreparedTrack> {
  val capture=transaction(token){sql->
  val op=token.preference.operation;val owner=op.owner?.takeIf{it.isNotBlank()}?:"legacy_guest"
  val namespace=op.owner?.takeIf{it.isNotBlank()}?.let(CycleCareDatabase::namespaceFor)?:"cycle_care_database"
  fun base(id:String):RustRoomBatch.Base {
   var rev=0L;var server=0L
   sql.query("SELECT revision,serverRevision FROM health_sync_revisions WHERE ownerId=? AND recordId=?",arrayOf(owner,id)).use{c->if(c.moveToFirst()){rev=c.getLong(0);server=c.getLong(1)}}
   return RustRoomBatch.Base(id,rev,RustDomainRows.physical(RustDomainRows.read(sql,id)),server,RustDomainRows.storedPhysical(sql,id))
  }
  val logBase=base(date);val settingsBase=base("settings");val profileBase=base("profile")
  val stored=RustDomainRows.read(sql,"settings") as? RustDomainRows.Row.Settings
  val profile=if(stored==null)RustDomainRows.read(sql,"profile") as? RustDomainRows.Row.Profile else null
  val settings=stored?.value?:com.cyclecare.app.data.local.CycleSettings(isOnboardingCompleted=profile?.value?.isCompleted==true,selectedGoalsCsv=profile?.value?.selectedGoalsCsv.orEmpty(),lastPeriodStartDate=profile?.value?.lastPeriodDateString.orEmpty(),averageCycleLength=profile?.value?.cycleLengthDays?:28,periodDuration=profile?.value?.periodDurationDays?:5,lutealPhaseDays=14,remindFertile=profile?.value?.remindFertileWindow?:false)
   TrackCapture(logBase,settingsBase,profileBase,settings,stored==null)
  }
  if(capture !is Result.Value)return when(capture){Result.Stale->Result.Stale;Result.Overflow->Result.Overflow;Result.Corrupt->Result.Corrupt;else->Result.Storage}
  return try{
   // No prefs/lifecycle/host/Room monitor during pure native planning. Commit
   // checks exact captured physical bases and full identity after this gap.
   val c=capture.value;val settings=c.settings;val op=token.preference.operation
   val namespace=op.owner?.takeIf{it.isNotBlank()}?.let(CycleCareDatabase::namespaceFor)?:"cycle_care_database"
   val identity=RustTrackWire.Identity(namespace,op.effectiveUserId,token.instance,token.epoch,op.session,op.consentGeneration,token.preference.localOnly)
   val reply=nativePlan(RustTrackWire.request(identity,operation,date,c.logBase.revision,c.settingsBase.revision,log,settings))
   val plan=RustTrackWire.validate(reply,identity,operation,date,c.logBase.revision,c.settingsBase.revision,log,settings)
   if(!isCurrent(token))return Result.Stale
   val writeSettings=log!=null&&(c.missing||plan.anchor)
   require(if(autoChildren)children.size==2 else children.size==if(writeSettings)2 else 1)
   val reads=mutableListOf<RustRoomBatch.Base>();if(log!=null){reads.add(c.settingsBase);if(c.missing)reads.add(c.profileBase)}
   val mutations=mutableListOf(RustRoomBatch.Mutation(c.logBase,log?.let(RustDomainRows.Row::Log),children[0]))
   if(writeSettings)mutations.add(RustRoomBatch.Mutation(c.settingsBase,RustDomainRows.Row.Settings(if(plan.anchor)settings.copy(lastPeriodStartDate=date)else settings),children[1]))
   Result.Value(PreparedTrack(token,RustRoomBatch.Intent(namespace,operation,op.consentGeneration,now,reads.toList(),mutations.toList(),plan.digest),plan.digest))
  }catch(_:LinkageError){Result.Storage}catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}catch(_:ArithmeticException){Result.Overflow}catch(_:Exception){Result.Storage}
 }

 fun commitTrack(prepared:PreparedTrack):Result<RustRoomBatch.Outcome> = commitBatch(prepared.token,prepared.intent)
 /** Typed non-Track writers share guard/physical OCC/journal. Policy remains
  * caller's existing semantics, never inferred from remote sync JSON alone.
  */
 fun prepareRows(token:Token,operation:String,now:Long,rows:List<RustDomainRows.Row>,children:List<String>):Result<PreparedTrack> = transaction(token){sql->
  require(rows.isNotEmpty()&&rows.size==children.size)
  val op=token.preference.operation;val owner=op.owner?.takeIf{it.isNotBlank()}?:"legacy_guest"
  val namespace=op.owner?.takeIf{it.isNotBlank()}?.let(CycleCareDatabase::namespaceFor)?:"cycle_care_database"
  val mutations=rows.mapIndexed{index,row->val id=RustDomainRows.record(row);var rev=0L;var server=0L
   sql.query("SELECT revision,serverRevision FROM health_sync_revisions WHERE ownerId=? AND recordId=?",arrayOf(owner,id)).use{c->if(c.moveToFirst()){rev=c.getLong(0);server=c.getLong(1)}}
   RustRoomBatch.Mutation(RustRoomBatch.Base(id,rev,RustDomainRows.physical(RustDomainRows.read(sql,id)),server,RustDomainRows.storedPhysical(sql,id)),row,children[index])}
  PreparedTrack(token,RustRoomBatch.Intent(namespace,operation,op.consentGeneration,now,emptyList(),mutations,"typed-full-row-v1"),"typed-full-row-v1")
 }
 private fun capturedBase(sql:androidx.sqlite.db.SupportSQLiteDatabase,owner:String,id:String):RustRoomBatch.Base {
  var rev=0L;var server=0L;sql.query("SELECT revision,serverRevision FROM health_sync_revisions WHERE ownerId=? AND recordId=?",arrayOf(owner,id)).use{c->if(c.moveToFirst()){rev=c.getLong(0);server=c.getLong(1)}}
  return RustRoomBatch.Base(id,rev,RustDomainRows.physical(RustDomainRows.read(sql,id)),server,RustDomainRows.storedPhysical(sql,id))
 }
 private fun derivedSettings(profile:com.cyclecare.app.data.local.OnboardingProfile?)=com.cyclecare.app.data.local.CycleSettings(isOnboardingCompleted=profile?.isCompleted==true,selectedGoalsCsv=profile?.selectedGoalsCsv.orEmpty(),lastPeriodStartDate=profile?.lastPeriodDateString.orEmpty(),averageCycleLength=profile?.cycleLengthDays?:28,periodDuration=profile?.periodDurationDays?:5,lutealPhaseDays=14,remindFertile=profile?.remindFertileWindow?:false)
 /** First read mirrors production: default derived from same profile and
  * materialized with revision/outbox/receipt in ONE identity-guarded transaction.
  */
 fun ensureSettings(token:Token,operation:String,child:String,now:Long):Result<DomainSnapshot> = transaction(token){sql->
  val existing=RustDomainRows.read(sql,"settings") as? RustDomainRows.Row.Settings
  val profile=(RustDomainRows.read(sql,"profile") as? RustDomainRows.Row.Profile)?.value
  val settings=existing?.value?:derivedSettings(profile)
  if(existing==null){
   val op=token.preference.operation;val owner=op.owner?.takeIf{it.isNotBlank()}?:"legacy_guest"
   val namespace=op.owner?.takeIf{it.isNotBlank()}?.let(CycleCareDatabase::namespaceFor)?:"cycle_care_database"
   val intent=RustRoomBatch.Intent(namespace,operation,op.consentGeneration,now,listOf(capturedBase(sql,owner,"profile")),listOf(RustRoomBatch.Mutation(capturedBase(sql,owner,"settings"),RustDomainRows.Row.Settings(settings),child)),"first-settings-v1")
   check(applyBatch(sql,token,intent) is RustRoomBatch.Outcome.Committed)
  }
  DomainSnapshot(token,null,settings,profile)
 }
 /** Source-issued settings editing base, not caller-built row equality. */
 class SettingsEditBase private constructor(internal val host:RustBoundRoomHost,internal val token:Token,internal val base:RustRoomBatch.Base,val settings:com.cyclecare.app.data.local.CycleSettings){
  companion object{internal fun capture(host:RustBoundRoomHost,token:Token,base:RustRoomBatch.Base,settings:com.cyclecare.app.data.local.CycleSettings)=SettingsEditBase(host,token,base,settings)}
 }
 sealed class SettingsIntent {
  data class CycleLength(val days:Int):SettingsIntent()
  data class PeriodDuration(val days:Int):SettingsIntent()
  data class DiscreetNotifications(val enabled:Boolean):SettingsIntent()
 }
 fun settingsEditBase(token:Token):Result<SettingsEditBase> = transaction(token){sql->
  val settings=(RustDomainRows.read(sql,"settings") as? RustDomainRows.Row.Settings)?.value?:throw ConflictRejected("settings_missing")
  val owner=token.preference.operation.owner?.takeIf{it.isNotBlank()}?:"legacy_guest"
  SettingsEditBase.capture(this,token,capturedBase(sql,owner,"settings"),settings)
 }
 class PreparedSettingsEdit private constructor(internal val host:RustBoundRoomHost,internal val token:Token,internal val intent:RustRoomBatch.Intent,val projection:DomainSnapshot){
  companion object{internal fun create(host:RustBoundRoomHost,token:Token,intent:RustRoomBatch.Intent,projection:DomainSnapshot)=PreparedSettingsEdit(host,token,intent,projection)}
 }
 /** Prepare freezes profile merge and exact operation/children ONCE. Retries use
  * the prepared value, so receipt lookup precedes OCC and cannot re-mint writes. */
 fun prepareSettingsIntent(base:SettingsEditBase,intent:SettingsIntent,operation:String,children:List<String>,now:Long):Result<PreparedSettingsEdit> = transaction(base.token){sql->
  if(base.host!==this)throw ConflictRejected("settings_base_wrong_host")
  val token=base.token;val owner=token.preference.operation.owner?.takeIf{it.isNotBlank()}?:"legacy_guest"
  val actual=capturedBase(sql,owner,"settings")
  if(actual!=base.base)throw ConflictRejected("stale_settings_edit")
  val current=(RustDomainRows.read(sql,"settings") as? RustDomainRows.Row.Settings)?.value?:throw ConflictRejected("settings_missing")
  val profile=(RustDomainRows.read(sql,"profile") as? RustDomainRows.Row.Profile)?.value
  val settings=when(intent){
   is SettingsIntent.CycleLength->{if(intent.days !in 21..45)throw ConflictRejected("invalid_cycle_length");current.copy(averageCycleLength=intent.days)}
   is SettingsIntent.PeriodDuration->{if(intent.days !in 2..10)throw ConflictRejected("invalid_period_duration");current.copy(periodDuration=intent.days)}
   is SettingsIntent.DiscreetNotifications->current.copy(isDiscreetNotificationMode=intent.enabled)
  }
  val changed=when(intent){
   is SettingsIntent.CycleLength->profile?.copy(cycleLengthDays=intent.days,cycleLengthOption="${intent.days} days")
   is SettingsIntent.PeriodDuration->profile?.copy(periodDurationDays=intent.days)
   is SettingsIntent.DiscreetNotifications->null
  }
  val rows=listOfNotNull(RustDomainRows.Row.Settings(settings),changed?.let(RustDomainRows.Row::Profile))
  if(children.size!=rows.size||children.any{it.isBlank()||it.length>128}||children.toSet().size!=children.size)throw ConflictRejected("invalid_settings_children")
  if(operation.isBlank()||operation.length>128||now<0)throw ConflictRejected("invalid_settings_operation")
  val namespace=token.preference.operation.owner?.takeIf{it.isNotBlank()}?.let(CycleCareDatabase::namespaceFor)?:"cycle_care_database"
  val mutations=rows.mapIndexed{index,row->RustRoomBatch.Mutation(capturedBase(sql,owner,RustDomainRows.record(row)),row,children[index])}
  val batch=RustRoomBatch.Intent(namespace,operation,token.preference.operation.consentGeneration,now,emptyList(),mutations,"settings-field-intent-v1")
  PreparedSettingsEdit.create(this,token,batch,DomainSnapshot(token,null,settings,changed?:profile))
 }
 fun commitSettingsIntent(prepared:PreparedSettingsEdit):Result<RustRoomBatch.Outcome> {
  if(prepared.host!==this)return Result.Rejected("settings_intent_wrong_host")
  return commitBatch(prepared.token,prepared.intent)
 }
 fun applySettingsIntent(base:SettingsEditBase,intent:SettingsIntent,operation:String,children:List<String>,now:Long):Result<DomainSnapshot> {
  // Compatibility convenience; durable/retry-aware callers keep PreparedSettingsEdit.
  val prepared=prepareSettingsIntent(base,intent,operation,children,now)
  if(prepared !is Result.Value)return when(prepared){Result.Stale->Result.Stale;Result.Storage->Result.Storage;Result.Corrupt->Result.Corrupt;Result.Overflow->Result.Overflow;is Result.Rejected->prepared;else->Result.Storage}
  return when(val committed=commitSettingsIntent(prepared.value)){
   is Result.Value->if(committed.value is RustRoomBatch.Outcome.Committed)Result.Value(prepared.value.projection)else Result.Rejected("settings_intent_conflict")
   Result.Stale->Result.Stale;Result.Storage->Result.Storage;Result.Corrupt->Result.Corrupt;Result.Overflow->Result.Overflow;is Result.Rejected->committed
  }
 }
 /** Composite settings/profile writer: full profile read and both effects in
  * one guarded tx, no stale read before writer lock and no loss of local fields.
  */
 fun updateSettings(token:Token,operation:String,children:List<String>,now:Long,settings:com.cyclecare.app.data.local.CycleSettings,previousAverage:Int):Result<DomainSnapshot> = transaction(token){sql->
  require(children.size==2)
  val profile=(RustDomainRows.read(sql,"profile") as? RustDomainRows.Row.Profile)?.value
  // Same native-first profile-sync decision as the Room path (M10); null
  // reply falls back to the verbatim Kotlin expression.
  val profileSync=com.cyclecare.app.rust.CycleCareCoreBridge.syncProfileOnSettings(profile?.cycleLengthOption,settings.averageCycleLength,previousAverage)
  val changed=when{
   profileSync==null->profile?.copy(cycleLengthOption=if(settings.averageCycleLength!=previousAverage)"${settings.averageCycleLength} days"else profile.cycleLengthOption,cycleLengthDays=settings.averageCycleLength,periodDurationDays=settings.periodDuration)
   profileSync.writeProfile&&profile!=null->profile.copy(cycleLengthOption=profileSync.cycleLengthOption!!,cycleLengthDays=settings.averageCycleLength,periodDurationDays=settings.periodDuration)
   else->null
  }
  applyRowsNow(sql,token,operation,children,now,listOfNotNull(RustDomainRows.Row.Settings(settings),changed?.let(RustDomainRows.Row::Profile)))
  DomainSnapshot(token,null,settings,changed?:profile)
 }
 /** Scoped anchor intent merges into current physical rows, never a caller's
  * old fullsettings/profile value. Both rows journal in this guarded tx.
  */
 fun setPeriodAnchor(token:Token,operation:String,children:List<String>,now:Long,date:String):Result<DomainSnapshot> = transaction(token){sql->
  require(children.size==2);java.time.LocalDate.parse(date)
  val profile=(RustDomainRows.read(sql,"profile") as? RustDomainRows.Row.Profile)?.value
  val prior=(RustDomainRows.read(sql,"settings") as? RustDomainRows.Row.Settings)?.value?:derivedSettings(profile)
  val settings=prior.copy(lastPeriodStartDate=date)
  val changed=profile?.copy(lastPeriodDateString=date,approximatePeriodChoice="EXACT")
  applyRowsNow(sql,token,operation,children,now,listOfNotNull(RustDomainRows.Row.Settings(settings),changed?.let(RustDomainRows.Row::Profile)))
  DomainSnapshot(token,null,settings,changed)
 }
 fun completeOnboarding(token:Token,operation:String,children:List<String>,now:Long,profile:com.cyclecare.app.data.local.OnboardingProfile,enableLock:Boolean):Result<DomainSnapshot> = transaction(token){sql->
  require(children.size==2)
  val prior=(RustDomainRows.read(sql,"settings") as? RustDomainRows.Row.Settings)?.value?:com.cyclecare.app.data.local.CycleSettings()
  val settings=prior.copy(isOnboardingCompleted=true,selectedGoalsCsv=profile.selectedGoalsCsv,lastPeriodStartDate=profile.lastPeriodDateString,averageCycleLength=profile.cycleLengthDays,periodDuration=profile.periodDurationDays,remindFertile=profile.remindFertileWindow,isAppLockEnabled=enableLock)
  applyRowsNow(sql,token,operation,children,now,listOf(RustDomainRows.Row.Profile(profile),RustDomainRows.Row.Settings(settings)))
  DomainSnapshot(token,null,settings,profile)
 }
 private fun applyRowsNow(sql:androidx.sqlite.db.SupportSQLiteDatabase,token:Token,operation:String,children:List<String>,now:Long,rows:List<RustDomainRows.Row>){
  val op=token.preference.operation;val owner=op.owner?.takeIf{it.isNotBlank()}?:"legacy_guest";val namespace=op.owner?.takeIf{it.isNotBlank()}?.let(CycleCareDatabase::namespaceFor)?:"cycle_care_database"
  val mutations=rows.mapIndexed{index,row->RustRoomBatch.Mutation(capturedBase(sql,owner,RustDomainRows.record(row)),row,children[index])}
  check(applyBatch(sql,token,RustRoomBatch.Intent(namespace,operation,op.consentGeneration,now,emptyList(),mutations,"typed-composite-v1")) is RustRoomBatch.Outcome.Committed)
 }
 /** Explicit erasure is one-way for old token. Durable gate is set BEFORE
  * DB effects so a failure stays blocked, never resurrects data via old workers.
  * Production sync remains disabled; server revocation is separate workflow.
  */
 /** Async entry when invoked by a synchronous publication subscriber. It
  * queues IO work and returns completion, never joins its own admission drain.
  */
 fun wipeAsync(token:Token):java.util.concurrent.CompletableFuture<Result<Unit>> {
  val admission=preferences.publicationAdmission
  return if(admission==null)java.util.concurrent.CompletableFuture.completedFuture(wipe(token))else admission.transitionIo{wipe(token)}
 }
 fun wipe(token:Token):Result<Unit> {
  val admission=preferences.publicationAdmission
  if(admission!=null&&!admission.isTransitionAction()){
   if(admission.isOnDrain())return Result.Rejected("use_async_wipe_from_publication")
   check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){"await wipe on IO"}
   return wipeAsync(token).join()
  }
  var cancellationNeeded=false
  val result=guarded(token){
   val owner=token.preference.operation.owner;val syncOwner=owner?.takeIf{it.isNotBlank()}?:"legacy_guest"
   OwnerDataGate.markWiped(preferences.context,owner)
   cancellationNeeded=preferences.pauseHealthSyncForErasureWithoutCallouts(owner,token.preference.operation.session)
   listOf("COHERE","GEMINI","OPENROUTER","LAJIWA").forEach(preferences::revokeProcessorReceipt)
   active=null
   token.database.runInTransaction{
    val sql=token.database.openHelper.writableDatabase
    listOf("cycle_day_logs","onboarding_profiles","cycle_settings","rust_float_bits").forEach{table->sql.execSQL("DELETE FROM $table")}
    listOf("health_sync_outbox","health_sync_revisions","health_sync_cursors","health_sync_conflicts").forEach{table->sql.execSQL("DELETE FROM $table WHERE ownerId=?",arrayOf(syncOwner))}
    val namespace=owner?.takeIf{it.isNotBlank()}?.let(CycleCareDatabase::namespaceFor)?:"cycle_care_database"
    sql.execSQL("DELETE FROM rust_commit_receipts WHERE namespace=?",arrayOf(namespace))
    // Stage tables may not exist before candidate schema registration.
    listOf("rust_enrollment_rows","rust_enrollment_receipts","rust_enrollments").forEach{table->
     val exists=sql.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use{it.moveToFirst()}
     if(exists)sql.execSQL("DELETE FROM $table WHERE namespace=?",arrayOf(namespace))
    }
    RustDomainRows.write(sql,"settings",RustDomainRows.Row.Settings(com.cyclecare.app.data.local.CycleSettings(isOnboardingCompleted=false,lastPeriodStartDate="",averageCycleLength=28,periodDuration=5)))
   }
  }
  if(cancellationNeeded)preferences.cancelHealthSyncWorkAfterErasure()
  return result
 }

 fun resolveConflict(token:Token,record:String,retain:Boolean,child:String,now:Long):Result<Unit> = transaction(token){sql->RustConflictRows.apply(sql,token.preference.operation.owner?.takeIf{it.isNotBlank()}?:"legacy_guest",record,retain,child,now,token.preference.operation.consentGeneration)}
 fun pendingConflicts(token:Token):Result<List<com.cyclecare.app.data.local.HealthSyncConflictEntity>> = transaction(token){sql->
  val owner=token.preference.operation.owner?.takeIf{it.isNotBlank()}?:"legacy_guest";val out=mutableListOf<com.cyclecare.app.data.local.HealthSyncConflictEntity>()
  sql.query("SELECT * FROM health_sync_conflicts WHERE ownerId=? AND resolvedAt IS NULL ORDER BY createdAt",arrayOf(owner)).use{c->
   fun s(k:String)=c.getString(c.getColumnIndexOrThrow(k))
   fun l(k:String)=c.getLong(c.getColumnIndexOrThrow(k))
   fun optional(k:String):String?=if(c.isNull(c.getColumnIndexOrThrow(k)))null else s(k)
   while(c.moveToNext())out.add(com.cyclecare.app.data.local.HealthSyncConflictEntity(s("ownerId"),s("recordId"),s("operationId"),l("localRevision"),optional("localPayloadJson"),s("localKind"),l("remoteRevision"),optional("remotePayloadJson"),l("remoteTombstone")==1L,l("createdAt"),null,optional("resolution")))
  };out
 }
 fun applyRemotePage(token:Token,serverGeneration:Long,changes:List<RustRemoteRows.Change>,now:Long):Result<Int> = transaction(token,serverGeneration){sql->
  val owner=token.preference.operation.owner?:error("signed_in_owner_required")
  check(preferences.isCloudSyncAllowed&&!preferences.hasPendingHealthSyncRevoke&&preferences.serverHealthConsentGeneration==serverGeneration){"sync_consent_changed"}
  RustRemoteRows.apply(sql,owner,changes,now)
 }
 fun acknowledgeSync(token:Token,serverGeneration:Long,entry:com.cyclecare.app.data.local.HealthSyncOutboxEntry,serverRevision:Long):Result<Boolean> = transaction(token,serverGeneration){sql->
  checkSync(token,serverGeneration,entry.ownerId);RustOutboxRows.acknowledge(sql,entry,serverRevision)
 }
 fun retrySync(token:Token,serverGeneration:Long,entry:com.cyclecare.app.data.local.HealthSyncOutboxEntry,nextAttempt:Long,error:String):Result<Boolean> = transaction(token,serverGeneration){sql->
  checkSync(token,serverGeneration,entry.ownerId);RustOutboxRows.retry(sql,entry,nextAttempt,error)
 }
 fun conflictSync(token:Token,serverGeneration:Long,entry:com.cyclecare.app.data.local.HealthSyncOutboxEntry,remoteRevision:Long,payload:String?,tombstone:Boolean,now:Long):Result<Boolean> = transaction(token,serverGeneration){sql->
  checkSync(token,serverGeneration,entry.ownerId);RustOutboxRows.conflict(sql,entry,remoteRevision,payload,tombstone,now)
 }
 /** Explicit include-existing-data enrollment only, not automatic background
  * upload. Re-journals full current rows under current server consent. Existing
  * pending retry/ACK evidence is retained instead of being silently replaced.
  */
 fun journalExisting(token:Token,serverGeneration:Long,operation:String,now:Long):Result<Int> = transaction(token){sql->
  val owner=token.preference.operation.owner?:error("owner_required");checkSync(token,serverGeneration,owner)
  val rows=RustFloatRows.readAll(sql).map{RustDomainRows.Row.Log(it)}+listOfNotNull(RustDomainRows.read(sql,"settings"),RustDomainRows.read(sql,"profile"))
  require(rows.size<=100){"explicit_snapshot_too_large"}
  val namespace=CycleCareDatabase.namespaceFor(owner)
  val mutations=rows.map{row->RustRoomBatch.Mutation(capturedBase(sql,owner,RustDomainRows.record(row)),row,java.util.UUID.randomUUID().toString())}
  if(mutations.isNotEmpty()){
   // Bounded chunks within one outer transaction, no partial enrollment if any
   // later record fails. Per-chunk operation identity avoids receipt collisions.
   mutations.chunked(16).forEachIndexed{index,chunk->check(applyBatch(sql,token,RustRoomBatch.Intent(namespace,"$operation:$index",token.preference.operation.consentGeneration,now,emptyList(),chunk,"explicit-history-v1")) is RustRoomBatch.Outcome.Committed)}
  };rows.size
 }
 private fun enrollmentBinding(token:Token,server:Long)=org.json.JSONArray().put(token.preference.operation.owner).put(token.preference.operation.effectiveUserId).put(token.preference.operation.session).put(token.preference.operation.consentGeneration).put(token.preference.localOnly).put(token.preference.accountMode).put(token.lifecycle.generation).put(server).toString()
 fun beginHistoryStage(token:Token,serverGeneration:Long,operation:String):Result<Unit> = transaction(token){sql->
  val owner=token.preference.operation.owner?:error("owner_required");checkSync(token,serverGeneration,owner)
  RustEnrollmentStage.begin(sql,CycleCareDatabase.namespaceFor(owner),operation,enrollmentBinding(token,serverGeneration))
 }
 fun abandonUnusedHistory(token:Token,serverGeneration:Long,operation:String):Result<Boolean> = transaction(token){sql->
  val owner=token.preference.operation.owner?:error("owner_required");checkSync(token,serverGeneration,owner)
  RustEnrollmentStage.abandonUnused(sql,CycleCareDatabase.namespaceFor(owner),operation,enrollmentBinding(token,serverGeneration))
 }
 fun cleanupAbortedHistory(token:Token,serverGeneration:Long,operation:String,limit:Int=64):Result<Boolean> = transaction(token){sql->
  val owner=token.preference.operation.owner?:error("owner_required");checkSync(token,serverGeneration,owner)
  RustEnrollmentStage.cleanupAborted(sql,CycleCareDatabase.namespaceFor(owner),operation,limit)
 }
 fun historyStagePage(token:Token,serverGeneration:Long,operation:String):Result<RustEnrollmentStage.Progress> = transaction(token){sql->
  val owner=token.preference.operation.owner?:error("owner_required");checkSync(token,serverGeneration,owner)
  RustEnrollmentStage.page(sql,CycleCareDatabase.namespaceFor(owner),operation,enrollmentBinding(token,serverGeneration),owner,32)
 }
 fun historySendRows(token:Token,serverGeneration:Long,operation:String,after:String):Result<List<RustEnrollmentStage.Staged>> = transaction(token){sql->
  val owner=token.preference.operation.owner?:error("owner_required");checkSync(token,serverGeneration,owner)
  RustEnrollmentStage.requireReady(sql,CycleCareDatabase.namespaceFor(owner),operation,enrollmentBinding(token,serverGeneration))
  RustEnrollmentStage.readyRows(sql,CycleCareDatabase.namespaceFor(owner),operation,enrollmentBinding(token,serverGeneration),after,32,System.currentTimeMillis(),true)
 }
 fun historyTakeSendWindow(token:Token,serverGeneration:Long,operation:String,limit:Int=32):Result<List<RustEnrollmentStage.Staged>> = transaction(token){sql->
  val owner=token.preference.operation.owner?:error("owner_required");checkSync(token,serverGeneration,owner)
  RustEnrollmentStage.takeSendWindow(sql,CycleCareDatabase.namespaceFor(owner),operation,enrollmentBinding(token,serverGeneration),System.currentTimeMillis(),limit)
 }
 fun historySendDecision(token:Token,serverGeneration:Long,operation:String,row:RustEnrollmentStage.Staged,now:Long):Result<RustEnrollmentStage.SendDecision> = transaction(token){sql->
  val owner=token.preference.operation.owner?:error("owner_required");checkSync(token,serverGeneration,owner)
  RustEnrollmentStage.sendDecision(sql,CycleCareDatabase.namespaceFor(owner),operation,enrollmentBinding(token,serverGeneration),owner,row,now)
 }
 /** Narrow dispatch capability: produced only by a guarded durable claim.
  * Captures the old context for receipt archival, never grants new-context
  * adoption, reopening a namespace, another send, or recovery after wipe.
  */
 class HistoryDispatch private constructor(internal val token:Token,internal val server:Long,internal val operation:String,internal val row:RustEnrollmentStage.Staged){
  companion object{internal fun claimed(token:Token,server:Long,operation:String,row:RustEnrollmentStage.Staged)=HistoryDispatch(token,server,operation,row)}
 }
 fun claimHistoryDispatch(token:Token,serverGeneration:Long,operation:String,row:RustEnrollmentStage.Staged,now:Long):Result<HistoryDispatch?> = transaction(token){sql->
  val owner=token.preference.operation.owner?:error("owner_required");checkSync(token,serverGeneration,owner)
  if(RustEnrollmentStage.claimSend(sql,CycleCareDatabase.namespaceFor(owner),operation,enrollmentBinding(token,serverGeneration),owner,row,now))HistoryDispatch.claimed(token,serverGeneration,operation,row).also{issuedDispatches.add(it)} else null
 }
 /** Effect scope is the already-issued dispatch in its captured open database.
  * Deliberately does not read current prefs, adopt data or clear pending. Wipe
  * wins under lifecycle exclusion; no key/provider/account opening occurs.
  */
 fun archiveHistoryReceipt(dispatch:HistoryDispatch,revision:Long,hash:String):Result<Boolean> = try{
  check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
  val token=dispatch.token;val owner=token.preference.operation.owner?:error("owner_required")
  OwnerDataGate.runIfUnchanged(preferences.context,owner,token.lifecycle){
   synchronized(lock){
    if(token.lifecycle.wiped||!issuedDispatches.contains(dispatch))Result.Stale else{
     var archived=false
     token.database.runInTransaction{archived=RustEnrollmentStage.archiveReceipt(token.database.openHelper.writableDatabase,CycleCareDatabase.namespaceFor(owner),dispatch.operation,enrollmentBinding(token,dispatch.server),dispatch.row,revision,hash)}
     Result.Value(archived)
    }
   }
  }?:Result.Stale
 }catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}catch(_:ArithmeticException){Result.Overflow}catch(_:Exception){Result.Storage}
 fun historyClaimSend(token:Token,serverGeneration:Long,operation:String,row:RustEnrollmentStage.Staged,now:Long):Result<Boolean> = transaction(token){sql->
  val owner=token.preference.operation.owner?:error("owner_required");checkSync(token,serverGeneration,owner)
  RustEnrollmentStage.claimSend(sql,CycleCareDatabase.namespaceFor(owner),operation,enrollmentBinding(token,serverGeneration),owner,row,now)
 }
 fun historyValidateSend(token:Token,serverGeneration:Long,row:RustEnrollmentStage.Staged):Result<Boolean> = transaction(token){sql->
  val owner=token.preference.operation.owner?:error("owner_required");checkSync(token,serverGeneration,owner);RustEnrollmentStage.validateSend(sql,owner,row)
 }
 fun reconcileHistoryReceipt(token:Token,serverGeneration:Long,operation:String,row:RustEnrollmentStage.Staged):Result<Boolean> = transaction(token){sql->
  val owner=token.preference.operation.owner?:error("owner_required");checkSync(token,serverGeneration,owner)
  RustEnrollmentStage.reconcileReceipt(sql,CycleCareDatabase.namespaceFor(owner),operation,enrollmentBinding(token,serverGeneration),owner,row)
 }
 fun historyAcknowledge(token:Token,serverGeneration:Long,operation:String,row:RustEnrollmentStage.Staged,revision:Long,hash:String,now:Long):Result<Boolean> = transaction(token){sql->
  val owner=token.preference.operation.owner?:error("owner_required");checkSync(token,serverGeneration,owner)
  RustEnrollmentStage.acknowledge(sql,CycleCareDatabase.namespaceFor(owner),operation,enrollmentBinding(token,serverGeneration),owner,row,revision,hash,now)
 }
 private fun checkSync(token:Token,serverGeneration:Long,owner:String){
  check(token.preference.operation.owner==owner&&preferences.isCloudSyncAllowed&&!preferences.hasPendingHealthSyncRevoke&&preferences.serverHealthConsentGeneration==serverGeneration){"sync_consent_changed"}
 }
 class DomainSnapshot internal constructor(val token:Token,val log:CycleDayLog?,val settings:com.cyclecare.app.data.local.CycleSettings?,val profile:com.cyclecare.app.data.local.OnboardingProfile?)
 fun readDomain(token:Token,date:String?=null):Result<DomainSnapshot> = transaction(token){sql->DomainSnapshot(token,date?.let{RustFloatRows.readOne(sql,it)},(RustDomainRows.read(sql,"settings") as? RustDomainRows.Row.Settings)?.value,(RustDomainRows.read(sql,"profile") as? RustDomainRows.Row.Profile)?.value)}
 /** Batch consumer, only reachable through the same full identity guard. */
 fun commitBatch(token:Token,intent:RustRoomBatch.Intent):Result<RustRoomBatch.Outcome> = transaction(token){sql->applyBatch(sql,token,intent)}
 private fun applyBatch(sql:androidx.sqlite.db.SupportSQLiteDatabase,token:Token,intent:RustRoomBatch.Intent):RustRoomBatch.Outcome {
  val op=token.preference.operation;val owner=op.owner?.takeIf{it.isNotBlank()}?:"legacy_guest"
  require((intent.reads+intent.mutations.map{it.base}).all{it.storage!=null})
  require(intent.namespace==(op.owner?.takeIf{it.isNotBlank()}?.let(CycleCareDatabase::namespaceFor)?:"cycle_care_database")&&intent.generation==op.consentGeneration)
  val binding=org.json.JSONArray().put(op.owner?:org.json.JSONObject.NULL).put(op.effectiveUserId).put(op.session).put(op.consentGeneration).put(token.preference.localOnly).put(token.preference.accountMode).put(token.epoch).put(token.instance).put(token.lifecycle.generation).toString()
  return RustRoomBatch.apply(sql,owner,binding,intent)
 }
 class UiRows internal constructor(val token:Token,val settings:com.cyclecare.app.data.local.CycleSettings?,val profile:com.cyclecare.app.data.local.OnboardingProfile?,val logs:List<CycleDayLog>)
 /** Pure read: legitimate absence remains data, never initialization here. */
 fun readUiRows(token:Token):Result<UiRows> = transaction(token){sql->
  UiRows(token,(RustDomainRows.read(sql,"settings") as? RustDomainRows.Row.Settings)?.value,
   (RustDomainRows.read(sql,"profile") as? RustDomainRows.Row.Profile)?.value,RustFloatRows.readAll(sql))
 }
 fun read(token:Token):Result<Snapshot> = transaction(token) {sql->Snapshot(token,RustFloatRows.readAll(sql))}
 /** Queue outside ALL monitors. Runnable rechecks current identity on MAIN,
  * then paints synchronously under exclusion; stale queued UI is dropped.
  * Callback must update UI state only, never DB/network/reentrant dispatch.
  */
 fun publishSnapshot(snapshot:Snapshot,paint:(List<CycleDayLog>)->Unit,complete:(Result<Unit>)->Unit={}) {
  val action=Runnable {
   val result=try {preferences.runIfRustOperationUnchanged(snapshot.token.preference) {
    OwnerDataGate.runIfUnchanged(preferences.context,snapshot.token.preference.operation.owner,snapshot.token.lifecycle){
     synchronized(lock){if(active!=snapshot.token||snapshot.token.lifecycle.wiped)Result.Stale else {paint(snapshot.rows);Result.Value(Unit)}}
    }?:Result.Stale
   }?:Result.Stale}catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}catch(_:ArithmeticException){Result.Overflow}catch(_:Exception){Result.Storage}
   complete(result)
  }
  val main=android.os.Looper.getMainLooper()
  if(android.os.Looper.myLooper()==main)action.run()else if(!android.os.Handler(main).post(action))complete(Result.Storage)
 }
 /** Observer is wake signal only. Every emission rereads domain+bits inside one
  * guarded transaction. DAO rows from stale owner cannot be relabeled current.
  * Renderer still must use publishSnapshot, not paint after map acceptance.
  */
 fun observe(token:Token)=callbackFlow<Unit> {
  val observer=object:InvalidationTracker.Observer("cycle_day_logs","rust_float_bits","cycle_settings","onboarding_profiles") {
   override fun onInvalidated(tables:Set<String>){trySend(Unit)}
  }
  token.database.invalidationTracker.addObserver(observer);trySend(Unit)
  awaitClose{token.database.invalidationTracker.removeObserver(observer)}
 }.conflate().map{read(token)}.flowOn(Dispatchers.IO)
}

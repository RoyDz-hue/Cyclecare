package com.cyclecare.app.lab.store

/** Pure validation. No Context, preferences, key, file or DB access. */
data class SyntheticFixtureConfig(val runId:String,val ownerDigest:String,val filename:String,val alias:String,val prefs:String) {
 fun validate(applicationId:String) {
  require(applicationId=="com.cyclecare.app.lab") { "Lab application required" }
  require(Regex("[0-9a-f]{32}").matches(runId)) { "Synthetic run ID required" }
  require(Regex("[0-9a-f]{64}").matches(ownerDigest)) { "Synthetic owner digest required" }
  require(filename=="cyclecare_lab_synthetic_${runId}_${ownerDigest}.db") { "Synthetic filename required" }
  require(alias=="cyclecare_lab_synthetic_${runId}_wrapping") { "Synthetic alias required" }
  require(prefs=="cyclecare_lab_synthetic_${runId}_keyprefs") { "Synthetic preferences required" }
 }
 companion object {
  fun create(runId:String,ownerDigest:String)=SyntheticFixtureConfig(runId,ownerDigest,"cyclecare_lab_synthetic_${runId}_${ownerDigest}.db","cyclecare_lab_synthetic_${runId}_wrapping","cyclecare_lab_synthetic_${runId}_keyprefs")
 }
}

data class LabIdentity(val namespace:String,val effective:String,val instance:String,val epoch:Long,val session:Long,val generation:Long,val local:Boolean) {
 fun validate(){require(namespace.isNotBlank()&&namespace.length<=128&&effective.isNotBlank()&&effective.length<=128&&instance.isNotBlank()&&instance.length<=128);require(epoch>=0&&session>=0&&generation>=0)}
}
data class LabMutation(val recordId:String,val expectedRevision:Long,val payload:String?,val payloadHash:String,val kind:String,val bbtBits:Long?=null,val sleepBits:Long?=null)
data class LabCommitPlan(val identity:LabIdentity,val operationId:String,val intentDigest:String,val mutations:List<LabMutation>)
sealed class LabCommitResult {
 data class Committed(val receipt:String,val replay:Boolean):LabCommitResult()
 data class Malformed(val field:String):LabCommitResult()
 object RejectedClosed:LabCommitResult()
 object StorageFailure:LabCommitResult()
 object Conflict:LabCommitResult()
 object Stale:LabCommitResult()
 object InvalidReuse:LabCommitResult()
 data class Unknown(val operationId:String):LabCommitResult()
}

/** Chosen policy: separate factory-owned copy retained until explicit adapter close. */
internal class RetainedFactorySecret(secret:ByteArray) {
 private var bytes=secret.copyOf()
 private var handedOff=false
 @Synchronized fun handOff():ByteArray {check(!handedOff);handedOff=true;return bytes}
 @Synchronized fun close(){bytes.fill(0)}
}
internal interface PreparedLabStore {
 val namespace:String
 val instance:String
 suspend fun capturedIdentity():LabIdentity?
 suspend fun validatePrepared():Boolean
 suspend fun apply(plan:LabCommitPlan):LabCommitResult
}
internal class PreopenedLabRouter(initial:LabIdentity,private var store:PreparedLabStore) {
 private val lock=kotlinx.coroutines.sync.Mutex()
 private var identity=initial
 suspend fun capture():LabIdentity {lock.lock();try{return identity}finally{lock.unlock()}}
 suspend fun publish(expected:LabIdentity,next:LabIdentity,target:PreparedLabStore):Boolean {
  // Validation can suspend before exclusion; never open/lookup keys here.
  if(!target.validatePrepared())return false
  lock.lock();try{next.validate();if(identity!=expected||next.epoch<=expected.epoch||target.namespace!=next.namespace||target.instance!=next.instance||target.capturedIdentity()!=next)return false;identity=next;store=target;return true}finally{lock.unlock()}
 }
 suspend fun commit(plan:LabCommitPlan):LabCommitResult {lock.lock();try{if(plan.identity!=identity)return LabCommitResult.Stale;return store.apply(plan)}finally{lock.unlock()}}
}

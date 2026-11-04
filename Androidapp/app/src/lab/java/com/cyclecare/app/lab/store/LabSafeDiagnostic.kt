package com.cyclecare.app.lab.store

/** No caller-controlled string, exception text, class name, stack or cause reaches this report. */
internal enum class LabStage { ENTRY, SETUP, PACKAGE_AUDIT, SERVICE_AUDIT, FIXTURE_PREFLIGHT, FACTORY_OPEN, TRACK_CORPUS, SETTINGS_CORPUS, CODEC, REOPEN, ENVELOPE, CONFLICT, ROUTER, CORRUPT_TARGET, FINAL_AUDIT, CLEANUP, COMPLETE }
internal enum class LabFailureKind { CHECK, SECURITY, ARGUMENT, STATE, IO, CANCELLED, LINKAGE, OTHER }
internal class LabCheckFailure:IllegalStateException()
internal class LabSafeDiagnostic {
 var stage=LabStage.ENTRY
 private var activeCheck=0
 private var failedCheck=0
 var checks=0;private set
 var cleanupStarted=false
 var evidenceAvailable=false
 fun beginCheck(id:Int){require(id in 1..128);activeCheck=id}
 fun verify(ok:Boolean,id:Int){beginCheck(id);checks++;if(!ok){failedCheck=id;throw LabCheckFailure()};activeCheck=0}
 private fun kind(t:Throwable)=when(t){
  is LabCheckFailure->LabFailureKind.CHECK
  is kotlinx.coroutines.CancellationException->LabFailureKind.CANCELLED
  is SecurityException->LabFailureKind.SECURITY
  is IllegalArgumentException->LabFailureKind.ARGUMENT
  is IllegalStateException->LabFailureKind.STATE
  is java.io.IOException->LabFailureKind.IO
  is LinkageError->LabFailureKind.LINKAGE
  else->LabFailureKind.OTHER
 }
 inline fun failure(t:Throwable,saveEvidence:()->Unit,close:()->Unit):String {
  // Freeze first failure before close/save. They cannot replace its stage/category/check.
  val originalStage=stage;val originalKind=kind(t);val originalCheck=if(failedCheck!=0)failedCheck else activeCheck
  var closeOk=true;try{close()}catch(_:Throwable){closeOk=false}
  var writeState="UNAVAILABLE";if(evidenceAvailable){try{saveEvidence();writeState="WRITTEN"}catch(_:Throwable){writeState="FAILED"}}
  return "FAIL|stage=${originalStage.name}|check=$originalCheck|kind=${originalKind.name}|checks=$checks|cleanup=$cleanupStarted|evidence=$writeState|close=$closeOk"
 }
 fun success()="PASS"
}

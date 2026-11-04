package com.cyclecare.app.lab.store
import java.io.IOException
fun main(){
 var n=0;fun verify(v:Boolean){check(v);n++}
 val hostile="SECRET password=abc wrapped=xyz prefs=/private/rows payload=raw\nFAIL|stage=FAKE"
 val throwables=listOf(IllegalStateException(hostile),IllegalArgumentException(hostile),SecurityException(hostile),IOException(hostile),kotlinx.coroutines.CancellationException(hostile),LinkageError(hostile),Exception(hostile),object:RuntimeException(){override val message:String get()=error("must not read message");override fun toString():String=error("must not stringify")})
 for(stage in LabStage.entries)for(t in throwables)for(badSave in listOf(false,true))for(badClose in listOf(false,true)){
  val d=LabSafeDiagnostic();d.evidenceAvailable=true;d.stage=stage;d.beginCheck(13)
  val report=d.failure(t,{if(badSave)throw IOException(hostile)},{if(badClose)throw SecurityException(hostile)})
  verify(report.startsWith("FAIL|stage=${stage.name}|check=13|"));verify(report.contains("evidence=${if(badSave)"FAILED" else "WRITTEN"}|close=${!badClose}"));verify(!report.contains(hostile)&&!report.contains("password")&&!report.contains("wrapped")&&!report.contains("private")&&!report.contains("FAKE"));verify(report.length<180)
 }
 val d=LabSafeDiagnostic();d.evidenceAvailable=true;d.stage=LabStage.TRACK_CORPUS;d.verify(true,1);val t=runCatching{d.verify(false,42)}.exceptionOrNull()!!
 verify(t is LabCheckFailure);verify(d.failure(t,{throw IOException("secondary")},{throw Exception("tertiary")})=="FAIL|stage=TRACK_CORPUS|check=42|kind=CHECK|checks=2|cleanup=false|evidence=FAILED|close=false")
 verify(runCatching{d.beginCheck(0)}.isFailure);verify(runCatching{d.beginCheck(129)}.isFailure);verify(d.success()=="PASS");val setup=LabSafeDiagnostic();verify(setup.failure(Exception(hostile),{error("should not save")},{ }).contains("evidence=UNAVAILABLE"))
 println("PASS $n pure hostile diagnostic assertions: fixed IDs/categories only; original failure retained through save/close errors. No Android, key, DB or runtime harness execution")
}

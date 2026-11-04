package com.cyclecare.app.lab.store

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Process
import kotlinx.coroutines.*
import org.json.*
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Explicit instrumentation facade. Calling run is a separately gated effect, never startup. */
object LabEncryptedHostProof {
 @JvmStatic fun run(context:Context,trackJson:String,settingsJson:String):String {
  val diagnostic=LabSafeDiagnostic()
  return try{runBlocking {execute(context,trackJson,settingsJson,diagnostic)}}catch(t:Throwable){diagnostic.failure(t,{}, {})}
 }
 private suspend fun execute(context:Context,trackJson:String,settingsJson:String,diagnostic:LabSafeDiagnostic):String {
  diagnostic.stage=LabStage.SETUP
  require(context.packageName=="com.cyclecare.app.lab")
  val run=UUID.randomUUID().toString().replace("-","")
  val evidence=File(context.filesDir,"cyclecare_lab_synthetic_evidence_$run.json")
  val events=JSONArray();val owned=mutableListOf<Pair<SyntheticFixtureConfig,LabIdentity>>()
  val handles=mutableListOf<LabEncryptedStore>();var checks=0;var cleanupStarted=false
  fun verify(ok:Boolean,id:Int){checks++;diagnostic.verify(ok,id)}
  fun event(label:String,value:Any){events.put(JSONObject().put("label",label).put("value",value))}
  fun write(status:String){evidence.writeText(JSONObject().put("run",run).put("pid",Process.myPid()).put("status",status).put("checks",checks).put("events",events).toString(2))}
  fun config(owner:String):Pair<SyntheticFixtureConfig,LabIdentity>{
   diagnostic.stage=LabStage.FIXTURE_PREFLIGHT
   val i=LabIdentity("synthetic-$run-$owner","effective-$owner","instance-$owner",1,1,0,true)
   // Per-owner fresh run prevents A/B envelope/alias sharing. No persisted user identifier.
   val c=SyntheticFixtureConfig.create(UUID.randomUUID().toString().replace("-",""),labHash(i.namespace));c.validate(context.packageName)
   val dir=File(context.applicationInfo.dataDir,"app_cyclecare_lab_synthetic")
   val pref=File(context.applicationInfo.dataDir,"shared_prefs/${c.prefs}.xml")
   val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
   verify(!File(dir,c.filename).exists()&&!pref.exists()&&!ks.containsAlias(c.alias),1)
   owned.add(c to i);return c to i
  }
  suspend fun open(pair:Pair<SyntheticFixtureConfig,LabIdentity>):LabEncryptedStore {
   val previousStage=diagnostic.stage;diagnostic.stage=LabStage.FACTORY_OPEN
   val s=LabEncryptedStoreFactory.open(context,pair.first,pair.second);handles.add(s);diagnostic.stage=previousStage;return s
  }
  suspend fun capture(s:LabEncryptedStore,label:String):LabSyntheticSnapshot{val v=s.inspectSynthetic();event(label,v.json().put("snapshotHash",v.digest()));return v}
  diagnostic.evidenceAvailable=true
  return try {
   diagnostic.stage=LabStage.PACKAGE_AUDIT
   val pm=context.packageManager
   @Suppress("DEPRECATION") val pkg=pm.getPackageInfo(context.packageName,PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS)
   verify(pkg.requestedPermissions.orEmpty().none{it=="android.permission.INTERNET"||it=="android.permission.ACCESS_NETWORK_STATE"},2)
   verify(pkg.providers.orEmpty().isEmpty(),3)
   verify((pkg.services.orEmpty().map{it.name}+pkg.receivers.orEmpty().map{it.name}).none{it.startsWith("androidx.work.")||it.contains("Supabase")},4)
   diagnostic.stage=LabStage.SERVICE_AUDIT
   @Suppress("DEPRECATION") val running=(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(Int.MAX_VALUE).filter{it.service.packageName==context.packageName}.map{it.service.className}
   verify(running.none{it.startsWith("androidx.work.")||it.contains("Supabase")},5)
   event("initialization",JSONObject().put("package",context.packageName).put("applicationClass",context.applicationContext.javaClass.name).put("services",JSONArray(running)).put("scope","No Activity launch; service audit is not a complete in-process object/thread audit"))
   val aPair=config("A");val ai=aPair.second
   val wrongPackage=object:ContextWrapper(context){override fun getPackageName()="com.cyclecare.app"}
   verify(runCatching{LabEncryptedStoreFactory.open(wrongPackage,aPair.first,ai)}.isFailure,6)
   verify(runCatching{LabEncryptedStoreFactory.open(context,aPair.first.copy(filename="cycle_care_database"),ai)}.isFailure,7)
   var a=open(aPair)
   // Invalid identity/config calls must reject before resources, key lookup or open.
   verify(runCatching{aPair.first.validate("com.cyclecare.app")}.isFailure,8)
   verify(runCatching{aPair.first.copy(filename="cycle_care_database").validate(context.packageName)}.isFailure,9)
   diagnostic.stage=LabStage.TRACK_CORPUS
   val corpus=JSONArray(trackJson);verify(corpus.length()==34,10)
   for(index in 0 until corpus.length()){
    val raw=corpus.getJSONObject(index);val before=capture(a,"track-$index-before")
    val plan=LabCorpusMapper.track(ai,raw,before.revision(raw.getString("date")),before.revision("settings"))
    val steps=plan.mutations.size*3+1
    for(step in 1..steps){verify(a.commit(plan,step)==LabCommitResult.StorageFailure,11);verify(a.inspectSynthetic().digest()==before.digest(),12)}
    val result=a.commit(plan);verify(result is LabCommitResult.Committed&&!result.replay,13)
    verify(a.commit(plan) is LabCommitResult.Committed && (a.commit(plan) as LabCommitResult.Committed).replay,14)
    val after=capture(a,"track-$index-after")
    for(m in plan.mutations){verify(after.revision(m.recordId)==m.expectedRevision+1,15);verify(after.rows.singleOrNull{it.recordId==m.recordId}?.payload==m.payload,16)}
    a.validateOpen()
   }
   diagnostic.stage=LabStage.SETTINGS_CORPUS
   val settings=JSONObject(settingsJson).getJSONArray("cases");verify(settings.length()==8,17)
   for(index in 0 until settings.length()){
    val row=settings.getJSONObject(index);val before=a.inspectSynthetic()
    val plan=LabCorpusMapper.settings(ai,"settings-$index",row.getString("settingsPayload"),if(row.isNull("profilePayload"))null else row.getString("profilePayload"),before.revision("settings"),before.revision("profile"))
    for(step in 1..plan.mutations.size*3+1){verify(a.commit(plan,step)==LabCommitResult.StorageFailure,18);verify(a.inspectSynthetic().digest()==before.digest(),19)}
    verify(a.commit(plan) is LabCommitResult.Committed,20);val after=capture(a,"settings-$index")
    for(m in plan.mutations){verify(after.rows.single{it.recordId==m.recordId}.payload==m.payload,21);verify(after.revision(m.recordId)==m.expectedRevision+1,22)}
    a.validateOpen()
   }
   // Canonical codec rejects noncanonical double, signedzero contradiction and NaN bits.
   diagnostic.stage=LabStage.CODEC
   val first=corpus.getJSONObject(0);val current=a.inspectSynthetic();val good=LabCorpusMapper.track(ai,first,current.revision(first.getString("date")),current.revision("settings"))
   verify(a.commit(good.copy(operationId="bad-bits",mutations=good.mutations.mapIndexed{i,m->if(i==0)m.copy(bbtBits=0x7fc00001L)else m})) is LabCommitResult.Malformed,23)
   val malformedPayload=JSONObject(first.getString("payload")).put("bbt_celsius",36.60000000001).toString()
   verify(runCatching{labValidatePayload(first.getString("date"),malformedPayload)}.isFailure,24)
   val zero=(0 until corpus.length()-1).map{corpus.getJSONObject(it)}.first{it.getLong("bbtBits")==0x80000000L}
   verify(runCatching{labValidatePayload(zero.getString("date"),zero.getString("payload"),0L,zero.getLong("sleepBits"))}.isFailure,25)
   // Unknown result recovery uses same file/alias but NEW factory/adapter; no implicit reset.
   diagnostic.stage=LabStage.REOPEN
   val u=good.copy(operationId="unknown")
   verify(a.commit(u,loseReceiptAfterCommit=true) is LabCommitResult.Unknown,26)
   val committed=capture(a,"unknown-committed");a.close();verify(a.capture()==null&&a.commit(u)==LabCommitResult.RejectedClosed,27)
   a=open(aPair);verify(a.inspectSynthetic().digest()==committed.digest(),28)
   val replay=a.commit(u);verify(replay is LabCommitResult.Committed&&replay.replay,29)
   val beforeConflict=a.inspectSynthetic().digest();verify(a.commit(good.copy(operationId="old-base"))==LabCommitResult.Conflict,30);verify(a.inspectSynthetic().digest()==beforeConflict,31)
   val different=LabCorpusMapper.settings(ai,"unknown",settings.getJSONObject(0).getString("settingsPayload"),null,committed.revision("settings"),0)
   verify(a.commit(different)==LabCommitResult.InvalidReuse,32)
   a.closeHelperForSyntheticReopen();a.validateOpen();verify(a.inspectSynthetic().digest()==committed.digest(),33)
   a.close();verify(a.capture()==null&&!a.transition(ai,ai.copy(epoch=2))&&!a.validatePrepared(),34);a.close();a=open(aPair)
   // Missing envelope and corrupt ciphertext must fail without changing owned file bytes.
   a.close()
   diagnostic.stage=LabStage.ENVELOPE
   val ownedFile=File(context.applicationInfo.dataDir,"app_cyclecare_lab_synthetic/${aPair.first.filename}")
   val bytesBefore=java.security.MessageDigest.getInstance("SHA-256").digest(ownedFile.readBytes())
   val prefs=context.getSharedPreferences(aPair.first.prefs,Context.MODE_PRIVATE)
   val envelope=prefs.getString("wrapped",null)!!
   try {
    verify(prefs.edit().remove("wrapped").commit(),35)
    verify(runCatching{LabEncryptedStoreFactory.open(context,aPair.first,ai)}.isFailure,36)
    verify(java.security.MessageDigest.getInstance("SHA-256").digest(ownedFile.readBytes()).contentEquals(bytesBefore),37)
    verify(prefs.edit().putString("wrapped","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").commit(),38)
    verify(runCatching{LabEncryptedStoreFactory.open(context,aPair.first,ai)}.isFailure,39)
    verify(java.security.MessageDigest.getInstance("SHA-256").digest(ownedFile.readBytes()).contentEquals(bytesBefore),40)
   }finally{check(prefs.edit().putString("wrapped",envelope).commit())}
   a=open(aPair)
   diagnostic.stage=LabStage.CONFLICT
   val raceBase=a.inspectSynthetic();val r1=LabCorpusMapper.settings(ai,"race-1",settings.getJSONObject(0).getString("settingsPayload"),null,raceBase.revision("settings"),0);val r2=r1.copy(operationId="race-2")
   val race=coroutineScope{listOf(async{a.commit(r1)},async{a.commit(r2)}).awaitAll()};verify(race.count{it is LabCommitResult.Committed}==1&&race.count{it==LabCommitResult.Conflict}==1,41)
   // Real adapters A/B. Publication paused after B validation to schedule old-A commit.
   diagnostic.stage=LabStage.ROUTER
   val bPair=config("B");val bi=bPair.second.copy(epoch=2);val b=open(bPair.first to bi);val router=PreopenedLabRouter(ai,a)
   val ready=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
   val paused=object:PreparedLabStore by b {override suspend fun validatePrepared():Boolean{val ok=b.validatePrepared();ready.complete(Unit);release.await();return ok}}
   coroutineScope {
    val publish=async{router.publish(ai,bi,paused)};ready.await()
    val base=a.inspectSynthetic();val p=LabCorpusMapper.settings(ai,"before-publish",settings.getJSONObject(0).getString("settingsPayload"),null,base.revision("settings"),0)
    try{verify(router.commit(p) is LabCommitResult.Committed,42)}finally{release.complete(Unit)};verify(publish.await(),43)
   }
   val aFrozen=a.inspectSynthetic().digest();val bFrozen=b.inspectSynthetic().digest()
   verify(router.commit(u)==LabCommitResult.Stale,44)
   val ai2=ai.copy(epoch=3,session=2);verify(a.transition(ai,ai2),45)
   val badNext=ai2.copy(session=3);verify(!router.publish(bi,badNext,a),46)
   verify(router.capture()==bi,47)
   verify(router.publish(bi,ai2,a),48)
   verify(router.commit(u)==LabCommitResult.Stale,49)
   verify(a.inspectSynthetic().digest()==aFrozen&&b.inspectSynthetic().digest()==bFrozen,50)
   // Corrupt B, publication fails; current A remains usable. Evidence preserves corrupt fixture on failure.
   diagnostic.stage=LabStage.CORRUPT_TARGET
   val bp=LabCorpusMapper.settings(bi,"B-seed",settings.getJSONObject(0).getString("settingsPayload"),null,0,0);verify(b.commit(bp) is LabCommitResult.Committed,51)
   b.corruptSyntheticReceiptForRejection();verify(!router.publish(ai2,bi.copy(epoch=4),b),52)
   verify(router.capture()==ai2,53)
   val finalBase=a.inspectSynthetic();verify(router.commit(LabCorpusMapper.settings(ai2,"after-corrupt-B",settings.getJSONObject(0).getString("settingsPayload"),null,finalBase.revision("settings"),0)) is LabCommitResult.Committed,54)
   capture(a,"final-A");capture(b,"final-B-deliberately-corrupt")
   diagnostic.stage=LabStage.FINAL_AUDIT
   @Suppress("DEPRECATION") val afterServices=(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(Int.MAX_VALUE).filter{it.service.packageName==context.packageName}.map{it.service.className}
   verify(afterServices.none{it.startsWith("androidx.work.")||it.contains("Supabase")},55)
   event("initialization-after",JSONArray(afterServices))
   handles.forEach{it.close()}
   // Cleanup ONLY resources preflighted absent and owned by this generated invocation.
   val registry=context.getSharedPreferences("cyclecare_lab_synthetic_registry",Context.MODE_PRIVATE)
   val dir=File(context.applicationInfo.dataDir,"app_cyclecare_lab_synthetic")
   val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
   write("CHECKS_PASS_CLEANUP_PENDING");cleanupStarted=true;diagnostic.cleanupStarted=true;diagnostic.stage=LabStage.CLEANUP
   for((c,_) in owned){
    val expected=c.runId+":"+c.ownerDigest+":"+c.alias+":"+c.prefs
    verify(registry.getString(c.filename,null)==expected,56)
    for(suffix in listOf("","-wal","-shm","-journal")){val f=File(dir,c.filename+suffix);verify(!f.exists()||f.delete(),57)}
    verify(context.deleteSharedPreferences(c.prefs),58)
    if(ks.containsAlias(c.alias))ks.deleteEntry(c.alias)
    verify(!ks.containsAlias(c.alias),59)
    verify(registry.edit().remove(c.filename).commit(),60)
   }
   event("cleanup","Exact invocation-owned files/envelopes/aliases/entries removed; shared directory and registry preserved")
   write("PASS");diagnostic.stage=LabStage.COMPLETE;diagnostic.success()
  }catch(t:Throwable){
   // First failure survives either evidence or handle-close failure; report is allowlisted.
   diagnostic.failure(t,{write("FAIL")},{handles.forEach{it.close()}})
  }
 }
}

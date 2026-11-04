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
 @JvmStatic fun run(context:Context,trackJson:String,settingsJson:String):String=runBlocking {
  require(context.packageName=="com.cyclecare.app.lab")
  val run=UUID.randomUUID().toString().replace("-","")
  val evidence=File(context.filesDir,"cyclecare_lab_synthetic_evidence_$run.json")
  val events=JSONArray();val owned=mutableListOf<Pair<SyntheticFixtureConfig,LabIdentity>>()
  val handles=mutableListOf<LabEncryptedStore>();var checks=0;var cleanupStarted=false
  fun verify(ok:Boolean,label:String){checks++;check(ok){label}}
  fun event(label:String,value:Any){events.put(JSONObject().put("label",label).put("value",value))}
  fun write(status:String){evidence.writeText(JSONObject().put("run",run).put("pid",Process.myPid()).put("status",status).put("checks",checks).put("events",events).toString(2))}
  fun config(owner:String):Pair<SyntheticFixtureConfig,LabIdentity>{
   val i=LabIdentity("synthetic-$run-$owner","effective-$owner","instance-$owner",1,1,0,true)
   // Per-owner fresh run prevents A/B envelope/alias sharing. No persisted user identifier.
   val c=SyntheticFixtureConfig.create(UUID.randomUUID().toString().replace("-",""),labHash(i.namespace));c.validate(context.packageName)
   val dir=File(context.applicationInfo.dataDir,"app_cyclecare_lab_synthetic")
   val pref=File(context.applicationInfo.dataDir,"shared_prefs/${c.prefs}.xml")
   val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
   verify(!File(dir,c.filename).exists()&&!pref.exists()&&!ks.containsAlias(c.alias),"fresh resources")
   owned.add(c to i);return c to i
  }
  suspend fun open(pair:Pair<SyntheticFixtureConfig,LabIdentity>):LabEncryptedStore {
   val s=LabEncryptedStoreFactory.open(context,pair.first,pair.second);handles.add(s);return s
  }
  suspend fun capture(s:LabEncryptedStore,label:String):LabSyntheticSnapshot{val v=s.inspectSynthetic();event(label,v.json().put("snapshotHash",v.digest()));return v}
  try {
   val pm=context.packageManager
   @Suppress("DEPRECATION") val pkg=pm.getPackageInfo(context.packageName,PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS)
   verify(pkg.requestedPermissions.orEmpty().none{it=="android.permission.INTERNET"||it=="android.permission.ACCESS_NETWORK_STATE"},"no network permissions")
   verify(pkg.providers.orEmpty().isEmpty(),"no startup providers")
   verify((pkg.services.orEmpty().map{it.name}+pkg.receivers.orEmpty().map{it.name}).none{it.startsWith("androidx.work.")||it.contains("Supabase")},"no worker components")
   @Suppress("DEPRECATION") val running=(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(Int.MAX_VALUE).filter{it.service.packageName==context.packageName}.map{it.service.className}
   verify(running.none{it.startsWith("androidx.work.")||it.contains("Supabase")},"no running worker service")
   event("initialization",JSONObject().put("package",context.packageName).put("applicationClass",context.applicationContext.javaClass.name).put("services",JSONArray(running)).put("scope","No Activity launch; service audit is not a complete in-process object/thread audit"))
   val aPair=config("A");val ai=aPair.second
   val wrongPackage=object:ContextWrapper(context){override fun getPackageName()="com.cyclecare.app"}
   verify(runCatching{LabEncryptedStoreFactory.open(wrongPackage,aPair.first,ai)}.isFailure,"actual factory package reject")
   verify(runCatching{LabEncryptedStoreFactory.open(context,aPair.first.copy(filename="cycle_care_database"),ai)}.isFailure,"actual factory production file reject")
   var a=open(aPair)
   // Invalid identity/config calls must reject before resources, key lookup or open.
   verify(runCatching{aPair.first.validate("com.cyclecare.app")}.isFailure,"production package rejects")
   verify(runCatching{aPair.first.copy(filename="cycle_care_database").validate(context.packageName)}.isFailure,"production filename rejects")
   val corpus=JSONArray(trackJson);verify(corpus.length()==34,"full track corpus")
   for(index in 0 until corpus.length()){
    val raw=corpus.getJSONObject(index);val before=capture(a,"track-$index-before")
    val plan=LabCorpusMapper.track(ai,raw,before.revision(raw.getString("date")),before.revision("settings"))
    val steps=plan.mutations.size*3+1
    for(step in 1..steps){verify(a.commit(plan,step)==LabCommitResult.StorageFailure,"track injected failure $index/$step");verify(a.inspectSynthetic().digest()==before.digest(),"track rollback independent DAO $index/$step")}
    val result=a.commit(plan);verify(result is LabCommitResult.Committed&&!result.replay,"track commit")
    verify(a.commit(plan) is LabCommitResult.Committed && (a.commit(plan) as LabCommitResult.Committed).replay,"track idempotency")
    val after=capture(a,"track-$index-after")
    for(m in plan.mutations){verify(after.revision(m.recordId)==m.expectedRevision+1,"revision increment");verify(after.rows.singleOrNull{it.recordId==m.recordId}?.payload==m.payload,"exact domain payload")}
    a.validateOpen()
   }
   val settings=JSONObject(settingsJson).getJSONArray("cases");verify(settings.length()==8,"full settings corpus")
   for(index in 0 until settings.length()){
    val row=settings.getJSONObject(index);val before=a.inspectSynthetic()
    val plan=LabCorpusMapper.settings(ai,"settings-$index",row.getString("settingsPayload"),if(row.isNull("profilePayload"))null else row.getString("profilePayload"),before.revision("settings"),before.revision("profile"))
    for(step in 1..plan.mutations.size*3+1){verify(a.commit(plan,step)==LabCommitResult.StorageFailure,"settings fault");verify(a.inspectSynthetic().digest()==before.digest(),"settings rollback")}
    verify(a.commit(plan) is LabCommitResult.Committed,"settings commit");val after=capture(a,"settings-$index")
    for(m in plan.mutations){verify(after.rows.single{it.recordId==m.recordId}.payload==m.payload,"exact settings/profile payload");verify(after.revision(m.recordId)==m.expectedRevision+1,"settings/profile revision")}
    a.validateOpen()
   }
   // Canonical codec rejects noncanonical double, signedzero contradiction and NaN bits.
   val first=corpus.getJSONObject(0);val current=a.inspectSynthetic();val good=LabCorpusMapper.track(ai,first,current.revision(first.getString("date")),current.revision("settings"))
   verify(a.commit(good.copy(operationId="bad-bits",mutations=good.mutations.mapIndexed{i,m->if(i==0)m.copy(bbtBits=0x7fc00001L)else m})) is LabCommitResult.Malformed,"nonfinite bits reject")
   val malformedPayload=JSONObject(first.getString("payload")).put("bbt_celsius",36.60000000001).toString()
   verify(runCatching{labValidatePayload(first.getString("date"),malformedPayload)}.isFailure,"noncanonical codec rejects")
   val zero=(0 until corpus.length()-1).map{corpus.getJSONObject(it)}.first{it.getLong("bbtBits")==0x80000000L}
   verify(runCatching{labValidatePayload(zero.getString("date"),zero.getString("payload"),0L,zero.getLong("sleepBits"))}.isFailure,"signedzero rejects")
   // Unknown result recovery uses same file/alias but NEW factory/adapter; no implicit reset.
   val u=good.copy(operationId="unknown")
   verify(a.commit(u,loseReceiptAfterCommit=true) is LabCommitResult.Unknown,"postcommit unknown")
   val committed=capture(a,"unknown-committed");a.close();verify(a.capture()==null&&a.commit(u)==LabCommitResult.RejectedClosed,"closed APIs")
   a=open(aPair);verify(a.inspectSynthetic().digest()==committed.digest(),"new factory reopen exact")
   val replay=a.commit(u);verify(replay is LabCommitResult.Committed&&replay.replay,"same op recovers")
   val beforeConflict=a.inspectSynthetic().digest();verify(a.commit(good.copy(operationId="old-base"))==LabCommitResult.Conflict,"old log base conflicts");verify(a.inspectSynthetic().digest()==beforeConflict,"conflict no writes")
   val different=LabCorpusMapper.settings(ai,"unknown",settings.getJSONObject(0).getString("settingsPayload"),null,committed.revision("settings"),0)
   verify(a.commit(different)==LabCommitResult.InvalidReuse,"different intent rejects")
   a.closeHelperForSyntheticReopen();a.validateOpen();verify(a.inspectSynthetic().digest()==committed.digest(),"helper reopen retained secret")
   a.close();verify(a.capture()==null&&!a.transition(ai,ai.copy(epoch=2))&&!a.validatePrepared(),"all closed APIs");a.close();a=open(aPair)
   // Missing envelope and corrupt ciphertext must fail without changing owned file bytes.
   a.close()
   val ownedFile=File(context.applicationInfo.dataDir,"app_cyclecare_lab_synthetic/${aPair.first.filename}")
   val bytesBefore=java.security.MessageDigest.getInstance("SHA-256").digest(ownedFile.readBytes())
   val prefs=context.getSharedPreferences(aPair.first.prefs,Context.MODE_PRIVATE)
   val envelope=prefs.getString("wrapped",null)!!
   try {
    verify(prefs.edit().remove("wrapped").commit(),"remove owned envelope")
    verify(runCatching{LabEncryptedStoreFactory.open(context,aPair.first,ai)}.isFailure,"missing envelope no fallback")
    verify(java.security.MessageDigest.getInstance("SHA-256").digest(ownedFile.readBytes()).contentEquals(bytesBefore),"missing envelope file unchanged")
    verify(prefs.edit().putString("wrapped","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").commit(),"corrupt owned envelope")
    verify(runCatching{LabEncryptedStoreFactory.open(context,aPair.first,ai)}.isFailure,"bad envelope no fallback")
    verify(java.security.MessageDigest.getInstance("SHA-256").digest(ownedFile.readBytes()).contentEquals(bytesBefore),"bad envelope file unchanged")
   }finally{check(prefs.edit().putString("wrapped",envelope).commit())}
   a=open(aPair)
   val raceBase=a.inspectSynthetic();val r1=LabCorpusMapper.settings(ai,"race-1",settings.getJSONObject(0).getString("settingsPayload"),null,raceBase.revision("settings"),0);val r2=r1.copy(operationId="race-2")
   val race=coroutineScope{listOf(async{a.commit(r1)},async{a.commit(r2)}).awaitAll()};verify(race.count{it is LabCommitResult.Committed}==1&&race.count{it==LabCommitResult.Conflict}==1,"perrecord base race")
   // Real adapters A/B. Publication paused after B validation to schedule old-A commit.
   val bPair=config("B");val bi=bPair.second.copy(epoch=2);val b=open(bPair.first to bi);val router=PreopenedLabRouter(ai,a)
   val ready=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
   val paused=object:PreparedLabStore by b {override suspend fun validatePrepared():Boolean{val ok=b.validatePrepared();ready.complete(Unit);release.await();return ok}}
   coroutineScope {
    val publish=async{router.publish(ai,bi,paused)};ready.await()
    val base=a.inspectSynthetic();val p=LabCorpusMapper.settings(ai,"before-publish",settings.getJSONObject(0).getString("settingsPayload"),null,base.revision("settings"),0)
    try{verify(router.commit(p) is LabCommitResult.Committed,"old A writable before publish")}finally{release.complete(Unit)};verify(publish.await(),"publish B")
   }
   val aFrozen=a.inspectSynthetic().digest();val bFrozen=b.inspectSynthetic().digest()
   verify(router.commit(u)==LabCommitResult.Stale,"crossowner ABA rejects old A")
   val ai2=ai.copy(epoch=3,session=2);verify(a.transition(ai,ai2),"prepare A next session")
   val badNext=ai2.copy(session=3);verify(!router.publish(bi,badNext,a),"fullidentity mismatch rejects")
   verify(router.capture()==bi,"failed publish keeps B")
   verify(router.publish(bi,ai2,a),"ABA publish A")
   verify(router.commit(u)==LabCommitResult.Stale,"sameowner ABA rejects")
   verify(a.inspectSynthetic().digest()==aFrozen&&b.inspectSynthetic().digest()==bFrozen,"ABA no writes either namespace")
   // Corrupt B, publication fails; current A remains usable. Evidence preserves corrupt fixture on failure.
   val bp=LabCorpusMapper.settings(bi,"B-seed",settings.getJSONObject(0).getString("settingsPayload"),null,0,0);verify(b.commit(bp) is LabCommitResult.Committed,"seed B receipt")
   b.corruptSyntheticReceiptForRejection();verify(!router.publish(ai2,bi.copy(epoch=4),b),"corrupt B rejected")
   verify(router.capture()==ai2,"corrupt B leaves A current")
   val finalBase=a.inspectSynthetic();verify(router.commit(LabCorpusMapper.settings(ai2,"after-corrupt-B",settings.getJSONObject(0).getString("settingsPayload"),null,finalBase.revision("settings"),0)) is LabCommitResult.Committed,"A writable after corrupt B")
   capture(a,"final-A");capture(b,"final-B-deliberately-corrupt")
   @Suppress("DEPRECATION") val afterServices=(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(Int.MAX_VALUE).filter{it.service.packageName==context.packageName}.map{it.service.className}
   verify(afterServices.none{it.startsWith("androidx.work.")||it.contains("Supabase")},"no worker services after corpus")
   event("initialization-after",JSONArray(afterServices))
   handles.forEach{it.close()}
   // Cleanup ONLY resources preflighted absent and owned by this generated invocation.
   val registry=context.getSharedPreferences("cyclecare_lab_synthetic_registry",Context.MODE_PRIVATE)
   val dir=File(context.applicationInfo.dataDir,"app_cyclecare_lab_synthetic")
   val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
   write("CHECKS_PASS_CLEANUP_PENDING");cleanupStarted=true
   for((c,_) in owned){
    val expected=c.runId+":"+c.ownerDigest+":"+c.alias+":"+c.prefs
    verify(registry.getString(c.filename,null)==expected,"cleanup binding")
    for(suffix in listOf("","-wal","-shm","-journal")){val f=File(dir,c.filename+suffix);verify(!f.exists()||f.delete(),"exact owned file cleanup")}
    verify(context.deleteSharedPreferences(c.prefs),"owned preferences cleanup")
    if(ks.containsAlias(c.alias))ks.deleteEntry(c.alias)
    verify(!ks.containsAlias(c.alias),"owned alias cleanup")
    verify(registry.edit().remove(c.filename).commit(),"owned registry entry cleanup")
   }
   event("cleanup","Exact invocation-owned files/envelopes/aliases/entries removed; shared directory and registry preserved")
   write("PASS");"PASS"
  }catch(e:Exception){
   // Never write exception text/stack/cause from crypto/provider paths.
   handles.forEach{runCatching{it.close()}};event("failure",JSONObject().put("class",e.javaClass.name).put("cleanupStarted",cleanupStarted).put("resourcesPreserved",!cleanupStarted));write("FAIL");"FAIL"
  }
 }
}

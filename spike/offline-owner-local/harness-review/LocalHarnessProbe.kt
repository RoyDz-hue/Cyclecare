package com.cyclecare.app.lab.store
import kotlinx.coroutines.*
import org.json.*
import java.io.File
fun main(args:Array<String>)=runBlocking {
 var n=0;fun verify(ok:Boolean){check(ok);n++}
 val i=LabIdentity("synthetic-test","test","test",1,1,0,true)
 val corpus=JSONArray(File(args[0]).readText());verify(corpus.length()==34)
 for(k in 0 until corpus.length()){
  val row=corpus.getJSONObject(k);val p=LabCorpusMapper.track(i,row,5,9);verify(p.identity==i&&p.mutations.first().expectedRevision==5L)
  verify(p.mutations.size==if(row.isNull("anchorAfter"))1 else 2)
  p.mutations.forEach{m->if(m.payload!=null)labValidatePayload(m.recordId,m.payload,m.bbtBits,m.sleepBits);verify(m.payloadHash==labHash(m.payload))}
 }
 val settings=JSONObject(File(args[1]).readText()).getJSONArray("cases");verify(settings.length()==8)
 for(k in 0 until settings.length()){val s=settings.getJSONObject(k);val p=LabCorpusMapper.settings(i,"s$k",s.getString("settingsPayload"),if(s.isNull("profilePayload"))null else s.getString("profilePayload"),4,6);verify(p.mutations.size==if(s.isNull("profilePayload"))1 else 2);p.mutations.forEach{labValidatePayload(it.recordId,it.payload!!);n++}}
 val snap=LabSyntheticSnapshot(listOf(LabRecord(i.namespace,"settings","{}")),listOf(LabRevision(i.namespace,"settings",2,false,labHash("{}"))),emptyList(),emptyList(),LabMetadata(runId="synthetic",ownerDigest="digest"));verify(snap.revision("settings")==2L&&snap.revision("absent")==0L);verify(snap.digest()==snap.digest());verify(!snap.json().toString().contains("wrapped"))
 class Mock(val id:LabIdentity):PreparedLabStore{override val namespace=id.namespace;override val instance=id.instance;override suspend fun capturedIdentity()=id;override suspend fun validatePrepared()=true;override suspend fun apply(plan:LabCommitPlan)=LabCommitResult.Committed("mock",false)}
 val a=Mock(i);val bi=i.copy(namespace="B",instance="B",epoch=2);val b=Mock(bi);val router=PreopenedLabRouter(i,a);val ready=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val paused=object:PreparedLabStore by b{override suspend fun validatePrepared():Boolean{ready.complete(Unit);release.await();return true}}
 coroutineScope{val pub=async{router.publish(i,bi,paused)};ready.await();try{verify(router.capture()==i);verify(router.commit(LabCommitPlan(i,"mock","",emptyList())) is LabCommitResult.Committed)}finally{release.complete(Unit)};verify(pub.await())};verify(router.capture()==bi);verify(router.commit(LabCommitPlan(i,"old","",emptyList()))==LabCommitResult.Stale)
 println("PASS $n local corpus/mapper/typed snapshot and controlled router mock assertions; no Android Context, Room, Keystore or SQLCipher effects executed")
}

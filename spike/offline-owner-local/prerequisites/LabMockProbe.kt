package com.cyclecare.app.lab.store
import kotlinx.coroutines.runBlocking
import org.json.*
import java.io.File
fun main(args:Array<String>)=runBlocking {
 var n=0;fun verify(v:Boolean){check(v);n++}
 val original=byteArrayOf(1,2,3);val owned=RetainedFactorySecret(original);original.fill(0);val passed=owned.handOff();verify(passed.contentEquals(byteArrayOf(1,2,3)));verify(runCatching{owned.handOff()}.isFailure);owned.close();verify(passed.all{it==0.toByte()})
 val i=LabIdentity("synthetic-A","effective-A","db-A",1,1,0,true)
 class Mock(override val namespace:String,override val instance:String,var valid:Boolean=true):PreparedLabStore {var writes=0;var captured=if(namespace==i.namespace)i else i.copy(namespace=namespace,effective="B",instance=instance,epoch=2);override suspend fun capturedIdentity()=captured;override suspend fun validatePrepared()=valid;override suspend fun apply(plan:LabCommitPlan):LabCommitResult {writes++;return LabCommitResult.Committed("mock",false)}}
 val a=Mock(i.namespace,i.instance);val b=Mock("synthetic-B","db-B");val router=PreopenedLabRouter(i,a);val plan=LabCommitPlan(i,"op","digest",emptyList());verify(router.commit(plan) is LabCommitResult.Committed)
 val bi=i.copy(namespace=b.namespace,effective="B",instance=b.instance,epoch=2);b.valid=false;verify(!router.publish(i,bi,b));verify(router.capture()==i&&a.writes==1&&b.writes==0);verify(router.commit(plan) is LabCommitResult.Committed);b.valid=true;verify(router.publish(i,bi,b));verify(router.commit(plan)==LabCommitResult.Stale&&a.writes==2&&b.writes==0);verify(!router.publish(i,bi,b));a.captured=i.copy(epoch=3);verify(router.publish(bi,i.copy(epoch=3),a));verify(router.commit(plan)==LabCommitResult.Stale&&a.writes==2)
 val corpus=JSONArray(File(args[0]).readText());for(index in 0 until corpus.length()-1){val row=corpus.getJSONObject(index);labValidatePayload(row.getString("date"),row.getString("payload"),row.getLong("bbtBits"),row.getLong("sleepBits"));n++}
 // Same namespace/instance but mismatched full session/epoch is rejected, A remains writable.
 val good=Mock(i.namespace,i.instance);val bad=Mock("synthetic-B","db-B");bad.captured=bi.copy(session=99);val r=PreopenedLabRouter(i,good);verify(!r.publish(i,bi,bad));verify(r.capture()==i);verify(r.commit(plan) is LabCommitResult.Committed&&good.writes==1&&bad.writes==0);bad.captured=bi;verify(r.publish(i,bi,bad));verify(r.commit(plan)==LabCommitResult.Stale)
 val row=corpus.getJSONObject(0);val payload=JSONObject(row.getString("payload")).put("bbt_celsius",36.60000000001).toString();verify(runCatching{labValidatePayload(row.getString("date"),payload,row.getLong("bbtBits"),row.getLong("sleepBits"))}.isFailure)
 for(bits in listOf(-1L,0x100000000L,0x7fc00001L,0x7f800000L,0xff800000L))verify(runCatching{labValidatePayload(row.getString("date"),row.getString("payload"),bits,row.getLong("sleepBits"))}.isFailure)
 // Actual adapter pure validator reopens signedzero from canonical literal if rawbits absent.
 val minus=(0 until corpus.length()-1).map{corpus.getJSONObject(it)}.first{it.getLong("bbtBits")==0x80000000L};labValidatePayload(minus.getString("date"),minus.getString("payload"));n++
 println("PASS $n actual lab pure validator/secret-lifecycle/router mock assertions; NO Room/key/open execution")
}

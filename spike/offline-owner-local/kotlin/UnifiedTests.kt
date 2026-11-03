import org.json.*
import java.io.File
import com.cyclecare.app.domain.ai.*
import com.cyclecare.app.data.local.*
fun main(args:Array<String>){
 trackLegacyMain(arrayOf(args[0],args[1]+"/legacy"))
 val plans=JSONArray(File(args[0]).readText());val root=File(args[1]);root.mkdirs();var n=0
 fun verify(v:Boolean){check(v);n++}
 val p=plans.getJSONObject(0);val id=identityFrom(p.getJSONObject("identity"));val file=File(root,"unified.json");file.delete();val s=TrackStore(file,id)
 verify(s.commit(p,fail=99)=="Unknown");val reopened=TrackStore(file,id);verify(reopened.commit(p)=="CommittedReplay");verify(reopened.bytes()==s.bytes())
 val gate=CareLogConsentGate();val proposal=gate.propose(id.effective,"2024-03-16","I have cramps")!!;val consumed=gate.confirm(proposal.id,id.effective)!!;verify(gate.confirm(proposal.id,id.effective)==null)
 val current=HealthSyncPayloadCodec.parseLogPayload(p.getString("payload"),p.getString("date"));val merged=CareConversationPolicy.merge(current,consumed.interpretation);val care=JSONObject(p.toString());care.put("op","care-once").put("payload",HealthSyncPayloadCodec.logPayload(merged));care.put("payloadHash",HealthSyncPayloadCodec.hash(care.getString("payload")));care.put("intentDigest",sha(JSONArray().put("UPSERT").put(care.getString("date")).put(care.getString("payloadHash")).put("").toString()))
 verify(reopened.commit(care,base=1,fail=99)=="Unknown");verify(TrackStore(file,id).commit(care,base=1)=="CommittedReplay");verify(gate.confirm(proposal.id,id.effective)==null)
 val unified=TrackStore(file,id);val oldBytes=file.readText();val b=id.copy(namespace="synthetic-B",effective="B",instance="db-B",epoch=2);verify(unified.transition(1,b));val bBytes=unified.bytes();verify(unified.commit(care,base=2)=="RejectedStale");verify(unified.bytes()==bBytes&&file.readText()==oldBytes);verify(!unified.transition(1,id));verify(unified.transition(2,id.copy(epoch=3)));verify(unified.commit(care,base=2)=="RejectedStale");verify(file.readText()==oldBytes)
 // Cross-domain proposal -> owner switch -> commit must not write either namespace.
 val pending=gate.propose(id.effective,"2024-03-16","I feel tired")!!;gate.confirm(pending.id,id.effective)!!;verify(unified.transition(3,b.copy(epoch=4)));verify(unified.commit(care,base=2)=="RejectedStale")
 val af=File(root,"anchor-race.json");af.delete();val a=TrackStore(af,id);val anchorPlan=(0 until plans.length()-1).map{plans.getJSONObject(it)}.first{!it.isNull("anchorAfter")}
 // settings edit through SAME coordinator, profile absent
 val settings=JSONObject(anchorPlan.getString("settingsBefore"));settings.put("average_cycle_length",30)
 fun settingsPlan(op:String,profile:JSONObject?=null):JSONObject{
  val after=profile?.let{JSONObject(it.toString()).put("cycle_length_option","30 days").put("cycle_length_days",30).put("period_duration_days",5)}
  val payload=settings.toString();return JSONObject().put("kind","SETTINGS").put("op",op).put("identity",identityJson(id)).put("settingsPayload",payload).put("previousAverage",28).put("profileBefore",profile?.toString()?:JSONObject.NULL).put("profileAfter",after?.toString()?:JSONObject.NULL).put("digest",sha(JSONArray().put("SETTINGS").put(payload).put(after?.toString()?:JSONObject.NULL).toString()))
 }
 verify(a.commit(settingsPlan("settings-first"))=="Committed");val ab=af.readText();verify(a.commit(anchorPlan,settingsBase=0)=="SettingsConflict");verify(af.readText()==ab)
 val profile=JSONObject(HealthSyncPayloadCodec.profilePayload(OnboardingProfile(selectedGoalsCsv="keep",isAgeVerified=false)))
 for(fault in 1..7){val sf=File(root,"settings-$fault.json");sf.delete();val sc=TrackStore(sf,id);val frozen=sc.bytes();verify(sc.commit(settingsPlan("settings-profile",profile),fail=fault)=="StorageFailure");verify(sc.bytes()==frozen&&!sf.exists());verify(sc.commit(settingsPlan("settings-profile",profile),fail=99)=="Unknown");verify(TrackStore(sf,id).commit(settingsPlan("settings-profile",profile))=="CommittedReplay")}
 val df=File(root,"delete-upsert.json");df.delete();val ds=TrackStore(df,id);verify(ds.commit(p)=="Committed");val del=plans.getJSONObject(plans.length()-1);verify(ds.commit(del,base=1)=="Committed");val up=JSONObject(p.toString()).put("op","up-after-delete");verify(ds.commit(up,base=2)=="Committed");verify(JSONObject(ds.bytes()).getJSONObject("revisions").getJSONObject(p.getString("date")).getLong("revision")==3L)
 // actual threads on expanded fullTrack path
 val tf=File(root,"thread.json");tf.delete();val ts=TrackStore(tf,id);val start=java.util.concurrent.CountDownLatch(1);val finish=java.util.concurrent.CountDownLatch(1);val worker=Thread{start.await();check(ts.commit(p)=="RejectedStale");finish.countDown()};worker.start();verify(ts.transition(1,b));start.countDown();finish.await();worker.join();verify(!tf.exists())
 val cf=File(root,"commit-first.json");cf.delete();val cs=TrackStore(cf,id);verify(cs.commit(p)=="Committed");verify(cs.transition(1,b));verify(cf.exists())
 val envelopes=JSONArray(File(args[2]).readText());val corpus=JSONObject(File(args[3]).readText()).getJSONArray("cases");val request=pinRequest(id,corpus.getJSONObject(0));verify(cs.projection(id,request,envelopes.getJSONObject(0))=="RejectedStale");verify(TrackStore(cf,id).projection(id,request,envelopes.getJSONObject(0))=="Valid")
 val corrupt=File(root,"corrupt.json");corrupt.writeText("{broken");val broken=TrackStore(corrupt,id);verify(broken.commit(p)=="Malformed(store)");verify(corrupt.readText()=="{broken")
 val tampered=JSONObject(cf.readText());tampered.getJSONObject("receipts").getJSONObject(tampered.getJSONObject("receipts").keys().next()).put("digest",true);corrupt.writeText(tampered.toString());val frozen=corrupt.readText();verify(TrackStore(corrupt,id).commit(p)=="Malformed(store)");verify(corrupt.readText()==frozen)
 for(field in listOf("records","revisions","journal","receipts","settings","namespace")){val malformed=JSONObject(cf.readText());malformed.put(field,true);corrupt.writeText(malformed.toString());val unchanged=corrupt.readText();verify(TrackStore(corrupt,id).commit(p)=="Malformed(store)");verify(corrupt.readText()==unchanged)}
 val settingsFile=File(root,"settings-malformed.json");settingsFile.delete();val sm=TrackStore(settingsFile,id);val settingPlan=settingsPlan("bad-setting");val settingsJson=JSONObject(settingPlan.getString("settingsPayload")).put("average_cycle_length",true);settingPlan.put("settingsPayload",settingsJson.toString());val original=sm.bytes();verify(sm.commit(settingPlan).startsWith("Malformed"));verify(sm.bytes()==original&&!settingsFile.exists())
 // G1: corrupt target B transition restores A identity/state/file and A remains writable.
 val gf=File(root,"g1.json");gf.delete();val ga=TrackStore(gf,id);verify(ga.commit(p)=="Committed");val gaState=ga.bytes();val gaFile=gf.readText();val target=File(gf.path+"."+sha(b.namespace));target.writeText("{corrupt B");verify(!ga.transition(1,b));verify(ga.capture()==id&&ga.bytes()==gaState&&gf.readText()==gaFile);verify(target.readText()=="{corrupt B");verify(ga.commit(JSONObject(p.toString()).put("op","afterfailedB"),base=1)=="Committed")
 // G2: valid u32 but nonfinite float patterns must reject and freeze unified state.
 val gBefore=ga.bytes();val gFile=gf.readText();for(bits in listOf(0x7fc00001L,0x7f800000L,0xff800000L)){val x=JSONObject(p.toString()).put("op","nonfinite-$bits").put("bbtBits",bits);verify(ga.commit(x,base=2)=="Malformed(nonFiniteBits)");verify(ga.bytes()==gBefore&&gf.readText()==gFile)}
 println("PASS $n unified fullTrack/care/settings/profile/validatedFILEreceipt/ABA/unknown/thread/corrupt/projection assertions; synthetic only")
}

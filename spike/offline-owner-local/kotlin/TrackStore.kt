import org.json.*
import java.io.File
import java.time.LocalDate
import java.security.MessageDigest
import com.cyclecare.app.data.local.*
private fun hash(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
private class InvalidTrack(val field:String):Exception()
private fun malformed(f:String):Nothing=throw InvalidTrack(f)
private fun str(o:JSONObject,k:String,max:Int=100000):String {val v=o.opt(k);if(v !is String||v.length>max)malformed(k);return v}
private fun int(o:JSONObject,k:String,max:Long=Long.MAX_VALUE):Long{val v=o.opt(k);if(v !is Int&&v !is Long)malformed(k);val n=(v as Number).toLong();if(n<0||n>max)malformed(k);return n}
private fun fields(o:JSONObject,s:Set<String>){if(o.keys().asSequence().toSet()!=s)malformed("keys")}
private fun strictLog(payload:String,record:String):CycleDayLog {
 val x=JSONObject(payload);fields(x,setOf("date","flow","energy","mood","symptoms","vaginal_health","urinary_health","cervical_mucus","ovulation_test","bbt_celsius","sleep_hours","sleep_quality","water_glasses","notes","pain_intensity"))
 for(k in listOf("date","flow","energy","mood","symptoms","vaginal_health","urinary_health","cervical_mucus","ovulation_test","sleep_quality","notes"))str(x,k,4096)
 if(str(x,"date")!=record)malformed("date");LocalDate.parse(record)
 for(k in listOf("bbt_celsius","sleep_hours")){val v=x.opt(k);if(v !is Number||!v.toDouble().isFinite()||!v.toFloat().isFinite())malformed(k)}
 // Preserve signed integer semantics of local model; remote-restoration ranges are NOT local save rules.
 for(k in listOf("water_glasses","pain_intensity")){val v=x.opt(k);if(v !is Int)malformed(k)}
 return CycleDayLog(record,x.getString("flow"),x.getString("energy"),x.getString("mood"),x.getString("symptoms"),x.getString("vaginal_health"),x.getString("urinary_health"),x.getString("cervical_mucus"),x.getString("ovulation_test"),x.getDouble("bbt_celsius").toFloat(),x.getDouble("sleep_hours").toFloat(),x.getString("sleep_quality"),x.getInt("water_glasses"),x.getString("notes"),x.getInt("pain_intensity"))
}
class TrackStore(file:File,private var currentIdentity:Identity=Identity("synthetic-A","synthetic-account-A","db-A-1",1,1,0,true)) {
 private val rootFile=file
 private var file=if(currentIdentity.namespace=="synthetic-A")file else File(file.path+"."+hash(currentIdentity.namespace))
 private val owner get()=currentIdentity.namespace
 private val lock=Any();private var state=JSONObject().put("records",JSONObject()).put("revisions",JSONObject()).put("journal",JSONObject()).put("receipts",JSONObject()).put("settings",JSONObject()).put("namespace",currentIdentity.namespace)
 private var openStatus="Valid"
 init{if(file.exists()){try{if(file.length()>4000000)malformed("size");val loaded=JSONObject(file.readText());validateOpen(loaded);state=loaded}catch(e:Exception){openStatus="Malformed(store)"}}}
 private fun validateOpen(x:JSONObject){
  fields(x,setOf("records","revisions","journal","receipts","settings","namespace"));if(str(x,"namespace",128)!=currentIdentity.namespace)malformed("namespace")
  for(k in listOf("records","revisions","journal","receipts","settings"))if(x.optJSONObject(k)==null)malformed(k)
  val revs=x.getJSONObject("revisions");for(k in revs.keys()){val r=revs.getJSONObject(k);fields(r,setOf("revision","tombstone","hash"));if(int(r,"revision")==0L||r.opt("tombstone") !is Boolean)malformed("revision");if(!r.isNull("hash")&&!Regex("[0-9a-f]{64}").matches(str(r,"hash",64)))malformed("hash")}
  for(k in x.getJSONObject("records").keys()){val payload=str(x.getJSONObject("records"),k);if(k=="settings")validateSettings(JSONObject(payload))else if(k=="profile")validateProfile(JSONObject(payload))else strictLog(payload,k);val r=revs.optJSONObject(k)?:malformed("missingRevision");if(r.getBoolean("tombstone")||r.getString("hash")!=HealthSyncPayloadCodec.hash(payload))malformed("recordRevision")}
  val journal=x.getJSONObject("journal");val receipts=x.getJSONObject("receipts")
  for(k in journal.keys()){val j=journal.getJSONObject(k);fields(j,setOf("record","revision","kind","payload","hash"));str(j,"record",128);int(j,"revision");if(str(j,"kind",8) !in setOf("UPSERT","DELETE"))malformed("journalKind");val p=if(j.isNull("payload"))null else str(j,"payload");if(str(j,"hash",64)!=HealthSyncPayloadCodec.hash(p))malformed("journalHash")}
  for(k in journal.keys()){val receiptKey=if(k.endsWith(":settings"))k.removeSuffix(":settings")else if(k.endsWith(":profile"))k.removeSuffix(":profile")else k;if(!receipts.has(receiptKey))malformed("orphanJournal")}
  for(k in receipts.keys()){val r=receipts.getJSONObject(k);fields(r,setOf("digest","revision"));if(!Regex("[0-9a-f]{64}").matches(str(r,"digest",64))||int(r,"revision")==0L||!journal.has(k)||int(journal.getJSONObject(k),"revision")!=int(r,"revision"))malformed("receipt")}
 }
 private fun validateSettings(s:JSONObject){
  fields(s,setOf("selected_goals","last_period_start_date","average_cycle_length","period_duration","luteal_phase_days","pregnancy_mode","pregnancy_due_date"));str(s,"selected_goals",4096)
  for(k in listOf("last_period_start_date","pregnancy_due_date")){val d=str(s,k,10);if(d.isNotEmpty()&&k!="last_period_start_date")LocalDate.parse(d)}
  for(k in listOf("average_cycle_length","period_duration","luteal_phase_days"))if(s.opt(k) !is Int)malformed(k)
  if(s.opt("pregnancy_mode") !is Boolean)malformed("pregnancy_mode")
 }
 private fun validateProfile(p:JSONObject){
  fields(p,setOf("selected_goals","last_period_date","last_period_end_date","approximate_period_choice","cycle_length_option","cycle_length_days","period_duration_days","typical_symptoms","typical_energy","typical_sleep_quality","has_completed_deep_baseline"))
  for(k in listOf("selected_goals","last_period_date","last_period_end_date","approximate_period_choice","cycle_length_option","typical_symptoms","typical_energy","typical_sleep_quality"))str(p,k,4096)
  for(k in listOf("cycle_length_days","period_duration_days"))if(p.opt(k) !is Int)malformed(k)
  if(p.opt("has_completed_deep_baseline") !is Boolean)malformed("has_completed_deep_baseline")
 }
 fun capture()=synchronized(lock){currentIdentity}
 fun transition(expectedEpoch:Long,next:Identity)=synchronized(lock){
  if(openStatus!="Valid"||expectedEpoch!=currentIdentity.epoch||next.epoch<=expectedEpoch||checked{validIdentity(next)}!="Valid"){rejected++;false}
  else{
   val target=if(next.namespace=="synthetic-A")rootFile else File(rootFile.path+"."+hash(next.namespace))
   val previousIdentity=currentIdentity
   try{currentIdentity=next;val loaded=if(target.exists())JSONObject(target.readText())else JSONObject().put("records",JSONObject()).put("revisions",JSONObject()).put("journal",JSONObject()).put("receipts",JSONObject()).put("settings",JSONObject()).put("namespace",currentIdentity.namespace);validateOpen(loaded);state=loaded;file=target;currentIdentity=next;true}catch(e:Exception){currentIdentity=previousIdentity;rejected++;false}
  }
 }
 fun projection(token:Identity,request:Request,envelope:JSONObject)=synchronized(lock){if(token!=currentIdentity){rejected++;"RejectedStale"}else validateProjection(request,envelope)}
 var rejected=0;private set
 fun bytes()=synchronized(lock){state.toString()}
 private fun commitSettingsLocked(plan:JSONObject,base:Long,profileBase:Long,fail:Int):String {
  fields(plan,setOf("kind","op","identity","settingsPayload","previousAverage","profileBefore","profileAfter","digest"))
  val op=str(plan,"op",128);if(op.isBlank())malformed("op");val payload=str(plan,"settingsPayload");val settings=JSONObject(payload);validateSettings(settings)
  if(base<0||profileBase<0)malformed("base")
  val previous=int(plan,"previousAverage",1000).toInt();val length=int(settings,"average_cycle_length",1000).toInt();val period=int(settings,"period_duration",1000).toInt()
  val before=if(plan.isNull("profileBefore"))null else JSONObject(str(plan,"profileBefore"))
  if(before!=null)validateProfile(before)
  val after=before?.let{JSONObject(it.toString()).put("cycle_length_option",if(length!=previous)"$length days" else it.getString("cycle_length_option")).put("cycle_length_days",length).put("period_duration_days",period)}
  val expectedAfter=if(after==null)null else after.toString();val providedAfter=if(plan.isNull("profileAfter"))null else str(plan,"profileAfter");if(expectedAfter!=providedAfter)malformed("profileDecision")
  val digest=hash(JSONArray().put("SETTINGS").put(payload).put(providedAfter?:JSONObject.NULL).toString());if(str(plan,"digest",64)!=digest)malformed("settingsDigest")
  val key=JSONArray().put(owner).put(op).toString();val receipts=state.getJSONObject("receipts");val receipt=receipts.optJSONObject(key);if(receipt!=null)return if(receipt.getString("digest")==digest)"CommittedReplay"else"InvalidReuse"
  val revs=state.getJSONObject("revisions");val sr=revs.optJSONObject("settings")?.getLong("revision")?:0L;val pr=revs.optJSONObject("profile")?.getLong("revision")?:0L
  if(sr!=base||after!=null&&pr!=profileBase)return "SettingsConflict"
  val stored=state.getJSONObject("records").optString("profile","");if(stored.isNotEmpty()&&stored!=plan.optString("profileBefore"))return "ProfileConflict"
  if(sr==Long.MAX_VALUE||pr==Long.MAX_VALUE)malformed("revisionOverflow")
  val next=JSONObject(state.toString());var step=0;fun fault(){step++;if(fail==step)throw java.io.IOException("injected")}
  fun put(record:String,p:String,revision:Long,jkey:String){next.getJSONObject("records").put(record,p);fault();next.getJSONObject("revisions").put(record,JSONObject().put("revision",revision).put("tombstone",false).put("hash",HealthSyncPayloadCodec.hash(p)));fault();next.getJSONObject("journal").put(jkey,JSONObject().put("record",record).put("revision",revision).put("kind","UPSERT").put("payload",p).put("hash",HealthSyncPayloadCodec.hash(p)));fault()}
  put("settings",payload,sr+1,key);next.getJSONObject("settings").put("payload",payload);if(after!=null)put("profile",after.toString(),pr+1,key+":profile")
  next.getJSONObject("receipts").put(key,JSONObject().put("digest",digest).put("revision",sr+1));fault();validateOpen(next)
  validateOpen(next);val tmp=File(file.path+".tmp");tmp.writeText(next.toString());java.nio.file.Files.move(tmp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);state=next;return if(fail==99)"Unknown"else"Committed"
 }
 fun commit(plan:JSONObject,base:Long=0,settingsBase:Long=0,fail:Int=0):String=synchronized(lock){
  fun reject(r:String):String{rejected++;return r}
  try{
   if(openStatus!="Valid")return@synchronized reject(openStatus)
   val id=plan.optJSONObject("identity")?:malformed("identity")
   if(identityFrom(id)!=currentIdentity)return@synchronized reject("RejectedStale")
   if(plan.optString("kind")=="SETTINGS"){val r=commitSettingsLocked(plan,base,settingsBase,fail);if(r!="Committed"&&r!="CommittedReplay"&&r!="Unknown")rejected++;return@synchronized r}
   val kind=str(plan,"kind",8);if(kind !in setOf("UPSERT","DELETE"))malformed("kind")
   val allowed=if(kind=="UPSERT")setOf("kind","op","date","payload","payloadHash","bbtBits","sleepBits","anchorBefore","anchorAfter","settingsBefore","settingsAfter","settingsHash","intentDigest","identity")else setOf("kind","op","date","payload","payloadHash","anchorAfter","intentDigest","identity")
   fields(plan,allowed)
   val op=str(plan,"op",128);if(op.isBlank())malformed("op");val date=str(plan,"date",10);LocalDate.parse(date)
   if(base<0||settingsBase<0)malformed("base")
   val payload=if(plan.isNull("payload"))null else str(plan,"payload")
   val log=if(kind=="UPSERT"){
    val parsed=strictLog(payload?:malformed("payload"),date)
    val b=Float.fromBits(int(plan,"bbtBits",0xffffffffL).toInt());val sh=Float.fromBits(int(plan,"sleepBits",0xffffffffL).toInt())
    if(!b.isFinite()||!sh.isFinite())malformed("nonFiniteBits")
    // AOSP parsing of literal -0 yields integer zero. Rawbits carry signedzero across plan.
    if(b!=parsed.bbtCelsius||sh!=parsed.sleepHours)malformed("bitsPayloadMismatch")
    parsed.copy(bbtCelsius=b,sleepHours=sh)
   }else null
   if(kind=="DELETE"&&payload!=null)malformed("deletePayload")
   if(log!=null&&!log.hasRecordedObservations())return@synchronized reject("Empty")
   val expectedHash=HealthSyncPayloadCodec.hash(payload);if(str(plan,"payloadHash",64)!=expectedHash)malformed("payloadHash")
   if(log!=null&&HealthSyncPayloadCodec.logPayload(log)!=payload)malformed("nonCanonicalPayload")
   val hasAnchor=plan.has("anchorAfter")&&!plan.isNull("anchorAfter")
   var nextSettings:String?=null
   if(log!=null){
    val before=str(plan,"settingsBefore");val obj=JSONObject(before)
    // Exact codec-produced settings tuple, no remote parser behavior imported.
    fields(obj,setOf("selected_goals","last_period_start_date","average_cycle_length","period_duration","luteal_phase_days","pregnancy_mode","pregnancy_due_date"))
    val previous=str(obj,"last_period_start_date",10);if(str(plan,"anchorBefore",10)!=previous)malformed("anchorBefore")
    val computed=anchor(log,CycleSettings(lastPeriodStartDate=previous))
    if((computed!=null)!=hasAnchor||(computed!=null&&str(plan,"anchorAfter",10)!=computed))malformed("anchorDecision")
    val after=str(plan,"settingsAfter");val expected=JSONObject(before);if(computed!=null)expected.put("last_period_start_date",computed)
    if(expected.toString()!=after||HealthSyncPayloadCodec.hash(after)!=str(plan,"settingsHash",64))malformed("settingsAfter")
    nextSettings=after
   }else if(hasAnchor)malformed("deleteAnchor")
   val digest=hash(JSONArray().put(kind).put(date).put(expectedHash).put(if(hasAnchor)HealthSyncPayloadCodec.hash(nextSettings)else "").toString())
   if(str(plan,"intentDigest",64)!=digest)malformed("intentDigest")
   val key=JSONArray().put(owner).put(op).toString();val receipt=state.getJSONObject("receipts").optJSONObject(key)
   if(receipt!=null)return@synchronized if(receipt.getString("digest")==digest)"CommittedReplay" else reject("InvalidReuse")
   val revisions=state.getJSONObject("revisions");val revision=revisions.optJSONObject(date)?.getLong("revision")?:0L
   if(revision!=base)return@synchronized reject("Conflict")
   val sr=revisions.optJSONObject("settings")?.getLong("revision")?:0L
   if(hasAnchor&&sr!=settingsBase)return@synchronized reject("SettingsConflict")
   if(revision==Long.MAX_VALUE||hasAnchor&&sr==Long.MAX_VALUE)malformed("revisionOverflow")
   if(log!=null){val stored=state.getJSONObject("settings").optString("payload","");if(stored.isNotEmpty()&&stored!=plan.getString("settingsBefore"))return@synchronized reject("SettingsConflict")}
   val next=JSONObject(state.toString());var step=0
   fun fault(){step++;if(fail==step)throw java.io.IOException("injected")}
   if(payload==null)next.getJSONObject("records").remove(date)else next.getJSONObject("records").put(date,payload);fault()
   if(hasAnchor){next.getJSONObject("settings").put("payload",nextSettings);next.getJSONObject("records").put("settings",nextSettings);fault()}
   fun journal(record:String,rev:Long,p:String?,opKey:String,tomb:Boolean){next.getJSONObject("revisions").put(record,JSONObject().put("revision",rev).put("tombstone",tomb).put("hash",if(tomb)JSONObject.NULL else HealthSyncPayloadCodec.hash(p)));fault();next.getJSONObject("journal").put(opKey,JSONObject().put("record",record).put("revision",rev).put("kind",if(tomb)"DELETE"else"UPSERT").put("payload",p?:JSONObject.NULL).put("hash",HealthSyncPayloadCodec.hash(p)));fault()}
   journal(date,revision+1,payload,key,kind=="DELETE");if(hasAnchor)journal("settings",sr+1,nextSettings,key+":settings",false)
   next.getJSONObject("receipts").put(key,JSONObject().put("digest",digest).put("revision",revision+1));fault()
   validateOpen(next);val tmp=File(file.path+".tmp");tmp.writeText(next.toString());java.nio.file.Files.move(tmp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);state=next
   if(fail==99)"Unknown"else"Committed"
  }catch(e:InvalidTrack){reject("Malformed(${e.field})")}catch(e:java.io.IOException){reject("StorageFailure")}catch(e:Exception){reject("Malformed(json)")}
 }
}
fun trackLegacyMain(args:Array<String>){
 val plans=JSONArray(File(args[0]).readText());val root=File(args[1]);root.mkdirs();var n=0
 fun verify(v:Boolean){check(v);n++}
 for(index in 0 until plans.length()-1){val p=plans.getJSONObject(index);val anchor=!p.isNull("anchorAfter");val steps=if(anchor)7 else 4
  for(step in 1..steps){val f=File(root,"$index-$step.json");f.delete();val s=TrackStore(f);val before=s.bytes();val result=s.commit(p,fail=step);check(result=="StorageFailure"){"index=$index step=$step result=$result"};n++;verify(s.bytes()==before&&!f.exists());verify(s.commit(p)=="Committed");verify(s.commit(p)=="CommittedReplay")}
 }
 val p=plans.getJSONObject(0);val f=File(root,"delete.json");f.delete();val s=TrackStore(f);verify(s.commit(p)=="Committed");val del=plans.getJSONObject(plans.length()-1);verify(s.commit(del,base=1)=="Committed");verify(s.commit(del,base=1)=="CommittedReplay");val state=JSONObject(s.bytes());verify(!state.getJSONObject("records").has(del.getString("date")));verify(state.getJSONObject("revisions").getJSONObject(del.getString("date")).getBoolean("tombstone"));verify(state.getJSONObject("revisions").getJSONObject(del.getString("date")).getLong("revision")==2L)
 val before=s.bytes();val bad=JSONObject(p.toString()).put("op","bad").put("payloadHash","0".repeat(64));verify(s.commit(bad,base=2).startsWith("Malformed"));verify(s.bytes()==before)
 for(k in listOf("kind","date","payload","intentDigest","bbtBits","sleepBits")){val x=JSONObject(p.toString());x.put(k,JSONObject());verify(s.commit(x).startsWith("Malformed"));verify(s.bytes()==before)}
 val anchorPlan=(0 until plans.length()-1).map{plans.getJSONObject(it)}.first{!it.isNull("anchorAfter")}
 val af=File(root,"anchor-conflict.json");af.delete();val a=TrackStore(af);val frozen=a.bytes();verify(a.commit(anchorPlan,settingsBase=1)=="SettingsConflict");verify(a.bytes()==frozen&&!af.exists())
 val changed=JSONObject(anchorPlan.toString());changed.put("anchorAfter","2020-01-01");verify(a.commit(changed).startsWith("Malformed"));verify(a.bytes()==frozen)
 for(bits in listOf<Any>(0x100000000L,-1,1.1,"1",true,JSONObject())){val x=JSONObject(p.toString());x.put("bbtBits",bits);verify(s.commit(x).startsWith("Malformed"));verify(s.bytes()==before)}
 val stale=JSONObject(p.toString());stale.getJSONObject("identity").put("epoch",3);verify(s.commit(stale)=="RejectedStale");verify(s.bytes()==before)
 val dfile=File(root,"delete-fault.json");dfile.delete();val ds=TrackStore(dfile);verify(ds.commit(p)=="Committed");val df=ds.bytes();for(step in 1..4){verify(ds.commit(del,base=1,fail=step)=="StorageFailure");verify(ds.bytes()==df)}
 verify(ds.commit(del,base=1,fail=99)=="Unknown");verify(ds.commit(del,base=1)=="CommittedReplay")
 val mismatch=JSONObject(p.toString()).put("anchorBefore","mismatch");verify(s.commit(mismatch).startsWith("Malformed"));verify(s.bytes()==before)
 // Finite double rounds to source Float: must reject noncanonical byte payload.
 val rounded=JSONObject(p.toString());val altered=JSONObject(rounded.getString("payload")).put("bbt_celsius",36.60000000001);val alteredText=altered.toString();rounded.put("payload",alteredText).put("payloadHash",HealthSyncPayloadCodec.hash(alteredText));verify(s.commit(rounded)=="Malformed(nonCanonicalPayload)");verify(s.bytes()==before)
 println("PASS $n full Track synthetic atomic anchor/delete/fault/receipt/rejection assertions; not encrypted durability or OSrestart")
}

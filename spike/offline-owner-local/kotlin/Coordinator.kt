import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

data class Identity(val namespace:String,val effective:String,val instance:String,val epoch:Long,val session:Long,val generation:Long,val local:Boolean)
fun resolve(owner:String?,effective:String,instance:String,epoch:Long,session:Long,generation:Long,local:Boolean):Identity {
 require(effective.isNotBlank() && instance.isNotBlank())
 require(owner==null || owner.isNotBlank())
 require(owner==null || owner==effective)
 return Identity(owner ?: "legacy_guest",effective,instance,epoch,session,generation,local)
}
data class Plan(val identity:Identity,val op:String,val base:Long,val date:String,val notes:String,val bbtBits:Long,val sleepBits:Long) {
 fun digest():String {
  val canonical=org.json.JSONArray().put(identity.namespace).put(identity.effective).put(identity.instance).put(identity.epoch).put(identity.session).put(identity.generation).put(identity.local).put(op).put(base).put(date).put(notes).put(bbtBits).put(sleepBits).toString()
  return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
 }
}
// All methods are synchronous, no coroutine suspension/callbacks under this monitor.
// This is a local skeleton, not the production coroutine coordinator.
class Coordinator(private var identity:Identity,private val file:File) {
 private val lock=Any()
 private var readFailure=false
 private var state=try{if(file.exists()) {if(file.length()>4000000)bad("store.size");JSONObject(file.readText())} else JSONObject().put("rows",JSONObject()).put("receipts",JSONObject()).put("journal",JSONObject())}catch(e:Exception){readFailure=true;JSONObject()}
 var rejectedOperations=0;private set
 private fun reject(result:String):String {rejectedOperations++;return result}
 private val storeStatus=if(readFailure)"Malformed(store)" else validateStore(state)
 fun capture()=synchronized(lock){identity}
 fun transition(expectedEpoch:Long,next:Identity):Boolean=synchronized(lock){
  if(checked{validIdentity(next);integer(expectedEpoch,"expectedEpoch")}!="Valid" || identity.epoch!=expectedEpoch || next.epoch<=identity.epoch){rejectedOperations++;false}else{identity=next;true}
 }
 fun commit(p:Plan,failAt:Int=0):String=synchronized(lock){
  val domain=checked{validatePlan(p)};if(domain!="Valid")return@synchronized reject(domain)
  if(storeStatus!="Valid")return@synchronized reject(storeStatus)
  if(p.identity!=identity)return@synchronized reject("RejectedStale")
  val b=Float.fromBits(p.bbtBits.toInt());val s=Float.fromBits(p.sleepBits.toInt())
  if(!b.isFinite()||!s.isFinite())return@synchronized reject("NonFinite")
  if(p.notes.isBlank() && !(b>0f||s>0f))return@synchronized reject("Empty")
  java.time.LocalDate.parse(p.date)
  val receiptKey=org.json.JSONArray().put(identity.namespace).put(identity.effective).put(p.op).toString()
  val receipt=state.getJSONObject("receipts").optJSONObject(receiptKey)
  if(receipt!=null)return@synchronized if(receipt.getString("digest")==p.digest()) "CommittedReplay" else reject("InvalidReuse")
  val rowKey=org.json.JSONArray().put(identity.namespace).put(p.date).toString()
  val old=state.getJSONObject("rows").optJSONObject(rowKey);val revision=old?.getLong("revision")?:0L
  if(revision!=p.base)return@synchronized reject("Conflict")
  if(revision==Long.MAX_VALUE)return@synchronized reject("Malformed(revisionOverflow)")
  val next=JSONObject(state.toString());val n=revision+1
  next.getJSONObject("rows").put(rowKey,JSONObject().put("notes",p.notes).put("bbtBits",p.bbtBits).put("sleepBits",p.sleepBits).put("revision",n))
  if(failAt==1)return@synchronized reject("StorageFailure")
  next.getJSONObject("journal").put(receiptKey,JSONObject().put("op",p.op).put("revision",n))
  if(failAt==2)return@synchronized reject("StorageFailure")
  next.getJSONObject("receipts").put(receiptKey,JSONObject().put("digest",p.digest()).put("revision",n))
  if(failAt==3)return@synchronized reject("StorageFailure")
  // Synthetic replace for JVM tests. NOT SQLite transaction/fsync durability proof.
  try{val tmp=File(file.path+".tmp");tmp.writeText(next.toString());java.nio.file.Files.move(tmp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)}catch(e:Exception){return@synchronized reject("StorageFailure")}
  state=next
  if(failAt==4)"Unknown" else "Committed"
 }
 fun snapshot(token:Identity):String?=synchronized(lock){if(token==identity && storeStatus=="Valid")state.toString() else {rejectedOperations++;null}}
}
fun identityJson(i:Identity)=JSONObject().put("namespace",i.namespace).put("effective",i.effective).put("instance",i.instance).put("epoch",i.epoch).put("session",i.session).put("generation",i.generation).put("local",i.local)
fun main(args:Array<String>){
 val decoded=decodePlan(File(args[0]).readText());check(decoded.result=="Valid");val p=decoded.plan!!;val i=p.identity
 val root=File(args[1]);root.mkdirs();var checks=0
 fun verify(x:Boolean){check(x);checks++}
 val guest=resolve(null,"derived-guest","g1",1,0,0,true);verify(guest.namespace=="legacy_guest"&&guest.effective=="derived-guest")
 for(f in 1..3){val file=File(root,"rollback-$f.json");file.delete();val c=Coordinator(i,file);verify(c.commit(p,f)=="StorageFailure");verify(!file.exists());verify(c.commit(p)=="Committed")}
 val file=File(root,"receipt.json");file.delete();val c=Coordinator(i,file);verify(c.commit(p,4)=="Unknown");val reopen=Coordinator(i,file);verify(reopen.commit(p)=="CommittedReplay");verify(reopen.commit(p.copy(notes="changed"))=="InvalidReuse");verify(reopen.commit(p.copy(op="new",base=0))=="Conflict")
 val before=file.readText();verify(reopen.commit(p.copy(op="nan",bbtBits=0x7fc00001))=="NonFinite");verify(file.readText()==before)
 verify(reopen.commit(p.copy(op="negative",notes="",bbtBits=0xbf800000L))=="Empty")
 val b=i.copy(namespace="synthetic-B",effective="B",instance="b1",epoch=2);verify(reopen.transition(1,b));verify(!reopen.transition(1,i));verify(reopen.commit(p)=="RejectedStale");verify(reopen.snapshot(i)==null);verify(reopen.transition(2,i.copy(epoch=3)));verify(reopen.commit(p)=="RejectedStale");verify(file.readText()==before)
 // Deterministic thread ordering through actual synchronous coordinator lock.
 val cfile=File(root,"threads.json");cfile.delete();val threaded=Coordinator(i,cfile)
 val go=java.util.concurrent.CountDownLatch(1);val done=java.util.concurrent.CountDownLatch(1)
 val worker=Thread{go.await();check(threaded.commit(p)=="RejectedStale");done.countDown()};worker.start()
 verify(threaded.transition(1,b));go.countDown();done.await();worker.join();verify(!cfile.exists())
 val firstFile=File(root,"commit-first.json");firstFile.delete();val first=Coordinator(i,firstFile)
 verify(first.commit(p)=="Committed");verify(first.transition(1,b));verify(first.snapshot(i)==null);verify(firstFile.exists())
 val envelopes=org.json.JSONArray(File(args[2]).readText())
 val corpus=JSONObject(File(args[3]).readText()).getJSONArray("cases")
 for(n in 0 until envelopes.length()){
  val e=envelopes.getJSONObject(n);val r=pinRequest(i,corpus.getJSONObject(n));verify(validateProjection(r,e)=="Valid")
  verify(validateProjection(r.copy(identity=i.copy(epoch=3)),e).startsWith("Malformed"))
  verify(validateProjection(r.copy(id="different"),e).startsWith("Malformed"))
  val partial=JSONObject(e.toString());partial.getJSONObject("predictions").remove(e.getString("selectedDate"));verify(validateProjection(r,partial).startsWith("Malformed"))
  val extra=JSONObject(e.toString());extra.getJSONObject("predictions").put("2099-01-01",JSONObject.NULL);verify(validateProjection(r,extra).startsWith("Malformed"))
 }
 checks+=hostileTests(i,p,root,envelopes,corpus)
 println("PASS $checks synthetic JVM assertions: receipt/unknown/rollback/revision/ABA/stale observer/full identity/finite. No encrypted storage or OS process restart claim")
}

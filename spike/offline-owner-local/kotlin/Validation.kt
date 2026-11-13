import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDate
import java.time.YearMonth
import java.security.MessageDigest
private class Invalid(val field:String):RuntimeException()
fun bad(field:String):Nothing=throw Invalid(field)
fun checked(block:()->Unit):String=try{block();"Valid"}catch(e:Invalid){"Malformed(${e.field})"}catch(e:Exception){"Malformed(json)"}
fun obj(v:Any?,f:String):JSONObject=v as? JSONObject?:bad(f)
fun arr(v:Any?,f:String):JSONArray=v as? JSONArray?:bad(f)
fun keys(o:JSONObject,want:Set<String>,f:String){if(o.keys().asSequence().toSet()!=want)bad(f)}
fun text(v:Any?,f:String,max:Int=4096,blank:Boolean=false):String {val x=v as? String?:bad(f);if(x.length>max||(!blank&&x.isBlank()))bad(f);return x}
fun integer(v:Any?,f:String,max:Long=Long.MAX_VALUE):Long {if(v !is Int && v !is Long)bad(f);val n=(v as Number).toLong();if(n<0||n>max)bad(f);return n}
fun bool(v:Any?,f:String):Boolean=v as? Boolean?:bad(f)
fun date(v:Any?,f:String):String {val x=text(v,f,10);if(!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(x))bad(f);try{LocalDate.parse(x)}catch(e:Exception){bad(f)};return x}
fun month(v:Any?,f:String):String {val x=text(v,f,7);if(!Regex("[0-9]{4}-[0-9]{2}").matches(x))bad(f);try{YearMonth.parse(x)}catch(e:Exception){bad(f)};return x}
fun identityFrom(v:Any?,f:String="identity"):Identity {val x=obj(v,f);keys(x,setOf("namespace","effective","instance","epoch","session","generation","local"),f);return Identity(text(x.opt("namespace"),"$f.namespace",128),text(x.opt("effective"),"$f.effective",128),text(x.opt("instance"),"$f.instance",128),integer(x.opt("epoch"),"$f.epoch"),integer(x.opt("session"),"$f.session"),integer(x.opt("generation"),"$f.generation"),bool(x.opt("local"),"$f.local"))}
fun validIdentity(i:Identity){identityFrom(identityJson(i))}
fun planFrom(v:Any?):Plan {val x=obj(v,"plan");keys(x,setOf("namespace","effective","instance","epoch","session","generation","local","op","base","date","notes","bbtBits","sleepBits"),"plan");val id=JSONObject();for(k in listOf("namespace","effective","instance","epoch","session","generation","local"))id.put(k,x.opt(k));return Plan(identityFrom(id),text(x.opt("op"),"op",128),integer(x.opt("base"),"base"),date(x.opt("date"),"date"),text(x.opt("notes"),"notes",4096,true),integer(x.opt("bbtBits"),"bbtBits",0xffffffffL),integer(x.opt("sleepBits"),"sleepBits",0xffffffffL))}
fun validatePlan(p:Plan){validIdentity(p.identity);text(p.op,"op",128);integer(p.base,"base");date(p.date,"date");text(p.notes,"notes",4096,true);integer(p.bbtBits,"bbtBits",0xffffffffL);integer(p.sleepBits,"sleepBits",0xffffffffL)}
fun sha(v:String)=MessageDigest.getInstance("SHA-256").digest(v.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
data class Request(val identity:Identity,val id:String,val month:String,val selected:String,val today:String,val anchor:Boolean,val settingsDigest:String,val cycleLength:Int) {
 fun grid()=(1..YearMonth.parse(month).lengthOfMonth()).map{YearMonth.parse(month).atDay(it).toString()}
 fun dates()=(grid()+selected+today).toSet()
}
fun pinRequest(i:Identity,c:JSONObject):Request {
 val s=obj(c.opt("settings"),"settings");val last=text(s.opt("lastPeriodStartDate"),"settings.lastPeriodStartDate",10,true);if(last.isNotEmpty())date(last,"settings.lastPeriodStartDate")
 val cycle=integer(s.opt("averageCycleLength"),"settings.averageCycleLength",1000).toInt();val period=integer(s.opt("periodDuration"),"settings.periodDuration",1000);val luteal=integer(s.opt("lutealPhaseDays"),"settings.lutealPhaseDays",1000)
 val canonical=JSONArray().put(last).put(cycle).put(period).put(luteal).toString()
 return Request(i,text(c.opt("id"),"id",128),month(c.opt("month"),"month"),date(c.opt("selected"),"selected"),date(c.opt("today"),"today"),bool(c.opt("hasCycleAnchor"),"anchor"),sha(canonical),cycle.coerceIn(21,45))
}
fun validateProjection(r:Request,v:Any?):String=checked{
 val e=obj(v,"envelope");keys(e,setOf("identity","request","month","selectedDate","todayDate","hasCycleAnchor","settingsDigest","gridDates","predictions"),"envelope")
 if(identityFrom(e.opt("identity"))!=r.identity)bad("identityMismatch")
 if(text(e.opt("request"),"request",128)!=r.id)bad("requestMismatch")
 if(month(e.opt("month"),"month")!=r.month)bad("monthMismatch")
 if(date(e.opt("selectedDate"),"selectedDate")!=r.selected||date(e.opt("todayDate"),"todayDate")!=r.today)bad("roleMismatch")
 if(bool(e.opt("hasCycleAnchor"),"hasCycleAnchor")!=r.anchor)bad("anchorMismatch")
 if(text(e.opt("settingsDigest"),"settingsDigest",64)!=r.settingsDigest)bad("settingsMismatch")
 val grid=arr(e.opt("gridDates"),"gridDates");if(grid.length()!=r.grid().size)bad("gridDates")
 for(n in 0 until grid.length())if(date(grid.opt(n),"gridDates.$n")!=r.grid()[n])bad("gridDates.$n")
 val ps=obj(e.opt("predictions"),"predictions");keys(ps,r.dates(),"predictions.coverage")
 for(d in r.dates()){
  val value=ps.opt(d)
  if(!r.anchor){if(value!==JSONObject.NULL)bad("prediction.$d.noAnchor");continue}
  val p=arr(value,"prediction.$d");if(p.length()!=8)bad("prediction.$d.length")
  if(date(p.opt(0),"prediction.$d.date")!=d)bad("prediction.$d.date")
  val day=integer(p.opt(1),"prediction.$d.day",r.cycleLength.toLong());if(day<1)bad("prediction.$d.day")
  if(text(p.opt(2),"prediction.$d.phase",32) !in setOf("MENSTRUAL","FOLLICULAR","OVULATION","LUTEAL"))bad("prediction.$d.phase")
  for(n in 3..5)bool(p.opt(n),"prediction.$d.$n")
  if(text(p.opt(6),"prediction.$d.confidence",64)!="Calendar Estimate")bad("prediction.$d.confidence")
  if(text(p.opt(7),"prediction.$d.chance",64)!="Not calculated from calendar dates")bad("prediction.$d.chance")
 }
}
fun validateStore(v:Any?):String=checked{
 val s=obj(v,"store");keys(s,setOf("rows","receipts","journal"),"store")
 val rows=obj(s.opt("rows"),"store.rows");val receipts=obj(s.opt("receipts"),"store.receipts");val journal=obj(s.opt("journal"),"store.journal")
 if(rows.length()>10000||receipts.length()>10000||journal.length()>10000)bad("store.size")
 for(k in rows.keys()){
  val key=arr(JSONArray(k),"row.key");if(key.length()!=2)bad("row.key");text(key.opt(0),"row.namespace",128);date(key.opt(1),"row.date")
  val row=obj(rows.opt(k),"row");keys(row,setOf("notes","bbtBits","sleepBits","revision"),"row");text(row.opt("notes"),"row.notes",4096,true)
  for(f in listOf("bbtBits","sleepBits")){val bits=integer(row.opt(f),"row.$f",0xffffffffL);if(!Float.fromBits(bits.toInt()).isFinite())bad("row.$f")}
  if(integer(row.opt("revision"),"row.revision")==0L)bad("row.revision")
 }
 if(receipts.keys().asSequence().toSet()!=journal.keys().asSequence().toSet())bad("store.receiptJournalCoverage")
 for(k in receipts.keys()){
  val key=JSONArray(k);if(key.length()!=3)bad("receipt.key");for(n in 0..2)text(key.opt(n),"receipt.key.$n",128)
  val row=obj(receipts.opt(k),"receipt");keys(row,setOf("digest","revision"),"receipt");val digest=text(row.opt("digest"),"receipt.digest",64);if(!Regex("[0-9a-f]{64}").matches(digest))bad("receipt.digest")
  val rev=integer(row.opt("revision"),"receipt.revision");if(rev==0L)bad("receipt.revision")
  val j=obj(journal.opt(k),"journal");keys(j,setOf("op","revision"),"journal");if(text(j.opt("op"),"journal.op",128)!=key.getString(2)||integer(j.opt("revision"),"journal.revision")!=rev)bad("journal.receiptMismatch")
 }
}

data class PlanDecode(val plan:Plan?,val result:String)
fun decodePlan(raw:String):PlanDecode {
 var plan:Plan?=null
 val result=checked{if(raw.length>20000)bad("plan.size");plan=planFrom(JSONObject(raw))}
 return PlanDecode(if(result=="Valid")plan else null,result)
}

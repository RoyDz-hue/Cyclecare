import org.json.JSONObject
import org.json.JSONArray
import java.io.File
fun hostileTests(i:Identity,p:Plan,root:File,envelopes:JSONArray,corpus:JSONArray):Int {
 var n=0
 fun verify(v:Boolean){check(v);n++}
 val source=envelopes.getJSONObject(0);val request=pinRequest(i,corpus.getJSONObject(0))
 fun reject(label:String,change:(JSONObject)->Unit){val e=JSONObject(source.toString());change(e);val result=validateProjection(request,e);check(result.startsWith("Malformed(")){"$label $result"};n++}
 for(value in listOf<Any>(1.9,"1",true,JSONObject(),Long.MAX_VALUE,java.math.BigInteger("9223372036854775808"),-1))reject("epoch type/range $value"){it.getJSONObject("identity").put("epoch",value)}
 reject("bad selected date"){it.put("selectedDate","2024-02-30")}
 reject("bad month"){it.put("month","2024-13")}
 reject("bad digest"){it.put("settingsDigest","0".repeat(64))}
 reject("anchor mismatch"){it.put("hasCycleAnchor",false)}
 reject("partial"){it.getJSONObject("predictions").remove(it.getString("selectedDate"))}
 reject("extra"){it.getJSONObject("predictions").put("2099-01-01",JSONObject.NULL)}
 reject("anchored null"){val ps=it.getJSONObject("predictions");ps.put(ps.keys().next(),JSONObject.NULL)}
 reject("self declared subset"){val d=it.getString("selectedDate");it.put("gridDates",JSONArray().put(d));it.put("todayDate",d);it.put("predictions",JSONObject().put(d,it.getJSONObject("predictions").get(d)))}
 for(index in 0..7){reject("wrong row type $index"){val ps=it.getJSONObject("predictions");val d=ps.keys().next();ps.getJSONArray(d).put(index,JSONObject())}}
 for(value in listOf<Any>(0,46,1.2,"14",true))reject("day range/type $value"){val ps=it.getJSONObject("predictions");ps.getJSONArray(ps.keys().next()).put(1,value)}
 for(index in 6..7)reject("wrong confidence/chance string"){val ps=it.getJSONObject("predictions");ps.getJSONArray(ps.keys().next()).put(index,"wrong") }
 reject("phase enum"){val ps=it.getJSONObject("predictions");ps.getJSONArray(ps.keys().next()).put(2,"UNKNOWN")}
 reject("date mismatch"){val ps=it.getJSONObject("predictions");ps.getJSONArray(ps.keys().next()).put(0,"2024-02-30")}
 for(index in 3..5)reject("boolean string"){val ps=it.getJSONObject("predictions");ps.getJSONArray(ps.keys().next()).put(index,"false")}
 val noAnchor=envelopes.getJSONObject(5);val nr=pinRequest(i,corpus.getJSONObject(5));verify(validateProjection(nr,noAnchor)=="Valid")
 val forged=JSONObject(noAnchor.toString());val d=forged.getJSONObject("predictions").keys().next();forged.getJSONObject("predictions").put(d,source.getJSONObject("predictions").getJSONArray(source.getJSONObject("predictions").keys().next()));verify(validateProjection(nr,forged).startsWith("Malformed("))
 // Structural checker must not recompute math. In-range different day is accepted structurally.
 val mathWrong=JSONObject(source.toString());val ps=mathWrong.getJSONObject("predictions");val row=ps.getJSONArray(ps.keys().next());row.put(1,if(row.getInt(1)==1)2 else 1);verify(validateProjection(request,mathWrong)=="Valid")
 val file=File(root,"hostile.json");file.delete();val c=Coordinator(i,file);verify(c.commit(p)=="Committed");val before=file.readText()
 val plans=listOf(p.copy(op="wide",bbtBits=0x100000000L),p.copy(op="negativebits",bbtBits=-1),p.copy(op="overflow",base=-1),p.copy(op="bad-date",date="2024-02-30"),p.copy(op="nan",bbtBits=0x7fc00001L),p.copy(op="infinity",sleepBits=0x7f800000L),p.copy(op="long-notes",notes="x".repeat(4097)))
 val old=c.rejectedOperations
 for(plan in plans){val result=c.commit(plan);verify(result.startsWith("Malformed(")||result=="NonFinite");verify(file.readText()==before)}
 verify(c.rejectedOperations==old+plans.size)
 val base=JSONObject(File(root.parentFile,"plan.json").readText())
 for(value in listOf<Any>(1.1,"1",false,JSONObject(),java.math.BigInteger("18446744073709551616"))){val x=JSONObject(base.toString());x.put("bbtBits",value);verify(checked{planFrom(x)}.startsWith("Malformed("))}
 val malformed=File(root,"malformed-store.json");malformed.writeText("{broken");val broken=Coordinator(i,malformed);verify(broken.commit(p).startsWith("Malformed("));verify(malformed.readText()=="{broken")
 val badStore=JSONObject(before);val rs=badStore.getJSONObject("rows");rs.getJSONObject(rs.keys().next()).put("bbtBits",0x100000000L);malformed.writeText(badStore.toString());val badBefore=malformed.readText();verify(Coordinator(i,malformed).commit(p).startsWith("Malformed("));verify(malformed.readText()==badBefore)
 verify(decodePlan("{bad").result=="Malformed(json)")
 for(field in listOf("base","epoch","session","generation"))for(value in listOf<Any>(1.3,"1",true,JSONObject(),java.math.BigInteger("9223372036854775808"))){val x=JSONObject(base.toString());x.put(field,value);verify(decodePlan(x.toString()).result.startsWith("Malformed("))}
 for(field in listOf("notes","date","op","local")){val x=JSONObject(base.toString());x.put(field,JSONArray());verify(decodePlan(x.toString()).result.startsWith("Malformed("))}
 for(field in listOf("revision","notes","sleepBits"))for(value in listOf<Any>(JSONObject(),true,JSONArray())){val x=JSONObject(before);val rows=x.getJSONObject("rows");rows.getJSONObject(rows.keys().next()).put(field,value);verify(validateStore(x).startsWith("Malformed("))}
 val overflow=JSONObject(before);val rows=overflow.getJSONObject("rows");rows.getJSONObject(rows.keys().next()).put("revision",Long.MAX_VALUE);malformed.writeText(overflow.toString());val frozen=malformed.readText();val oc=Coordinator(i,malformed);verify(oc.commit(p.copy(op="overflowRevision",base=Long.MAX_VALUE))=="Malformed(revisionOverflow)");verify(malformed.readText()==frozen)
 return n
}

import com.cyclecare.app.domain.ai.*
import com.cyclecare.app.data.local.*
import org.json.*
fun main(){
 val cases=JSONArray()
 for(input in listOf("I have cramps and feel tired", "please log heavy period", "why do I have cramps?", "hello", "no cramps today and no headache", "I feel sad", "I have severe headache", "record my flow is light", "I have cramps?", "I want to log nausea", "log "+"a".repeat(1190)+" cramps", "nimechoka")){
  val gate=CareLogConsentGate();val p=gate.propose("owner-A","2024-03-01",input)
  val row=JSONObject().put("input",input).put("intent",CareConversationPolicy.isLoggingIntent(input)).put("present",p!=null)
  if(p!=null){val parsed=p.interpretation;row.put("flow",parsed.flowLevelResult?:JSONObject.NULL).put("energy",parsed.energyResult?:JSONObject.NULL).put("mood",parsed.moodResult?:JSONObject.NULL).put("symptoms",JSONArray(parsed.symptomsResult)).put("pain",parsed.painIntensityResult)
   check(gate.confirm("wrong",p.ownerId)==null);check(gate.confirm(p.id,"owner-B")==null);check(gate.confirm(p.id,p.ownerId)!=null);check(gate.confirm(p.id,p.ownerId)==null)
   val old=CycleDayLog("2024-03-01",flowLevel="MODERATE",symptomsCsv=" cramps,headache,cramps ",notes="existing-private-notes",bbtCelsius=36.6f,sleepHours=7.3f,waterGlasses=4,painIntensity=1)
   row.put("mergedPayload",HealthSyncPayloadCodec.logPayload(CareConversationPolicy.merge(old,parsed)))
  }
  gate.cancel();cases.put(row)
 }
 // Actual IDs are fresh random UUIDs; test relational semantics, do not snapshot random values.
 val g=CareLogConsentGate();val a=g.propose("A","2024-03-01","I have cramps")!!;val b=g.propose("A","2024-03-02","I feel tired")!!;check(a.id!=b.id);check(g.confirm(a.id,"A")==null);check(g.confirm(b.id,"A")?.date=="2024-03-02");g.propose("A","2024-03-01","I have cramps");g.cancel();check(g.confirm(b.id,"A")==null)
 val p=g.propose("A","2024-03-01","I have cramps")!!;check(g.propose("A","2024-03-01","hello")==null);check(g.confirm(p.id,"A")==null)
 for(wrong in listOf("","wrong","\u0000","é")){val gate=CareLogConsentGate();val p=gate.propose("A","2024-03-01","I have cramps")!!;check(gate.confirm(wrong,"A")==null);check(gate.confirm(p.id,wrong)==null);check(gate.confirm(p.id,"A")!=null)}
 val astral="a".repeat(44)+"😀"+"tail";val detail=com.cyclecare.app.domain.parser.NaturalLanguageLogParser.parse(astral).items.first().detail
 val units=JSONArray();detail.forEach{units.put(it.code)}
 println(JSONObject().put("astralInput",astral).put("astralDetailUTF16",units).put("cases",cases).put("gateSchedules","PASS actual proposalID owner wrongID cancel edit replace null clears confirm one-shot").toString(2))
}

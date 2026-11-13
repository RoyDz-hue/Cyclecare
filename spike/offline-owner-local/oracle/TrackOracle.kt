import com.cyclecare.app.data.local.*
import org.json.*
import java.io.File
fun main(args:Array<String>){
 val cases=JSONArray()
 val flows=listOf("","SPOTTING","LIGHT","MODERATE","HEAVY","UNUSUALLY_HEAVY","UNKNOWN")
 for(flow in flows)for(pair in listOf("2024-03-16" to "2024-03-01","2024-03-17" to "2024-03-01","2024-02-29" to "","2024-03-01" to "bad")){
  val log=CycleDayLog(dateString=pair.first,flowLevel=flow,energyLevel="NORMAL",moodLevel="CALM",symptomsCsv="cramps,headache",vaginalHealthCsv="itching",urinaryHealthCsv="urgency",cervicalMucus="EGG_WHITE",ovulationTest="POSITIVE",bbtCelsius=36.6f,sleepHours=7.3f,sleepQuality="GOOD",waterGlasses=6,notes="α / quote\" \n",painIntensity=2)
  val settings=CycleSettings(lastPeriodStartDate=pair.second,selectedGoalsCsv="cycle",isPregnancyMode=false)
  val payload=HealthSyncPayloadCodec.logPayload(log);val next=anchor(log,settings)
  cases.put(JSONObject().put("flow",flow).put("date",pair.first).put("anchorBefore",pair.second).put("anchorAfter",next?:JSONObject.NULL).put("payload",payload).put("hash",HealthSyncPayloadCodec.hash(payload)).put("settingsPayloadAfter",HealthSyncPayloadCodec.settingsPayload(if(next==null)settings else settings.copy(lastPeriodStartDate=next))).put("recorded",log.hasRecordedObservations()))
 }
 for(bits in listOf(0,Int.MIN_VALUE,0x4212999a,1,0x7f7fffff)){
  val log=CycleDayLog("2024-03-01",notes="bits",bbtCelsius=Float.fromBits(bits),sleepHours=Float.fromBits(bits));val p=HealthSyncPayloadCodec.logPayload(log)
  cases.put(JSONObject().put("flow","").put("date",log.dateString).put("anchorBefore","").put("anchorAfter",JSONObject.NULL).put("payload",p).put("hash",HealthSyncPayloadCodec.hash(p)).put("recorded",log.hasRecordedObservations()))
 }
 println(JSONObject().put("cases",cases).put("deleteHash",HealthSyncPayloadCodec.hash(null)).toString(2))
}

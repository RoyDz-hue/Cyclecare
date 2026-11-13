import com.cyclecare.app.data.local.*
import org.json.*
// Current transaction-time profile read supplied as argument; effects converted to returned values.
fun changedProfile(newSettings:CycleSettings,previousAverageCycleLength:Int,read:OnboardingProfile?):OnboardingProfile? = read?.copy(
                cycleLengthOption = if (newSettings.averageCycleLength != previousAverageCycleLength) {
                    "${newSettings.averageCycleLength} days"
                } else read.cycleLengthOption,
                cycleLengthDays = newSettings.averageCycleLength,
                periodDurationDays = newSettings.periodDuration
            )
fun main(){
 val cases=JSONArray()
 for(present in listOf(false,true))for(previous in listOf(28,30))for(newLength in listOf(28,30)){
  val settings=CycleSettings(selectedGoalsCsv="new",lastPeriodStartDate="2024-02-01",averageCycleLength=newLength,periodDuration=6,isAppLockEnabled=true,lockTimeoutMinutes=5,remindDailyLog=true)
  val profile=if(present)OnboardingProfile(cycleLengthOption="It changes a lot",cycleLengthDays=25,periodDurationDays=4,selectedGoalsCsv="preserve",lastPeriodDateString="2024-01-01",isAgeVerified=false)else null
  val changed=changedProfile(settings,previous,profile)
  cases.put(JSONObject().put("present",present).put("previousAverage",previous).put("newLength",newLength).put("period",6).put("beforeOption",profile?.cycleLengthOption?:JSONObject.NULL).put("settingsPayload",HealthSyncPayloadCodec.settingsPayload(settings)).put("profilePayload",if(changed==null)JSONObject.NULL else HealthSyncPayloadCodec.profilePayload(changed)).put("retainedLocalAppLock",settings.isAppLockEnabled).put("retainedLocalReminder",settings.remindDailyLog).put("retainedAge",changed?.isAgeVerified?:JSONObject.NULL))
 }
 println(JSONObject().put("cases",cases).toString(2))
}

package com.cyclecare.bridge
import java.io.File
import org.json.JSONObject
import com.cyclecare.app.data.local.*

/** Synthetic lab fixture only. Explicit caller-created file, no provider/path
 * discovery, accounts, preferences, keys or production Room instance. */
internal class CandidateCycleFixtureSource(private val fixture:File):CandidateCycleSession.Source {
 override suspend fun read():CandidateCycleSession.Rows {
  val root=JSONObject(fixture.readText())
  check(root.getString("fixture")=="cycle-session-synthetic-v1")
  val settings=root.getJSONObject("settings")
  val logs=root.getJSONArray("logs")
  return CandidateCycleSession.Rows(
   CycleSettings(isOnboardingCompleted=true,lastPeriodStartDate=settings.getString("anchor"),averageCycleLength=settings.getInt("cycle"),periodDuration=settings.getInt("period")),
   OnboardingProfile(isCompleted=true,approximatePeriodChoice="EXACT",cycleLengthOption="28"),
   (0 until logs.length()).map{val row=logs.getJSONObject(it);CycleDayLog(row.getString("date"),flowLevel=row.getString("flow"),notes=row.getString("notes"))})
 }
 companion object {
  fun writeSynthetic(file:File){
   check(!file.exists()){"fixture_must_be_new"}
   file.writeText("""{"fixture":"cycle-session-synthetic-v1","settings":{"anchor":"2024-04-01","cycle":28,"period":5},"logs":[{"date":"2024-04-01","flow":"HEAVY","notes":"Synthetic saved log"}]}""")
  }
 }
}

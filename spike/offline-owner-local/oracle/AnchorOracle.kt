import com.cyclecare.app.data.local.*
import java.time.LocalDate
fun anchor(log:CycleDayLog,settings:CycleSettings):String? {
            if (log.flowLevel in listOf("LIGHT", "MODERATE", "HEAVY", "UNUSUALLY_HEAVY")) {
                val logDate = LocalDate.parse(log.dateString)
                val currentStart = try { LocalDate.parse(settings.lastPeriodStartDate) } catch (e: Exception) { null }
                if (currentStart == null || logDate.isAfter(currentStart.plusDays(15))) {
                    return log.dateString
                }
            }
return null
}

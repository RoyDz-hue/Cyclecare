package com.cyclecare.app.ui.viewmodel
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import com.cyclecare.app.data.repository.CycleRepository
/** Actual date-entry UI reducer; clock/date primitives retain original lazy evaluation. */
internal object OnboardingDateReducer {
    enum class Action { EXACT_START, APPROXIMATE, EXACT_END, DURATION }
    data class Event(val action:Action,val date:LocalDate?=null,val text:String="",val days:Int=0,val ongoing:Boolean=false)
    data class Evidence(val start:LocalDate?,val end:LocalDate?,val today:LocalDate?)
    internal fun effectiveStart(s:OnboardingUiState,now:()->LocalDate):LocalDate {
        if(s.selectedExactDate!=null)return s.selectedExactDate
        val daysAgo=when(s.selectedApproximateChip){"Today"->0;"A few days ago"->4;"About a week ago"->7;"About two weeks ago"->14;"It varies / I don’t know", "I don’t know"->0;else->11}
        return now().minusDays(daysAgo.toLong())
    }
    internal fun evidence(s:OnboardingUiState,e:Event,now:()->LocalDate):Evidence = when(e.action){
        Action.APPROXIMATE -> Evidence(null,null,null)
        Action.EXACT_START -> Evidence(null,null,if(s.selectedPeriodEndDate!=null)now()else null)
        Action.EXACT_END -> {val start=effectiveStart(s,now);Evidence(start,null,if(requireNotNull(e.date)>=start)now()else null)}
        Action.DURATION -> if(e.ongoing){now();Evidence(null,null,null)}else Evidence(null,effectiveStart(s,now).plusDays((e.days-1).toLong().coerceAtLeast(0)),null)
    }
    fun reduce(current:OnboardingUiState,event:Event,now:()->LocalDate={LocalDate.now()}):OnboardingUiState {
        // Materialize only the platform primitives the original branch evaluates.
        // A thrown LocalDate overflow still escapes at the original decision point.
        val clocks=mutableListOf<LocalDate>()
        val evidence=evidence(current,event){now().also{clocks+=it}}
        val native=com.cyclecare.app.rust.CycleCareCoreBridge.fullOnboardingDate(current,event,evidence)
        if(native!=null)return native
        var n=0
        return reduceKotlin(current,event){clocks[n++]}
    }
    internal fun reduceKotlin(current:OnboardingUiState,event:Event,now:()->LocalDate={LocalDate.now()}):OnboardingUiState {
        return when(event.action) {
        Action.EXACT_START -> { val date=requireNotNull(event.date)

            // If an exact end date was previously chosen and is now before the new start date,
            // clamp to new start date so the invariant end >= start is strictly maintained.
            val validEndDate = current.selectedPeriodEndDate?.let { end ->
                if (CycleRepository.validatePeriodDates(date, end)) end else date
            }
            val decision = if (validEndDate != null) {
                com.cyclecare.app.domain.health.PeriodEpisodeEntry.decideKotlin(
                    start = date,
                    end = validEndDate,
                    isOngoing = false,
                    currentTypicalDurationDays = current.periodDurationDays, today = now()
                )
            } else {
                null
            }
            current.copy(
                selectedExactDate = date,
                selectedPeriodEndDate = validEndDate,
                periodDurationDays = decision?.typicalDurationDays ?: current.periodDurationDays,
                periodDateDialogMessage = decision?.takeIf { it.requiresConfirmation }?.warningMessage,
                periodEndDateConfirmed = decision?.let { !it.requiresConfirmation } ?: current.periodEndDateConfirmed,
                selectedApproximateChip = null,
                showDatePickerDialog = false
            )

        }
        Action.APPROXIMATE -> { val chip=event.text

            current.copy(
                selectedApproximateChip = chip,
                selectedExactDate = null
            )

        }
        Action.EXACT_END -> { val date=requireNotNull(event.date)

            val start = effectiveStart(current,now)
            // Hard chronology rule: end before start is rejected (clamped to start).
            if (!CycleRepository.validatePeriodDates(start, date)) {
                return current.copy(
                    selectedPeriodEndDate = start,
                    isPeriodOngoing = false,
                    showEndDatePickerDialog = false,
                    periodDateDialogMessage = null,
                    periodEndDateConfirmed = true
                )
            }
            // The entered end date is preserved exactly; an unusual duration
            // asks for explicit confirmation and never drags the typical
            // duration estimate (no silent 2..10 clamping of real entries).
            val decision = com.cyclecare.app.domain.health.PeriodEpisodeEntry.decideKotlin(
                start = start,
                end = date,
                isOngoing = false,
                currentTypicalDurationDays = current.periodDurationDays, today = now()
            )
            current.copy(
                selectedPeriodEndDate = date,
                periodDurationDays = decision.typicalDurationDays,
                selectedPeriodDurationOption = "${ChronoUnit.DAYS.between(start, date) + 1} days",
                isPeriodOngoing = false,
                showEndDatePickerDialog = false,
                periodDateDialogMessage = decision.takeIf { it.requiresConfirmation }?.warningMessage,
                periodEndDateConfirmed = !decision.requiresConfirmation
            )

        }
        Action.DURATION -> { val option=event.text;val days=event.days;val isOngoing=event.ongoing

            val endDate = if (isOngoing) now() else effectiveStart(current,now).plusDays((days - 1).toLong().coerceAtLeast(0))
            current.copy(
                selectedPeriodDurationOption = option,
                periodDurationDays = days,
                isPeriodOngoing = isOngoing,
                selectedPeriodEndDate = if (!isOngoing) endDate else null,
                // A preset-derived end date is not a raw user-entered record.
                periodEndDateConfirmed = false,
                periodDateDialogMessage = null
            )

        }
        }
    }
}

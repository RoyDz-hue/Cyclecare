package com.cyclecare.app.ui.viewmodel

/** Current live screen-state transitions. Date/clock/network effects are not in this reducer. */
internal object OnboardingStateReducer {
    enum class Action { NEXT, PREVIOUS, GOAL, ANSWER, NOT_SURE, DATE_PICKER, END_PICKER, CYCLE, REMIND, AGE, TERMS, CLOUD, TERMS_DIALOG, PRIVACY_DIALOG, SYMPTOM, ENERGY, SLEEP, DEEP, CONFIRM_END, EDIT_END }
    data class Event(val action:Action,val text:String="",val other:String="",val flag:Boolean=false,val days:Int=0)
    fun reduce(current:OnboardingUiState,event:Event):OnboardingUiState =
        com.cyclecare.app.rust.CycleCareCoreBridge.fullOnboardingState(current,event) ?: reduceKotlin(current,event)

    /** Complete original non-date expressions, independent of the native decoder. */
    internal fun reduceKotlin(current:OnboardingUiState,event:Event):OnboardingUiState = when(event.action) {
        Action.NEXT -> if(current.step<current.totalSteps)current.copy(step=current.step+1)else current
        Action.PREVIOUS -> if(current.step>1)current.copy(step=current.step-1)else current
        Action.GOAL -> {val updated=if(current.selectedGoals.contains(event.text))current.selectedGoals-event.text else current.selectedGoals+event.text;current.copy(selectedGoals=updated)}
        Action.ANSWER -> {val existing=current.dynamicAnswers[event.text] ?: emptySet();val next=when{!event.flag->setOf(event.other);existing.contains(event.other)->existing-event.other;else->existing+event.other};current.copy(dynamicAnswers=current.dynamicAnswers+(event.text to next))}
        Action.NOT_SURE -> current.copy(isNotSureSelected=event.flag)
        Action.DATE_PICKER -> current.copy(showDatePickerDialog=event.flag)
        Action.END_PICKER -> current.copy(showEndDatePickerDialog=event.flag)
        Action.CYCLE -> current.copy(selectedCycleOption=event.text,cycleLengthDays=event.days)
        Action.REMIND -> current.copy(remindFertileWindow=event.flag)
        Action.AGE -> current.copy(isAgeVerified=event.flag)
        Action.TERMS -> current.copy(isTermsAgreed=event.flag)
        Action.CLOUD -> current.copy(isCloudSyncAllowed=event.flag)
        Action.TERMS_DIALOG -> current.copy(showTermsDialog=event.flag)
        Action.PRIVACY_DIALOG -> current.copy(showPrivacyDialog=event.flag)
        Action.SYMPTOM -> {val updated=if(current.selectedTypicalSymptoms.contains(event.text))current.selectedTypicalSymptoms-event.text else current.selectedTypicalSymptoms+event.text;current.copy(selectedTypicalSymptoms=updated,hasCompletedDeepBaseline=true)}
        Action.ENERGY -> current.copy(selectedTypicalEnergy=event.text,hasCompletedDeepBaseline=true)
        Action.SLEEP -> current.copy(selectedTypicalSleep=event.text,hasCompletedDeepBaseline=true)
        Action.DEEP -> current.copy(hasCompletedDeepBaseline=event.flag)
        Action.CONFIRM_END -> current.copy(periodEndDateConfirmed=true,periodDateDialogMessage=null)
        Action.EDIT_END -> current.copy(periodDateDialogMessage=null,showEndDatePickerDialog=true)
    }
}

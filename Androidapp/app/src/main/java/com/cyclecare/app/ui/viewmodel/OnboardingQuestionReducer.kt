package com.cyclecare.app.ui.viewmodel
import com.cyclecare.app.domain.onboarding.OnboardingQuestion
internal object OnboardingQuestionReducer {
    enum class Action { LOCAL, START, GENERATED }
    fun reduce(current:OnboardingUiState,step:Int,incoming:List<OnboardingQuestion>?,action:Action):OnboardingUiState =
        com.cyclecare.app.rust.CycleCareCoreBridge.fullOnboardingQuestions(current,step,incoming,action) ?: reduceKotlin(current,step,incoming,action)
    internal fun reduceKotlin(current:OnboardingUiState,step:Int,incoming:List<OnboardingQuestion>?,action:Action):OnboardingUiState = when(action) {
        Action.LOCAL -> {val local=requireNotNull(incoming);val it=current
            it.copy(dynamicQuestions = it.dynamicQuestions + (step to local))
        }
        Action.START -> {val it=current
            it.copy(isLoadingDynamicQuestions = true)
        }
        Action.GENERATED -> {val generated=incoming
            current.copy(
                    dynamicQuestions = if (!generated.isNullOrEmpty()) current.dynamicQuestions + (step to generated) else current.dynamicQuestions,
                    isLoadingDynamicQuestions = false
                )
        }
    }
}

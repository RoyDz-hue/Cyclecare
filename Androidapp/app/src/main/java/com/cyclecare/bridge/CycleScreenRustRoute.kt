package com.cyclecare.bridge

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.model.CyclePhase
import com.cyclecare.app.data.model.DayPrediction
import com.cyclecare.app.ui.screens.CycleScreenContent
import com.cyclecare.app.ui.viewmodel.CycleCareViewModel
import com.cyclecare.app.ui.viewmodel.CycleUiState
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One captured UI-state snapshot supplies the request and Kotlin fallback.
 * Owner identity tags are local only: never placed in native payloads. */
internal data class CycleMonthRequest(
    val owner: String,
    val inputs: CyclePredictionInputs,
    val month: YearMonth,
    val producer: Any
)
internal data class CycleMonthResult(
    val request: CycleMonthRequest,
    val predictions: Map<String, DayPrediction>
)

internal fun CycleMonthResult.acceptFor(request: CycleMonthRequest): Map<String, DayPrediction>? =
    predictions.takeIf { this.request == request }

internal fun CycleUiState.predictionInputs() = CyclePredictionInputs(
    settings.lastPeriodStartDate, settings.averageCycleLength, settings.periodDuration,
    settings.lutealPhaseDays, onboardingProfile?.approximatePeriodChoice,
    onboardingProfile?.cycleLengthOption, currentDate, hasCycleAnchor
)

/** Reuse all phase metadata from the real enum, not untrusted native strings.
 * Unknown phase/date rejects the entire native month and runs Kotlin. */
internal fun Map<String, DayPredictionModel>.toAppPredictions(): Map<String, DayPrediction> =
    mapValues { (_, p) ->
        DayPrediction(LocalDate.parse(p.date), p.dayOfCycle, CyclePhase.valueOf(p.phase),
            p.isPeriod, p.isFertile, p.isOvulation, p.fertilityConfidence, p.pregnancyChance)
    }

@Composable
fun CycleScreenRustRoute(
    viewModel: CycleCareViewModel,
    uiState: CycleUiState,
    logsMap: Map<String, CycleDayLog>,
    modifier: Modifier = Modifier
) {
    var month by remember { mutableStateOf(YearMonth.from(uiState.selectedDate)) }
    val request = CycleMonthRequest(uiState.localDataOwnerKey, uiState.predictionInputs(), month, viewModel)
    val result by produceState<CycleMonthResult?>(null, request, viewModel) {
        val snapshot = uiState
        value = withContext(Dispatchers.Default) {
            val native = CycleScreenStateAdapter.fetchMonthPredictions(request.inputs, request.month.toString())
            val predictions = native?.let {
                try { it.toAppPredictions() } catch (_: IllegalArgumentException) { null }
                catch (_: java.time.DateTimeException) { null }
            } ?: viewModel.getPredictionsForSnapshot(snapshot, request.month)
            CycleMonthResult(request, predictions)
        }
    }
    // produceState retains old value during a new request. Never expose it
    // across month/settings/anchor/owner changes. Legacy source UI remains
    // available while the matching async result is pending.
    val accepted = result?.acceptFor(request)
    CycleScreenContent(
        uiState = uiState,
        logsMap = logsMap,
        predictionForDate = { date ->
            if (accepted != null) accepted[date.toString()]
            else viewModel.getPredictionForSnapshot(uiState, date)
        },
        onSelectDate = viewModel::selectDate,
        onOpenTrackForDate = viewModel::openTrackForDate,
        modifier = modifier,
        controlledMonth = month,
        onDisplayedMonthChange = { month = it }
    )
}

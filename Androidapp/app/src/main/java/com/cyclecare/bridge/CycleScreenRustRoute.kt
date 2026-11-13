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
    val producer: Any,
    val epoch: Long = 0L
)
internal data class CycleMonthResult(
    val request: CycleMonthRequest,
    val predictions: Map<String, DayPrediction>,
    val nativeAnswered: Boolean = false,
    val computedOffMain: Boolean = false,
    val elapsedNanos: Long = 0L
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
    CycleScreenRustContent(
        uiState = uiState, logsMap = logsMap, modifier = modifier,
        producer = viewModel,
        fallbackMonth = { snapshot, month -> viewModel.getPredictionsForSnapshot(snapshot, month) },
        fallbackDate = { snapshot, date -> viewModel.getPredictionForSnapshot(snapshot, date) },
        onSelectDate = viewModel::selectDate, onOpenTrackForDate = viewModel::openTrackForDate
    )
}

/** Shared existing-route seam: accepts state/events without constructing a VM.
 * Production keeps its existing fallback. A lab caller can require a native
 * answer and then loading/failure never masquerades as empty predictions.
 * Epoch is local freshness metadata, never sent to the native payload.
 */
@Composable
internal fun CycleScreenRustContent(
    uiState: CycleUiState,
    logsMap: Map<String, CycleDayLog>,
    producer: Any,
    fallbackMonth: (CycleUiState, YearMonth) -> Map<String, DayPrediction>,
    fallbackDate: (CycleUiState, LocalDate) -> DayPrediction?,
    onSelectDate: (LocalDate) -> Unit,
    onOpenTrackForDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    epoch: Long = 0L,
    requireNative: Boolean = false,
    onNativeState: (String) -> Unit = {},
    onAcceptedResult: (CycleMonthResult?) -> Unit = {}
) {
    var month by remember { mutableStateOf(YearMonth.from(uiState.selectedDate)) }
    val request = CycleMonthRequest(uiState.localDataOwnerKey, uiState.predictionInputs(), month, producer, epoch)
    val result by produceState<CycleMonthResult?>(null, request) {
        val snapshot = uiState
        value = withContext(Dispatchers.Default) {
            val started = System.nanoTime()
            val computedOffMain = android.os.Looper.myLooper() != android.os.Looper.getMainLooper()
            val native = CycleScreenStateAdapter.fetchMonthPredictions(request.inputs, request.month.toString())
            val decoded = native?.let {
                try { it.toAppPredictions() } catch (_: IllegalArgumentException) { null }
                catch (_: java.time.DateTimeException) { null }
            }
            CycleMonthResult(request, decoded ?: if (requireNative) emptyMap() else fallbackMonth(snapshot, request.month),
                nativeAnswered = decoded != null, computedOffMain = computedOffMain,
                elapsedNanos = System.nanoTime() - started)
        }
    }
    // produceState retains its previous value while work is pending. Reject
    // owner/settings/month/producer/epoch mismatch before any cell can see it.
    val matching = result?.takeIf { it.request == request }
    val accepted = matching?.predictions
    val nativeState = when {
        matching == null -> "loading"
        matching.nativeAnswered -> "native"
        else -> "unavailable"
    }
    SideEffect {
        onNativeState(nativeState)
        onAcceptedResult(matching)
    }
    if (requireNative && nativeState != "native") {
        androidx.compose.material3.Text(
            if (nativeState == "loading") "Loading calendar estimates" else "Calendar estimates unavailable. Native calculation failed.",
            modifier = modifier
        )
        return
    }
    // Lab selected-card evidence is only valid when selected date belongs to
    // displayed month; cross-month today/selected-card parity remains open.
    val displayState = if (requireNative) uiState.copy(
        selectedDatePrediction = accepted?.get(uiState.selectedDate.toString()),
        todayPrediction = accepted?.get(uiState.currentDate.toString())
    ) else uiState
    CycleScreenContent(
        uiState = displayState,
        logsMap = logsMap,
        predictionForDate = { date ->
            if (accepted != null) accepted[date.toString()]
            else fallbackDate(uiState, date)
        },
        onSelectDate = onSelectDate,
        onOpenTrackForDate = onOpenTrackForDate,
        modifier = modifier,
        controlledMonth = month,
        onDisplayedMonthChange = { month = it }
    )
}

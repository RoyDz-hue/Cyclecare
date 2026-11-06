//! Cycle screen state path (ui/screens/CycleScreen.kt + the
//! CycleCareViewModel.updateCalculations cycle slice, main@31b88f5):
//! the full per-screen state composition a host UI consumes, built ONLY
//! from corpus-locked primitives (home_anchor, cycle_math, cycle_screen).
//! The composition itself is a 1:1 port of the named source lines and is
//! structurally tested; it is NOT separately JVM-oracle-locked (each
//! primitive it calls is). Compose layout, month-grid span/corner math
//! (:245-266) and locale formatting stay Android seams.

use crate::cycle_math::{calculate_day_prediction, DayPrediction};
use crate::cycle_screen::{days_left_chip, selected_day_title};
use crate::home_anchor::{compute_anchor_state, compute_countdowns};
use chrono::{Datelike, NaiveDate};

/// Verbatim disclaimer chain, CycleScreen.kt :426-431.
pub const DISCLAIMER_NO_ANCHOR: &str = "No cycle start date is saved, so calendar predictions are unavailable. Settings below are not measured history.";
pub const DISCLAIMER_APPROXIMATE: &str = "These are approximate calendar estimates from a date range you selected. They can shift, do not confirm ovulation, and should not be used as contraception.";
pub const DISCLAIMER_STANDARD: &str = "Calendar dates are estimates from the date you entered and can shift. They do not confirm ovulation and should not be used as contraception.";

/// :426-431 if / else-if / else chain, order preserved.
pub fn disclaimer_text(has_cycle_anchor: bool, cycle_start_is_approximate: bool) -> &'static str {
    if !has_cycle_anchor {
        DISCLAIMER_NO_ANCHOR
    } else if cycle_start_is_approximate {
        DISCLAIMER_APPROXIMATE
    } else {
        DISCLAIMER_STANDARD
    }
}

pub struct CycleScreenInput {
    pub last_period_start_date: String,
    pub average_cycle_length: i32,
    pub period_duration: i32,
    pub luteal_phase_days: i32,
    /// onboardingProfile.approximatePeriodChoice (raw; classification
    /// trims/lowercases per source).
    pub date_choice: Option<String>,
    /// onboardingProfile.cycleLengthOption (raw).
    pub cycle_length_choice: Option<String>,
    pub today: NaiveDate,
    pub selected_date: NaiveDate,
}

pub struct CycleScreenState {
    pub has_cycle_anchor: bool,
    pub cycle_start_is_approximate: bool,
    pub days_until_next_period: i32,
    pub next_period_date: Option<NaiveDate>,
    pub days_until_ovulation: i32,
    pub is_period_active_today: bool,
    pub today_prediction: Option<DayPrediction>,
    pub selected_prediction: Option<DayPrediction>,
    /// :149 "${selectedPrediction?.dayOfCycle ?: "—"}" (em dash U+2014).
    pub selected_day_of_cycle_text: String,
    /// :90-91 daysUntilPeriodFromSelected: raw averageCycleLength, no
    /// 21..45 clamp (ported quirk, see cycle_screen::days_left_chip).
    pub days_until_period_from_selected: Option<i32>,
    /// :158 title with the fallback chain.
    pub selected_day_title: String,
    /// :360 "Calendar-estimated day N · <phase title>" prefix text parts.
    pub disclaimer: &'static str,
}

/// updateCalculations cycle slice (:483-531) + getPredictionForDate gating
/// (:2153-2156: predictions are null when !hasCycleAnchor) composed.
/// None: anchor Java-valid but outside the native range, or a checked
/// countdown step overflowed it - caller falls back to Kotlin. A
/// Java-invalid anchor is NOT an error: it keeps has_cycle_anchor's raw
/// non-blank semantics with null predictions, matching the Kotlin route.
pub fn build_cycle_screen_state(input: &CycleScreenInput) -> Option<CycleScreenState> {
    let (anchor, parsed_anchor) = compute_anchor_state(
        &input.last_period_start_date,
        input.date_choice.as_deref(),
        input.cycle_length_choice.as_deref(),
        input.average_cycle_length,
        input.today,
    )?;
    // :487-488 + :2153-2156: predictions only exist with an anchor, and
    // only when the anchor actually parses (Kotlin's runCatching null
    // otherwise - e.g. a Java-invalid string like "-0000-01-01").
    let can_predict = anchor.has_cycle_anchor && parsed_anchor.is_some();
    let today_prediction = if can_predict {
        calculate_day_prediction(
            input.today,
            &input.last_period_start_date,
            input.average_cycle_length,
            input.period_duration,
            input.luteal_phase_days,
        )
    } else {
        None
    };
    let selected_prediction = if can_predict {
        calculate_day_prediction(
            input.selected_date,
            &input.last_period_start_date,
            input.average_cycle_length,
            input.period_duration,
            input.luteal_phase_days,
        )
    } else {
        None
    };
    let countdowns = compute_countdowns(
        input.today,
        anchor.next_period_date,
        today_prediction.as_ref().map(|p| p.day_of_cycle),
        input.average_cycle_length,
        input.luteal_phase_days,
        input.period_duration,
    );
    let selected_day_of_cycle_text = selected_prediction
        .as_ref()
        .map(|p| p.day_of_cycle.to_string())
        .unwrap_or_else(|| "\u{2014}".to_string()); // :149 em dash
    let days_until_period_from_selected = selected_prediction
        .as_ref()
        .map(|p| days_left_chip(p.is_period, input.average_cycle_length, p.day_of_cycle));
    let selected_day_title = selected_day_title(
        selected_prediction.as_ref().map(|p| p.phase.kotlin_title()),
        anchor.has_cycle_anchor,
    );
    Some(CycleScreenState {
        has_cycle_anchor: anchor.has_cycle_anchor,
        cycle_start_is_approximate: anchor.cycle_start_is_approximate,
        days_until_next_period: anchor.days_until_next_period,
        next_period_date: anchor.next_period_date,
        days_until_ovulation: countdowns.days_until_ovulation,
        is_period_active_today: today_prediction.as_ref().map(|p| p.is_period) == Some(true),
        today_prediction,
        selected_prediction,
        selected_day_of_cycle_text,
        days_until_period_from_selected,
        selected_day_title,
        disclaimer: disclaimer_text(anchor.has_cycle_anchor, anchor.cycle_start_is_approximate),
    })
}

/// Batch grid contract: CycleCareViewModel.getPredictionForDate (:2153-2156)
/// applied to every date the month grid can query - [first ..= last] of the
/// displayed month. The span-merge neighbor lookups (:245-253) are guarded
/// by column > 0 / column < 6 AND dayNumber > 1 / dayNumber < daysInMonth,
/// so they NEVER leave the displayed month; no padding entries are needed.
/// Semantics mirror the source exactly: predictions exist iff
/// hasCycleAnchor; each per-date value is the corpus-locked
/// calculate_day_prediction primitive, so batch == per-date by
/// construction.
pub struct MonthPredictionsInput {
    pub last_period_start_date: String,
    pub average_cycle_length: i32,
    pub period_duration: i32,
    pub luteal_phase_days: i32,
    pub date_choice: Option<String>,
    pub cycle_length_choice: Option<String>,
    /// Parity anchor date for compute_anchor_state (has_cycle_anchor does
    /// not depend on it, but the shared code path does).
    pub today: NaiveDate,
    /// First day of the displayed month (ffi validates day == 1).
    pub month_first_day: NaiveDate,
}

pub struct MonthPredictions {
    pub has_cycle_anchor: bool,
    /// One entry per date in [month_first_day ..= month_last_day],
    /// ascending; empty when !has_cycle_anchor (source: getPredictionForDate
    /// returns null for every date).
    pub predictions: Vec<(NaiveDate, Option<DayPrediction>)>,
}

/// Last day of the month containing `first` (first must be day 1).
/// None only for December of the native range's last year, where the
/// next-month boundary is unrepresentable; Kotlin's wider YearMonth range
/// still resolves it, so the caller falls back instead of panicking.
fn month_last_day(first: NaiveDate) -> Option<NaiveDate> {
    let (y, m) = (first.year(), first.month());
    let next_first = if m == 12 {
        NaiveDate::from_ymd_opt(y + 1, 1, 1)?
    } else {
        NaiveDate::from_ymd_opt(y, m + 1, 1)?
    };
    next_first.pred_opt()
}

/// None: same unsupported-anchor rule as build_cycle_screen_state. A
/// Java-invalid anchor keeps has_cycle_anchor true with every date's
/// prediction None (Kotlin getPredictionForDate returns null per date).
pub fn build_month_predictions(input: &MonthPredictionsInput) -> Option<MonthPredictions> {
    let (anchor, parsed_anchor) = compute_anchor_state(
        &input.last_period_start_date,
        input.date_choice.as_deref(),
        input.cycle_length_choice.as_deref(),
        input.average_cycle_length,
        input.today,
    )?;
    if !anchor.has_cycle_anchor {
        return Some(MonthPredictions { has_cycle_anchor: false, predictions: Vec::new() });
    }
    let can_predict = parsed_anchor.is_some();
    let first = input.month_first_day;
    let last = month_last_day(first)?;
    let end = last;
    let mut predictions = Vec::new();
    let mut d = first;
    while d <= end {
        // Java-invalid anchor: Kotlin getPredictionForDate is null per date.
        let p = if can_predict {
            calculate_day_prediction(
                d,
                &input.last_period_start_date,
                input.average_cycle_length,
                input.period_duration,
                input.luteal_phase_days,
            )
        } else {
            None
        };
        predictions.push((d, p));
        d = match d.succ_opt() {
            Some(n) => n,
            None => break, // native range edge: `last` was the final date
        };
    }
    Some(MonthPredictions { has_cycle_anchor: true, predictions })
}

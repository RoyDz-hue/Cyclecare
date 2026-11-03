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
use chrono::NaiveDate;

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
pub fn build_cycle_screen_state(input: &CycleScreenInput) -> CycleScreenState {
    let anchor = compute_anchor_state(
        &input.last_period_start_date,
        input.date_choice.as_deref(),
        input.cycle_length_choice.as_deref(),
        input.average_cycle_length,
        input.today,
    );
    // :487-488 + :2153-2156: predictions only exist with an anchor.
    let today_prediction = if anchor.has_cycle_anchor {
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
    let selected_prediction = if anchor.has_cycle_anchor {
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
    CycleScreenState {
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
    }
}

//! Port of OnboardingViewModel.kt's OnboardingUiState fields and step-1..6
//! mutation handlers (main@31b88f5), so onboarding behavior executes in Rust
//! with JVM-corpus parity (fixtures/onboarding_vm_corpus.jsonl).
//!
//! Kotlin sets (selectedGoals, selectedTypicalSymptoms, dynamic answer sets)
//! are LinkedHashSet-backed via +/- on emptySet: insertion order is
//! preserved, so Vec-with-dedup is the faithful port. `today` is always a
//! parameter (Kotlin LocalDate.now() is host-supplied).

use chrono::NaiveDate;

use crate::ob_cycle::{self, ObCycleInput};
use crate::cycle_math as pc;

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct OnboardingState {
    pub step: i64,
    pub total_steps: i64,
    /// Insertion-ordered set (Kotlin Set<String> built by +/-).
    pub selected_goals: Vec<String>,
    pub selected_exact_date: Option<NaiveDate>,
    pub is_not_sure_selected: bool,
    pub selected_approximate_chip: Option<String>,
    pub show_date_picker_dialog: bool,
    pub selected_period_end_date: Option<NaiveDate>,
    pub period_date_dialog_message: Option<String>,
    pub period_end_date_confirmed: bool,
    pub selected_period_duration_option: String,
    pub period_duration_days: i64,
    pub is_period_ongoing: bool,
    pub show_end_date_picker_dialog: bool,
    pub selected_cycle_option: String,
    pub cycle_length_days: i64,
    pub remind_fertile_window: Option<bool>,
    pub is_age_verified: bool,
    pub is_terms_agreed: bool,
    pub show_terms_dialog: bool,
    pub show_privacy_dialog: bool,
    /// Insertion-ordered set.
    pub selected_typical_symptoms: Vec<String>,
    pub selected_typical_energy: String,
    pub selected_typical_sleep: String,
    pub has_completed_deep_baseline: bool,
    pub is_cloud_sync_allowed: bool,
    /// Insertion-ordered map of insertion-ordered sets.
    pub dynamic_answers: Vec<(String, Vec<String>)>,
    pub is_loading_dynamic_questions: bool,
}

impl OnboardingState {
    /// Kotlin data-class defaults.
    pub fn new() -> Self {
        OnboardingState {
            step: 1,
            total_steps: 6,
            period_duration_days: 5,
            cycle_length_days: 28,
            ..Default::default()
        }
    }

    pub fn can_proceed_from_privacy(&self) -> bool {
        self.is_age_verified && self.is_terms_agreed
    }

    pub fn last_period_answered(&self) -> bool {
        self.selected_exact_date.is_some() || self.selected_approximate_chip.is_some()
    }

    /// Verbatim needsCycleFollowup: any goal containing one of the three
    /// substrings, ignoreCase (substrings are ASCII).
    pub fn needs_cycle_followup(&self) -> bool {
        self.selected_goals.iter().any(|goal| {
            ["understand my cycle", "track my periods", "fertility"]
                .iter()
                .any(|sub| contains_ignore_case(goal, sub))
        })
    }

    pub fn needs_period_followup(&self) -> bool {
        self.needs_cycle_followup()
    }

    pub fn needs_symptom_followup(&self) -> bool {
        self.selected_goals.iter().any(|goal| {
            contains_ignore_case(goal, "symptom")
                || contains_ignore_case(goal, "changes in my body")
        })
    }

    pub fn needs_fertility_followup(&self) -> bool {
        self.selected_goals
            .iter()
            .any(|goal| contains_ignore_case(goal, "fertility"))
    }

    fn cycle_input(&self) -> ObCycleInput {
        ObCycleInput {
            selected_exact_date: self.selected_exact_date,
            selected_approximate_chip: self.selected_approximate_chip.clone(),
            selected_period_end_date: self.selected_period_end_date,
            period_duration_days: self.period_duration_days,
            is_period_ongoing: self.is_period_ongoing,
            cycle_length_days: self.cycle_length_days,
        }
    }

    pub fn compute_cycle(&self, today: NaiveDate) -> ob_cycle::ObCycleResult {
        ob_cycle::compute(&self.cycle_input(), today)
    }

    pub fn period_start_choice(&self) -> String {
        self.compute_cycle(NaiveDate::from_ymd_opt(2000, 1, 1).unwrap())
            .period_start_choice
    }

    // ---- handlers (verbatim OnboardingViewModel bodies) ----

    pub fn toggle_goal(&mut self, goal: &str) {
        toggle_ordered(&mut self.selected_goals, goal);
    }

    pub fn set_not_sure_selected(&mut self, not_sure: bool) {
        self.is_not_sure_selected = not_sure;
    }

    pub fn select_approximate_chip(&mut self, chip: &str) {
        self.selected_approximate_chip = Some(chip.to_string());
        self.selected_exact_date = None;
    }

    pub fn set_date_picker_dialog_visible(&mut self, visible: bool) {
        self.show_date_picker_dialog = visible;
    }

    pub fn set_end_date_picker_dialog_visible(&mut self, visible: bool) {
        self.show_end_date_picker_dialog = visible;
    }

    /// Verbatim setExactDate: a previously chosen exact end date that is now
    /// before the new start is clamped to the new start (end >= start
    /// invariant); a surviving end date re-runs PeriodEpisodeEntry.decide.
    pub fn set_exact_date(&mut self, date: NaiveDate, today: NaiveDate) {
        let valid_end_date = self
            .selected_period_end_date
            .map(|end| {
                if pc::validate_period_dates(Some(date), Some(end)) {
                    end
                } else {
                    date
                }
            });
        let decision = valid_end_date.map(|end| {
            pc::decide_episode_storage(Some(date), Some(end), false, self.period_duration_days, today)
        });
        self.selected_exact_date = Some(date);
        self.selected_period_end_date = valid_end_date;
        if let Some(dec) = &decision {
            self.period_duration_days = dec.typical_duration_days;
            self.period_date_dialog_message = if dec.requires_confirmation {
                dec.warning_message.clone()
            } else {
                None
            };
            self.period_end_date_confirmed = !dec.requires_confirmation;
        }
        self.selected_approximate_chip = None;
        self.show_date_picker_dialog = false;
    }

    /// Verbatim setExactEndDate. Hard chronology rule: end before start is
    /// rejected (clamped to start, confirmed). Otherwise the entered end date
    /// is preserved exactly; an unusual duration asks for explicit
    /// confirmation and never drags the typical-duration estimate. The
    /// decision's error channel (e.g. future date) is dropped by the Kotlin
    /// VM - the date is stored regardless; that quirk is ported faithfully.
    pub fn set_exact_end_date(&mut self, date: NaiveDate, today: NaiveDate) {
        let start = self.compute_cycle(today).effective_last_period_date;
        if !pc::validate_period_dates(Some(start), Some(date)) {
            self.selected_period_end_date = Some(start);
            self.is_period_ongoing = false;
            self.show_end_date_picker_dialog = false;
            self.period_date_dialog_message = None;
            self.period_end_date_confirmed = true;
            return;
        }
        let decision =
            pc::decide_episode_storage(Some(start), Some(date), false, self.period_duration_days, today);
        self.selected_period_end_date = Some(date);
        self.period_duration_days = decision.typical_duration_days;
        self.selected_period_duration_option =
            format!("{} days", (date - start).num_days() + 1);
        self.is_period_ongoing = false;
        self.show_end_date_picker_dialog = false;
        self.period_date_dialog_message = if decision.requires_confirmation {
            decision.warning_message.clone()
        } else {
            None
        };
        self.period_end_date_confirmed = !decision.requires_confirmation;
    }

    /// "Dates are correct": the confirmed raw end date is kept.
    pub fn confirm_period_end_date(&mut self) {
        self.period_end_date_confirmed = true;
        self.period_date_dialog_message = None;
    }

    /// "Edit dates": reopen the end date picker.
    pub fn edit_period_end_date(&mut self) {
        self.period_date_dialog_message = None;
        self.show_end_date_picker_dialog = true;
    }

    /// Verbatim confirmedPeriodEndDate(): the raw end date, kept only when
    /// the pair is valid and (typical or explicitly confirmed). Null when
    /// ongoing, absent, not exact-start, or unconfirmed.
    pub fn confirmed_period_end_date(&self) -> Option<NaiveDate> {
        if self.is_period_ongoing || !self.period_end_date_confirmed {
            return None;
        }
        let start = self.selected_exact_date?;
        let end = self.selected_period_end_date?;
        if pc::validate_period_dates(Some(start), Some(end)) {
            Some(end)
        } else {
            None
        }
    }

    /// Verbatim selectPeriodDurationOption: preset-derived end dates are not
    /// raw user-entered records (periodEndDateConfirmed = false).
    pub fn select_period_duration_option(
        &mut self,
        option: &str,
        days: i64,
        is_ongoing: bool,
        today: NaiveDate,
    ) {
        let end_date = if is_ongoing {
            today
        } else {
            self.compute_cycle(today).effective_last_period_date
                + chrono::Duration::days((days - 1).max(0))
        };
        self.selected_period_duration_option = option.to_string();
        self.period_duration_days = days;
        self.is_period_ongoing = is_ongoing;
        self.selected_period_end_date = if !is_ongoing { Some(end_date) } else { None };
        self.period_end_date_confirmed = false;
        self.period_date_dialog_message = None;
    }

    /// The screen's "Still ongoing" chip computes the current bleeding day:
    /// (DAYS.between(effectiveStartDate, now) + 1).coerceIn(1, 10).
    pub fn still_ongoing_current_day(&self, today: NaiveDate) -> i64 {
        let start = self.compute_cycle(today).effective_last_period_date;
        ((today - start).num_days() + 1).clamp(1, 10)
    }

    pub fn select_cycle_option(&mut self, option: &str, days: i64) {
        self.selected_cycle_option = option.to_string();
        self.cycle_length_days = days;
    }

    pub fn set_remind_fertile(&mut self, remind: bool) {
        self.remind_fertile_window = Some(remind);
    }

    pub fn set_age_verified(&mut self, verified: bool) {
        self.is_age_verified = verified;
    }

    pub fn set_terms_agreed(&mut self, agreed: bool) {
        self.is_terms_agreed = agreed;
    }

    pub fn toggle_typical_symptom(&mut self, symptom: &str) {
        toggle_ordered(&mut self.selected_typical_symptoms, symptom);
        self.has_completed_deep_baseline = true;
    }

    pub fn select_typical_energy(&mut self, energy: &str) {
        self.selected_typical_energy = energy.to_string();
        self.has_completed_deep_baseline = true;
    }

    pub fn select_typical_sleep(&mut self, sleep: &str) {
        self.selected_typical_sleep = sleep.to_string();
        self.has_completed_deep_baseline = true;
    }

    /// Verbatim toggleDynamicAnswer.
    pub fn toggle_dynamic_answer(&mut self, question_id: &str, option: &str, multi_select: bool) {
        let pos = self
            .dynamic_answers
            .iter()
            .position(|(id, _)| id == question_id);
        let existing: Vec<String> = pos
            .map(|i| self.dynamic_answers[i].1.clone())
            .unwrap_or_default();
        let next = if !multi_select {
            vec![option.to_string()]
        } else if existing.iter().any(|o| o == option) {
            existing.into_iter().filter(|o| o != option).collect()
        } else {
            let mut e = existing;
            e.push(option.to_string());
            e
        };
        match pos {
            Some(i) => self.dynamic_answers[i].1 = next,
            None => self
                .dynamic_answers
                .push((question_id.to_string(), next)),
        }
    }

    pub fn next_step(&mut self) {
        if self.step < self.total_steps {
            self.step += 1;
        }
    }

    pub fn previous_step(&mut self) {
        if self.step > 1 {
            self.step -= 1;
        }
    }
}

/// Kotlin `contains(other, ignoreCase = true)` for ASCII needles.
fn contains_ignore_case(haystack: &str, needle: &str) -> bool {
    haystack
        .to_lowercase()
        .contains(&needle.to_lowercase())
}

/// LinkedHashSet +/- semantics: insert preserving order, remove if present.
fn toggle_ordered(set: &mut Vec<String>, item: &str) {
    if let Some(i) = set.iter().position(|x| x == item) {
        set.remove(i);
    } else {
        set.push(item.to_string());
    }
}

/// The screen's onComplete assembly (OnboardingScreen.kt ~286): the 17 params
/// handed to CycleCareViewModel.completeOnboarding. pin/enableLock are always
/// ""/false from the onboarding CTA, so they are not represented here.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OnboardingCompletionParams {
    pub goals: Vec<String>,
    pub last_period_date: NaiveDate,
    pub last_period_end_date: Option<NaiveDate>,
    pub period_start_choice: String,
    pub cycle_length_days: i64,
    pub cycle_length_option: String,
    pub period_duration_days: i64,
    pub remind_fertile: bool,
    pub typical_symptoms: Vec<String>,
    pub typical_energy: String,
    pub typical_sleep: String,
    pub has_completed_deep_baseline: bool,
    pub is_cloud_sync_allowed: bool,
    pub dynamic_answers: Vec<(String, Vec<String>)>,
}

impl OnboardingState {
    pub fn completion_params(&self, today: NaiveDate) -> OnboardingCompletionParams {
        let cycle = self.compute_cycle(today);
        OnboardingCompletionParams {
            goals: self.selected_goals.clone(),
            last_period_date: cycle.effective_last_period_date,
            last_period_end_date: self.confirmed_period_end_date(),
            period_start_choice: cycle.period_start_choice,
            cycle_length_days: self.cycle_length_days,
            cycle_length_option: self.selected_cycle_option.clone(),
            period_duration_days: self.period_duration_days,
            remind_fertile: self.remind_fertile_window == Some(true),
            typical_symptoms: self.selected_typical_symptoms.clone(),
            typical_energy: self.selected_typical_energy.clone(),
            typical_sleep: self.selected_typical_sleep.clone(),
            has_completed_deep_baseline: self.has_completed_deep_baseline,
            is_cloud_sync_allowed: self.is_cloud_sync_allowed,
            dynamic_answers: self.dynamic_answers.clone(),
        }
    }
}

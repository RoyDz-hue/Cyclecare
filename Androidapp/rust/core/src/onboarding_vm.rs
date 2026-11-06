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

/// Typed period-date prompt state (integrated M1 review): hard errors and
/// confirmable warnings are different states with different affordances.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PeriodDatePrompt {
    /// Chronology/future violation: blocks progression; only "Edit dates"
    /// (or a new valid entry) resolves it. Confirmation NEVER applies.
    HardError(String),
    /// Real but unusually long duration: "Dates are correct" confirms it.
    UnusualConfirm(String),
    /// Unusually long duration where the standing end is a CALCULATED
    /// estimate (preset-derived), not an entered date. Never confirmable:
    /// the user must enter an actual end date or re-pick a preset - a
    /// generic confirm must not silently adopt a calculation as a raw
    /// entered end (integrated M4 review).
    UnusualDerived(String),
}

/// A typed PENDING rejection for ONE date field: the submitted value was
/// refused, and that unresolved intent is owned by the state INDEPENDENTLY of
/// prompt visibility. Each field owns its own slot - a rejection or
/// correction on one field never overwrites the other's unresolved intent
/// (integrated M3 review finding 3). Cleared only by an accepted correction
/// for the SAME field or the explicit semantic clear for that field.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DateRejection {
    /// The verbatim submitted value that was refused.
    pub raw: String,
    pub message: String,
}

/// Provenance of the standing end date: a RAW ENTERED date the user typed
/// and had accepted, versus a preset-derived estimate. A derived end is
/// never emitted as an entered date, however often setters re-validate the
/// pair (integrated M3 review finding 2: the confirmed Boolean is not
/// provenance). Confirming an unusual pair explicitly ADOPTS it as entered.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PeriodEndSource {
    Entered,
    DerivedPreset,
}

impl PeriodDatePrompt {
    pub fn message(&self) -> &str {
        match self {
            PeriodDatePrompt::HardError(m)
            | PeriodDatePrompt::UnusualConfirm(m)
            | PeriodDatePrompt::UnusualDerived(m) => m,
        }
    }
    pub fn is_hard_error(&self) -> bool {
        matches!(self, PeriodDatePrompt::HardError(_))
    }
}

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
    /// Typed period-date prompt. A HardError BLOCKS progression until the
    /// pair is corrected or explicitly cleared; an UnusualConfirm asks about
    /// a currently-valid unusual pair. Never a bare message (integrated M1
    /// review finding 2).
    pub period_date_prompt: Option<PeriodDatePrompt>,
    /// Unresolved rejected START submission, independent of prompt visibility.
    pub pending_start_rejection: Option<DateRejection>,
    /// Unresolved rejected END submission, independent of prompt visibility.
    pub pending_end_rejection: Option<DateRejection>,
    /// Provenance of the standing end date (raw entered vs preset-derived).
    pub period_end_source: Option<PeriodEndSource>,
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

    /// setExactDate under the approved 2026-10-23 date intent (integrated
    /// M1 review findings 1+3): the entered end date is NEVER silently
    /// rewritten or clamped, and a future start gives feedback even with no
    /// end date yet. A new start that crosses the stored end surfaces the
    /// hard chronology error and keeps both entered values; a future start
    /// surfaces the hard future error. Hard errors store no decision and
    /// confirm nothing.
    pub fn set_exact_date(&mut self, date: NaiveDate, today: NaiveDate) {
        self.selected_exact_date = Some(date);
        self.selected_approximate_chip = None;
        self.show_date_picker_dialog = false;
        if date > today {
            let m = "Period dates cannot be in the future".to_string();
            self.period_date_prompt = Some(PeriodDatePrompt::HardError(m.clone()));
            self.pending_start_rejection = Some(DateRejection { raw: date.to_string(), message: m });
            self.period_end_date_confirmed = false;
            return;
        }
        if let Some(end) = self.selected_period_end_date {
            if end < date {
                let m = "Period end date cannot be earlier than start date".to_string();
                self.period_date_prompt = Some(PeriodDatePrompt::HardError(m.clone()));
                self.pending_start_rejection = Some(DateRejection { raw: date.to_string(), message: m });
                self.period_end_date_confirmed = false;
                return;
            }
        }
        self.pending_start_rejection = None;
        let decision = self.selected_period_end_date.map(|end| {
            pc::decide_episode_storage(Some(date), Some(end), false, self.period_duration_days, today)
        });
        if let Some(dec) = &decision {
            if let Some(err) = dec.error_message {
                self.period_date_prompt = Some(PeriodDatePrompt::HardError(err.to_string()));
                self.period_end_date_confirmed = false;
            } else {
                self.period_duration_days = dec.typical_duration_days;
                self.period_date_prompt = if dec.requires_confirmation {
                    dec.warning_message.clone().map(|m| {
                        if self.period_end_source == Some(PeriodEndSource::Entered) {
                            PeriodDatePrompt::UnusualConfirm(m)
                        } else {
                            PeriodDatePrompt::UnusualDerived(m)
                        }
                    })
                } else {
                    None
                };
                // Re-validating a PRESET-DERIVED end against a new start never
                // makes it raw/confirmed (M3 finding 2); only an entered end
                // is confirmed by a typical decision.
                self.period_end_date_confirmed = !dec.requires_confirmation
                    && self.period_end_source == Some(PeriodEndSource::Entered);
            }
        } else {
            self.period_date_prompt = None;
        }
    }

    /// setExactEndDate under the approved 2026-10-23 date intent (main
    /// 2026-11-05 direction): hard chronology/future errors BLOCK - the
    /// error is surfaced, nothing is stored, the entry is not confirmed, and
    /// the entered end date is never silently rewritten. Typical pairs store
    /// the end and update the estimate; unusual durations preserve the raw
    /// end exactly, ask for explicit confirmation, and never drag the
    /// typical-duration estimate.
    ///
    /// This replaces the Kotlin VM's two silent paths (chronology clamped
    /// end-to-start with confirmed=true; decide's error channel dropped so a
    /// future end date was stored with no message). The Kotlin source stays
    /// as-is; this is the integrated Rust behavior.
    pub fn set_exact_end_date(&mut self, date: NaiveDate, today: NaiveDate) {
        let start = self.compute_cycle(today).effective_last_period_date;
        if !pc::validate_period_dates(Some(start), Some(date)) {
            // Hard chronology error: surface it, store nothing, never clamp.
            self.is_period_ongoing = false;
            self.show_end_date_picker_dialog = false;
            let m = "Period end date cannot be earlier than start date".to_string();
            self.period_date_prompt = Some(PeriodDatePrompt::HardError(m.clone()));
            self.pending_end_rejection = Some(DateRejection { raw: date.to_string(), message: m });
            self.period_end_date_confirmed = false;
            return;
        }
        let decision =
            pc::decide_episode_storage(Some(start), Some(date), false, self.period_duration_days, today);
        if let Some(err) = decision.error_message {
            // Hard future-date error: surface it, store nothing.
            self.is_period_ongoing = false;
            self.show_end_date_picker_dialog = false;
            let m = err.to_string();
            self.period_date_prompt = Some(PeriodDatePrompt::HardError(m.clone()));
            self.pending_end_rejection = Some(DateRejection { raw: date.to_string(), message: m });
            self.period_end_date_confirmed = false;
            return;
        }
        self.pending_end_rejection = None;
        self.selected_period_end_date = Some(date);
        self.period_end_source = Some(PeriodEndSource::Entered);
        self.period_duration_days = decision.typical_duration_days;
        self.selected_period_duration_option =
            format!("{} days", (date - start).num_days() + 1);
        self.is_period_ongoing = false;
        self.show_end_date_picker_dialog = false;
        self.period_date_prompt = if decision.requires_confirmation {
            decision.warning_message.clone().map(PeriodDatePrompt::UnusualConfirm)
        } else {
            None
        };
        self.period_end_date_confirmed = !decision.requires_confirmation;
    }

    /// "Dates are correct": confirms ONLY when the current exact start/end
    /// pair revalidates RIGHT NOW as valid AND unusual AND unconfirmed
    /// (integrated M1 finding 2). The affordance is driven by the pair, not
    /// by prompt visibility: after an Edit-dates dismissal the same current
    /// pair can still be confirmed without a blind resubmit (M3 finding 5).
    /// A preset-derived end is NEVER confirmable: only an actually entered
    /// end can be confirmed (integrated M4 review - no silent derived->raw
    /// adoption via a generic button).
    pub fn confirm_period_end_date(&mut self, today: NaiveDate) {
        // A pending rejected submission, a hard error, or a non-entered end
        // makes "Dates are correct" ambiguous or untruthful: confirmation
        // NEVER applies in those states.
        if self.pending_start_rejection.is_some()
            || self.pending_end_rejection.is_some()
            || matches!(self.period_date_prompt, Some(PeriodDatePrompt::HardError(_)))
            || self.period_end_source != Some(PeriodEndSource::Entered)
        {
            return;
        }
        let valid_unusual = match (self.selected_exact_date, self.selected_period_end_date) {
            (Some(start), Some(end)) => {
                let check = pc::validate_pair(Some(start), Some(end), today, false);
                check.is_valid && check.warning_message.is_some() && !self.period_end_date_confirmed
            }
            _ => false,
        };
        if valid_unusual {
            self.period_end_date_confirmed = true;
            self.period_date_prompt = None;
        }
    }

    /// The warning the renderer must show for the CURRENT pair when no typed
    /// prompt or pending rejection is set: re-issued after dialog
    /// dismissal/clear so the confirm affordance never requires a blind
    /// resubmit (M3 finding 5). Never shown while a pending rejection exists
    /// (M4 review): the actionable rejection is displayed instead.
    pub fn current_unusual_warning(&self, today: NaiveDate) -> Option<String> {
        if self.period_date_prompt.is_some()
            || self.period_end_date_confirmed
            || self.pending_start_rejection.is_some()
            || self.pending_end_rejection.is_some()
        {
            return None;
        }
        let (Some(start), Some(end)) = (self.selected_exact_date, self.selected_period_end_date) else {
            return None;
        };
        let check = pc::validate_pair(Some(start), Some(end), today, false);
        if check.is_valid { check.warning_message } else { None }
    }

    /// Single source of truth for what the date dialog area displays, in
    /// actionable priority (M4 review): typed prompt, then pending start
    /// rejection, then pending end rejection, then the re-issued current
    /// warning (typed UnusualDerived when the standing end is an estimate,
    /// so the renderer never offers a confirm that would no-op or adopt a
    /// calculation).
    pub fn current_display_prompt(&self, today: NaiveDate) -> Option<PeriodDatePrompt> {
        // A current typed hard error stays first: it reflects the field the
        // user is actively editing right now.
        if let Some(PeriodDatePrompt::HardError(_)) = &self.period_date_prompt {
            return self.period_date_prompt.clone();
        }
        // Pending rejections are the most actionable state and must precede
        // ANY confirmable warning (typed or regenerated): showing a confirm
        // affordance while confirm_period_end_date would refuse is an
        // enabled no-op.
        if let Some(r) = &self.pending_start_rejection {
            return Some(PeriodDatePrompt::HardError(r.message.clone()));
        }
        if let Some(r) = &self.pending_end_rejection {
            return Some(PeriodDatePrompt::HardError(r.message.clone()));
        }
        if let Some(p) = &self.period_date_prompt {
            return Some(p.clone());
        }
        self.current_unusual_warning(today).map(|m| {
            if self.period_end_source == Some(PeriodEndSource::Entered) {
                PeriodDatePrompt::UnusualConfirm(m)
            } else {
                PeriodDatePrompt::UnusualDerived(m)
            }
        })
    }

    /// "Edit dates": dismiss the prompt and reopen the end date picker.
    /// Dismissal is NOT clearance: a still-invalid or still-unconfirmed
    /// stored pair keeps blocking progression (period_dates_block_progression)
    /// until a valid entry replaces it.
    pub fn edit_period_end_date(&mut self) {
        self.period_date_prompt = None;
        self.show_end_date_picker_dialog = true;
    }

    /// The raw end date to store: only when the pair validates RIGHT NOW
    /// (full validate_pair, not chronology-only) and is typical or was
    /// explicitly confirmed while it was the current pair. Null when
    /// ongoing, absent, not exact-start, invalid, or unconfirmed.
    pub fn confirmed_period_end_date(&self, today: NaiveDate) -> Option<NaiveDate> {
        // An unconfirmed OR non-entered end is never raw: preset-derived
        // ends carry confirmed=false by construction and provenance stays
        // DerivedPreset until an explicit entry/confirm adopts them
        // (M2 finding 3 + M3 finding 2 - no-invented-end).
        if self.is_period_ongoing
            || !self.period_end_date_confirmed
            || self.period_end_source != Some(PeriodEndSource::Entered)
        {
            return None;
        }
        let start = self.selected_exact_date?;
        let end = self.selected_period_end_date?;
        let check = pc::validate_pair(Some(start), Some(end), today, false);
        if !check.is_valid {
            return None;
        }
        if check.warning_message.is_some() && !self.period_end_date_confirmed {
            return None;
        }
        Some(end)
    }

    /// Progression gate (integrated M1 review finding 3): returns the
    /// blocking reason while a hard error is pending, or while a stored
    /// exact pair is currently invalid or unusual-but-unconfirmed. Next /
    /// completion must refuse while this is Some - the entered dates are
    /// never silently dropped to None to let the flow proceed.
    pub fn period_dates_block_progression(&self, today: NaiveDate) -> Option<String> {
        // Unresolved rejected submissions block FIRST, per field, whether or
        // not any dialog is still visible (M2 finding 1, M3 finding 3).
        if let Some(r) = &self.pending_start_rejection {
            return Some(r.message.clone());
        }
        if let Some(r) = &self.pending_end_rejection {
            return Some(r.message.clone());
        }
        if let Some(PeriodDatePrompt::HardError(m)) = &self.period_date_prompt {
            return Some(m.clone());
        }
        if let Some(start) = self.selected_exact_date {
            if start > today {
                return Some("Period dates cannot be in the future".to_string());
            }
        }
        if self.is_period_ongoing {
            return None;
        }
        let (Some(start), Some(end)) = (self.selected_exact_date, self.selected_period_end_date) else {
            return None;
        };
        let check = pc::validate_pair(Some(start), Some(end), today, false);
        if !check.is_valid {
            return check.error_message.map(|m| m.to_string());
        }
        if check.warning_message.is_some() && !self.period_end_date_confirmed {
            return check.warning_message;
        }
        None
    }

    /// Explicit semantic clear of a rejected START submission: abandons the
    /// refused intent AND resets the selected start together with its
    /// DEPENDENT end date (a startless end would otherwise remain visible in
    /// the field yet be silently omitted at completion - M4 review). Source,
    /// confirmation and prompt are reset with it. Discarding draft TEXT
    /// alone never reaches this operation (M2 finding 1).
    pub fn clear_rejected_start_intent(&mut self) {
        if self.pending_start_rejection.is_some() {
            self.pending_start_rejection = None;
            self.selected_exact_date = None;
            self.selected_period_end_date = None;
            self.period_end_source = None;
            self.period_end_date_confirmed = false;
            self.period_date_prompt = None;
        }
    }

    /// Explicit semantic clear of a rejected END submission: abandons the
    /// refused intent. The refused end was never stored, so the previously
    /// selected end (if any) consciously stands - this makes the fallback to
    /// a prior end EXPLICIT rather than silent. Confirmation is recomputed
    /// for the standing pair RIGHT NOW (valid typical pair reconfirms; an
    /// unusual pair requires a fresh explicit confirm) so value and
    /// confirmation always change together (integrated M2 review finding 1).
    pub fn clear_rejected_end_intent(&mut self, today: NaiveDate) {
        if self.pending_end_rejection.is_some() {
            self.pending_end_rejection = None;
            self.period_date_prompt = None;
            // Recompute confirmation for the standing pair ONLY when it was
            // actually ENTERED: a preset-derived end is never reconfirmed
            // into a raw date by clearing a rejected re-entry (M3 finding 2).
            self.period_end_date_confirmed = match (self.period_end_source, self.selected_exact_date, self.selected_period_end_date) {
                (Some(PeriodEndSource::Entered), Some(start), Some(end)) => {
                    let check = pc::validate_pair(Some(start), Some(end), today, false);
                    check.is_valid && check.warning_message.is_none()
                }
                _ => false,
            };
        }
    }

    /// Display text of the current prompt, if any.
    pub fn period_date_dialog_message(&self) -> Option<&str> {
        self.period_date_prompt.as_ref().map(|p| p.message())
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
        self.period_end_source = Some(PeriodEndSource::DerivedPreset);
        self.period_date_prompt = None;
        // A preset replaces the END estimate only; it never clears a rejected
        // START's unresolved intent (M3 finding 4).
        self.pending_end_rejection = None;
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
            last_period_end_date: self.confirmed_period_end_date(today),
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

#[cfg(test)]
mod date_intent_tests {
    use super::*;
    use chrono::NaiveDate;

    fn d(s: &str) -> NaiveDate {
        NaiveDate::parse_from_str(s, "%Y-%m-%d").unwrap()
    }
    fn base() -> OnboardingState {
        OnboardingState::new()
    }
    const TODAY: &str = "2026-11-05";
    const UNUSUAL_MSG: &str = "Your period lasted 20 days. That's longer than usual. Please check the dates. If they're right, it's worth mentioning to a healthcare professional.";

    // Approved 2026-10-23 intent: hard chronology error surfaces, stores
    // nothing, never clamps, never confirms (was: silent clamp+confirm).
    #[test]
    fn end_before_start_is_surfaced_error_not_silent_clamp() {
        let mut s = base();
        s.set_exact_date(d("2026-11-01"), d(TODAY));
        s.period_end_date_confirmed = true;
        s.set_exact_end_date(d("2026-10-30"), d(TODAY));
        assert!(s.period_date_prompt.as_ref().map(|p| p.is_hard_error()) == Some(true));
        assert_eq!(
            s.period_date_dialog_message(),
            Some("Period end date cannot be earlier than start date")
        );
        assert!(!s.period_end_date_confirmed);
        assert_eq!(s.selected_period_end_date, None); // nothing stored, no clamp
        assert_eq!(s.confirmed_period_end_date(d(TODAY)), None);
        assert_eq!(
            s.period_dates_block_progression(d(TODAY)).as_deref(),
            Some("Period end date cannot be earlier than start date")
        );
    }

    // Future end date: surfaced, stored as nothing, not confirmed (was:
    // stored regardless with no message).
    #[test]
    fn future_end_date_is_surfaced_error_not_stored() {
        let mut s = base();
        s.set_exact_date(d("2026-11-01"), d(TODAY));
        s.set_exact_end_date(d("2026-11-20"), d(TODAY));
        assert!(s.period_date_prompt.as_ref().map(|p| p.is_hard_error()) == Some(true));
        assert_eq!(
            s.period_date_dialog_message(),
            Some("Period dates cannot be in the future")
        );
        assert!(!s.period_end_date_confirmed);
        assert_eq!(s.selected_period_end_date, None);
        assert!(s.period_dates_block_progression(d(TODAY)).is_some());
    }

    // Integrated M1 review finding 1: moving the start past an existing end
    // must NOT silently clamp/rewrite the end to the start. The entered end
    // is preserved verbatim, the hard chronology error surfaces, nothing is
    // confirmed, the estimate is untouched, and progression is blocked.
    #[test]
    fn start_moved_past_end_never_clamps_entered_end() {
        let mut s = base();
        s.period_duration_days = 4;
        s.set_exact_date(d("2026-10-10"), d(TODAY));
        s.set_exact_end_date(d("2026-10-13"), d(TODAY));
        assert_eq!(s.selected_period_end_date, Some(d("2026-10-13")));
        assert!(s.period_end_date_confirmed); // typical pair, confirmed
        // Now move the start past the entered end:
        s.set_exact_date(d("2026-10-20"), d(TODAY));
        assert_eq!(s.selected_exact_date, Some(d("2026-10-20")));
        assert_eq!(s.selected_period_end_date, Some(d("2026-10-13"))); // preserved, NOT clamped
        assert_eq!(s.period_duration_days, 4); // estimate untouched
        assert!(!s.period_end_date_confirmed); // never silently confirmed
        assert_eq!(
            s.period_date_dialog_message(),
            Some("Period end date cannot be earlier than start date")
        );
        assert!(s.period_date_prompt.as_ref().map(|p| p.is_hard_error()) == Some(true));
        assert!(s.period_dates_block_progression(d(TODAY)).is_some());
        // A valid re-entry resolves the error and restores a valid pair:
        s.set_exact_date(d("2026-10-09"), d(TODAY));
        assert_eq!(s.period_date_prompt, None);
        assert_eq!(s.selected_period_end_date, Some(d("2026-10-13")));
        assert!(s.period_dates_block_progression(d(TODAY)).is_none());
    }

    // Future start with NO end date: still gives hard-error feedback
    // (integrated M1 review finding 3: future start without end was silent).
    #[test]
    fn future_start_without_end_has_feedback() {
        let mut s = base();
        s.set_exact_date(d("2999-01-01"), d(TODAY));
        assert_eq!(
            s.period_date_dialog_message(),
            Some("Period dates cannot be in the future")
        );
        assert!(s.period_date_prompt.as_ref().map(|p| p.is_hard_error()) == Some(true));
        assert!(!s.period_end_date_confirmed);
        assert!(s.pending_start_rejection.is_some());
        assert!(s.period_dates_block_progression(d(TODAY)).is_some());
        // Dismissing the dialog (203) must NOT lift the block (M2 finding 1).
        s.edit_period_end_date();
        assert_eq!(s.period_date_prompt, None);
        assert!(s.period_dates_block_progression(d(TODAY)).is_some());
    }

    // Integrated M1 review finding 2: confirm_period_end_date must be a
    // no-op while a HARD ERROR is pending. A "Dates are correct" tap can
    // never dismiss an error or mark a hard-error pair confirmed.
    #[test]
    fn confirm_is_noop_during_hard_error() {
        let mut s = base();
        s.set_exact_date(d("2026-11-01"), d(TODAY));
        s.set_exact_end_date(d("2026-10-30"), d(TODAY)); // hard chronology error
        s.confirm_period_end_date(d(TODAY));
        assert!(!s.period_end_date_confirmed);
        assert!(s.period_date_prompt.as_ref().map(|p| p.is_hard_error()) == Some(true));
        assert_eq!(s.confirmed_period_end_date(d(TODAY)), None);
        assert!(s.period_dates_block_progression(d(TODAY)).is_some());
    }

    // Integrated M1 review finding 2: confirmation applies ONLY to the
    // currently valid unusual pair it was shown for. If the pair changed
    // after the warning was issued (here: replaced by a hard-error entry),
    // the stale confirm does nothing.
    #[test]
    fn confirm_only_applies_to_current_valid_unusual_pair() {
        let mut s = base();
        s.period_duration_days = 4;
        s.set_exact_date(d("2026-10-10"), d(TODAY));
        s.set_exact_end_date(d("2026-10-29"), d(TODAY)); // 20 days: warning
        assert_eq!(
            s.period_date_prompt,
            Some(PeriodDatePrompt::UnusualConfirm(UNUSUAL_MSG.to_string()))
        );
        // Pair changed after the warning: the confirm tap that arrives now
        // refers to the OLD prompt; the new state is a hard error.
        s.set_exact_end_date(d("2026-10-05"), d(TODAY));
        assert!(s.period_date_prompt.as_ref().map(|p| p.is_hard_error()) == Some(true));
        s.confirm_period_end_date(d(TODAY));
        assert!(!s.period_end_date_confirmed);
        assert!(s.period_date_prompt.as_ref().map(|p| p.is_hard_error()) == Some(true));
        assert!(s.period_dates_block_progression(d(TODAY)).is_some());
    }

    // confirmed_period_end_date revalidates against TODAY, not just
    // chronology: a stored pair that becomes future as time passes can
    // never be carried into completion.
    #[test]
    fn confirmed_end_revalidated_against_today() {
        let mut s = base();
        s.set_exact_date(d("2026-11-01"), d(TODAY));
        s.set_exact_end_date(d("2026-11-04"), d(TODAY));
        assert!(s.period_end_date_confirmed);
        assert_eq!(s.confirmed_period_end_date(d(TODAY)), Some(d("2026-11-04")));
        // Same stored pair, evaluated the day before the end: now future.
        assert_eq!(s.confirmed_period_end_date(d("2026-11-03")), None);
        assert!(s.period_dates_block_progression(d("2026-11-03")).is_some());
    }

    // An unusual pair that was dismissed via "Edit dates" without a new
    // valid entry still blocks progression (dismissal is not clearance).
    #[test]
    fn edit_dismissal_does_not_clear_unconfirmed_unusual_pair() {
        let mut s = base();
        s.period_duration_days = 4;
        s.set_exact_date(d("2026-10-10"), d(TODAY));
        s.set_exact_end_date(d("2026-10-29"), d(TODAY));
        s.edit_period_end_date(); // dialog dismissed, no new entry
        assert_eq!(s.period_date_prompt, None);
        assert_eq!(
            s.period_dates_block_progression(d(TODAY)).as_deref(),
            Some(UNUSUAL_MSG)
        );
        assert_eq!(s.confirmed_period_end_date(d(TODAY)), None);
    }

    // Regression guards: approved behavior unchanged for typical + unusual.
    #[test]
    fn typical_and_unusual_paths_unchanged() {
        let mut s = base();
        s.set_exact_date(d("2026-11-01"), d(TODAY));
        s.set_exact_end_date(d("2026-11-04"), d(TODAY));
        assert_eq!(s.selected_period_end_date, Some(d("2026-11-04")));
        assert_eq!(s.period_duration_days, 4);
        assert!(s.period_end_date_confirmed);
        assert_eq!(s.period_date_prompt, None);
        assert!(s.period_dates_block_progression(d(TODAY)).is_none());

        let mut s = base();
        s.period_duration_days = 4;
        s.set_exact_date(d("2026-10-10"), d(TODAY));
        s.set_exact_end_date(d("2026-10-29"), d(TODAY));
        assert_eq!(s.selected_period_end_date, Some(d("2026-10-29"))); // raw preserved
        assert_eq!(s.period_duration_days, 4); // estimate never dragged
        assert!(!s.period_end_date_confirmed); // explicit confirmation required
        assert_eq!(
            s.period_date_prompt,
            Some(PeriodDatePrompt::UnusualConfirm(UNUSUAL_MSG.to_string()))
        );
        s.confirm_period_end_date(d(TODAY));
        assert_eq!(s.confirmed_period_end_date(d(TODAY)), Some(d("2026-10-29")));
        assert_eq!(s.period_date_prompt, None);
        assert!(s.period_dates_block_progression(d(TODAY)).is_none());
    }
    // Integrated M2 review finding 1: a rejected submission is owned as
    // pending state INDEPENDENTLY of prompt visibility. Dialog dismissal does
    // not lift it; only an accepted correction or the explicit semantic
    // clear does. After clearing with no stored end, nothing is invented.
    #[test]
    fn pending_rejection_survives_dismissal_until_explicit_clear() {
        let mut s = base();
        s.set_exact_date(d("2026-11-01"), d(TODAY));
        s.set_exact_end_date(d("2026-11-20"), d(TODAY)); // future end: rejected
        assert!(s.pending_end_rejection.is_some());
        assert_eq!(s.pending_end_rejection.as_ref().map(|r| r.raw.as_str()), Some("2026-11-20"));
        s.edit_period_end_date(); // dismiss dialog only
        assert_eq!(s.period_date_prompt, None);
        assert_eq!(
            s.period_dates_block_progression(d(TODAY)).as_deref(),
            Some("Period dates cannot be in the future")
        );
        // Explicit semantic clear: the rejected intent is consciously
        // abandoned; with no stored end, completion carries None (no invention).
        s.clear_rejected_end_intent(d(TODAY));
        assert_eq!(s.pending_end_rejection, None);
        assert!(s.period_dates_block_progression(d(TODAY)).is_none());
        assert_eq!(s.selected_period_end_date, None);
        assert_eq!(s.confirmed_period_end_date(d(TODAY)), None);
    }

    // Integrated M2 review finding 1: with a previous valid typical end, the
    // reject -> dismiss -> clear trace leaves the OLD end standing EXPLICITLY
    // (clear is the approved operation), confirmed state intact.
    #[test]
    fn explicit_clear_makes_old_end_fallback_conscious() {
        let mut s = base();
        s.set_exact_date(d("2026-11-01"), d(TODAY));
        s.set_exact_end_date(d("2026-11-04"), d(TODAY)); // valid typical, confirmed
        assert!(s.period_end_date_confirmed);
        s.set_exact_end_date(d("2026-11-20"), d(TODAY)); // rejected; old end untouched
        assert_eq!(s.selected_period_end_date, Some(d("2026-11-04")));
        assert!(s.period_dates_block_progression(d(TODAY)).is_some());
        s.edit_period_end_date();
        assert!(s.period_dates_block_progression(d(TODAY)).is_some()); // dismissal is not clearance
        s.clear_rejected_end_intent(d(TODAY)); // explicit abandonment of the new entry
        assert!(s.period_dates_block_progression(d(TODAY)).is_none());
        assert_eq!(s.selected_period_end_date, Some(d("2026-11-04")));
        assert!(s.period_end_date_confirmed);
        assert_eq!(s.confirmed_period_end_date(d(TODAY)), Some(d("2026-11-04")));
    }

    // Integrated M2 review finding 1: changing the OTHER field does not clear
    // an end rejection.
    #[test]
    fn other_field_change_does_not_clear_end_rejection() {
        let mut s = base();
        s.set_exact_date(d("2026-11-01"), d(TODAY));
        s.set_exact_end_date(d("2026-11-20"), d(TODAY)); // rejected end
        s.set_exact_date(d("2026-10-28"), d(TODAY)); // valid other-field change
        assert!(s.pending_end_rejection.is_some());
        assert!(s.period_dates_block_progression(d(TODAY)).is_some());
        // An accepted end correction clears it.
        s.set_exact_end_date(d("2026-10-31"), d(TODAY));
        assert_eq!(s.pending_end_rejection, None);
        assert!(s.period_dates_block_progression(d(TODAY)).is_none());
    }

    // Integrated M2 review finding 3: a preset-derived typical end
    // (confirmed=false by construction) is NEVER emitted as a raw entered
    // end date - the no-invented-end guarantee.
    #[test]
    fn preset_derived_typical_end_is_never_raw() {
        let mut s = base();
        s.set_exact_date(d("2026-11-01"), d(TODAY));
        s.select_period_duration_option("5 days", 5, false, d(TODAY));
        assert!(s.selected_period_end_date.is_some()); // estimate exists
        assert!(!s.period_end_date_confirmed); // preset-derived: not raw
        assert_eq!(s.confirmed_period_end_date(d(TODAY)), None);
        assert_eq!(s.completion_params(d(TODAY)).last_period_end_date, None);
    }

    // M3 finding 2: preset-derived end stays NON-RAW through a start
    // re-entry that re-runs decide over it (typical result must not confirm
    // or emit it).
    #[test]
    fn preset_then_start_reentry_keeps_derived_end_non_raw() {
        let mut s = base();
        s.set_exact_date(d("2026-10-20"), d(TODAY));
        s.select_period_duration_option("5 days", 5, false, d(TODAY));
        assert_eq!(s.period_end_source, Some(PeriodEndSource::DerivedPreset));
        // Re-enter a valid start: decide runs over the derived end (typical).
        s.set_exact_date(d("2026-10-22"), d(TODAY));
        assert_eq!(s.period_end_source, Some(PeriodEndSource::DerivedPreset));
        assert!(!s.period_end_date_confirmed);
        assert_eq!(s.confirmed_period_end_date(d(TODAY)), None);
        assert_eq!(s.completion_params(d(TODAY)).last_period_end_date, None);
    }

    // M3 finding 2: preset -> rejected future end -> explicit clear must NOT
    // reconfirm the standing derived end into a raw date.
    #[test]
    fn preset_reject_clear_never_reconfirms_derived_end() {
        let mut s = base();
        s.set_exact_date(d("2026-10-20"), d(TODAY));
        s.select_period_duration_option("5 days", 5, false, d(TODAY));
        s.set_exact_end_date(d("2026-11-20"), d(TODAY)); // rejected
        assert!(s.pending_end_rejection.is_some());
        s.clear_rejected_end_intent(d(TODAY));
        assert_eq!(s.pending_end_rejection, None);
        assert!(!s.period_end_date_confirmed); // derived end NOT reconfirmed
        assert_eq!(s.period_end_source, Some(PeriodEndSource::DerivedPreset));
        assert_eq!(s.confirmed_period_end_date(d(TODAY)), None);
        assert!(s.period_dates_block_progression(d(TODAY)).is_none());
    }

    // M3 finding 3: both fields can hold unresolved rejections; an accepted
    // START correction clears only the start slot.
    #[test]
    fn per_field_pending_ownership() {
        let mut s = base();
        s.set_exact_date(d("2026-11-01"), d(TODAY));
        s.set_exact_end_date(d("2026-11-20"), d(TODAY)); // rejected end
        s.set_exact_date(d("2999-01-01"), d(TODAY)); // rejected start too
        assert!(s.pending_end_rejection.is_some());
        assert!(s.pending_start_rejection.is_some());
        // Gate reports the start rejection first:
        assert!(s.period_dates_block_progression(d(TODAY)).is_some());
        // Accepted start correction clears ONLY the start slot:
        s.set_exact_date(d("2026-11-01"), d(TODAY));
        assert_eq!(s.pending_start_rejection, None);
        assert!(s.pending_end_rejection.is_some());
        assert_eq!(
            s.period_dates_block_progression(d(TODAY)).as_deref(),
            Some("Period dates cannot be in the future")
        );
        // Accepted end correction unblocks:
        s.set_exact_end_date(d("2026-11-04"), d(TODAY));
        assert!(s.period_dates_block_progression(d(TODAY)).is_none());
    }

    // M3 finding 4: preset selection never clears a rejected START.
    #[test]
    fn preset_never_clears_rejected_start() {
        let mut s = base();
        s.set_exact_date(d("2999-01-01"), d(TODAY)); // rejected start
        assert!(s.pending_start_rejection.is_some());
        s.select_period_duration_option("5 days", 5, false, d(TODAY));
        assert!(s.pending_start_rejection.is_some()); // still owned
        assert!(s.period_dates_block_progression(d(TODAY)).is_some());
    }

    // M3 finding 5: the confirm affordance is driven by the CURRENT pair,
    // not prompt visibility: dismissal does not force a blind resubmit.
    // (Entered pair throughout this test; derived pairs are covered by
    // derived_unusual_pair_offers_no_confirm below.)
    #[test]
    fn confirm_affordance_survives_dismissal_entered_pair() {
        let mut s = base();
        s.period_duration_days = 4;
        s.set_exact_date(d("2026-10-10"), d(TODAY));
        s.set_exact_end_date(d("2026-10-29"), d(TODAY)); // unusual, Entered
        s.edit_period_end_date(); // dismiss
        assert_eq!(s.period_date_prompt, None);
        // Warning re-issued for the renderer from the current pair:
        assert_eq!(
            s.current_unusual_warning(d(TODAY)).as_deref(),
            Some(UNUSUAL_MSG)
        );
        // Confirm works on the current pair without resubmit:
        s.confirm_period_end_date(d(TODAY));
        assert!(s.period_end_date_confirmed);
        assert_eq!(s.confirmed_period_end_date(d(TODAY)), Some(d("2026-10-29")));
        assert!(s.period_dates_block_progression(d(TODAY)).is_none());
        assert_eq!(s.current_unusual_warning(d(TODAY)), None);
    }

    // M4 review: a preset-derived end that becomes unusual via a start
    // re-entry is typed UnusualDerived and CANNOT be confirmed into a raw
    /// date. Recovery: re-pick a preset or enter an actual end.
    #[test]
    fn derived_unusual_pair_offers_no_confirm() {
        let mut s = base();
        s.set_exact_date(d("2026-10-20"), d(TODAY));
        s.select_period_duration_option("5 days", 5, false, d(TODAY)); // derived end 10-24
        s.set_exact_date(d("2026-10-05"), d(TODAY)); // 20-day pair, derived end preserved
        assert!(matches!(s.period_date_prompt, Some(PeriodDatePrompt::UnusualDerived(_))));
        assert_eq!(s.period_end_source, Some(PeriodEndSource::DerivedPreset));
        s.confirm_period_end_date(d(TODAY)); // must be a no-op
        assert!(!s.period_end_date_confirmed);
        assert_eq!(s.period_end_source, Some(PeriodEndSource::DerivedPreset));
        assert_eq!(s.confirmed_period_end_date(d(TODAY)), None);
        assert!(s.period_dates_block_progression(d(TODAY)).is_some());
        // Re-picking a preset derives a fresh end from the new start:
        s.select_period_duration_option("5 days", 5, false, d(TODAY));
        assert!(s.period_dates_block_progression(d(TODAY)).is_none());
        assert_eq!(s.confirmed_period_end_date(d(TODAY)), None); // still non-raw
        // Entering an actual end date restores the normal confirm path:
        s.set_exact_end_date(d("2026-10-25"), d(TODAY)); // 21 days, unusual, Entered
        assert!(matches!(s.period_date_prompt, Some(PeriodDatePrompt::UnusualConfirm(_))));
        s.confirm_period_end_date(d(TODAY));
        assert_eq!(s.confirmed_period_end_date(d(TODAY)), Some(d("2026-10-25")));
    }

    // M4 review: after unusual pair -> rejected end -> Edit dismissal, the
    // display prompt is the ACTIONABLE pending rejection (hard), never a
    // regenerated enabled warning whose confirm would no-op.
    #[test]
    fn display_prompt_prefers_pending_rejection_over_regenerated_warning() {
        let mut s = base();
        s.period_duration_days = 4;
        s.set_exact_date(d("2026-10-10"), d(TODAY));
        s.set_exact_end_date(d("2026-10-29"), d(TODAY)); // unusual, Entered
        s.set_exact_end_date(d("2026-11-20"), d(TODAY)); // rejected future end
        s.edit_period_end_date(); // dismiss typed prompt
        assert_eq!(s.current_unusual_warning(d(TODAY)), None); // not regenerated
        match s.current_display_prompt(d(TODAY)) {
            Some(PeriodDatePrompt::HardError(m)) => {
                assert_eq!(m, "Period dates cannot be in the future")
            }
            other => panic!("expected HardError from pending rejection, got {other:?}"),
        }
        // Confirm still refuses while the rejection is owned:
        s.confirm_period_end_date(d(TODAY));
        assert!(!s.period_end_date_confirmed);
    }

    // M5 review trace: a pending rejection must precede ANY confirmable
    // warning, including a freshly typed one. Accepting the OTHER field
    // re-types an UnusualConfirm prompt while the end rejection is still
    // owned; the display prompt must remain the actionable rejection and
    // confirm must refuse (no enabled no-op).
    #[test]
    fn display_prompt_prefers_pending_rejection_over_confirmable_warning() {
        let mut s = base();
        s.period_duration_days = 4;
        s.set_exact_date(d("2026-10-10"), d(TODAY));
        s.set_exact_end_date(d("2026-10-29"), d(TODAY)); // unusual, Entered
        s.set_exact_end_date(d("2026-11-20"), d(TODAY)); // rejected future end (pending END)
        s.set_exact_date(d("2026-10-11"), d(TODAY)); // accepted start: re-types UnusualConfirm
        assert!(matches!(
            s.period_date_prompt,
            Some(PeriodDatePrompt::UnusualConfirm(_))
        ));
        assert!(s.pending_end_rejection.is_some());
        match s.current_display_prompt(d(TODAY)) {
            Some(PeriodDatePrompt::HardError(m)) => {
                assert_eq!(m, "Period dates cannot be in the future")
            }
            other => panic!("expected HardError from pending rejection, got {other:?}"),
        }
        s.confirm_period_end_date(d(TODAY));
        assert!(!s.period_end_date_confirmed);
    }

    // M4 review: clearing a rejected start also removes its DEPENDENT end so
    // a startless end is never shown-then-silently-omitted at completion.
    #[test]
    fn clear_rejected_start_removes_dependent_end() {
        let mut s = base();
        s.set_exact_date(d("2026-11-01"), d(TODAY));
        s.set_exact_end_date(d("2026-11-04"), d(TODAY)); // entered, confirmed
        s.set_exact_date(d("2999-01-01"), d(TODAY)); // rejected future start (stored)
        assert!(s.pending_start_rejection.is_some());
        s.clear_rejected_start_intent();
        assert_eq!(s.selected_exact_date, None);
        assert_eq!(s.selected_period_end_date, None); // dependent end removed
        assert_eq!(s.period_end_source, None);
        assert!(!s.period_end_date_confirmed);
        assert!(s.period_dates_block_progression(d(TODAY)).is_none());
        assert_eq!(s.completion_params(d(TODAY)).last_period_end_date, None);
    }

    // Explicit clear of a rejected START resets value and confirmation
    // together (the refused start WAS stored).
    #[test]
    fn rejected_start_clear_resets_value_and_confirmation() {
        let mut s = base();
        s.set_exact_date(d("2999-01-01"), d(TODAY)); // future start: stored + rejected
        assert_eq!(s.selected_exact_date, Some(d("2999-01-01")));
        assert!(s.pending_start_rejection.is_some());
        s.clear_rejected_start_intent();
        assert_eq!(s.selected_exact_date, None);
        assert_eq!(s.pending_start_rejection, None);
        assert!(!s.period_end_date_confirmed);
        assert!(s.period_dates_block_progression(d(TODAY)).is_none());
    }
}

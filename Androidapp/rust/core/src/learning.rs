//! Learning summaries built only from saved logs and explicitly stored
//! settings. A calendar estimate is never presented as a measured biological
//! event. Port of domain/learning/LearningEngine.kt (main@31b88f5):
//! compileLearnedObservations, generateDoctorSummary, synthesizeExplainMyBody,
//! and the phase-summary helper, with all thresholds (top-5, top-3,
//! MIN_PHASE_LOGGED_DAYS=3, MAX_PHASE_SUMMARIES=3) and strings verbatim.
//!
//! DEFERRED (documented gap, not silently dropped):
//! generateClinicianReportText and generateMonthlyTimeline - they need
//! locale-resolved date formatting ("MMM d, yyyy" / "MMMM yyyy"), where
//! DateTimeFormatter follows the device default locale. Port next cut with
//! the locale behavior made explicit.

use crate::kotlin_text::{kotlin_trim};
use crate::cycle_math::{CyclePhase, DayPrediction};
use crate::pattern_engine::{calculate_phase_for_date, PatternSettings};
use crate::safety::SafetyLevel;
use chrono::NaiveDate;

const MIN_PHASE_LOGGED_DAYS: usize = 3;
const MAX_PHASE_SUMMARIES: usize = 3;

/// Settings fields this engine reads (verbatim names).
#[derive(Debug, Clone)]
pub struct LearningSettings {
    pub is_onboarding_completed: bool,
    pub average_cycle_length: i32,
    pub period_duration: i32,
    pub luteal_phase_days: i32,
    pub last_period_start_date: String,
}

impl LearningSettings {
    fn as_pattern_settings(&self) -> PatternSettings {
        PatternSettings {
            average_cycle_length: self.average_cycle_length,
            period_duration: self.period_duration,
            luteal_phase_days: self.luteal_phase_days,
            last_period_start_date: self.last_period_start_date.clone(),
        }
    }
}

/// Profile fields this engine reads (verbatim names from OnboardingProfile).
#[derive(Debug, Clone, Default)]
pub struct LearningProfile {
    pub is_completed: bool,
    pub typical_symptoms_csv: String,
}

/// Log fields this engine reads (verbatim names from CycleDayLog).
#[derive(Debug, Clone)]
pub struct LearningLog {
    pub date_string: String,
    pub flow_level: String,
    pub symptoms_csv: String,
    pub pain_intensity: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LearnedObservation {
    pub id: String,
    pub category: String,
    pub headline: String,
    pub description: String,
    pub confidence: String,
}

/// Verbatim output shape from data/model/CycleModels.kt ExplainMyBodyResult.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExplainMyBodyResult {
    pub summary: String,
    pub what_might_be_happening: String,
    pub what_supports_that: String,
    pub what_else_could_explain: String,
    pub what_you_can_do: String,
    pub what_to_watch: String,
    pub when_to_get_checked: String,
    pub safety_level: SafetyLevel,
}

fn display_label(value: &str) -> String {
    let spaced = value.replace('_', " ");
    let trimmed = kotlin_trim(&spaced);
    let mut chars = trimmed.chars();
    match chars.next() {
        Some(first) if first.is_lowercase() => {
            first.to_uppercase().collect::<String>() + chars.as_str()
        }
        Some(first) => first.to_string() + chars.as_str(),
        None => String::new(),
    }
}

#[derive(Debug, Clone)]
struct DatedLog<'a> {
    date: NaiveDate,
    log: &'a LearningLog,
}

fn logs_with_dates(logs: &[LearningLog]) -> Vec<DatedLog<'_>> {
    let mut seen = std::collections::HashSet::new();
    let mut out: Vec<DatedLog> = logs
        .iter()
        .filter_map(|log| {
            NaiveDate::parse_from_str(&log.date_string, "%Y-%m-%d")
                .ok()
                .map(|date| DatedLog { date, log })
        })
        .filter(|d| seen.insert(d.date))
        .collect();
    out.sort_by_key(|d| d.date);
    out
}

/// Counts are per logged DATE: within one log a symptom is deduplicated,
/// then each log containing it adds one.
fn symptom_counts(logs: &[DatedLog]) -> Vec<(String, i32)> {
    let mut counts: std::collections::HashMap<String, i32> = std::collections::HashMap::new();
    for d in logs {
        let mut seen = std::collections::HashSet::new();
        for s in d
            .log
            .symptoms_csv
            .split(',')
            .map(|s| kotlin_trim(s))
            .filter(|s| !s.is_empty())
        {
            if seen.insert(s.to_string()) {
                *counts.entry(s.to_string()).or_insert(0) += 1;
            }
        }
    }
    let mut v: Vec<(String, i32)> = counts.into_iter().collect();
    v.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
    v
}

pub fn compile_learned_observations(
    settings: &LearningSettings,
    logs: &[LearningLog],
    profile: Option<&LearningProfile>,
) -> Vec<LearnedObservation> {
    let mut observations: Vec<LearnedObservation> = Vec::new();
    let valid_logs = logs_with_dates(logs);
    let has_completed_setup =
        settings.is_onboarding_completed || profile.map(|p| p.is_completed).unwrap_or(false);

    if has_completed_setup {
        observations.push(LearnedObservation {
            id: "starting_cycle_settings".to_string(),
            category: "Starting settings".to_string(),
            headline: "Your cycle estimate settings".to_string(),
            description: format!(
                "Current settings: {} days per cycle and {} days for bleeding. These are setup values, not averages measured from your history.",
                settings.average_cycle_length, settings.period_duration
            ),
            confidence: "User settings".to_string(),
        });
    }

    let setup_symptoms: Vec<String> = {
        let mut seen = std::collections::HashSet::new();
        profile
            .map(|p| p.typical_symptoms_csv.clone())
            .unwrap_or_default()
            .split(',')
            .map(|s| kotlin_trim(s).to_string())
            .filter(|s| !s.is_empty())
            .filter(|s| seen.insert(s.clone()))
            .collect()
    };
    if !setup_symptoms.is_empty() {
        let joined = setup_symptoms
            .iter()
            .map(|s| display_label(s))
            .collect::<Vec<_>>()
            .join(", ");
        observations.push(LearnedObservation {
            id: "setup_notes".to_string(),
            category: "Onboarding notes".to_string(),
            headline: "What you noted during setup".to_string(),
            description: format!("You marked {joined} as typical during setup. These are self-reported setup notes, not daily log entries."),
            confidence: "User-provided".to_string(),
        });
    }

    if valid_logs.is_empty() {
        observations.push(LearnedObservation {
            id: "still_learning_no_logs".to_string(),
            category: "Still learning".to_string(),
            headline: "No daily records to summarize yet".to_string(),
            description: "Insights will use only dates and observations you choose to save. Log whenever it is useful; missed days are not filled in.".to_string(),
            confidence: "Not enough data".to_string(),
        });
        return observations;
    }

    for (symptom, count) in symptom_counts(&valid_logs).into_iter().take(5) {
        observations.push(LearnedObservation {
            id: format!("logged_symptom_{symptom}"),
            category: "Recorded observation".to_string(),
            headline: format!("Logged {}", display_label(&symptom)),
            description: format!(
                "You recorded {} on {} of {} saved log dates. This describes your entries only; it does not establish a cause or predict what will happen.",
                display_label(&symptom),
                count,
                valid_logs.len()
            ),
            confidence: "Recorded entries".to_string(),
        });
    }

    let n = valid_logs.len();
    observations.push(LearnedObservation {
        id: "log_coverage".to_string(),
        category: "Your records".to_string(),
        headline: format!("{n} saved log {}", if n == 1 { "date" } else { "dates" }),
        description: format!(
            "Your saved entries span {} to {}. Unlogged dates are left blank rather than estimated.",
            valid_logs.first().unwrap().date,
            valid_logs.last().unwrap().date
        ),
        confidence: "Saved records".to_string(),
    });

    if n < 3 {
        observations.push(LearnedObservation {
            id: "still_learning_sparse_logs".to_string(),
            category: "Still learning".to_string(),
            headline: "More entries are needed for a trend".to_string(),
            description: "There are fewer than three saved log dates, so CycleCare is showing the entries themselves rather than inferring a recurring pattern.".to_string(),
            confidence: "Early records".to_string(),
        });
    }

    observations.extend(compile_phase_symptom_summaries(settings, &valid_logs));
    observations
}

fn phase_display_name(phase: CyclePhase) -> &'static str {
    match phase {
        CyclePhase::Menstrual => "menstrual",
        CyclePhase::Follicular => "follicular",
        CyclePhase::Ovulation => "ovulation",
        CyclePhase::Luteal => "luteal",
    }
}

fn phase_name_lowercase(phase: CyclePhase) -> &'static str {
    match phase {
        CyclePhase::Menstrual => "menstrual",
        CyclePhase::Follicular => "follicular",
        CyclePhase::Ovulation => "ovulation",
        CyclePhase::Luteal => "luteal",
    }
}

/// Descriptive symptom frequencies per estimated phase. Denominators are
/// logged days only; no causes, no predictions, no probabilities.
fn compile_phase_symptom_summaries(
    settings: &LearningSettings,
    valid_logs: &[DatedLog],
) -> Vec<LearnedObservation> {
    let Ok(_) = NaiveDate::parse_from_str(&settings.last_period_start_date, "%Y-%m-%d") else {
        return Vec::new();
    };
    let ps = settings.as_pattern_settings();
    // group by phase, insertion order irrelevant after explicit sort
    let mut by_phase: std::collections::HashMap<CyclePhase, Vec<&DatedLog>> =
        std::collections::HashMap::new();
    for d in valid_logs {
        by_phase
            .entry(calculate_phase_for_date(d.date, &ps))
            .or_default()
            .push(d);
    }
    struct Row {
        phase: CyclePhase,
        phase_logged_days: usize,
        symptom: String,
        count: i32,
    }
    let mut rows: Vec<Row> = Vec::new();
    for (phase, phase_logs) in &by_phase {
        if phase_logs.len() < MIN_PHASE_LOGGED_DAYS {
            continue;
        }
        let owned: Vec<DatedLog> = phase_logs.iter().map(|d| (*d).clone()).collect();
        for (symptom, count) in symptom_counts(&owned) {
            rows.push(Row {
                phase: *phase,
                phase_logged_days: phase_logs.len(),
                symptom,
                count,
            });
        }
    }
    rows.sort_by(|a, b| {
        b.count
            .cmp(&a.count)
            .then_with(|| phase_name_lowercase(a.phase).cmp(phase_name_lowercase(b.phase)))
            .then_with(|| a.symptom.cmp(&b.symptom))
    });
    rows.into_iter()
        .take(MAX_PHASE_SUMMARIES)
        .map(|row| {
            let phase_name = phase_display_name(row.phase);
            LearnedObservation {
                id: format!("phase_symptom_{}_{}", phase_name_lowercase(row.phase), row.symptom),
                category: "Recorded observation".to_string(),
                headline: format!("{} in estimated {} phase", display_label(&row.symptom), phase_name),
                description: format!(
                    "You recorded {} on {} of {} logged days in your estimated {} phase. The phase is a calendar estimate, not a measured biological event. This describes saved entries only; it does not establish a cause or predict what will happen.",
                    display_label(&row.symptom), row.count, row.phase_logged_days, phase_name
                ),
                confidence: "Recorded entries".to_string(),
            }
        })
        .collect()
}

/// Returns only discussion prompts that can point to an actual saved observation.
pub fn generate_doctor_summary(settings: &LearningSettings, logs: &[LearningLog]) -> Vec<String> {
    let _ = settings; // signature parity; the source accepts settings unused here
    let valid_logs = logs_with_dates(logs);
    if valid_logs.is_empty() {
        return Vec::new();
    }
    let mut questions: Vec<String> = Vec::new();
    for (symptom, count) in symptom_counts(&valid_logs).into_iter().take(3) {
        questions.push(format!(
            "I recorded {} on {} saved {}. Is there anything specific I should monitor or bring to a follow-up?",
            display_label(&symptom),
            count,
            if count == 1 { "date" } else { "dates" }
        ));
    }
    let severe: Vec<&DatedLog> = valid_logs.iter().filter(|d| d.log.pain_intensity >= 7).collect();
    if !severe.is_empty() {
        let highest = severe.iter().map(|d| d.log.pain_intensity).max().unwrap();
        questions.push(format!(
            "I recorded pain up to {}/10 on {} saved {}. What follow-up, if any, would you recommend?",
            highest,
            severe.len(),
            if severe.len() == 1 { "date" } else { "dates" }
        ));
    }
    // NOTE: exact-case match, verbatim from the source (no uppercase()).
    let heavy: Vec<&DatedLog> = valid_logs
        .iter()
        .filter(|d| matches!(d.log.flow_level.as_str(), "HEAVY" | "UNUSUALLY_HEAVY"))
        .collect();
    if !heavy.is_empty() {
        questions.push(format!(
            "I recorded heavy flow on {} saved {}. What details would be useful to track or discuss?",
            heavy.len(),
            if heavy.len() == 1 { "date" } else { "dates" }
        ));
    }
    let mut seen = std::collections::HashSet::new();
    questions.into_iter().filter(|q| seen.insert(q.clone())).collect()
}

/// General education tied to a calendar estimate, never a measured phase.
pub fn synthesize_explain_my_body(prediction: Option<&DayPrediction>) -> ExplainMyBodyResult {
    let Some(prediction) = prediction else {
        return ExplainMyBodyResult {
            summary: "No cycle estimate is available yet.".to_string(),
            what_might_be_happening: "Add a period start date if you want CycleCare to show a calendar estimate. You can still use daily logging and general care information without one.".to_string(),
            what_supports_that: "There is no saved date anchor to calculate a cycle day.".to_string(),
            what_else_could_explain: "Symptoms and body changes can have many causes and cannot be identified from an empty cycle calendar.".to_string(),
            what_you_can_do: "Use the calendar only when its assumptions fit; otherwise leave the estimate unset.".to_string(),
            what_to_watch: "You can record any changes that matter to you without attributing them to a cycle phase.".to_string(),
            when_to_get_checked: "Contact a qualified health professional if you have symptoms that concern you.".to_string(),
            safety_level: SafetyLevel::Low,
        };
    };
    let phase_name = prediction.phase.title();
    let estimate_note = "Based on the period date and cycle length in your settings; this is a calendar estimate, not a hormone measurement or confirmation of ovulation.";
    let phase_education = match prediction.phase {
        CyclePhase::Menstrual => "During menstrual bleeding, the uterus sheds its lining. Experiences vary from person to person and from cycle to cycle.",
        CyclePhase::Follicular => "The follicular phase runs from the start of bleeding toward ovulation. Timing varies, and calendar dates cannot confirm what is happening in an individual body.",
        CyclePhase::Ovulation => "Ovulation timing varies. A calendar window cannot confirm whether or when ovulation occurred.",
        CyclePhase::Luteal => "The luteal phase follows ovulation and ends when the next period begins. Its timing and associated experiences vary.",
    };
    ExplainMyBodyResult {
        summary: format!("Calendar estimate: cycle day {}, {}.", prediction.day_of_cycle, phase_name),
        what_might_be_happening: phase_education.to_string(),
        what_supports_that: estimate_note.to_string(),
        what_else_could_explain: "Any symptoms you experience may have explanations unrelated to cycle timing; these notes do not identify a cause.".to_string(),
        what_you_can_do: "If it is useful, keep notes on dates and observations. Calendar-based fertile-window estimates should not be used as contraception.".to_string(),
        what_to_watch: "If your dates or symptoms change, you can update your record; the estimate will change with the information you enter.".to_string(),
        when_to_get_checked: "Seek advice from a qualified health professional about symptoms that are severe, new, persistent, or worrying to you.".to_string(),
        safety_level: SafetyLevel::Low,
    }
}

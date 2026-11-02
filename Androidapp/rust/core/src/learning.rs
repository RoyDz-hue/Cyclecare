//! Learning summaries built only from saved logs and explicitly stored
//! settings. A calendar estimate is never presented as a measured biological
//! event. Port of domain/learning/LearningEngine.kt (main@31b88f5):
//! compileLearnedObservations, generateDoctorSummary, synthesizeExplainMyBody,
//! and the phase-summary helper, with all thresholds (top-5, top-3,
//! MIN_PHASE_LOGGED_DAYS=3, MAX_PHASE_SUMMARIES=3) and strings verbatim.
//!
//! generateClinicianReportText and generateMonthlyTimeline are ported with
//! explicit date formatting: the JVM probe (en_US default locale) confirms
//! "MMM d, yyyy" -> "Nov 2, 2026" and "MMMM yyyy" -> "October 2026" in
//! English. DateTimeFormatter follows the DEVICE default locale; on a
//! non-English device the production text differs. Corpus: JVM probe
//! 2026-11-02, fixtures A-D + TL (locked in tests/learning_report_corpus.rs).
//!
//! Source-signature gaps (checked, intentional): synthesizeExplainMyBody's
//! Kotlin params (settings, logs, query) and generateMonthlyTimeline's
//! `settings` are unused in the Kotlin bodies, so the Rust signatures drop
//! them. generateClinicianReportText takes `today` instead of calling
//! LocalDate.now() so the pure function stays deterministic.

use crate::kotlin_text::{kotlin_trim};
use crate::cycle_math::{CyclePhase, DayPrediction};
use crate::pattern_engine::{calculate_phase_for_date, PatternSettings};
use crate::safety::SafetyLevel;
use chrono::{Datelike, NaiveDate};

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
    /// approximatePeriodChoice; report branch checks prefix "APPROXIMATE:".
    pub approximate_period_choice: String,
}

/// Log fields this engine reads (verbatim names from CycleDayLog).
#[derive(Debug, Clone)]
pub struct LearningLog {
    pub date_string: String,
    pub flow_level: String,
    pub energy_level: String,
    pub symptoms_csv: String,
    pub pain_intensity: i32,
    pub notes: String,
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
fn month_abbrev(m: u32) -> &'static str {
    match m {
        1 => "Jan", 2 => "Feb", 3 => "Mar", 4 => "Apr", 5 => "May", 6 => "Jun",
        7 => "Jul", 8 => "Aug", 9 => "Sep", 10 => "Oct", 11 => "Nov", _ => "Dec",
    }
}
fn month_full(m: u32) -> &'static str {
    match m {
        1 => "January", 2 => "February", 3 => "March", 4 => "April",
        5 => "May", 6 => "June", 7 => "July", 8 => "August",
        9 => "September", 10 => "October", 11 => "November", _ => "December",
    }
}

/// Kotlin formatCounts: empty -> "None entered"; else count-desc, key-asc,
/// "Label (n)" joined with ", ".
fn format_counts(counts: &[(String, i32)]) -> String {
    if counts.is_empty() {
        return "None entered".to_string();
    }
    let mut v: Vec<(String, i32)> = counts.to_vec();
    v.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
    v.iter()
        .map(|(k, n)| format!("{} ({})", display_label(k), n))
        .collect::<Vec<_>>()
        .join(", ")
}

/// Insertion-ordered counts (Kotlin groupingBy().eachCount() = LinkedHashMap
/// first-seen order). maxByOrNull picks the FIRST maximal in that order.
fn counts_first_seen(items: impl Iterator<Item = String>) -> Vec<(String, i32)> {
    let mut order: Vec<String> = Vec::new();
    let mut counts: std::collections::HashMap<String, i32> = std::collections::HashMap::new();
    for it in items {
        if !counts.contains_key(&it) {
            order.push(it.clone());
        }
        *counts.entry(it).or_insert(0) += 1;
    }
    order.into_iter().map(|k| { let n = counts[&k]; (k, n) }).collect()
}

/// First maximal by count in FIRST-SEEN order (Kotlin Map.maxByOrNull
/// returns the FIRST maximal; Rust Iterator::max_by returns the LAST, so
/// iterate in reverse).
fn first_maximal(counts: &[(String, i32)]) -> Option<&str> {
    counts.iter().rev().max_by(|a, b| a.1.cmp(&b.1)).map(|(k, _)| k.as_str())
}

/// Port of generateClinicianReportText (LearningEngine.kt). `today` replaces
/// LocalDate.now(); date format "MMM d, yyyy" under the probed en_US locale.
pub fn generate_clinician_report_text(
    settings: &LearningSettings,
    logs: &[LearningLog],
    profile: Option<&LearningProfile>,
    today: NaiveDate,
) -> String {
    let today_str = format!("{} {}, {}", month_abbrev(today.month()), today.day(), today.year());
    let valid_logs = logs_with_dates(logs);
    let symptoms = symptom_counts(&valid_logs);
    let questions = generate_doctor_summary(settings, logs);
    let date_choice = profile.map(|p| p.approximate_period_choice.clone()).unwrap_or_default();
    let is_approximate_date = date_choice.to_lowercase().starts_with("approximate:");

    let mut out = String::new();
    let mut line = |s: String| { out.push_str(&s); out.push('\n'); };
    line("CYCLECARE \u{2014} USER-ENTERED SUMMARY".to_string());
    line(format!("Generated: {today_str}"));
    line("This summary reproduces saved information. It is not a diagnosis or a clinical assessment.".to_string());
    line(String::new());
    line("SETUP SETTINGS (not measured averages)".to_string());
    line(format!("\u{2022} Cycle-length setting: {} days", settings.average_cycle_length));
    line(format!("\u{2022} Bleeding-duration setting: {} days", settings.period_duration));
    if kotlin_trim(&settings.last_period_start_date).is_empty() {
        line("\u{2022} Last period start date: Not provided".to_string());
    } else if is_approximate_date {
        line(format!("\u{2022} Last period start date: {} (estimated from the approximate range selected during setup)", settings.last_period_start_date));
    } else {
        line(format!("\u{2022} Last period start date entered: {}", settings.last_period_start_date));
    }
    line(String::new());

    if valid_logs.is_empty() {
        line("SAVED DAILY LOGS".to_string());
        line("\u{2022} No daily log entries are saved.".to_string());
        line("\u{2022} No symptom, pain, flow, sleep, mood, or energy history is inferred.".to_string());
    } else {
        line(format!("SAVED DAILY LOGS ({} dates)", valid_logs.len()));
        line(format!("\u{2022} Date range: {} to {}", valid_logs.first().unwrap().date, valid_logs.last().unwrap().date));
        let flow_counts = counts_first_seen(valid_logs.iter().filter_map(|d| {
            let f = d.log.flow_level.to_uppercase();
            if f.is_empty() || f == "NONE" || f == "UNKNOWN" { None } else { Some(f) }
        }));
        line(format!("\u{2022} Flow entries: {}", format_counts(&flow_counts)));
        line(format!("\u{2022} Symptom tags: {}", format_counts(&symptoms)));
        let pain_entries: Vec<String> = valid_logs.iter()
            .filter(|d| d.log.pain_intensity > 0)
            .map(|d| format!("{} {}/10", d.date, d.log.pain_intensity))
            .collect();
        if !pain_entries.is_empty() {
            line(format!("\u{2022} Pain ratings entered: {}", pain_entries.join(", ")));
        } else {
            line("\u{2022} Pain ratings: None entered".to_string());
        }
        let other_notes: Vec<&DatedLog> = valid_logs.iter()
            .filter(|d| !kotlin_trim(&d.log.notes).is_empty())
            .collect();
        if !other_notes.is_empty() {
            line("\u{2022} Notes entered:".to_string());
            for d in other_notes {
                line(format!("  - {}: {}", d.date, kotlin_trim(&d.log.notes)));
            }
        }
    }

    if !questions.is_empty() {
        line(String::new());
        line("OPTIONAL DISCUSSION PROMPTS FROM SAVED ENTRIES".to_string());
        for (i, q) in questions.iter().enumerate() {
            line(format!("{}. {}", i + 1, q));
        }
    }
    // Kotlin .trimEnd()
    let trimmed = out.trim_end();
    trimmed.to_string()
}

/// Verbatim output shape from data/model/CycleModels.kt MonthlyTimelineEntry.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MonthlyTimelineEntry {
    pub month_title: String,
    /// Kotlin always passes 0 here (parity quirk, not filled in upstream).
    pub cycle_length_days: i32,
    pub period_bleeding_days: i32,
    pub dominant_energy: String,
    pub dominant_symptom: String,
    /// Kotlin always passes null (parity quirk).
    pub pattern_noticed: Option<String>,
}

/// Port of generateMonthlyTimeline (LearningEngine.kt). Kotlin `settings`
/// param is unused in the body; dropped here (documented gap). Groups by
/// YearMonth, months sorted descending.
pub fn generate_monthly_timeline(logs: &[LearningLog]) -> Vec<MonthlyTimelineEntry> {
    let valid = logs_with_dates(logs);
    let mut months: Vec<(i32, u32)> = Vec::new(); // (year, month) first-seen
    let mut grouped: std::collections::HashMap<(i32, u32), Vec<&DatedLog>> = std::collections::HashMap::new();
    for d in &valid {
        let key = (d.date.year(), d.date.month());
        if !grouped.contains_key(&key) {
            months.push(key);
        }
        grouped.entry(key).or_default().push(d);
    }
    months.sort_by(|a, b| b.cmp(a)); // YearMonth descending
    months.into_iter().map(|(y, m)| {
        let entries = &grouped[&(y, m)];
        let month_symptoms: Vec<(String, i32)> = {
            // first-seen counts per logged date, csv order within a date
            let mut order: Vec<String> = Vec::new();
            let mut counts: std::collections::HashMap<String, i32> = std::collections::HashMap::new();
            for d in entries.iter() {
                let mut seen = std::collections::HashSet::new();
                for s in d.log.symptoms_csv.split(',').map(kotlin_trim).filter(|s| !s.is_empty()) {
                    if seen.insert(s.to_string()) {
                        if !counts.contains_key(s) { order.push(s.to_string()); }
                        *counts.entry(s.to_string()).or_insert(0) += 1;
                    }
                }
            }
            order.into_iter().map(|k| { let n = counts[&k]; (k, n) }).collect()
        };
        let recorded_energy = counts_first_seen(entries.iter().filter_map(|d| {
            let e = d.log.energy_level.to_uppercase();
            if e.is_empty() || e == "NORMAL" || e == "UNKNOWN" { None } else { Some(e) }
        }));
        let bleeding_days = entries.iter().filter(|d| {
            matches!(d.log.flow_level.to_uppercase().as_str(), "LIGHT" | "MODERATE" | "HEAVY" | "UNUSUALLY_HEAVY")
        }).count() as i32;
        MonthlyTimelineEntry {
            month_title: format!("{} {}", month_full(m), y),
            cycle_length_days: 0,
            period_bleeding_days: bleeding_days,
            dominant_energy: first_maximal(&recorded_energy).map(display_label).unwrap_or_else(|| "Not recorded".to_string()),
            dominant_symptom: first_maximal(&month_symptoms).map(display_label).unwrap_or_else(|| "No symptom tags recorded".to_string()),
            pattern_noticed: None,
        }
    }).collect()
}

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

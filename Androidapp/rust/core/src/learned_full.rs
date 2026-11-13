//! Actual learned observations, full Java dates/UTF16 order and exact JVM labels.
use crate::cycle_math::{parse_local_date_iso_full, ParsedLocalDate, CyclePhase};
use crate::learning::{LearnedObservation, LearningSettings};
use chrono::Datelike;
const MIN_PHASE_LOGGED_DAYS:usize=3;
const MAX_PHASE_SUMMARIES:usize=3;
#[derive(Clone)]
pub struct Symptom {pub key:String,pub label:String}
pub struct LearningLog {pub date_string:String,pub symptoms:Vec<Symptom>}
pub struct LearningProfile {pub is_completed:bool,pub symptoms:Vec<Symptom>}
#[derive(Clone)]
struct DatedLog<'a> {date:String,epoch:i64,log:&'a LearningLog}
pub(crate) fn date_text(parsed:ParsedLocalDate)->Option<(i64,String)> {
    let epoch=parsed.epoch_day()?;
    let (year,month,day)=match parsed {
        ParsedLocalDate::Representable(d)=>(d.year() as i64,d.month(),d.day()),
        ParsedLocalDate::Unrepresentable{year,month,day}=>(year,month,day),
        ParsedLocalDate::Invalid=>return None,
    };
    let y=if year<0 {format!("-{:04}",-year)} else if year>9999 {format!("+{}",year)} else {format!("{:04}",year)};
    Some((epoch,format!("{}-{:02}-{:02}",y,month,day)))
}
fn logs_with_dates(logs:&[LearningLog])->Vec<DatedLog<'_>> {
    let mut seen=std::collections::HashSet::new();
    let mut out:Vec<_>=logs.iter().filter_map(|log|date_text(parse_local_date_iso_full(&log.date_string))
        .map(|(epoch,date)|DatedLog{date,epoch,log})).filter(|d|seen.insert(d.epoch)).collect();
    out.sort_by_key(|d|d.epoch);out
}
fn symptom_counts(logs:&[DatedLog])->Vec<(String,i32)> {
    let mut counts=std::collections::HashMap::new();
    for d in logs {let mut seen=std::collections::HashSet::new();for s in &d.log.symptoms {
        if seen.insert(&s.key) {*counts.entry(s.key.clone()).or_insert(0)+=1;}
    }}
    let mut out:Vec<_>=counts.into_iter().collect();
    out.sort_by(|a,b|b.1.cmp(&a.1).then_with(||a.0.encode_utf16().cmp(b.0.encode_utf16())));out
}
fn label(logs:&[DatedLog],key:&str)->String {
    logs.iter().flat_map(|l|l.log.symptoms.iter()).find(|s|s.key==key).map(|s|s.label.clone()).unwrap()
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

    let mut setup_seen=std::collections::HashSet::new();
    let setup_symptoms:Vec<_> = profile.map(|p| p.symptoms.as_slice()).unwrap_or(&[]).iter()
        .filter(|s|setup_seen.insert(&s.key)).collect();
    if !setup_symptoms.is_empty() {
        let joined = setup_symptoms
            .iter()
            .map(|s| s.label.clone())
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
            headline: format!("Logged {}", label(&valid_logs, &symptom)),
            description: format!(
                "You recorded {} on {} of {} saved log dates. This describes your entries only; it does not establish a cause or predict what will happen.",
                label(&valid_logs, &symptom),
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
    if parse_local_date_iso_full(&settings.last_period_start_date).epoch_day().is_none() {
        return Vec::new();
    }

    // group by phase, insertion order irrelevant after explicit sort
    let mut by_phase: std::collections::HashMap<CyclePhase, Vec<&DatedLog>> =
        std::collections::HashMap::new();
    for d in valid_logs {
        by_phase
            .entry(crate::phase_full::calculate(&d.log.date_string, &settings.last_period_start_date, settings.average_cycle_length, settings.period_duration, settings.luteal_phase_days).unwrap())
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
            .then_with(|| a.symptom.encode_utf16().cmp(b.symptom.encode_utf16()))
    });
    rows.into_iter()
        .take(MAX_PHASE_SUMMARIES)
        .map(|row| {
            let phase_name = phase_display_name(row.phase);
            LearnedObservation {
                id: format!("phase_symptom_{}_{}", phase_name_lowercase(row.phase), row.symptom),
                category: "Recorded observation".to_string(),
                headline: format!("{} in estimated {} phase", label(valid_logs, &row.symptom), phase_name),
                description: format!(
                    "You recorded {} on {} of {} logged days in your estimated {} phase. The phase is a calendar estimate, not a measured biological event. This describes saved entries only; it does not establish a cause or predict what will happen.",
                    label(valid_logs, &row.symptom), row.count, row.phase_logged_days, phase_name
                ),
                confidence: "Recorded entries".to_string(),
            }
        })
        .collect()
}


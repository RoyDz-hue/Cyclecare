//! Production doctor-summary ownership. The JVM supplies its exact platform
//! text primitives (trim + displayLabel) without interpreting the evidence.
//! Rust owns date validity/dedup, per-date symptom counts, UTF16 tie ordering,
//! top-three selection, pain/flow thresholds, verbatim prompts and distinct.
//! No clock, storage, identity or network access.
use crate::cycle_math::parse_local_date_iso_full;
use std::collections::{HashMap, HashSet};

pub struct Symptom {
    pub key: String,
    pub label: String,
}
pub struct Log {
    pub date: String,
    pub symptoms: Vec<Symptom>,
    pub pain: i32,
    pub flow: String,
}

pub fn generate(logs: &[Log]) -> Vec<String> {
    let mut seen_dates = HashSet::new();
    let valid: Vec<_> = logs.iter().filter(|log| {
        parse_local_date_iso_full(&log.date).epoch_day()
            .map(|day| seen_dates.insert(day)).unwrap_or(false)
    }).collect();
    let mut counts: HashMap<&str, (&str, usize)> = HashMap::new();
    for log in &valid {
        let mut seen = HashSet::new();
        for symptom in &log.symptoms {
            if seen.insert(symptom.key.as_str()) {
                let entry = counts.entry(&symptom.key).or_insert((&symptom.label, 0));
                entry.1 += 1;
            }
        }
    }
    let mut counts: Vec<_> = counts.into_iter().collect();
    counts.sort_by(|a,b| b.1.1.cmp(&a.1.1)
        .then_with(|| a.0.encode_utf16().cmp(b.0.encode_utf16())));
    let mut questions = Vec::new();
    for (_, (label, count)) in counts.into_iter().take(3) {
        questions.push(format!("I recorded {} on {} saved {}. Is there anything specific I should monitor or bring to a follow-up?",
            label, count, if count == 1 { "date" } else { "dates" }));
    }
    let severe: Vec<_> = valid.iter().filter(|log| log.pain >= 7).collect();
    if let Some(highest) = severe.iter().map(|log| log.pain).max() {
        questions.push(format!("I recorded pain up to {}/10 on {} saved {}. What follow-up, if any, would you recommend?",
            highest, severe.len(), if severe.len() == 1 { "date" } else { "dates" }));
    }
    let heavy = valid.iter().filter(|log| matches!(log.flow.as_str(), "HEAVY" | "UNUSUALLY_HEAVY")).count();
    if heavy != 0 {
        questions.push(format!("I recorded heavy flow on {} saved {}. What details would be useful to track or discuss?",
            heavy, if heavy == 1 { "date" } else { "dates" }));
    }
    let mut seen = HashSet::new();
    questions.into_iter().filter(|q| seen.insert(q.clone())).collect()
}

//! Whole clinician-report ownership with exact Java full dates/JVM text adapters.
use crate::cycle_math::parse_local_date_iso_full;
use crate::learned_full::date_text;
pub struct Tag {pub key:String,pub label:String}
pub struct Log {pub date:String,pub symptoms:Vec<Tag>,pub flow:String,pub flow_upper:Tag,pub pain:i32,pub note:String}
pub struct Settings {pub average_cycle_length:i32,pub period_duration:i32,pub last_period_start_date:String,pub anchor_blank:bool,pub approximate:bool}
struct DatedLog<'a> {epoch:i64,date:String,log:&'a Log}
fn symptom_counts(logs:&[DatedLog])->Vec<(String,i32)> {
 let mut counts=std::collections::HashMap::new();
 for d in logs {let mut seen=std::collections::HashSet::new();for s in &d.log.symptoms {if seen.insert(&s.key){*counts.entry(s.key.clone()).or_insert(0)+=1;}}}
 counts.into_iter().collect()
}
fn counts_first_seen(items:impl Iterator<Item=String>)->Vec<(String,i32)> {
 let mut counts=std::collections::HashMap::new();for key in items {*counts.entry(key).or_insert(0)+=1;}counts.into_iter().collect()
}
fn format_counts(counts:&[(String,i32)],label:impl Fn(&str)->String)->String {
 if counts.is_empty(){return "None entered".into()}
 let mut counts=counts.to_vec();counts.sort_by(|a,b|b.1.cmp(&a.1).then_with(||a.0.encode_utf16().cmp(b.0.encode_utf16())));
 counts.iter().map(|(k,n)|format!("{} ({})",label(k),n)).collect::<Vec<_>>().join(", ")
}
pub fn generate_clinician_report_text(
    settings: &Settings,
    logs: &[Log],
    today_str: &str,
) -> String {
    let mut seen=std::collections::HashSet::new();
    let mut valid_logs:Vec<_>=logs.iter().filter_map(|log|date_text(parse_local_date_iso_full(&log.date)).map(|(epoch,date)|DatedLog{epoch,date,log})).filter(|d|seen.insert(d.epoch)).collect();
    valid_logs.sort_by_key(|d|d.epoch);
    let symptoms=symptom_counts(&valid_logs);
    let doctor_logs:Vec<_>=valid_logs.iter().map(|d|crate::doctor_summary::Log {date:d.date.clone(),symptoms:d.log.symptoms.iter().map(|s|crate::doctor_summary::Symptom{key:s.key.clone(),label:s.label.clone()}).collect(),pain:d.log.pain,flow:d.log.flow.clone()}).collect();
    let questions=crate::doctor_summary::generate(&doctor_logs);
    let is_approximate_date=settings.approximate;
    let mut out = String::new();
    let mut line = |s: String| { out.push_str(&s); out.push('\n'); };
    line("CYCLECARE \u{2014} USER-ENTERED SUMMARY".to_string());
    line(format!("Generated: {today_str}"));
    line("This summary reproduces saved information. It is not a diagnosis or a clinical assessment.".to_string());
    line(String::new());
    line("SETUP SETTINGS (not measured averages)".to_string());
    line(format!("\u{2022} Cycle-length setting: {} days", settings.average_cycle_length));
    line(format!("\u{2022} Bleeding-duration setting: {} days", settings.period_duration));
    if settings.anchor_blank {
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
            let f = d.log.flow_upper.key.clone();
            if f.is_empty() || f == "NONE" || f == "UNKNOWN" { None } else { Some(f) }
        }));
        line(format!("\u{2022} Flow entries: {}", format_counts(&flow_counts, |key|valid_logs.iter().find(|d|d.log.flow_upper.key==key).unwrap().log.flow_upper.label.clone())));
        line(format!("\u{2022} Symptom tags: {}", format_counts(&symptoms, |key|valid_logs.iter().flat_map(|d|d.log.symptoms.iter()).find(|s|s.key==key).unwrap().label.clone())));
        let pain_entries: Vec<String> = valid_logs.iter()
            .filter(|d| d.log.pain > 0)
            .map(|d| format!("{} {}/10", d.date, d.log.pain))
            .collect();
        if !pain_entries.is_empty() {
            line(format!("\u{2022} Pain ratings entered: {}", pain_entries.join(", ")));
        } else {
            line("\u{2022} Pain ratings: None entered".to_string());
        }
        let other_notes: Vec<&DatedLog> = valid_logs.iter()
            .filter(|d| !d.log.note.is_empty())
            .collect();
        if !other_notes.is_empty() {
            line("\u{2022} Notes entered:".to_string());
            for d in other_notes {
                line(format!("  - {}: {}", d.date, d.log.note));
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
    let trimmed = out.trim_end_matches('\n');
    trimmed.to_string()
}


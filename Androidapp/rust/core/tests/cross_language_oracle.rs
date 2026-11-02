//! Cross-language parity oracle. Compares the Rust ports against outputs
//! produced by running the PRODUCTION Kotlin functions over the shared corpus
//! (fixtures/oracle/corpus_*.tsv -> results_*.tsv, generated on the JVM by
//! fixtures/oracle/kotlin/CrossLanguageFixtureRunner.kt).
//!
//! Each comparator skips (with a loud note) when its results file is absent,
//! so `cargo test` stays green before the Kotlin side has run. Once ops
//! executes the runner and drops results_*.tsv into fixtures/oracle/, these
//! tests become the parity gate for the "exact port" claim.

use std::path::{Path, PathBuf};

use cyclecare_core::notifications::*;
use cyclecare_core::pin::is_complete_pin;
use cyclecare_core::safety::{self, SafetyLog};

fn oracle_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures/oracle")
}

fn load_results(name: &str) -> Option<Vec<(String, Vec<String>)>> {
    let path = oracle_dir().join(name);
    if !path.exists() {
        eprintln!("oracle: {name} not generated yet (Kotlin runner has not run); skipping");
        return None;
    }
    Some(
        std::fs::read_to_string(path)
            .unwrap()
            .lines()
            .filter(|l| !l.trim().is_empty())
            .map(|l| {
                let cols: Vec<String> = l.split('\t').map(|s| s.to_string()).collect();
                (cols[0].clone(), cols[1..].to_vec())
            })
            .collect(),
    )
}

fn corpus(name: &str) -> Vec<(String, Vec<String>)> {
    std::fs::read_to_string(oracle_dir().join(name))
        .unwrap()
        .lines()
        .filter(|l| !l.trim().is_empty())
        .map(|l| {
            let cols: Vec<String> = l.split('\t').map(|s| s.to_string()).collect();
            (cols[0].clone(), cols[1..].to_vec())
        })
        .collect()
}

fn decode(token: &str) -> Option<String> {
    match token {
        "<NULL>" => None,
        "<EMPTY>" => Some(String::new()),
        "<SPACES>" => Some("   ".to_string()),
        other => Some(other.to_string()),
    }
}

fn safety_cols(c: &[String]) -> Option<SafetyLog> {
    if c[0] != "PRESENT" {
        return None;
    }
    Some(SafetyLog {
        flow_level: c[1].clone(),
        symptoms_csv: c[2].clone(),
        vaginal_health_csv: c[3].clone(),
        urinary_health_csv: c[4].clone(),
        pain_intensity: c[5].parse().unwrap(),
    })
}

fn level_name(l: safety::SafetyLevel) -> &'static str {
    match l {
        safety::SafetyLevel::Low => "LOW",
        safety::SafetyLevel::Monitor => "MONITOR",
        safety::SafetyLevel::Review => "REVIEW",
        safety::SafetyLevel::SeekProfessionalAdvice => "SEEK_PROFESSIONAL_ADVICE",
        safety::SafetyLevel::Urgent => "URGENT",
    }
}

#[test]
fn pin_matches_kotlin_outputs() {
    let Some(results) = load_results("results_pin.tsv") else { return };
    let cases = corpus("corpus_pin.tsv");
    for ((case, args), (_, expected)) in cases.iter().zip(results.iter()) {
        let actual = is_complete_pin(&decode(&args[0]).unwrap());
        assert_eq!(
            actual.to_string(),
            expected[0],
            "pin case {case} diverged from Kotlin"
        );
    }
}

#[test]
fn notification_timestamps_match_kotlin_outputs() {
    let Some(results) = load_results("results_notifications.tsv") else { return };
    let cases = corpus("corpus_notifications.tsv");
    for ((case, args), (_, expected)) in cases.iter().zip(results.iter()) {
        let actual = parse_iso_timestamp(decode(&args[0]).as_deref());
        assert_eq!(
            actual.to_string(),
            expected[0],
            "timestamp case {case} diverged from Kotlin"
        );
    }
}

#[test]
fn priorities_match_kotlin_outputs() {
    let Some(results) = load_results("results_priority.tsv") else { return };
    let cases = corpus("corpus_priority.tsv");
    for ((case, args), (_, expected)) in cases.iter().zip(results.iter()) {
        let actual = explicit_priority_or_normal(decode(&args[0]).as_deref());
        assert_eq!(actual, expected[0], "priority case {case} diverged from Kotlin");
    }
}

#[test]
fn safety_evaluate_matches_kotlin_outputs() {
    let Some(results) = load_results("results_safety.tsv") else { return };
    let cases = corpus("corpus_safety.tsv");
    for ((case, args), (_, expected)) in cases.iter().zip(results.iter()) {
        let actual = safety::evaluate(safety_cols(args).as_ref());
        let fields = vec![
            level_name(actual.level).to_string(),
            actual.title.to_string(),
            actual.message,
            actual.action_advice,
            actual.is_red_flag.to_string(),
        ];
        assert_eq!(fields, *expected, "safety case {case} diverged from Kotlin");
    }
}

#[test]
fn safety_evaluate_input_matches_kotlin_outputs() {
    let Some(results) = load_results("results_safety_input.tsv") else { return };
    let cases = corpus("corpus_safety_input.tsv");
    for ((case, args), (_, expected)) in cases.iter().zip(results.iter()) {
        let raw = decode(&args[0]).unwrap_or_default();
        let actual = safety::evaluate_input(&raw, safety_cols(&args[1..]).as_ref());
        let fields = vec![
            level_name(actual.level).to_string(),
            actual.title.to_string(),
            actual.message,
            actual.action_advice,
            actual.is_red_flag.to_string(),
        ];
        assert_eq!(fields, *expected, "input case {case} diverged from Kotlin");
    }
}

fn phase_name(p: cyclecare_core::cycle_math::CyclePhase) -> &'static str {
    use cyclecare_core::cycle_math::CyclePhase::*;
    match p {
        Menstrual => "MENSTRUAL",
        Follicular => "FOLLICULAR",
        Ovulation => "OVULATION",
        Luteal => "LUTEAL",
    }
}

#[test]
fn cycle_math_predictions_match_kotlin_outputs() {
    use cyclecare_core::cycle_math::calculate_day_prediction;
    let Some(results) = load_results("results_cycle_math.tsv") else { return };
    let cases = corpus("corpus_cycle_math.tsv");
    for ((case, args), (_, expected)) in cases.iter().zip(results.iter()) {
        let date = chrono::NaiveDate::parse_from_str(&args[0], "%Y-%m-%d").unwrap();
        let anchor = decode(&args[1]).unwrap();
        let avg: i32 = args[2].parse().unwrap();
        let dur: i32 = args[3].parse().unwrap();
        let lut: i32 = args[4].parse().unwrap();
        let actual = calculate_day_prediction(date, &anchor, avg, dur, lut);
        match (&actual, expected[0].as_str()) {
            (None, "NULL") => {}
            (Some(p), _) => {
                let fields = vec![
                    p.day_of_cycle.to_string(),
                    phase_name(p.phase).to_string(),
                    p.is_period.to_string(),
                    p.is_fertile.to_string(),
                    p.is_ovulation.to_string(),
                    p.fertility_confidence.to_string(),
                    p.pregnancy_chance.to_string(),
                ];
                assert_eq!(&fields, expected, "cycle_math case {case} diverged from Kotlin");
            }
            (None, other) => panic!("cycle_math case {case}: Rust None, Kotlin {other}"),
        }
    }
}

#[test]
fn verdicts_match_kotlin_outputs() {
    use cyclecare_core::cycle_math::{unusual_message, verdict_for};
    let Some(results) = load_results("results_verdict.tsv") else { return };
    let cases = corpus("corpus_verdict.tsv");
    for ((case, args), (_, expected)) in cases.iter().zip(results.iter()) {
        let duration: i64 = args[0].parse().unwrap();
        let ongoing: bool = args[1].parse().unwrap();
        let verdict = format!("{:?}", verdict_for(duration)).to_uppercase();
        assert_eq!(&verdict, &expected[0], "verdict case {case} diverged from Kotlin");
        let msg = unusual_message(duration, ongoing);
        assert_eq!(&msg, &expected[1], "message case {case} diverged from Kotlin");
    }
}

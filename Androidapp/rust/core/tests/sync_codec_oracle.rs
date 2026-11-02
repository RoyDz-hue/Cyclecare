//! Comparator: sync_codec.rs against executed AOSP org.json oracles.
//! Oracles were produced by compiling AOSP libcore org.json
//! (android-14.0.0_r1) sources and running the verbatim Kotlin codec
//! (CodecParity.kt, extracted from HealthSyncLocal.kt) against them.
//! These supersede the earlier Maven org.json outputs, which differ in
//! object key order (HashMap vs insertion order) and slash escaping.

use cyclecare_core::sync_codec::*;
use std::path::PathBuf;

fn fixture(name: &str) -> String {
    let mut p = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    p.push("fixtures/oracle");
    p.push(name);
    std::fs::read_to_string(p).unwrap()
}

fn tok(t: &str) -> String {
    t.replace("<TAB>", "\t").replace("<EMPTY>", "")
}

fn rows(name: &str) -> Vec<Vec<String>> {
    fixture(name)
        .lines()
        .filter(|l| !l.is_empty())
        .map(|l| l.split('\t').map(String::from).collect())
        .collect()
}

#[test]
fn log_payloads_and_hashes_match_aosp() {
    let results = rows("results_codec_log.tsv");
    let mut checked = 0;
    for line in fixture("corpus_codec_log.tsv").lines().filter(|l| !l.is_empty()) {
        let c: Vec<&str> = line.split('\t').collect();
        assert_eq!(c.len(), 16, "corpus row shape");
        let log = CycleDayLog {
            date: c[1].to_string(),
            flow: tok(c[2]),
            energy: tok(c[3]),
            mood: tok(c[4]),
            symptoms: tok(c[5]),
            vaginal_health: tok(c[6]),
            urinary_health: tok(c[7]),
            cervical_mucus: tok(c[8]),
            ovulation_test: tok(c[9]),
            bbt_celsius: c[10].parse::<f32>().unwrap(),
            sleep_hours: c[11].parse::<f32>().unwrap(),
            sleep_quality: tok(c[12]),
            water_glasses: c[13].parse::<i32>().unwrap(),
            notes: tok(c[14]),
            pain_intensity: c[15].parse::<i32>().unwrap(),
        };
        let expected = results.iter().find(|r| r[0] == c[0]).unwrap();
        let payload = log_payload(&log);
        assert_eq!(payload, expected[1], "payload mismatch for {}", c[0]);
        assert_eq!(hash(Some(&payload)), expected[2], "hash mismatch for {}", c[0]);
        checked += 1;
    }
    assert!(checked >= 8);
}

#[test]
fn settings_payloads_and_hashes_match_aosp() {
    let results = rows("results_codec_settings.tsv");
    for line in fixture("corpus_codec_settings.tsv").lines().filter(|l| !l.is_empty()) {
        let c: Vec<&str> = line.split('\t').collect();
        let s = CycleSettings {
            selected_goals_csv: tok(c[1]),
            last_period_start_date: tok(c[2]),
            average_cycle_length: c[3].parse().unwrap(),
            period_duration: c[4].parse().unwrap(),
            luteal_phase_days: c[5].parse().unwrap(),
            is_pregnancy_mode: c[6].parse().unwrap(),
            pregnancy_due_date: tok(c[7]),
            ..Default::default()
        };
        let expected = results.iter().find(|r| r[0] == c[0]).unwrap();
        let payload = settings_payload(&s);
        assert_eq!(payload, expected[1], "payload mismatch for {}", c[0]);
        assert_eq!(hash(Some(&payload)), expected[2], "hash mismatch for {}", c[0]);
    }
}

#[test]
fn profile_payloads_and_hashes_match_aosp() {
    let results = rows("results_codec_profile.tsv");
    for line in fixture("corpus_codec_profile.tsv").lines().filter(|l| !l.is_empty()) {
        let c: Vec<&str> = line.split('\t').collect();
        let pr = OnboardingProfile {
            selected_goals_csv: tok(c[1]),
            last_period_date_string: tok(c[2]),
            last_period_end_date_string: tok(c[3]),
            approximate_period_choice: tok(c[4]),
            cycle_length_option: tok(c[5]),
            cycle_length_days: c[6].parse().unwrap(),
            period_duration_days: c[7].parse().unwrap(),
            typical_symptoms_csv: tok(c[8]),
            typical_energy: tok(c[9]),
            typical_sleep_quality: tok(c[10]),
            has_completed_deep_baseline: c[11].parse().unwrap(),
            ..Default::default()
        };
        let expected = results.iter().find(|r| r[0] == c[0]).unwrap();
        let payload = profile_payload(&pr);
        assert_eq!(payload, expected[1], "payload mismatch for {}", c[0]);
        assert_eq!(hash(Some(&payload)), expected[2], "hash mismatch for {}", c[0]);
    }
}

#[test]
fn log_parse_matches_aosp() {
    let results = rows("results_codec_parse.tsv");
    let mut checked = 0;
    for r in &results {
        let corpus_line = fixture("corpus_codec_parse.tsv");
        let line = corpus_line
            .lines()
            .find(|l| l.starts_with(&format!("{}\t", r[0])))
            .unwrap();
        let c: Vec<&str> = line.split('\t').collect();
        let actual = match parse_log_payload(c[2], c[1]) {
            Ok(log) => format!("OK:{}", log_payload(&log)),
            Err(e) => format!("ERR:{}", e),
        };
        assert_eq!(actual, r[1], "parse mismatch for {}", r[0]);
        checked += 1;
    }
    assert_eq!(checked, results.len());
}

#[test]
fn settings_parse_matches_aosp() {
    let results = rows("results_codec_parse_settings.tsv");
    for r in &results {
        let corpus = fixture("corpus_codec_parse_settings.tsv");
        let line = corpus
            .lines()
            .find(|l| l.starts_with(&format!("{}\t", r[0])))
            .unwrap();
        let c: Vec<&str> = line.split('\t').collect();
        let local = match c.get(3).map(|s| *s).unwrap_or("-") {
            "-" => None,
            spec => {
                let m: std::collections::HashMap<&str, &str> =
                    spec.split(',').map(|kv| kv.split_once('=').unwrap()).collect();
                Some(CycleSettings {
                    is_onboarding_completed: m["isOnboardingCompleted"].parse().unwrap(),
                    is_app_lock_enabled: m["isAppLockEnabled"].parse().unwrap(),
                    lock_timeout_minutes: m["lockTimeoutMinutes"].parse().unwrap(),
                    is_discreet_notification_mode: m["isDiscreetNotificationMode"].parse().unwrap(),
                    remind_period: m["remindPeriod"].parse().unwrap(),
                    remind_fertile: m["remindFertile"].parse().unwrap(),
                    remind_daily_log: m["remindDailyLog"].parse().unwrap(),
                    ..Default::default()
                })
            }
        };
        let actual = match parse_settings_payload(c[2], c[1], local.as_ref()) {
            Ok(s) => format!(
                "OK:{}|preserved:oc={},al={},to={},dm={},rp={},rf={},rd={}",
                settings_payload(&s), s.is_onboarding_completed, s.is_app_lock_enabled,
                s.lock_timeout_minutes, s.is_discreet_notification_mode,
                s.remind_period, s.remind_fertile, s.remind_daily_log
            ),
            Err(e) => format!("ERR:{}", e),
        };
        assert_eq!(actual, r[1], "settings parse mismatch for {}", r[0]);
    }
}

#[test]
fn profile_parse_matches_aosp() {
    let results = rows("results_codec_parse_profile.tsv");
    for r in &results {
        let corpus = fixture("corpus_codec_parse_profile.tsv");
        let line = corpus
            .lines()
            .find(|l| l.starts_with(&format!("{}\t", r[0])))
            .unwrap();
        let c: Vec<&str> = line.split('\t').collect();
        let local = match c.get(3).map(|s| *s).unwrap_or("-") {
            "-" => None,
            spec => {
                let m: std::collections::HashMap<&str, &str> =
                    spec.split(',').map(|kv| kv.split_once('=').unwrap()).collect();
                Some(OnboardingProfile {
                    is_completed: m["isCompleted"].parse().unwrap(),
                    completed_at_timestamp: m["completedAtTimestamp"].parse().unwrap(),
                    is_age_verified: m["isAgeVerified"].parse().unwrap(),
                    is_terms_agreed: m["isTermsAgreed"].parse().unwrap(),
                    account_mode: m["accountMode"].to_string(),
                    remind_fertile_window: m["remindFertileWindow"].parse().unwrap(),
                    ..Default::default()
                })
            }
        };
        let actual = match parse_profile_payload(c[2], c[1], local.as_ref()) {
            Ok(p) => format!(
                "OK:{}|preserved:c={},ts={},av={},ta={},am={},rfw={}",
                profile_payload(&p), p.is_completed, p.completed_at_timestamp,
                p.is_age_verified, p.is_terms_agreed, p.account_mode, p.remind_fertile_window
            ),
            Err(e) => format!("ERR:{}", e),
        };
        assert_eq!(actual, r[1], "profile parse mismatch for {}", r[0]);
    }
}

#[test]
fn hash_null_matches_aosp() {
    let results = rows("results_codec_hashnull.tsv");
    assert_eq!(hash(None), results[0][1]);
}

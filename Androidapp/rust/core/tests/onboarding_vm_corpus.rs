//! JVM-parity corpus for the onboarding step handlers + completion mapping.
//! fixtures/onboarding_vm_corpus.jsonl was generated 2026-11-02 (today fixed
//! to 2026-11-02) by compiling the verbatim Kotlin bodies of
//! OnboardingViewModel handlers, PeriodDateConsistency, PeriodEpisodeEntry,
//! CycleRepository validators and the completeOnboarding field mapping
//! (kotlinc/JVM) and running the 20 scenarios replayed here.

use chrono::NaiveDate;
use cyclecare_core::onboarding_complete::{complete_onboarding, CompletionOutcome};
use cyclecare_core::onboarding_vm::{OnboardingCompletionParams, OnboardingState};

fn d(s: &str) -> NaiveDate {
    NaiveDate::parse_from_str(s, "%Y-%m-%d").unwrap()
}
fn today() -> NaiveDate {
    d("2026-11-02")
}

fn enc(s: Option<&str>) -> String {
    match s {
        None => "~".to_string(),
        Some(v) => v
            .replace('%', "%25")
            .replace('|', "%7C")
            .replace('\n', "%0A"),
    }
}

fn snap(id: &str, s: &OnboardingState, c: Option<&CompletionOutcome>) -> String {
    let cycle = s.compute_cycle(today());
    let params: Option<OnboardingCompletionParams> =
        c.map(|_| s.completion_params(today()));
    let mut f: Vec<String> = Vec::new();
    f.push(id.to_string());
    f.push(s.step.to_string());
    f.push(enc(Some(&s.selected_goals.join(","))));
    f.push(enc(s.selected_exact_date.map(|x| x.to_string()).as_deref()));
    f.push(enc(s.selected_approximate_chip.as_deref()));
    f.push(enc(s.selected_period_end_date.map(|x| x.to_string()).as_deref()));
    f.push(enc(Some(&s.selected_period_duration_option)));
    f.push(s.period_duration_days.to_string());
    f.push(s.is_period_ongoing.to_string());
    f.push(s.period_end_date_confirmed.to_string());
    f.push(enc(s.period_date_dialog_message.as_deref()));
    f.push(enc(Some(&s.selected_cycle_option)));
    f.push(s.cycle_length_days.to_string());
    f.push(enc(Some(&cycle.period_start_choice)));
    f.push(cycle.effective_last_period_date.to_string());
    f.push(cycle.effective_period_end_date.to_string());
    f.push(cycle.has_cycle_start_estimate.to_string());
    f.push(enc(s.confirmed_period_end_date().map(|x| x.to_string()).as_deref()));
    f.push(s.still_ongoing_current_day(today()).to_string());
    f.push(enc(Some(&s.selected_typical_symptoms.join(","))));
    f.push(enc(Some(&s.has_completed_deep_baseline.to_string())));
    f.push(enc(Some(
        &s.dynamic_answers
            .iter()
            .map(|(k, v)| format!("{}={}", k, v.join("+")))
            .collect::<Vec<_>>()
            .join(";"),
    )));
    let _ = params;
    f.push(enc(c.map(|o| o.profile.last_period_date_string.as_str())));
    f.push(enc(c.map(|o| o.profile.last_period_end_date_string.as_str())));
    f.push(enc(c.map(|o| o.profile.approximate_period_choice.as_str())));
    f.push(enc(c.map(|o| o.profile.cycle_length_option.as_str())));
    f.push(c.map(|o| o.profile.cycle_length_days.to_string()).unwrap_or_else(|| "~".into()));
    f.push(c.map(|o| o.profile.period_duration_days.to_string()).unwrap_or_else(|| "~".into()));
    f.push(enc(c.map(|o| o.profile.selected_goals_csv.as_str())));
    f.push(enc(c.map(|o| o.profile.typical_symptoms_csv.as_str())));
    f.push(c.map(|o| o.profile.remind_fertile_window.to_string()).unwrap_or_else(|| "~".into()));
    f.push(enc(c.map(|o| o.settings_patch.last_period_start_date.as_str())));
    f.push(c.map(|o| o.settings_patch.average_cycle_length.to_string()).unwrap_or_else(|| "~".into()));
    f.push(c.map(|o| o.settings_patch.period_duration.to_string()).unwrap_or_else(|| "~".into()));
    f.push(c.map(|o| o.settings_patch.remind_fertile.to_string()).unwrap_or_else(|| "~".into()));
    f.push(c.map(|o| o.settings_patch.is_app_lock_enabled.to_string()).unwrap_or_else(|| "~".into()));
    f.push(enc(c.map(|o| match o.stored_period_range {
        Some((a, b)) => format!("{}..{}", a, b),
        None => "~".to_string(),
    }).as_deref()));
    f.join("|")
}

fn complete(s: &OnboardingState, enable_lock: bool) -> CompletionOutcome {
    complete_onboarding(&s.completion_params(today()), "PRIVATE", 1_762_000_000_000, false, enable_lock)
}

fn replay(id: &str) -> String {
    let t = today();
    let dd = d("2026-10-20");
    match id {
        "s01" => {
            let mut s = OnboardingState::new();
            s.select_approximate_chip("Today");
            snap(id, &s, None)
        }
        "s02" => {
            let mut s = OnboardingState::new();
            s.select_approximate_chip("A few days ago");
            snap(id, &s, None)
        }
        "s03" => {
            let mut s = OnboardingState::new();
            s.select_approximate_chip("About two weeks ago");
            snap(id, &s, None)
        }
        "s04" => {
            let mut s = OnboardingState::new();
            s.select_approximate_chip("I don\u{2019}t know");
            snap(id, &s, None)
        }
        "s05" => {
            let mut s = OnboardingState::new();
            s.set_exact_date(dd, t);
            snap(id, &s, None)
        }
        "s06" => {
            let mut s = OnboardingState::new();
            s.set_exact_date(dd, t);
            s.set_exact_end_date(d("2026-10-24"), t);
            s.set_exact_date(d("2026-10-25"), t);
            snap(id, &s, None)
        }
        "s07" => {
            let mut s = OnboardingState::new();
            s.set_exact_date(dd, t);
            s.set_exact_end_date(d("2026-10-24"), t);
            snap(id, &s, None)
        }
        "s08" => {
            let mut s = OnboardingState::new();
            s.set_exact_date(d("2026-10-10"), t);
            s.set_exact_end_date(d("2026-10-19"), t);
            snap(id, &s, None)
        }
        "s09" => {
            let mut s = OnboardingState::new();
            s.set_exact_date(d("2026-10-10"), t);
            s.set_exact_end_date(d("2026-10-19"), t);
            s.confirm_period_end_date();
            snap(id, &s, None)
        }
        "s10" => {
            let mut s = OnboardingState::new();
            s.set_exact_date(d("2026-10-10"), t);
            s.set_exact_end_date(d("2026-10-09"), t);
            snap(id, &s, None)
        }
        "s11" => {
            let mut s = OnboardingState::new();
            s.select_approximate_chip("A few days ago");
            s.select_period_duration_option("4 days", 4, false, t);
            snap(id, &s, None)
        }
        "s12" => {
            let mut s = OnboardingState::new();
            s.select_approximate_chip("Today");
            let day = s.still_ongoing_current_day(t);
            s.select_period_duration_option("Still ongoing", day, true, t);
            snap(id, &s, None)
        }
        "s13" => {
            let mut s = OnboardingState::new();
            s.select_approximate_chip("About two weeks ago");
            snap(id, &s, None)
        }
        "s14" => {
            let mut s = OnboardingState::new();
            s.toggle_goal("Track my periods");
            s.toggle_goal("Understand my cycle");
            s.set_exact_date(dd, t);
            s.set_exact_end_date(d("2026-10-24"), t);
            s.select_cycle_option("28 days", 28);
            s.toggle_typical_symptom("Cramps");
            s.toggle_typical_symptom("Bloating");
            s.select_typical_energy("Steady");
            s.select_typical_sleep("Good");
            s.set_remind_fertile(true);
            s.is_cloud_sync_allowed = true;
            s.toggle_dynamic_answer("q1", "a", true);
            s.toggle_dynamic_answer("q1", "b", true);
            let c = complete(&s, false);
            snap(id, &s, Some(&c))
        }
        "s15" => {
            let mut s = OnboardingState::new();
            s.select_approximate_chip("I don\u{2019}t know");
            let c = complete(&s, false);
            snap(id, &s, Some(&c))
        }
        "s16" => {
            let mut s = OnboardingState::new();
            s.select_approximate_chip("About a week ago");
            s.select_period_duration_option("1 days", 1, false, t);
            let c = complete(&s, false);
            snap(id, &s, Some(&c))
        }
        "s17" => {
            let mut s = OnboardingState::new();
            s.set_exact_date(dd, t);
            s.set_exact_end_date(d("2026-11-05"), t);
            snap(id, &s, None)
        }
        "s18" => {
            let mut s = OnboardingState::new();
            s.toggle_dynamic_answer("q", "a", false);
            s.toggle_dynamic_answer("q", "b", false);
            s.toggle_dynamic_answer("m", "x", true);
            s.toggle_dynamic_answer("m", "y", true);
            s.toggle_dynamic_answer("m", "x", true);
            snap(id, &s, None)
        }
        "s19" => {
            let mut s = OnboardingState::new();
            s.set_exact_date(dd, t);
            s.select_approximate_chip("About a week ago");
            s.set_exact_date(d("2026-10-21"), t);
            snap(id, &s, None)
        }
        "s20" => {
            let mut s = OnboardingState::new();
            s.select_approximate_chip("Today");
            let day = s.still_ongoing_current_day(t);
            s.select_period_duration_option("Still ongoing", day, true, t);
            let c = complete(&s, true);
            snap(id, &s, Some(&c))
        }
        other => panic!("unknown scenario {other}"),
    }
}

#[test]
fn onboarding_vm_and_completion_match_the_jvm_row_for_row() {
    let corpus = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/fixtures/onboarding_vm_corpus.jsonl"
    ))
    .expect("corpus fixture");
    let mut checked = 0;
    for line in corpus.lines() {
        if line.trim().is_empty() {
            continue;
        }
        let id = line.split('|').next().unwrap().to_string();
        let actual = replay(&id);
        assert_eq!(actual, line, "scenario {id} drifted from the JVM row");
        checked += 1;
    }
    assert_eq!(checked, 20, "expected 20 corpus rows");
}

//! JVM-executed oracle replay for settings_core (see fixtures README).
use cyclecare_core::settings_core::*;

#[test]
fn settings_core_matches_jvm_on_probe_corpus() {
    let expected = include_str!("fixtures/settings_core/jvm_expected.txt");
    let mut lines = expected.lines();

    // --- 15 bounded_conflict_preview inputs, identical to Probe.kt ---
    let big = format!("{{\"big\":\"{}\"}}", "x".repeat(2000));
    let multi = format!(
        "{{{}}}",
        (1..=12)
            .map(|i| format!("\"k{:02}\":\"{}\"", i, "y".repeat(120)))
            .collect::<Vec<_>>()
            .join(",")
    );
    let inputs: Vec<String> = vec![
        r#"{"mood":"HAPPY","flow":"light_flow"}"#.into(),
        r#"{"owner":"x","user_id":"y"}"#.into(),
        r#"{"note":"NULL"}"#.into(),
        r#"{"note":null}"#.into(),
        r#"{"log":{"a":1}}"#.into(),
        "not json{".into(),
        r#"{"k01":1,"k02":2,"k03":3,"k04":4,"k05":5,"k06":6,"k07":7,"k08":8,"k09":9,"k10":10,"k11":11,"k12":12,"k13":13,"k14":14}"#.into(),
        format!("{{\"story\":\"{}\"}}", "a".repeat(150)),
        r#"{"cramps":true,"pain":7,"temp":36.6}"#.into(),
        r#"{"sleep_quality":"VERY_GOOD"}"#.into(),
        format!("{{\"emoji\":\"{}\"}}", "\u{1F600}".repeat(61)),
        "{}".into(),
        big,
        "[1,2,3]".into(),
        multi,
    ];
    for input in &inputs {
        let expected_line = lines.next().expect("corpus short (previews)");
        let actual = bounded_conflict_preview(input).replace('\n', "\\n");
        assert_eq!(actual, expected_line, "preview mismatch for {input:?}");
    }
    assert_eq!(lines.next(), Some("---gates---"));

    // --- 9 conflict_review_gates rows ---
    let gate_cases: Vec<(&str, &str, Option<&str>, bool, i64, Option<&str>)> = vec![
        ("settings", "UPSERT", Some(r#"{"a":1}"#), false, 5, Some(r#"{"b":2}"#)),
        ("profile", "UPSERT", None, false, 5, Some(r#"{"b":2}"#)),
        ("2026-11-01", "REMOTE", None, false, 5, Some(r#"{"b":2}"#)),
        ("2026-11-01", "upsert", Some("  "), true, 5, None),
        ("2026-11-02", "LOCAL", Some("{}"), false, 0, Some(r#"{"b":2}"#)),
        ("2026-11-03", "LOCAL", Some("{}"), false, 5, Some("{}")),
        ("2026-11-04", "LOCAL", Some("{}"), false, 5, Some("[1,2]")),
        ("2026-11-05", "LOCAL", Some("{}"), false, 5, Some("junk{{")),
        ("2026-11-06", "REMOTE", None, false, 5, Some(r#"{"b":2}"#)),
    ];
    for (rid, lk, lp, tomb, rev, rp) in &gate_cases {
        let g = conflict_review_gates(rid, lk, *lp, *tomb, *rev, *rp);
        let actual = format!(
            "{}|{}|{}|{}",
            g.local_missing, g.keep_disabled, g.remote_valid, g.record_label
        );
        assert_eq!(actual, lines.next().expect("corpus short (gates)"), "gates {rid}");
    }
    assert_eq!(lines.next(), Some("---cycleLength---"));

    // --- 10 cycleLengthIsSet rows ---
    let options: Vec<Option<&str>> = vec![
        Some("28 days"),
        Some("unknown"),
        Some("I don't know"),
        Some("I don\u{2019}t know"),
        Some("It changes"),
        Some("  30 Days  "),
        Some(""),
        None,
        Some("UNKNOWN"),
        Some("my cycle changes a lot"),
    ];
    for o in &options {
        assert_eq!(
            cycle_length_is_set(*o).to_string(),
            lines.next().expect("corpus short (cycleLength)"),
            "cycleLengthIsSet {o:?}"
        );
    }
    assert_eq!(lines.next(), Some("---pinDraft---"));

    // --- 7 pin draft rows ---
    for raw in ["12ab34", "12345", "\u{0661}\u{0662}\u{0663}\u{0664}\u{0665}", "a", "", "\u{1D7D8}\u{1D7D8}\u{1D7D8}\u{1D7D8}", "12 3"] {
        assert_eq!(
            pin_draft_filter(raw),
            lines.next().expect("corpus short (pinDraft)"),
            "pinDraft {raw:?}"
        );
    }
    assert!(lines.next().is_none(), "corpus longer than sweep");
}

#[test]
fn backup_status_and_stepper_rules() {
    assert_eq!(
        backup_status_message(false, true),
        "Online backup is off in this version. Your records stay on this device."
    );
    assert_eq!(backup_status_message(true, true), "Everything looks good.");
    assert_eq!(
        backup_status_message(true, false),
        "Backup is paused or needs attention. CycleCare never overwrites one of your records without asking you first."
    );
    assert!(!show_last_sync_message(false));
    assert!(show_last_sync_message(true));
    assert!(!show_check_again_button(false, false));
    assert!(!show_check_again_button(true, true));
    assert!(show_check_again_button(true, false));
    // Stepper guards: boundary no-ops, interior moves.
    assert_eq!(decrease_cycle_length(21), None);
    assert_eq!(decrease_cycle_length(22), Some(21));
    assert_eq!(increase_cycle_length(45), None);
    assert_eq!(increase_cycle_length(44), Some(45));
    assert_eq!(decrease_period_duration(2), None);
    assert_eq!(decrease_period_duration(3), Some(2));
    assert_eq!(increase_period_duration(10), None);
    assert_eq!(increase_period_duration(9), Some(10));
    assert_eq!(pin_draft_filter("12ab"), "12");
    assert!(is_complete_pin("1234"));
    assert!(!is_complete_pin("123"));
}

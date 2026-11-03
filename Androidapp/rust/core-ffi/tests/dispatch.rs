//! Dispatcher-level tests for the cycle_screen_state contract (pure Rust
//! side of the JNI envelope; no JVM involved).

#[test]
fn cycle_screen_state_envelope_anchored() {
    let out = cyclecare_core::dispatch_for_test(
        "cycle_screen_state",
        r#"{"settings":{"lastPeriodStartDate":"2026-10-05","averageCycleLength":28,"periodDuration":5,"lutealPhaseDays":14},"dateChoice":"exact","cycleLengthChoice":"28","today":"2026-10-19","selectedDate":"2026-10-26"}"#,
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    let ok = &v["ok"];
    assert_eq!(ok["hasCycleAnchor"], true);
    assert_eq!(ok["daysUntilNextPeriod"], 14);
    assert_eq!(ok["daysUntilOvulation"], 27);
    assert_eq!(ok["selectedDayOfCycleText"], "22");
    assert_eq!(ok["daysUntilPeriodFromSelected"], 6);
    assert_eq!(ok["selectedDayTitle"], "Luteal Phase");
    assert_eq!(ok["selectedDatePrediction"]["phase"], "Luteal");
    assert_eq!(ok["selectedDatePrediction"]["phaseSubtitle"], "Nurture & Balance");
    assert_eq!(ok["selectedDatePrediction"]["phaseColorHex"], 0xFF695873u32);
    assert_eq!(ok["todayPrediction"]["phase"], "Ovulation");
    assert!(ok["disclaimer"].as_str().unwrap().starts_with("Calendar dates are estimates"));
}

#[test]
fn cycle_screen_state_envelope_no_anchor() {
    let out = cyclecare_core::dispatch_for_test(
        "cycle_screen_state",
        r#"{"settings":{"lastPeriodStartDate":"2026-10-05","averageCycleLength":28,"periodDuration":5,"lutealPhaseDays":14},"dateChoice":"","cycleLengthChoice":"28","today":"2026-10-19","selectedDate":"2026-10-26"}"#,
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    let ok = &v["ok"];
    assert_eq!(ok["hasCycleAnchor"], false);
    assert!(ok["todayPrediction"].is_null());
    assert_eq!(ok["selectedDayOfCycleText"], "\u{2014}");
    assert_eq!(ok["selectedDayTitle"], "Add a start date for estimates");
    assert!(ok["disclaimer"].as_str().unwrap().starts_with("No cycle start date is saved"));
}

#[test]
fn cycle_screen_state_bad_date_is_typed_error() {
    let out = cyclecare_core::dispatch_for_test(
        "cycle_screen_state",
        r#"{"settings":{},"today":"nope","selectedDate":"2026-10-26"}"#,
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert!(v["error"].as_str().unwrap().contains("today"));
}

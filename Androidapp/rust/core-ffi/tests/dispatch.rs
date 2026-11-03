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
    assert_eq!(ok["selectedDatePrediction"]["phase"], "LUTEAL");
    assert_eq!(ok["selectedDatePrediction"]["phaseSubtitle"], "Nurture & Balance");
    assert_eq!(ok["selectedDatePrediction"]["phaseColorHex"], 0xFF695873u32);
    assert_eq!(ok["todayPrediction"]["phase"], "OVULATION");
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
        r#"{"settings":{"lastPeriodStartDate":"2026-10-05","averageCycleLength":28,"periodDuration":5,"lutealPhaseDays":14},"today":"nope","selectedDate":"2026-10-26"}"#,
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert!(v["error"].as_str().unwrap().contains("today"));
}

#[test]
fn cycle_screen_state_missing_settings_field_is_typed_error() {
    let out = cyclecare_core::dispatch_for_test(
        "cycle_screen_state",
        r#"{"settings":{"lastPeriodStartDate":"2026-10-05","averageCycleLength":28,"periodDuration":5},"today":"2026-10-19","selectedDate":"2026-10-26"}"#,
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert!(v["error"].as_str().unwrap().contains("lutealPhaseDays"));
}

#[test]
fn cycle_screen_state_out_of_range_is_typed_error() {
    let out = cyclecare_core::dispatch_for_test(
        "cycle_screen_state",
        r#"{"settings":{"lastPeriodStartDate":"2026-10-05","averageCycleLength":99999999999,"periodDuration":5,"lutealPhaseDays":14},"today":"2026-10-19","selectedDate":"2026-10-26"}"#,
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert!(v["error"].as_str().unwrap().contains("averageCycleLength"));
}

fn month_payload(month: &str) -> String {
    format!(
        r#"{{"settings":{{"lastPeriodStartDate":"2026-10-05","averageCycleLength":28,"periodDuration":5,"lutealPhaseDays":14}},"dateChoice":"exact","cycleLengthChoice":"28","today":"2026-11-03","month":"{month}"}}"#
    )
}

#[test]
fn cycle_month_predictions_valid_month() {
    let out = cyclecare_core::dispatch_for_test("cycle_month_predictions", &month_payload("2026-11"));
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    let ok = &v["ok"];
    assert_eq!(ok["month"], "2026-11");
    assert_eq!(ok["hasCycleAnchor"], true);
    let preds = ok["predictions"].as_array().unwrap();
    assert_eq!(preds.len(), 30); // exactly the grid cells; neighbors never leave the month
    assert_eq!(preds[0]["date"], "2026-11-01");
    assert_eq!(preds[29]["date"], "2026-11-30");
    // entries carry the full prediction object with the Kotlin enum name
    let p = &preds[0]["prediction"];
    assert_eq!(p["date"], "2026-11-01");
    assert!(["MENSTRUAL", "FOLLICULAR", "OVULATION", "LUTEAL"].contains(&p["phase"].as_str().unwrap()));
}

#[test]
fn cycle_month_predictions_no_anchor_empty() {
    let payload = month_payload("2026-11").replace(r#""dateChoice":"exact""#, r#""dateChoice":"I don't know""#);
    let out = cyclecare_core::dispatch_for_test("cycle_month_predictions", &payload);
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert_eq!(v["ok"]["hasCycleAnchor"], false);
    assert_eq!(v["ok"]["predictions"].as_array().unwrap().len(), 0);
}

#[test]
fn cycle_month_predictions_bad_month_typed_error() {
    let out = cyclecare_core::dispatch_for_test("cycle_month_predictions", &month_payload("2026-13"));
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert!(v["error"].as_str().unwrap().contains("month"));
    let out2 = cyclecare_core::dispatch_for_test("cycle_month_predictions", &month_payload("Nov 2026"));
    let v2: serde_json::Value = serde_json::from_str(&out2).unwrap();
    assert!(v2["error"].as_str().unwrap().contains("month"));
}

#[test]
fn cycle_month_predictions_strict_settings() {
    let payload = month_payload("2026-11").replace(r#""averageCycleLength":28"#, r#""averageCycleLength":"28""#);
    let out = cyclecare_core::dispatch_for_test("cycle_month_predictions", &payload);
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert!(v["error"].as_str().unwrap().contains("averageCycleLength"));
}

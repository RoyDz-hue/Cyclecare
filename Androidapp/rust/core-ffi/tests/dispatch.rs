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

#[test]
fn verify_pin_contract_matrix() {
    // No stored PIN -> rejected (mirrors Kotlin readSecureString null -> false).
    let out = cyclecare_core::dispatch_for_test("verify_pin", r#"{"stored":null,"candidate":"1234","device_id":"dev"}"#);
    assert_eq!(serde_json::from_str::<serde_json::Value>(&out).unwrap()["ok"], "rejected");
    // Legacy plaintext match -> legacy_migrate (caller re-encodes, mirroring pin = candidate).
    let out = cyclecare_core::dispatch_for_test("verify_pin", r#"{"stored":"1234","candidate":"1234","device_id":"dev"}"#);
    assert_eq!(serde_json::from_str::<serde_json::Value>(&out).unwrap()["ok"], "legacy_migrate");
    // Legacy plaintext mismatch -> rejected.
    let out = cyclecare_core::dispatch_for_test("verify_pin", r#"{"stored":"1234","candidate":"9999","device_id":"dev"}"#);
    assert_eq!(serde_json::from_str::<serde_json::Value>(&out).unwrap()["ok"], "rejected");
    // Legacy salt-hash match -> legacy_migrate. sha256("cyclecare_salt_1234") known vector:
    let legacy_salt = "30768ee0714152082cf533609fdd9ec34a5f31b44481216d501a999f074a7168";
    let payload = format!(r#"{{"stored":"{}","candidate":"1234","device_id":"dev"}}"#, legacy_salt);
    let out = cyclecare_core::dispatch_for_test("verify_pin", &payload);
    assert_eq!(serde_json::from_str::<serde_json::Value>(&out).unwrap()["ok"], "legacy_migrate");
}

#[test]
fn encode_then_verify_roundtrip_and_bad_salt() {
    // Deterministic salt -> deterministic stored string; must verify as current.
    let salt = "0102030405060708090a0b0c0d0e0f10";
    let payload = format!(r#"{{"pin":"1234","salt_hex":"{}"}}"#, salt);
    let out = cyclecare_core::dispatch_for_test("encode_pin", &payload);
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    let stored = v["ok"].as_str().unwrap().to_string();
    assert!(stored.starts_with("pbkdf2:150000:"));
    assert!(stored.contains(salt));
    let payload = format!(r#"{{"stored":"{}","candidate":"1234","device_id":"dev"}}"#, stored);
    let out = cyclecare_core::dispatch_for_test("verify_pin", &payload);
    assert_eq!(serde_json::from_str::<serde_json::Value>(&out).unwrap()["ok"], "current");
    let payload = format!(r#"{{"stored":"{}","candidate":"0000","device_id":"dev"}}"#, stored);
    let out = cyclecare_core::dispatch_for_test("verify_pin", &payload);
    assert_eq!(serde_json::from_str::<serde_json::Value>(&out).unwrap()["ok"], "rejected");
    // Malformed salts: wrong length, non-hex, non-ASCII (must not panic).
    for bad in ["0102", "zz02030405060708090a0b0c0d0e0f10", "0102030405060708090a0b0c0d0e0f1", "éééééééééééééééé"] {
        let payload = format!(r#"{{"pin":"1234","salt_hex":"{}"}}"#, bad);
        let out = cyclecare_core::dispatch_for_test("encode_pin", &payload);
        assert!(serde_json::from_str::<serde_json::Value>(&out).unwrap()["error"].is_string(), "bad salt {:?} must error", bad);
    }
}

#[test]
fn validate_period_dates_contract() {
    let cases = [
        (r#"{"start":"2026-10-20","end":"2026-10-24"}"#, true),
        (r#"{"start":"2026-10-24","end":"2026-10-20"}"#, false), // end before start
        (r#"{"start":"2026-10-20","end":"2026-10-20"}"#, true),  // same day ok
        (r#"{"start":null,"end":"2026-10-24"}"#, false),         // null start
        (r#"{"start":"2026-10-20","end":null}"#, false),         // null end
        (r#"{"start":"not-a-date","end":"2026-10-24"}"#, false), // unparseable
        (r#"{"start":"2026-10-20","end":"+00123456-01-01"}"#, true),  // JVM accepts +5..10 digit year; far-future end valid
        (r#"{"start":"2026-10-20","end":"12345678-01-01"}"#, false), // unsigned 8-digit year: probe-locked reject
        // Full Java LocalDate domain (years +/-999999999): compared by epoch
        // millis, not reported false (reviewer: Java/Chrono domain gap).
        (r#"{"start":"2026-10-20","end":"+999999999-12-31"}"#, true),   // Java MAX end
        (r#"{"start":"+999999999-12-31","end":"+999999999-12-31"}"#, true), // equal MAX pair
        (r#"{"start":"+999999999-12-31","end":"2026-10-20"}"#, false),  // MAX start after ordinary end
        (r#"{"start":"-999999999-01-01","end":"+999999999-12-31"}"#, true), // Java MIN..MAX span
        (r#"{"start":"-999999999-01-01","end":"-999999998-12-31"}"#, true), // negative boundary ordering
        (r#"{"start":"-999999998-12-31","end":"-999999999-01-01"}"#, false), // negative reversed
        (r#"{"start":"2026-10-20","end":"+999999999-13-40"}"#, false),  // calendar-invalid even in Java
        // Out of the Java domain entirely: not valid Java LocalDates.
        (r#"{"start":"2026-10-20","end":"+1000000000-01-01"}"#, false),  // beyond Java MAX year
        (r#"{"start":"-1000000000-01-01","end":"2026-10-20"}"#, false),  // beyond Java MIN year
        (r#"{"start":"-0000-01-01","end":"2026-10-20"}"#, false),        // negative zero rejected
    ];
    for (payload, want) in cases {
        let out = cyclecare_core::dispatch_for_test("validate_period_dates", payload);
        let reply = serde_json::from_str::<serde_json::Value>(&out).unwrap();
        assert_eq!(reply["ok"], want, "payload {payload}");
        assert_eq!(reply["supported"], true, "payload {payload}");
    }
}

#[test]
fn calculate_day_prediction_contract() {
    let settings = |last: &str, acl: i64, pd: i64, lpd: i64| {
        format!(
            r#"{{"averageCycleLength":{acl},"periodDuration":{pd},"lutealPhaseDays":{lpd},"lastPeriodStartDate":"{last}"}}"#
        )
    };
    let call = |date: &str, s: String| {
        let out = cyclecare_core::dispatch_for_test(
            "calculate_day_prediction",
            &format!(r#"{{"date":"{date}","settings":{s}}}"#),
        );
        serde_json::from_str::<serde_json::Value>(&out).unwrap()
    };

    // Typical 28/5/14 cycle anchored 2026-10-01:
    // ovulation day = 28-14 = 14, fertile = 10..=15.
    let r = call("2026-10-20", settings("2026-10-01", 28, 5, 14));
    let ok = &r["ok"];
    assert_eq!(ok["dayOfCycle"], 20);
    assert_eq!(ok["phase"], "LUTEAL");
    assert_eq!(ok["isPeriod"], false);
    assert_eq!(ok["isFertile"], false);
    assert_eq!(ok["isOvulation"], false);
    assert_eq!(ok["fertilityConfidence"], "Calendar Estimate");
    assert_eq!(ok["pregnancyChance"], "Not calculated from calendar dates");

    // Period day (day 3 of 5).
    let r = call("2026-10-03", settings("2026-10-01", 28, 5, 14));
    assert_eq!(r["ok"]["dayOfCycle"], 3);
    assert_eq!(r["ok"]["phase"], "MENSTRUAL");
    assert_eq!(r["ok"]["isPeriod"], true);

    // Ovulation day exactly.
    let r = call("2026-10-14", settings("2026-10-01", 28, 5, 14));
    assert_eq!(r["ok"]["dayOfCycle"], 14);
    assert_eq!(r["ok"]["phase"], "OVULATION");
    assert_eq!(r["ok"]["isOvulation"], true);
    assert_eq!(r["ok"]["isFertile"], true);

    // Fertile window edge (last fertile day).
    let r = call("2026-10-15", settings("2026-10-01", 28, 5, 14));
    assert_eq!(r["ok"]["isFertile"], true);
    assert_eq!(r["ok"]["isOvulation"], false);
    assert_eq!(r["ok"]["phase"], "OVULATION");

    // First follicular day after period (day 6, fertile starts at 10).
    let r = call("2026-10-06", settings("2026-10-01", 28, 5, 14));
    assert_eq!(r["ok"]["phase"], "FOLLICULAR");
    assert_eq!(r["ok"]["isPeriod"], false);
    assert_eq!(r["ok"]["isFertile"], false);

    // Clamps: cycle length 10 -> 21, duration 20 -> 10, luteal 30 -> 16.
    // clamped: ovulation = max(21-16, 10+2) = 12; fertile = max(8, 11)=11 ..= min(13,21)=13.
    let r = call("2026-10-12", settings("2026-10-01", 10, 20, 30));
    assert_eq!(r["ok"]["dayOfCycle"], 12);
    assert_eq!(r["ok"]["isOvulation"], true);
    assert_eq!(r["ok"]["isPeriod"], false); // day 12 > clamped duration 10

    // Before-anchor wrap: floorMod(-6, 28) = 22 -> day 23 (Kotlin floorMod).
    let r = call("2026-09-25", settings("2026-10-01", 28, 5, 14));
    assert_eq!(r["ok"]["dayOfCycle"], 23);

    // No anchor: blank lastPeriodStartDate -> authoritative null prediction.
    let r = call("2026-10-20", settings("", 28, 5, 14));
    assert!(r["ok"].is_null());

    // Unparseable stored anchor -> same null as the Kotlin route.
    let r = call("2026-10-20", settings("not-a-date", 28, 5, 14));
    assert!(r["ok"].is_null());

    // Malformed inputs produce no "ok" (bridge falls back, never a guess).
    let no_ok = |payload: &str| {
        let out = cyclecare_core::dispatch_for_test("calculate_day_prediction", payload);
        assert!(serde_json::from_str::<serde_json::Value>(&out).unwrap().get("ok").is_none(), "payload {payload}");
    };
    no_ok(r#"{"date":"2026-13-40","settings":{}}"#);
    no_ok(r#"{"date":"2026-10-20","settings":"nope"}"#);

    // Domain edges: Java-valid but chrono-unrepresentable anchor must FALL
    // BACK (the Kotlin route computes there); native must not claim ok:null.
    no_ok(&format!(r#"{{"date":"2026-10-20","settings":{}}}"#, settings("+999999999-12-31", 28, 5, 14)));
    no_ok(&format!(r#"{{"date":"2026-10-20","settings":{}}}"#, settings("-999999999-01-01", 28, 5, 14)));
    // Java-invalid anchor (negative zero rejected by Java) -> true
    // invalid/no-prediction: the Kotlin route's own null, so ok:null.
    let r = call("2026-10-20", settings("-0000-01-01", 28, 5, 14));
    assert!(r["ok"].is_null());

    // Per-field strictness: no silent 28/5/14 defaults, no truncation.
    no_ok(r#"{"date":"2026-10-20","settings":{"averageCycleLength":"28","periodDuration":5,"lutealPhaseDays":14,"lastPeriodStartDate":"2026-10-01"}}"#); // string numeric
    no_ok(r#"{"date":"2026-10-20","settings":{"averageCycleLength":28,"lutealPhaseDays":14,"lastPeriodStartDate":"2026-10-01"}}"#); // missing periodDuration
    no_ok(r#"{"date":"2026-10-20","settings":{"averageCycleLength":3000000000,"periodDuration":5,"lutealPhaseDays":14,"lastPeriodStartDate":"2026-10-01"}}"#); // out of Int32
    no_ok(r#"{"date":"2026-10-20","settings":{"averageCycleLength":28.5,"periodDuration":5,"lutealPhaseDays":14,"lastPeriodStartDate":"2026-10-01"}}"#); // non-integer
    no_ok(r#"{"date":"2026-10-20","settings":{"averageCycleLength":28,"periodDuration":5,"lutealPhaseDays":14,"lastPeriodStartDate":20261001}}"#); // non-string anchor
}

#[test]
fn evaluate_safety_contract() {
    let call = |payload: &str| {
        let out = cyclecare_core::dispatch_for_test("evaluate_safety", payload);
        serde_json::from_str::<serde_json::Value>(&out).unwrap()
    };
    let log = |flow: &str, sym: &str, vag: &str, uri: &str, pain: i64| {
        format!(
            r#"{{"log":{{"flowLevel":"{flow}","symptomsCsv":"{sym}","vaginalHealthCsv":"{vag}","urinaryHealthCsv":"{uri}","painIntensity":{pain}}}}}"#
        )
    };

    // Null log -> the Kotlin evaluate(null) route.
    let r = call(r#"{"log":null}"#);
    assert_eq!(r["ok"]["level"], "LOW");
    assert_eq!(r["ok"]["title"], "Routine health status");
    assert_eq!(r["ok"]["message"], "No acute symptoms are recorded.");
    assert_eq!(r["ok"]["actionAdvice"], "Continue tracking and seek care for severe or worsening symptoms.");
    assert_eq!(r["ok"]["isRedFlag"], false);

    // Urgent pair: fainting + unusually heavy.
    let r = call(&log("UNUSUALLY_HEAVY", "fainting", "", "", 0));
    assert_eq!(r["ok"]["level"], "URGENT");
    assert_eq!(r["ok"]["title"], "Urgent medical attention advised");
    assert_eq!(r["ok"]["message"], "Fainting and unusually heavy bleeding were recorded together.");
    assert_eq!(r["ok"]["isRedFlag"], true);

    // Urgent pair: heavy + severe pain (>= 8).
    let r = call(&log("UNUSUALLY_HEAVY", "", "", "", 8));
    assert_eq!(r["ok"]["level"], "URGENT");
    assert_eq!(r["ok"]["message"], "Unusually heavy bleeding and severe pain were recorded together.");

    // Fainting alone -> URGENT. Enum NAME, not Debug label.
    let r = call(&log("", "fainting", "", "", 0));
    assert_eq!(r["ok"]["level"], "URGENT");

    // Sores -> SEEK_PROFESSIONAL_ADVICE (this is the case the old
    // format!("{:?}") arm would have emitted as SeekProfessionalAdvice).
    let r = call(&log("", "", "sores_lesions", "", 0));
    assert_eq!(r["ok"]["level"], "SEEK_PROFESSIONAL_ADVICE");
    assert_eq!(r["ok"]["title"], "Professional advice recommended");
    assert_eq!(r["ok"]["isRedFlag"], true);

    // Heavy alone -> SEEK.
    let r = call(&log("UNUSUALLY_HEAVY", "", "", "", 0));
    assert_eq!(r["ok"]["level"], "SEEK_PROFESSIONAL_ADVICE");

    // Severe cramps symptom -> SEEK.
    let r = call(&log("", "severe_cramps", "", "", 2));
    assert_eq!(r["ok"]["level"], "SEEK_PROFESSIONAL_ADVICE");

    // Urinary pair -> SEEK; single urinary -> not the pair rule.
    let r = call(&log("", "", "", "urinary_burning,urinary_urgency", 0));
    assert_eq!(r["ok"]["level"], "SEEK_PROFESSIONAL_ADVICE");

    // Vaginal irritation -> REVIEW (name, not Debug).
    let r = call(&log("", "", "vaginal_itching", "", 0));
    assert_eq!(r["ok"]["level"], "REVIEW");
    assert_eq!(r["ok"]["title"], "Review recommended");
    assert_eq!(r["ok"]["isRedFlag"], false);

    // Symptoms but no combination -> MONITOR.
    let r = call(&log("", "headache", "", "", 0));
    assert_eq!(r["ok"]["level"], "MONITOR");
    assert_eq!(r["ok"]["title"], "Symptoms to monitor");

    // Pain 3..6 alone -> MONITOR; pain 7 -> SEEK.
    let r = call(&log("", "", "", "", 5));
    assert_eq!(r["ok"]["level"], "MONITOR");
    let r = call(&log("", "", "", "", 7));
    assert_eq!(r["ok"]["level"], "SEEK_PROFESSIONAL_ADVICE");

    // Empty log object -> LOW.
    let r = call(&log("", "", "", "", 0));
    assert_eq!(r["ok"]["level"], "LOW");

    // Competing-rule precedence (canonical order is load-bearing):
    // fainting+heavy beats BOTH heavy+pain>=8 and the later sores SEEK rule.
    let r = call(&log("UNUSUALLY_HEAVY", "fainting", "sores_lesions", "", 9));
    assert_eq!(r["ok"]["level"], "URGENT");
    assert_eq!(r["ok"]["message"], "Fainting and unusually heavy bleeding were recorded together.");

    // heavy+pain>=8 (rule 2) only wins when fainting is absent.
    let r = call(&log("UNUSUALLY_HEAVY", "", "sores_lesions", "", 9));
    assert_eq!(r["ok"]["message"], "Unusually heavy bleeding and severe pain were recorded together.");

    // CSV normalization parity: trim + lowercase + space->underscore.
    let r = call(&log("", " Fainting , Headache ", "", "", 0));
    assert_eq!(r["ok"]["level"], "URGENT"); // fainting detected despite case/space
    // flowLevel trim+uppercase normalization.
    let r = call(&log(" unusually_heavy ", "", "", "", 0));
    assert_eq!(r["ok"]["level"], "SEEK_PROFESSIONAL_ADVICE");
    // urinary ids written with spaces normalize to the underscore pair.
    let r = call(&log("", "", "", "urinary burning, urinary urgency", 0));
    assert_eq!(r["ok"]["level"], "SEEK_PROFESSIONAL_ADVICE");

    // Strict contract: wrong types / missing fields / non-object -> no ok.
    let no_ok = |payload: &str| {
        let out = cyclecare_core::dispatch_for_test("evaluate_safety", payload);
        assert!(serde_json::from_str::<serde_json::Value>(&out).unwrap().get("ok").is_none(), "payload {payload}");
    };
    no_ok(r#"{"log":"nope"}"#);
    no_ok(r#"{"log":{"flowLevel":"","symptomsCsv":"","vaginalHealthCsv":"","urinaryHealthCsv":"","painIntensity":"3"}}"#);
    no_ok(r#"{"log":{"flowLevel":"","symptomsCsv":"","vaginalHealthCsv":"","urinaryHealthCsv":""}}"#);
    no_ok(r#"{"log":{"flowLevel":5,"symptomsCsv":"","vaginalHealthCsv":"","urinaryHealthCsv":"","painIntensity":0}}"#);
    no_ok(r#"{"log":{"flowLevel":"","symptomsCsv":"","vaginalHealthCsv":"","urinaryHealthCsv":"","painIntensity":3000000000}}"#);
}

// flow_anchor_decision: tracking-write anchor policy ported from
// CycleRepository.saveDayLogBound. isAfter(start.plusDays(15)) is strict.

#[test]
fn flow_anchor_advances_after_15_days() {
    let out = cyclecare_core::dispatch_for_test(
        "flow_anchor_decision",
        r#"{"flowLevel":"HEAVY","date":"2026-10-21","priorAnchor":"2026-10-05"}"#,
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert_eq!(v["ok"]["update"], true);
    assert_eq!(v["ok"]["newAnchor"], "2026-10-21");
}

#[test]
fn flow_anchor_exactly_plus_15_does_not_advance() {
    let out = cyclecare_core::dispatch_for_test(
        "flow_anchor_decision",
        r#"{"flowLevel":"HEAVY","date":"2026-10-20","priorAnchor":"2026-10-05"}"#,
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert_eq!(v["ok"]["update"], false);
    assert!(v["ok"].get("newAnchor").is_none());
}

#[test]
fn flow_anchor_earlier_date_does_not_advance() {
    let out = cyclecare_core::dispatch_for_test(
        "flow_anchor_decision",
        r#"{"flowLevel":"LIGHT","date":"2026-10-10","priorAnchor":"2026-10-05"}"#,
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert_eq!(v["ok"]["update"], false);
}

#[test]
fn flow_anchor_non_listed_flow_never_advances() {
    let out = cyclecare_core::dispatch_for_test(
        "flow_anchor_decision",
        r#"{"flowLevel":"SPOTTING","date":"2027-01-01","priorAnchor":"2026-10-05"}"#,
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert_eq!(v["ok"]["update"], false);
    // Case-sensitivity is exact Kotlin membership: lowercase does not match.
    let out = cyclecare_core::dispatch_for_test(
        "flow_anchor_decision",
        r#"{"flowLevel":"heavy","date":"2027-01-01","priorAnchor":"2026-10-05"}"#,
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert_eq!(v["ok"]["update"], false);
}

#[test]
fn flow_anchor_blank_or_invalid_prior_advances() {
    // Kotlin runCatching{LocalDate.parse(...)}.getOrNull(): blank and
    // unparseable priors both read as no anchor, so the log date anchors.
    for prior in ["", "not-a-date"] {
        let payload = format!(
            r#"{{"flowLevel":"MODERATE","date":"2026-10-10","priorAnchor":"{prior}"}}"#
        );
        let out = cyclecare_core::dispatch_for_test("flow_anchor_decision", &payload);
        let v: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["ok"]["update"], true, "prior={prior:?}");
        assert_eq!(v["ok"]["newAnchor"], "2026-10-10");
    }
    let out = cyclecare_core::dispatch_for_test(
        "flow_anchor_decision",
        r#"{"flowLevel":"UNUSUALLY_HEAVY","date":"2026-10-10","priorAnchor":null}"#,
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert_eq!(v["ok"]["update"], true);
}

#[test]
fn flow_anchor_echoes_date_verbatim() {
    // The new anchor is the input dateString, never a reformat.
    let out = cyclecare_core::dispatch_for_test(
        "flow_anchor_decision",
        r#"{"flowLevel":"HEAVY","date":"2026-02-28","priorAnchor":"2026-01-01"}"#,
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert_eq!(v["ok"]["newAnchor"], "2026-02-28");
}

#[test]
fn flow_anchor_missing_fields_are_contract_breach() {
    // CycleDayLog fields are non-null: absent/wrong-typed input is a
    // fallback-triggering error, not a silent default.
    let out = cyclecare_core::dispatch_for_test(
        "flow_anchor_decision",
        r#"{"date":"2026-10-21","priorAnchor":"2026-10-05"}"#,
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert!(v.get("error").is_some());
    let out = cyclecare_core::dispatch_for_test(
        "flow_anchor_decision",
        r#"{"flowLevel":"HEAVY","date":20261021,"priorAnchor":"2026-10-05"}"#,
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert!(v.get("error").is_some());
}

#[test]
fn flow_anchor_malformed_log_date_errors() {
    // Kotlin's LocalDate.parse(log.dateString) throws in-transaction; the
    // arm errors so the bridge falls back to that behavior.
    let out = cyclecare_core::dispatch_for_test(
        "flow_anchor_decision",
        r#"{"flowLevel":"HEAVY","date":"2026-13-40","priorAnchor":"2026-10-05"}"#,
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert!(v.get("error").is_some());
}

// ===== M9 reviewer domain/overflow cases (anchor admission) =====
// Full-parser admission: java.time's domain (+/-999999999 years) is wider
// than chrono's (+/-262143). A Java-VALID anchor the native port cannot
// represent (or a step that overflows the range) is a typed fallback,
// never a fake countdown-0; a Java-INVALID anchor keeps hasCycleAnchor's
// raw non-blank semantics with null predictions, exactly the Kotlin
// runCatching{LocalDate.parse}=null route.

fn screen_payload(anchor: &str, today: &str, selected: &str) -> String {
    format!(
        r#"{{"settings":{{"lastPeriodStartDate":"{anchor}","averageCycleLength":28,"periodDuration":5,"lutealPhaseDays":14}},"dateChoice":"exact","cycleLengthChoice":"28","today":"{today}","selectedDate":"{selected}"}}"#
    )
}

#[test]
fn cycle_screen_state_java_valid_unrepresentable_anchor_is_typed_error() {
    // "+300000-01-01" is a valid java.time.LocalDate (year <= 999999999)
    // outside chrono's range: Kotlin computes real countdowns the native
    // port cannot reproduce -> typed fallback, never a silent countdown-0.
    let out = cyclecare_core::dispatch_for_test(
        "cycle_screen_state",
        &screen_payload("+300000-01-01", "2026-10-19", "2026-10-26"),
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert!(
        v["error"].as_str().unwrap().contains("anchor outside native range"),
        "{out}"
    );
    assert!(v["ok"].is_null(), "{out}");
}

#[test]
fn cycle_screen_state_java_invalid_anchor_keeps_anchor_without_predictions() {
    // "-0000-01-01" is rejected by java.time (negative-zero year): the
    // Kotlin route reads runCatching{LocalDate.parse} = null, so
    // hasCycleAnchor (raw non-blank) stays true while the countdown is 0
    // and every prediction is null. The disclaimer chain keys only on
    // (hasCycleAnchor, approximate) -> standard text, as in Kotlin.
    let out = cyclecare_core::dispatch_for_test(
        "cycle_screen_state",
        &screen_payload("-0000-01-01", "2026-10-19", "2026-10-26"),
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    let ok = &v["ok"];
    assert_eq!(ok["hasCycleAnchor"], true, "{out}");
    assert_eq!(ok["daysUntilNextPeriod"], 0, "{out}");
    assert!(ok["nextPeriodDate"].is_null(), "{out}");
    assert!(ok["todayPrediction"].is_null(), "{out}");
    assert!(ok["selectedDatePrediction"].is_null(), "{out}");
    assert_eq!(ok["selectedDayOfCycleText"], "\u{2014}", "{out}");
    assert!(
        ok["disclaimer"].as_str().unwrap().starts_with("Calendar dates are estimates"),
        "{out}"
    );
}

#[test]
fn cycle_screen_state_near_range_edge_step_overflow_is_typed_error_not_panic() {
    // Anchor and today both representable near chrono's MAX
    // (+262142-12-31, probed on this toolchain): the first
    // next-period step (anchor + 28d) leaves the native range. Kotlin's
    // wider LocalDate range still computes -> typed fallback via CHECKED
    // arithmetic; this call returning at all proves no panic.
    let out = cyclecare_core::dispatch_for_test(
        "cycle_screen_state",
        &screen_payload("+262142-12-20", "+262142-12-25", "+262142-12-26"),
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert!(
        v["error"].as_str().unwrap().contains("anchor outside native range"),
        "{out}"
    );
}

#[test]
fn cycle_screen_state_distant_future_today_overflow_is_typed_error_not_panic() {
    // Ordinary anchor, today near the range edge: Kotlin's while-loop
    // would run ~3.4M iterations and land past the native range; the O(1)
    // equivalent hits the same overflow through one checked add.
    let out = cyclecare_core::dispatch_for_test(
        "cycle_screen_state",
        &screen_payload("2026-10-05", "+262142-12-25", "2026-10-26"),
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert!(
        v["error"].as_str().unwrap().contains("anchor outside native range"),
        "{out}"
    );
}

#[test]
fn cycle_screen_state_ancient_anchor_o1_matches_loop_semantics() {
    // 1900 anchor: Kotlin's loop would run 1655 iterations. diff is an
    // exact 28-multiple (46312 = 1654*28), so the strictly-after rule
    // steps once more: next = 2026-11-16, 28 days out. Expected values
    // computed independently (date arithmetic), not captured.
    let out = cyclecare_core::dispatch_for_test(
        "cycle_screen_state",
        &screen_payload("1900-01-01", "2026-10-19", "2026-10-26"),
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    let ok = &v["ok"];
    assert_eq!(ok["nextPeriodDate"], "2026-11-16", "{out}");
    assert_eq!(ok["daysUntilNextPeriod"], 28, "{out}");
}

#[test]
fn cycle_screen_state_future_anchor_next_is_anchor() {
    // Anchor after today: Kotlin's loop body never runs (k=0) -> next is
    // the anchor itself.
    let out = cyclecare_core::dispatch_for_test(
        "cycle_screen_state",
        &screen_payload("2026-11-02", "2026-10-19", "2026-10-26"),
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    let ok = &v["ok"];
    assert_eq!(ok["nextPeriodDate"], "2026-11-02", "{out}");
    assert_eq!(ok["daysUntilNextPeriod"], 14, "{out}");
}

#[test]
fn cycle_month_predictions_java_valid_unrepresentable_anchor_is_typed_error() {
    let payload = month_payload("2026-11")
        .replace(r#""lastPeriodStartDate":"2026-10-05""#, r#""lastPeriodStartDate":"+300000-01-01""#);
    let out = cyclecare_core::dispatch_for_test("cycle_month_predictions", &payload);
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert!(
        v["error"].as_str().unwrap().contains("date outside native range"),
        "{out}"
    );
}

#[test]
fn cycle_month_predictions_java_invalid_anchor_keeps_anchor_null_predictions() {
    // Java-invalid anchor: hasCycleAnchor stays true and every grid date's
    // prediction is null (Kotlin getPredictionForDate per-date null).
    let payload = month_payload("2026-11")
        .replace(r#""lastPeriodStartDate":"2026-10-05""#, r#""lastPeriodStartDate":"-0000-01-01""#);
    let out = cyclecare_core::dispatch_for_test("cycle_month_predictions", &payload);
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    let ok = &v["ok"];
    assert_eq!(ok["hasCycleAnchor"], true, "{out}");
    let preds = ok["predictions"].as_array().unwrap();
    assert_eq!(preds.len(), 30, "{out}");
    assert_eq!(preds[0]["date"], "2026-11-01", "{out}");
    assert!(
        preds.iter().all(|p| p["prediction"].is_null()),
        "{out}"
    );
}

#[test]
fn cycle_month_predictions_range_edge_month_is_typed_error_not_panic() {
    // December of the native range's last year has no representable
    // next-month boundary; Kotlin's wider YearMonth still resolves it ->
    // typed fallback via checked arithmetic, never an unwrap panic.
    let out = cyclecare_core::dispatch_for_test("cycle_month_predictions", &month_payload("+262142-12"));
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert!(
        v["error"].as_str().unwrap().contains("date outside native range"),
        "{out}"
    );
}

// ===== M10 sync_profile_on_settings contract =====

#[test]
fn sync_profile_on_settings_contract_matrix() {
    // No stored profile -> no profile write.
    let out = cyclecare_core::dispatch_for_test(
        "sync_profile_on_settings",
        r#"{"hasProfile":false,"existingOption":null,"newAverage":30,"previousAverage":28}"#,
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert_eq!(v["ok"]["writeProfile"], false, "{out}");
    assert!(v["ok"]["cycleLengthOption"].is_null(), "{out}");
    // Changed average -> recompute from the NEW value (raw Int interpolation).
    let out = cyclecare_core::dispatch_for_test(
        "sync_profile_on_settings",
        r#"{"hasProfile":true,"existingOption":"28 days","newAverage":30,"previousAverage":28}"#,
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert_eq!(v["ok"]["writeProfile"], true, "{out}");
    assert_eq!(v["ok"]["cycleLengthOption"], "30 days", "{out}");
    // Unchanged average -> stored option verbatim, even a stale-looking one.
    let out = cyclecare_core::dispatch_for_test(
        "sync_profile_on_settings",
        r#"{"hasProfile":true,"existingOption":"21 days","newAverage":28,"previousAverage":28}"#,
    );
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert_eq!(v["ok"]["writeProfile"], true, "{out}");
    assert_eq!(v["ok"]["cycleLengthOption"], "21 days", "{out}");
}

#[test]
fn sync_profile_on_settings_strict_typing() {
    for bad in [
        r#"{"hasProfile":"yes","existingOption":null,"newAverage":30,"previousAverage":28}"#,
        r#"{"hasProfile":true,"existingOption":null,"newAverage":30,"previousAverage":28}"#,
        r#"{"hasProfile":true,"existingOption":"28 days","newAverage":"30","previousAverage":28}"#,
        r#"{"hasProfile":true,"existingOption":"28 days","newAverage":30}"#,
        r#"{"hasProfile":false,"existingOption":5,"newAverage":30,"previousAverage":28}"#,
    ] {
        let out = cyclecare_core::dispatch_for_test("sync_profile_on_settings", bad);
        let v: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert!(v["error"].is_string(), "expected typed error for {bad}: {out}");
        assert!(v["ok"].is_null(), "{out}");
    }
}

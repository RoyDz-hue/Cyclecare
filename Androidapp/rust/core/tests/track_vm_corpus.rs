//! JVM-locked corpus for the TrackScreen state port: the 12 rows printed by
//! the kotlinc 2.1.0/JRE 21 probe of the verbatim Kotlin semantics
//! (probe run 2026-11-02). Row labels r1..r12 match the probe output.

use cyclecare_core::track_log::CycleDayLog;
use cyclecare_core::track_vm::{OrderedSet, TrackDayState};

#[test]
fn reset_csv_semantics_match_jvm() {
    // r1: "cramps,,acne" -> cramps|acne (blank filtered, order kept)
    assert_eq!(OrderedSet::from_csv("cramps,,acne").join_csv(), "cramps,acne");
    // r2/r3: empty CSV -> empty set
    assert_eq!(OrderedSet::from_csv("").len(), 0);
}

#[test]
fn toggle_ordering_matches_linked_hash_set() {
    // r4: +bloating +acne -bloating +cramps -> "acne,cramps"
    let mut s = OrderedSet::default();
    s.toggle("bloating");
    s.toggle("acne");
    s.toggle("bloating");
    s.toggle("cramps");
    assert_eq!(s.join_csv(), "acne,cramps");
    // r5 (JVM): `set + existing` is a position-keeping no-op. The UI only
    // ever TOGGLES, and toggle is remove-then-append: acne off then on moves
    // it to the end. Both are Kotlin LinkedHashSet semantics.
    let mut plus = s.clone();
    if !plus.contains("acne") {
        plus.toggle("acne");
    }
    assert_eq!(plus.join_csv(), "acne,cramps");
    s.toggle("acne");
    s.toggle("acne");
    assert_eq!(s.join_csv(), "cramps,acne");
}

#[test]
fn observation_count_matches_jvm_including_empty_ovulation_quirk() {
    // r6: all empty -> 0
    let st = TrackDayState::default();
    assert_eq!(st.observation_count(), 0);
    // r7: ovulationTest "" counts as 1 (Kotlin != "NONE", no empty check)
    let mut st = TrackDayState::default();
    st.selected_ovulation_test = String::new();
    assert_eq!(st.observation_count(), 1);
    // r8: 2 vaginal + 1 urinary + mucus + PEAK -> 5
    let mut st = TrackDayState::default();
    st.selected_vaginal.toggle("a");
    st.selected_vaginal.toggle("b");
    st.selected_urinary.toggle("c");
    st.toggle_mucus("Dry");
    st.selected_ovulation_test = "PEAK".into();
    assert_eq!(st.observation_count(), 5);
}

#[test]
fn candidate_mapping_gates_numerics_on_recorded_flags() {
    // r9: sleep not recorded -> 0f even with the 7.5 slider default
    let st = TrackDayState::default();
    assert_eq!(st.candidate_log("2026-11-02").sleep_hours, 0.0);
    // r10: recorded -> the slider value
    let mut st = TrackDayState::default();
    st.set_sleep_hours(6.0);
    assert_eq!(st.candidate_log("2026-11-02").sleep_hours, 6.0);
}

#[test]
fn comfort_count_reads_raw_values_not_recorded_flags() {
    // r11: pain 3 (unrecorded flag irrelevant) + water 2 -> 2
    let mut st = TrackDayState::default();
    st.pain_rating = 3;
    st.water_count = 2;
    assert_eq!(st.comfort_count(), 2);
    // r12: sleep recorded + quality + notes -> 3
    let mut st = TrackDayState::default();
    st.set_sleep_hours(8.0);
    st.selected_sleep_quality = "GOOD".into();
    st.notes_text = "note".into();
    assert_eq!(st.comfort_count(), 3);
}

#[test]
fn reset_from_stored_log_restores_every_holder() {
    let mut log = CycleDayLog::new("2026-11-01");
    log.flow_level = "LIGHT".into();
    log.symptoms_csv = "cramps,,acne".into();
    log.pain_intensity = 4;
    log.sleep_hours = 6.5;
    log.water_glasses = 3;
    log.notes = "hello".into();
    let st = TrackDayState::reset_from_log(Some(&log));
    assert_eq!(st.selected_flow, "LIGHT");
    assert_eq!(st.selected_symptoms.join_csv(), "cramps,acne");
    assert!(st.pain_recorded && st.pain_rating == 4);
    assert!(st.sleep_hours_recorded && st.sleep_hours == 6.5);
    assert!(st.water_recorded && st.water_count == 3);
    assert_eq!(st.notes_text, "hello");
}

#[test]
fn mucus_toggles_off_on_reselect() {
    let mut st = TrackDayState::default();
    st.toggle_mucus("Dry");
    assert_eq!(st.selected_mucus, "Dry");
    st.toggle_mucus("Dry");
    assert_eq!(st.selected_mucus, "");
}

#[test]
fn safety_evaluation_flows_from_candidate() {
    // Unusually heavy flow + pain >= 8 must surface a non-LOW evaluation.
    let mut st = TrackDayState::default();
    st.selected_flow = "UNUSUALLY_HEAVY".into();
    st.set_pain(9);
    let eval = st.evaluate_safety("2026-11-02");
    assert_ne!(format!("{:?}", eval.level), "Low");
    // Default state stays LOW.
    let st = TrackDayState::default();
    assert_eq!(format!("{:?}", st.evaluate_safety("2026-11-02").level), "Low");
}

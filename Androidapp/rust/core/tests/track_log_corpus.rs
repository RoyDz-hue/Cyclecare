//! JVM-locked corpus for the CycleDayLog port: the 12 rows printed by the
//! kotlinc 2.1.0/JRE 21 probe of the verbatim Kotlin data class
//! (fixtures/probes run on 2026-11-02). Any semantic drift fails here.

use cyclecare_core::track_log::CycleDayLog;

#[test]
fn has_recorded_observations_matches_jvm() {
    let nbsp = "\u{00A0}";
    let cases: Vec<(CycleDayLog, bool, &str)> = vec![
        (CycleDayLog::new("2026-11-02"), false, "all defaults"),
        (
            { let mut l = CycleDayLog::new("2026-11-02"); l.notes = " ".into(); l },
            false,
            "notes single space",
        ),
        (
            { let mut l = CycleDayLog::new("2026-11-02"); l.notes = nbsp.into(); l },
            false,
            "notes NBSP only",
        ),
        (
            { let mut l = CycleDayLog::new("2026-11-02"); l.notes = format!("{nbsp}x"); l },
            true,
            "notes NBSP+x",
        ),
        (
            { let mut l = CycleDayLog::new("2026-11-02"); l.flow_level = "\t".into(); l },
            false,
            "flowLevel tab only",
        ),
        (
            { let mut l = CycleDayLog::new("2026-11-02"); l.flow_level = "LIGHT".into(); l },
            true,
            "flowLevel LIGHT",
        ),
        (
            { let mut l = CycleDayLog::new("2026-11-02"); l.ovulation_test = "".into(); l },
            false,
            "ovulationTest empty",
        ),
        (
            { let mut l = CycleDayLog::new("2026-11-02"); l.ovulation_test = "POSITIVE".into(); l },
            true,
            "ovulationTest POSITIVE",
        ),
        (
            { let mut l = CycleDayLog::new("2026-11-02"); l.bbt_celsius = 0.0000001; l },
            true,
            "bbt tiny positive",
        ),
        (
            { let mut l = CycleDayLog::new("2026-11-02"); l.sleep_hours = -0.5; l },
            false,
            "sleepHours negative",
        ),
        (
            { let mut l = CycleDayLog::new("2026-11-02"); l.water_glasses = -1; l },
            false,
            "waterGlasses -1",
        ),
        (
            { let mut l = CycleDayLog::new("2026-11-02"); l.pain_intensity = 10; l },
            true,
            "painIntensity 10",
        ),
    ];
    assert_eq!(cases.len(), 12, "corpus row count matches the JVM probe");
    for (log, expected, label) in cases {
        assert_eq!(log.has_recorded_observations(), expected, "row: {label}");
    }
}

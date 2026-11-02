//! JVM-executed tables: V3Probe/V3Probe2 on JRE 21 (java.util.UUID,
//! java.time.LocalDate are JDK classes), 2026-11-03.
use cyclecare_core::v3_sync::*;
use cyclecare_core::sync_codec::JsonValue;

#[test]
fn wire_record_id_executed_vectors() {
    let cases = [
        ("owner-uid", "profile", "82549e6c-bdcf-3aa6-acbf-fc9ca6dbb68e"),
        ("owner-uid", "settings", "3fb32885-b36b-33e6-82b2-60aebb1a84d5"),
        ("owner-uid", "2026-11-03", "db586910-111b-3e7c-8372-2f97451550be"),
        ("", "profile", "b5619e86-d2ed-3baf-877f-713d7af35886"),
        ("owner-uid", "", "88728fa5-5f0c-36e3-a39b-3e681207708e"),
        ("\u{00fc}\u{00fc}\u{00ef} \u{1f600}", "2024-01-01", "5187dad7-338d-3df8-8c8b-a579e169ff97"),
        ("Owner-UID", "profile", "16a06bf8-5f51-3edb-a96e-dafbb485897d"),
        ("owner-uid", "Profile", "e472b6ba-ad00-37bc-956f-811a952dd70a"),
    ];
    for (owner, logical, want) in cases {
        assert_eq!(wire_record_id(owner, logical), want, "{owner}/{logical}");
    }
}

#[test]
fn iso_local_date_grammar_executed_table() {
    let yes = ["2024-01-31","2024-02-29","-2024-01-01","0000-01-01","+10000-01-01",
        "-10000-01-01","-2024-02-29","0000-02-29","0400-02-29","+123456789-01-01"];
    let no = ["2023-02-29","2024-2-3","2024-13-01","2024-00-10","2024-01-32",
        "+2024-01-01","10000-01-01","999-01-01","2024-01-01 "," 2024-01-01",
        "2024-01-01T00:00","profilex","", "+9999-01-01","2024-01-00","2024-04-31",
        "-2023-02-29","0100-02-29","+1234567890-01-01","-1234567890-01-01","2024-11-3"];
    for d in yes { assert!(valid_logical_id(d), "{d}"); }
    for d in no { assert!(!valid_logical_id(d), "{d}"); }
    assert!(valid_logical_id("profile"));
    assert!(valid_logical_id("settings"));
    assert!(!valid_logical_id("Profile"));
}

#[test]
fn envelope_insertion_order_and_gate() {
    let e = envelope("2026-11-03", JsonValue::Object(vec![("flow".into(), JsonValue::Str("LIGHT".into()))]));
    match e {
        JsonValue::Object(pairs) => {
            let keys: Vec<&str> = pairs.iter().map(|(k, _)| k.as_str()).collect();
            assert_eq!(keys, ["kind", "logicalRecordId", "value"]);
            assert_eq!(pairs[0].1, JsonValue::Str("UPSERT".into()));
            assert_eq!(pairs[1].1, JsonValue::Str("2026-11-03".into()));
        }
        _ => panic!("envelope must be an object"),
    }
    // Gate: all-live match passes; each single drift fails.
    let base = || sync_gate_current(Some("o"), 7, 3, Some(11), true, false, "o", 7, 3, 11);
    assert!(base());
    assert!(!sync_gate_current(Some("other"), 7, 3, Some(11), true, false, "o", 7, 3, 11));
    assert!(!sync_gate_current(None, 7, 3, Some(11), true, false, "o", 7, 3, 11));
    assert!(!sync_gate_current(Some("o"), 8, 3, Some(11), true, false, "o", 7, 3, 11));
    assert!(!sync_gate_current(Some("o"), 7, 4, Some(11), true, false, "o", 7, 3, 11));
    assert!(!sync_gate_current(Some("o"), 7, 3, Some(12), true, false, "o", 7, 3, 11));
    assert!(!sync_gate_current(Some("o"), 7, 3, None, true, false, "o", 7, 3, 11));
    assert!(!sync_gate_current(Some("o"), 7, 3, Some(11), false, false, "o", 7, 3, 11));
    assert!(!sync_gate_current(Some("o"), 7, 3, Some(11), true, true, "o", 7, 3, 11));
    assert!(!is_online_backup_active(), "release policy closed: uploads nothing");
}

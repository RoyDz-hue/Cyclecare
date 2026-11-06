//! Owner-data-gate tests, incl. cross-language SHA-256 naming parity with the
//! Kotlin implementation (expected values computed independently).

use cyclecare_core::owner_data_gate::*;

#[test]
fn namespace_matches_kotlin_sha256_algorithm() {
    // sha256 of a SYNTHETIC UUID in the exact format SupabaseSessionManager
    // stores, computed independently (python hashlib). No real user identity
    // appears in fixtures.
    assert_eq!(
        namespace_for("00000000-0000-4000-8000-0000000000aa"),
        "cycle_care_owner_87d96e07d1bf390d144fce80f3c634be66524878ea3133a800b7c597952fc714"
    );
}

#[test]
fn null_and_blank_identity_map_to_legacy_guest() {
    assert_eq!(database_name_for(None), "cycle_care_database");
    assert_eq!(database_name_for(Some("")), "cycle_care_database");
    assert_eq!(database_name_for(Some("   ")), "cycle_care_database");
    assert_eq!(wipe_marker_key(None), "wiped_legacy_guest");
    assert_eq!(wipe_marker_key(Some("")), "wiped_legacy_guest");
}

#[test]
fn wipe_is_durable_until_explicit_user_resume() {
    let mut gate = OwnerDataGate::new();
    let owner_a = Some("00000000-0000-4000-8000-0000000000aa");
    assert!(!gate.is_wiped(owner_a));
    gate.mark_wiped(owner_a);
    // Repeated reads, other identities, other namespaces: the marker stands.
    for _ in 0..3 {
        assert!(gate.is_wiped(owner_a), "a wipe cannot be undone by a later pull or stale worker");
    }
    assert!(!gate.is_wiped(Some("someone-else")), "namespaces are isolated");
    assert!(!gate.is_wiped(None), "legacy guest namespace is isolated");
    gate.resume_after_explicit_user_action(owner_a);
    assert!(!gate.is_wiped(owner_a));
}

#[test]
fn wiping_one_owner_never_touches_another() {
    let mut gate = OwnerDataGate::new();
    gate.mark_wiped(Some("owner-a"));
    gate.mark_wiped(None);
    assert!(gate.is_wiped(Some("owner-a")));
    assert!(gate.is_wiped(None));
    assert!(!gate.is_wiped(Some("owner-b")));
    gate.resume_after_explicit_user_action(Some("owner-a"));
    assert!(gate.is_wiped(None), "resuming owner-a must not resume the guest namespace");
}

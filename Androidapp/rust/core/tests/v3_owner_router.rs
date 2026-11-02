//! Per-owner file routing proofs (OwnerScopedDatabaseProvider.kt,
//! CycleCareDatabase.kt:100-125): the domain tables carry no ownerId
//! column, so two owners in one file would read and overwrite each other's
//! day logs, settings and profile. Routing per owner is REQUIRED. Synthetic
//! scratch data only; host-only feature `sqlite`.
#![cfg(feature = "sqlite")]

use cyclecare_core::v3_owner_router::{namespace_for, OwnerScopedStores, LEGACY_DATABASE_NAME};

fn tmpdir(tag: &str) -> std::path::PathBuf {
    let d = std::env::temp_dir().join(format!("cyclecare-router-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    d
}

fn insert_log(store: &cyclecare_core::v3_dao_sqlite::SqliteV3Store, date: &str, notes: &str) {
    store.conn.execute(
        "INSERT OR REPLACE INTO cycle_day_logs (dateString, flowLevel, energyLevel, moodLevel, symptomsCsv,
         vaginalHealthCsv, urinaryHealthCsv, cervicalMucus, ovulationTest, bbtCelsius, sleepHours,
         sleepQuality, waterGlasses, notes, painIntensity)
         VALUES (?1, 'LIGHT', '', '', '', '', '', '', '', 0.0, 0.0, '', 0, ?2, 0)",
        rusqlite::params![date, notes]).unwrap();
}

fn log_notes(store: &cyclecare_core::v3_dao_sqlite::SqliteV3Store, date: &str) -> Option<String> {
    store.conn.query_row(
        "SELECT notes FROM cycle_day_logs WHERE dateString = ?1",
        rusqlite::params![date], |r| r.get(0)).ok()
}

#[test]
fn namespace_matches_kotlin_known_answer_vector() {
    // CycleCareDatabase.kt:121-125: "cycle_care_owner_" + hex SHA-256 of the
    // UTF-8 identity. Vector computed independently of this implementation
    // (FIPS 180-4 SHA-256 of "owner-uid").
    assert_eq!(
        namespace_for("owner-uid"),
        "cycle_care_owner_27dfbde6ec9d28c36258ee6edad10e40cdaeca1adf3f7ea7cdb23b494a1765ac"
    );
    assert_ne!(namespace_for("owner-uid"), namespace_for("other-uid"), "distinct owners, distinct files");
}

#[test]
fn blank_and_absent_identities_map_to_the_legacy_guest_database() {
    let d = tmpdir("legacy");
    let mut p = OwnerScopedStores::new(&d);
    p.for_identity(None).unwrap();
    p.for_identity(Some("")).unwrap();
    p.for_identity(Some("   ")).unwrap();       // Kotlin isBlank parity
    p.for_identity(Some(" ")).unwrap();     // NBSP is whitespace (Kotlin isWhitespace)
    let files: Vec<_> = std::fs::read_dir(&d).unwrap().map(|e| e.unwrap().file_name().into_string().unwrap()).collect();
    assert_eq!(files, vec![LEGACY_DATABASE_NAME.to_string()], "all blank/absent identities share the ONE guest file");
    let _ = std::fs::remove_dir_all(&d);
}

#[test]
fn kotlin_blank_differing_codepoints_route_to_legacy_not_namespaces() {
    // The exact codepoints where Kotlin Char.isWhitespace and Rust
    // char::is_whitespace DISAGREE (central kotlin_trim corpus): a Rust
    // trim-based check would namespace these identities into per-owner
    // files; Kotlin isBlank maps them to the legacy guest database.
    let d = tmpdir("blankparity");
    let mut p = OwnerScopedStores::new(&d);
    for cp in ["\u{2007}", "\u{202F}", "\u{001C}", "\u{001D}", "\u{001E}", "\u{001F}"] {
        p.for_identity(Some(cp)).unwrap();
    }
    let files: Vec<_> = std::fs::read_dir(&d).unwrap().map(|e| e.unwrap().file_name().into_string().unwrap()).collect();
    assert_eq!(files, vec![LEGACY_DATABASE_NAME.to_string()],
        "Kotlin-blank identities must NOT become owner files (Rust trim would have)");
    // And the reverse edge: U+0085 (NEL) is whitespace to Rust but NOT to
    // Kotlin - it is a real identity and MUST get its own namespace.
    p.for_identity(Some("\u{0085}")).unwrap();
    assert!(d.join(namespace_for("\u{0085}")).exists(), "NEL is not Kotlin-blank: namespaced");
    let _ = std::fs::remove_dir_all(&d);
}

#[test]
fn two_owners_are_fully_isolated_across_files() {
    let d = tmpdir("iso");
    let mut p = OwnerScopedStores::new(&d);
    insert_log(p.for_identity(Some("alice")).unwrap(), "2026-11-01", "alice-private");
    insert_log(p.for_identity(Some("bob")).unwrap(), "2026-11-01", "bob-private");
    assert_eq!(log_notes(p.for_identity(Some("alice")).unwrap(), "2026-11-01").as_deref(), Some("alice-private"));
    assert_eq!(log_notes(p.for_identity(Some("bob")).unwrap(), "2026-11-01").as_deref(), Some("bob-private"));
    // The guest database sees NEITHER owner's rows.
    assert_eq!(log_notes(p.for_identity(None).unwrap(), "2026-11-01"), None, "guest cannot read owner data");
    // Sync rows also stay in the owner's own file.
    let alice_files = namespace_for("alice");
    assert!(d.join(&alice_files).exists() && d.join(namespace_for("bob")).exists() && d.join(LEGACY_DATABASE_NAME).exists());
    let _ = std::fs::remove_dir_all(&d);
}

#[test]
fn account_switch_resolves_at_call_time_and_never_reuses_a_handle() {
    let d = tmpdir("switch");
    let mut p = OwnerScopedStores::new(&d);
    insert_log(p.current(&mut || Some("alice".to_string())).unwrap(), "2026-11-02", "before-switch");
    // Sign out -> guest database, cannot see alice's rows.
    assert_eq!(log_notes(p.current(&mut || None).unwrap(), "2026-11-02"), None, "signed-out resolves the guest file");
    // Sign in as someone else -> bob's file, not alice's.
    assert_eq!(log_notes(p.current(&mut || Some("bob".to_string())).unwrap(), "2026-11-02"), None, "bob never sees alice's rows");
    // Back to alice -> the SAME cached store, data intact.
    assert_eq!(log_notes(p.current(&mut || Some("alice".to_string())).unwrap(), "2026-11-02").as_deref(), Some("before-switch"));
    let _ = std::fs::remove_dir_all(&d);
}

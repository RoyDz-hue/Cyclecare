//! Storage hardening review (parent directive 2026-11-02 14:48:19):
//! column-set preservation vs entity contracts and the real Room DDL,
//! user_version policy, failed-open preservation (never delete), reopen
//! no-op, and foreign-content preservation.
//!
//! Scope honesty: these are plain bundled-SQLite scratch files. The
//! production Android DBs are SQLCipher-encrypted with Keystore-derived keys;
//! nothing here reads or migrates a live encrypted record, and no plaintext
//! fallback is introduced. Keystore-invalidated DBs are unrecoverable by
//! design (learning: detect, preserve, never delete).

use cyclecare_core::storage_db::StorageDb;
use cyclecare_core::sync_codec::{CycleSettings, OnboardingProfile};
use cyclecare_core::track_log::CycleDayLog;

fn table_columns(db: &StorageDb, table: &str) -> Vec<(String, String, bool, bool)> {
    let mut stmt = db
        .conn
        .prepare(&format!("PRAGMA table_info({table})"))
        .unwrap();
    stmt.query_map([], |row| {
        Ok((
            row.get::<_, String>(1)?,       // name
            row.get::<_, String>(2)?,       // type
            row.get::<_, i32>(3)? == 1,     // notnull
            row.get::<_, i32>(5)? == 1,     // pk
        ))
    })
    .unwrap()
    .map(|r| r.unwrap())
    .collect()
}

/// Exact Room column contracts from the KSP-generated DDL
/// (fixtures/schema/room-ddl-18dd603.sql), cross-checked against the Kotlin
/// entities: CycleDayLog (15 fields, CycleDayLog.kt), CycleSettings
/// (id + 15, CycleSettings.kt), OnboardingProfile (id + 17 = 18,
/// OnboardingProfile.kt). Room column names are the Kotlin property names.
#[test]
fn column_sets_match_entity_contracts() {
    let db = StorageDb::open_memory().unwrap();

    let logs = table_columns(&db, "cycle_day_logs");
    let expect_logs = [
        ("dateString", "TEXT"), ("flowLevel", "TEXT"), ("energyLevel", "TEXT"),
        ("moodLevel", "TEXT"), ("symptomsCsv", "TEXT"), ("vaginalHealthCsv", "TEXT"),
        ("urinaryHealthCsv", "TEXT"), ("cervicalMucus", "TEXT"), ("ovulationTest", "TEXT"),
        ("bbtCelsius", "REAL"), ("sleepHours", "REAL"), ("sleepQuality", "TEXT"),
        ("waterGlasses", "INTEGER"), ("notes", "TEXT"), ("painIntensity", "INTEGER"),
    ];
    assert_eq!(logs.len(), 15, "cycle_day_logs column count");
    for (i, (name, ty)) in expect_logs.iter().enumerate() {
        assert_eq!(&logs[i].0, name, "cycle_day_logs col {i}");
        assert_eq!(&logs[i].1, ty, "cycle_day_logs type {i}");
        assert!(logs[i].2, "cycle_day_logs notnull {i}");
        assert_eq!(logs[i].3, i == 0, "cycle_day_logs pk {i}");
    }

    let settings = table_columns(&db, "cycle_settings");
    let expect_settings = [
        "id", "isOnboardingCompleted", "selectedGoalsCsv", "lastPeriodStartDate",
        "averageCycleLength", "periodDuration", "lutealPhaseDays", "isAppLockEnabled",
        "lockTimeoutMinutes", "isDiscreetNotificationMode", "isPregnancyMode",
        "pregnancyDueDate", "remindPeriod", "remindFertile", "remindDailyLog",
    ];
    assert_eq!(settings.len(), 15, "cycle_settings column count");
    for (i, name) in expect_settings.iter().enumerate() {
        assert_eq!(&settings[i].0, name, "cycle_settings col {i}");
        assert!(settings[i].2, "cycle_settings notnull {i}");
        assert_eq!(settings[i].3, i == 0, "cycle_settings pk {i}");
    }

    let profiles = table_columns(&db, "onboarding_profiles");
    let expect_profiles = [
        "id", "isCompleted", "completedAtTimestamp", "isAgeVerified", "isTermsAgreed",
        "accountMode", "selectedGoalsCsv", "lastPeriodDateString", "lastPeriodEndDateString",
        "approximatePeriodChoice", "cycleLengthOption", "cycleLengthDays",
        "periodDurationDays", "remindFertileWindow", "typicalSymptomsCsv", "typicalEnergy",
        "typicalSleepQuality", "hasCompletedDeepBaseline",
    ];
    assert_eq!(profiles.len(), 18, "onboarding_profiles column count");
    for (i, name) in expect_profiles.iter().enumerate() {
        assert_eq!(&profiles[i].0, name, "onboarding_profiles col {i}");
        assert!(profiles[i].2, "onboarding_profiles notnull {i}");
        assert_eq!(profiles[i].3, i == 0, "onboarding_profiles pk {i}");
    }

    // All 7 Room tables exist after applying the mirrored DDL.
    let count: i64 = db
        .conn
        .query_row(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN (
                'cycle_day_logs','cycle_settings','onboarding_profiles',
                'health_sync_revisions','health_sync_outbox','health_sync_cursors','health_sync_conflicts')",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(count, 7);
}

/// user_version policy: the mirror does NOT set PRAGMA user_version. Room
/// writes 9 (CycleCareDatabase version) plus its room_master_table identity
/// hash on device; faking that bookkeeping in scratch fixtures would make
/// them look like Room-owned DBs they are not. Conservative decision
/// (parent 14:48:19): scratch files stay user_version=0 and the header
/// documents that production-version interop is an OPEN item for the real
/// encrypted migration path, which does not exist yet.
#[test]
fn user_version_is_not_faked() {
    let dir = std::env::temp_dir().join(format!("cc-harden-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("scratch.db");
    let _ = std::fs::remove_file(&path);
    let db = StorageDb::open_file(&path).unwrap();
    let v: i64 = db
        .conn
        .query_row("PRAGMA user_version", [], |r| r.get(0))
        .unwrap();
    assert_eq!(v, 0, "scratch DBs must not fake Room's user_version=9");
    std::fs::remove_dir_all(&dir).ok();
}

/// Failed-open path: a non-SQLite file must produce an error, keep every
/// byte, and never be deleted or replaced (keystore-invalidated DBs are
/// unrecoverable; destructive recovery is not allowed).
#[test]
fn garbage_file_is_preserved_never_deleted() {
    let dir = std::env::temp_dir().join(format!("cc-harden-garbage-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("owner.db");
    let garbage: Vec<u8> = b"This is not a sqlite database at all\x00\x01\x02".to_vec();
    std::fs::write(&path, &garbage).unwrap();
    let result = StorageDb::open_file(&path);
    assert!(result.is_err(), "garbage file must fail to open as a database");
    assert!(path.exists(), "file must not be deleted");
    assert_eq!(std::fs::read(&path).unwrap(), garbage, "file bytes must be untouched");
    std::fs::remove_dir_all(&dir).ok();
}

/// Reopen is a no-op over existing data: DDL re-application must not wipe
/// rows, and same-owner reopen/update keeps working (REPLACE semantics).
#[test]
fn reopen_preserves_rows_and_updates() {
    let dir = std::env::temp_dir().join(format!("cc-harden-reopen-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("owner.db");
    let _ = std::fs::remove_file(&path);

    let log = CycleDayLog {
        date_string: "2026-11-02".into(),
        flow_level: "MEDIUM".into(),
        energy_level: "LOW".into(),
        mood_level: String::new(),
        symptoms_csv: "fatigue".into(),
        vaginal_health_csv: String::new(),
        urinary_health_csv: String::new(),
        cervical_mucus: String::new(),
        ovulation_test: "NONE".into(),
        bbt_celsius: 0.0,
        sleep_hours: 6.5,
        sleep_quality: "POOR".into(),
        water_glasses: 4,
        notes: "tired".into(),
        pain_intensity: 2,
    };
    {
        let db = StorageDb::open_file(&path).unwrap();
        db.insert_or_update_log(&log).unwrap();
        db.save_settings(&CycleSettings::default()).unwrap();
        db.insert_or_update_profile(&OnboardingProfile::default()).unwrap();
    }
    {
        let db = StorageDb::open_file(&path).unwrap();
        let got = db.get_log_by_date("2026-11-02").unwrap().unwrap();
        assert_eq!(got.flow_level, "MEDIUM");
        assert!(db.get_settings().unwrap().is_some());
        assert!(db.get_profile().unwrap().is_some());
        // update through REPLACE
        let updated = CycleDayLog { flow_level: "HEAVY".into(), ..log.clone() };
        db.insert_or_update_log(&updated).unwrap();
    }
    {
        let db = StorageDb::open_file(&path).unwrap();
        assert_eq!(db.get_log_by_date("2026-11-02").unwrap().unwrap().flow_level, "HEAVY");
        assert_eq!(db.get_all_logs().unwrap().len(), 1, "REPLACE must not duplicate the PK row");
    }
    std::fs::remove_dir_all(&dir).ok();
}

/// Foreign SQLite content in the same file is preserved when the mirrored
/// DDL is applied (CREATE TABLE IF NOT EXISTS never touches other tables).
#[test]
fn foreign_sqlite_content_is_preserved() {
    let dir = std::env::temp_dir().join(format!("cc-harden-foreign-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("shared.db");
    let _ = std::fs::remove_file(&path);
    {
        let conn = rusqlite::Connection::open(&path).unwrap();
        conn.execute_batch("CREATE TABLE foreign_table (k TEXT PRIMARY KEY, v TEXT); INSERT INTO foreign_table VALUES ('a','b');").unwrap();
    }
    let db = StorageDb::open_file(&path).unwrap();
    let v: String = db
        .conn
        .query_row("SELECT v FROM foreign_table WHERE k='a'", [], |r| r.get(0))
        .unwrap();
    assert_eq!(v, "b", "foreign rows must survive schema application");
    // and the mirrored tables now exist alongside
    assert!(db.get_settings().unwrap().is_none());
    std::fs::remove_dir_all(&dir).ok();
}

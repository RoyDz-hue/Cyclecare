//! ACTUAL SQLite execution proof for migrations 4->9 on disposable fixture
//! databases (rusqlite bundled PLAINTEXT SQLite; the device path is
//! SQLCipher - codec difference labeled, migration SQL is codec-agnostic).
//! Proves: the JVM-locked SQL constants EXECUTE, the 4->5 sweep reads a
//! real legacy plaintext PIN, and schema+data survive the full 4->9 chain.
//! NOT a Room acceptance claim: no room_master_table, no Room identity
//! hash, no migration runner; raw statement execution only.
//! Source-parity quirks stay labeled (failed secureprefs sweep still drops
//! the plaintext PIN; fault between setPIN/enabling leaves disabled) -
//! parity is not safety approval and nothing here runs against a user db.
#![cfg(feature = "sqlite")]

use rusqlite::Connection;

use cyclecare_core::migrations::*;

fn fresh_db(tag: &str) -> (std::path::PathBuf, Connection) {
    let path = std::env::temp_dir().join(format!(
        "cyclecare_mig_{}_{}_{}.db",
        tag,
        std::process::id(),
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()
    ));
    let conn = Connection::open(&path).unwrap();
    (path, conn)
}

/// The v4 schema, reconstructed from the entity sources (CycleSettings.kt,
/// CycleDayLog.kt, OnboardingProfile.kt at main 31b88f5) MINUS what later
/// migrations add: cycle_settings carries the plaintext appLockPin;
/// onboarding_profiles lacks lastPeriodEndDateString (8->9); health-sync
/// tables do not exist (5->6). Room type mapping: String->TEXT,
/// Int/Long->INTEGER, Float->REAL, Boolean->INTEGER.
fn build_v4(conn: &Connection) {
    conn.execute_batch(
        "CREATE TABLE `cycle_settings` (
            `id` INTEGER NOT NULL PRIMARY KEY,
            `isOnboardingCompleted` INTEGER NOT NULL,
            `selectedGoalsCsv` TEXT NOT NULL,
            `lastPeriodStartDate` TEXT NOT NULL,
            `averageCycleLength` INTEGER NOT NULL,
            `periodDuration` INTEGER NOT NULL,
            `lutealPhaseDays` INTEGER NOT NULL,
            `isAppLockEnabled` INTEGER NOT NULL,
            `lockTimeoutMinutes` INTEGER NOT NULL,
            `isDiscreetNotificationMode` INTEGER NOT NULL,
            `isPregnancyMode` INTEGER NOT NULL,
            `pregnancyDueDate` TEXT NOT NULL,
            `remindPeriod` INTEGER NOT NULL,
            `remindFertile` INTEGER NOT NULL,
            `remindDailyLog` INTEGER NOT NULL,
            `appLockPin` TEXT
        );
        CREATE TABLE `onboarding_profiles` (
            `id` INTEGER NOT NULL PRIMARY KEY,
            `isCompleted` INTEGER NOT NULL,
            `completedAtTimestamp` INTEGER NOT NULL,
            `isAgeVerified` INTEGER NOT NULL,
            `isTermsAgreed` INTEGER NOT NULL,
            `accountMode` TEXT NOT NULL,
            `selectedGoalsCsv` TEXT NOT NULL,
            `lastPeriodDateString` TEXT NOT NULL,
            `approximatePeriodChoice` TEXT NOT NULL,
            `cycleLengthOption` TEXT NOT NULL,
            `cycleLengthDays` INTEGER NOT NULL,
            `periodDurationDays` INTEGER NOT NULL,
            `remindFertileWindow` INTEGER NOT NULL,
            `typicalSymptomsCsv` TEXT NOT NULL,
            `typicalEnergy` TEXT NOT NULL,
            `typicalSleepQuality` TEXT NOT NULL,
            `hasCompletedDeepBaseline` INTEGER NOT NULL
        );
        CREATE TABLE `cycle_day_logs` (
            `dateString` TEXT NOT NULL PRIMARY KEY,
            `flowLevel` TEXT NOT NULL,
            `energyLevel` TEXT NOT NULL,
            `moodLevel` TEXT NOT NULL,
            `symptomsCsv` TEXT NOT NULL,
            `vaginalHealthCsv` TEXT NOT NULL,
            `urinaryHealthCsv` TEXT NOT NULL,
            `cervicalMucus` TEXT NOT NULL,
            `ovulationTest` TEXT NOT NULL,
            `bbtCelsius` REAL NOT NULL,
            `sleepHours` REAL NOT NULL,
            `sleepQuality` TEXT NOT NULL,
            `waterGlasses` INTEGER NOT NULL,
            `notes` TEXT NOT NULL,
            `painIntensity` INTEGER NOT NULL
        );",
    )
    .unwrap();
}

fn seed_v4(conn: &Connection, pin: Option<&str>) {
    conn.execute(
        "INSERT INTO `cycle_settings` VALUES (1,1,'goal_a,goal_b','2026-10-01',29,6,13,1,5,0,0,'2027-01-01',1,0,1,?1)",
        [pin],
    )
    .unwrap();
    conn.execute(
        "INSERT INTO `onboarding_profiles` VALUES (1,1,1730000000000,1,1,'SYNC_ACCOUNT','goal_a','2026-09-28','EXACT','',31,4,1,'cramps','LOW','POOR',1)",
        [],
    )
    .unwrap();
    for (d, notes) in [("2026-10-01", "first log"), ("2026-10-02", "second log")] {
        conn.execute(
            "INSERT INTO `cycle_day_logs` VALUES (?1,'MEDIUM','HIGH','CALM','cramps,acne','','','','POSITIVE',36.7,7.5,'GOOD',8,?2,3)",
            rusqlite::params![d, notes],
        )
        .unwrap();
    }
}

fn table_columns(conn: &Connection, table: &str) -> Vec<String> {
    let mut stmt = conn.prepare(&format!("PRAGMA table_info(`{table}`)")).unwrap();
    stmt.query_map([], |r| r.get::<_, String>(1))
        .unwrap()
        .collect::<Result<_, _>>()
        .unwrap()
}

fn table_exists(conn: &Connection, name: &str) -> bool {
    conn.query_row(
        "SELECT COUNT(*) FROM sqlite_master WHERE type IN ('table','index') AND name = ?1",
        [name],
        |r| r.get::<_, i64>(0),
    )
    .unwrap()
        > 0
}

fn run_migration_4_to_9_sql(conn: &Connection) {
    conn.execute_batch(SQL_4_TO_5_CREATE).unwrap();
    conn.execute_batch(SQL_4_TO_5_INSERT).unwrap();
    conn.execute_batch(SQL_4_TO_5_DROP).unwrap();
    conn.execute_batch(SQL_4_TO_5_RENAME).unwrap();
    for s in SQL_5_TO_6 { conn.execute_batch(s).unwrap(); }
    for s in SQL_6_TO_7 { conn.execute_batch(s).unwrap(); }
    for s in SQL_7_TO_8 { conn.execute_batch(s).unwrap(); }
    for s in SQL_8_TO_9 { conn.execute_batch(s).unwrap(); }
}

#[test]
fn full_4_to_9_chain_executes_and_preserves_data() {
    let (path, conn) = fresh_db("full");
    build_v4(&conn);
    seed_v4(&conn, Some("4321"));

    // The PIN sweep query reads the real legacy plaintext PIN.
    let pin: Option<String> = conn
        .query_row(SQL_4_TO_5_PIN_QUERY, [], |r| r.get(0))
        .unwrap();
    assert_eq!(pin.as_deref(), Some("4321"));

    run_migration_4_to_9_sql(&conn);

    // 4->5: appLockPin GONE, exactly the 15 new columns, row preserved.
    let cols = table_columns(&conn, "cycle_settings");
    assert_eq!(cols.len(), 15, "post-4->5 column count");
    assert!(!cols.contains(&"appLockPin".to_string()), "plaintext PIN column dropped");
    let row: (i64, String, String, i64, i64, String, i64) = conn
        .query_row(
            "SELECT id, selectedGoalsCsv, lastPeriodStartDate, averageCycleLength, lockTimeoutMinutes, pregnancyDueDate, remindDailyLog FROM `cycle_settings`",
            [],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?, r.get(5)?, r.get(6)?)),
        )
        .unwrap();
    assert_eq!(row, (1, "goal_a,goal_b".into(), "2026-10-01".into(), 29, 5, "2027-01-01".into(), 1));

    // 5->6 tables + index exist.
    for t in ["health_sync_revisions", "health_sync_outbox", "health_sync_cursors", "index_health_sync_outbox_ownerId_nextAttemptAt"] {
        assert!(table_exists(&conn, t), "{t} missing after 5->6");
    }
    // 6->7: outbox columns + conflicts table + index.
    let outbox_cols = table_columns(&conn, "health_sync_outbox");
    assert!(outbox_cols.contains(&"serverReceipt".to_string()));
    assert!(outbox_cols.contains(&"serverRevision".to_string()));
    assert!(table_exists(&conn, "health_sync_conflicts"));
    assert!(table_exists(&conn, "index_health_sync_conflicts_ownerId_resolvedAt"));
    // 7->8: revisions gained serverRevision with default 0.
    assert!(table_columns(&conn, "health_sync_revisions").contains(&"serverRevision".to_string()));
    // 8->9: onboarding_profiles gained lastPeriodEndDateString; existing
    // row got the DEFAULT '' (data preservation through ALTER).
    let end_date: String = conn
        .query_row("SELECT lastPeriodEndDateString FROM `onboarding_profiles` WHERE id = 1", [], |r| r.get(0))
        .unwrap();
    assert_eq!(end_date, "");
    // Pre-existing rows across every table preserved.
    let (n_settings, n_profiles, n_logs): (i64, i64, i64) = (
        conn.query_row("SELECT COUNT(*) FROM `cycle_settings`", [], |r| r.get(0)).unwrap(),
        conn.query_row("SELECT COUNT(*) FROM `onboarding_profiles`", [], |r| r.get(0)).unwrap(),
        conn.query_row("SELECT COUNT(*) FROM `cycle_day_logs`", [], |r| r.get(0)).unwrap(),
    );
    assert_eq!((n_settings, n_profiles, n_logs), (1, 1, 2));
    let log_notes: String = conn
        .query_row("SELECT notes FROM `cycle_day_logs` WHERE dateString = '2026-10-02'", [], |r| r.get(0))
        .unwrap();
    assert_eq!(log_notes, "second log");
    // onboarding row content preserved through 8->9.
    let mode: String = conn
        .query_row("SELECT accountMode FROM `onboarding_profiles` WHERE id = 1", [], |r| r.get(0))
        .unwrap();
    assert_eq!(mode, "SYNC_ACCOUNT");

    drop(conn);
    let _ = std::fs::remove_file(path);
}

#[test]
fn blank_pin_and_missing_row_variants_execute() {
    // Blank PIN fixture: sweep query returns '', Kotlin isNullOrBlank ->
    // no credential migration (decision covered in tests/migrations.rs);
    // SQL chain still executes and preserves.
    let (path, conn) = fresh_db("blank");
    build_v4(&conn);
    seed_v4(&conn, Some(""));
    run_migration_4_to_9_sql(&conn);
    assert_eq!(table_columns(&conn, "cycle_settings").len(), 15);
    assert_eq!(conn.query_row("SELECT COUNT(*) FROM `cycle_settings`", [], |r| r.get::<_, i64>(0)).unwrap(), 1);
    drop(conn);
    let _ = std::fs::remove_file(path);

    // Fresh install: no cycle_settings row at all -> INSERT..SELECT moves
    // 0 rows, chain succeeds.
    let (path, conn) = fresh_db("empty");
    build_v4(&conn);
    run_migration_4_to_9_sql(&conn);
    assert_eq!(conn.query_row("SELECT COUNT(*) FROM `cycle_settings`", [], |r| r.get::<_, i64>(0)).unwrap(), 0);
    assert!(table_exists(&conn, "health_sync_conflicts"));
    drop(conn);
    let _ = std::fs::remove_file(path);
}

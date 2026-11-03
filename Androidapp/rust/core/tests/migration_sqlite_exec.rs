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
//!
//! SCHEMA PROVENANCE (reviewer history check 2026-11-03, reviewer-executed,
//! NOT independently re-verified here - the github surface cannot list
//! commits): NO v4 commit exists in repo history; versions went v3 -> v5.
//! The v4-shaped fixture below is therefore INFERRED (current entities
//! minus later-migration effects), not a historical schema match. Known
//! historical deltas per reviewer: v3 appLockPin was TEXT NOT NULL
//! (fixture follows that shape); the v3 profile lacked 4 of the current
//! fields. And the CURRENT source has no 3->4 migration at all: a v3
//! install upgrading to current has NO Room migration path and
//! buildDatabase shows no destructive fallback - a pre-existing
//! compatibility concern in the source, NOT a port regression. Parity
//! claim stays: the ported SQL/decisions match the current source text.
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
            `appLockPin` TEXT NOT NULL DEFAULT ''
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
    // A revisions row written BETWEEN 5->6 and 7->8 must read back
    // serverRevision = 0 (the 7->8 ALTER default) afterwards.
    conn.execute(
        "INSERT INTO `health_sync_revisions` VALUES ('owner1','rec1',7,'hash7',0,1730000000123)",
        [],
    )
    .unwrap();
    for s in SQL_6_TO_7 { conn.execute_batch(s).unwrap(); }
    for s in SQL_7_TO_8 { conn.execute_batch(s).unwrap(); }
    for s in SQL_8_TO_9 { conn.execute_batch(s).unwrap(); }
}

/// Full PRAGMA table_info tuples: (name, type, notnull, dflt_value, pk).
fn table_info(conn: &Connection, table: &str) -> Vec<(String, String, bool, Option<String>, i64)> {
    let mut stmt = conn.prepare(&format!("PRAGMA table_info(`{table}`)")).unwrap();
    stmt.query_map([], |r| {
        Ok((r.get(1)?, r.get(2)?, r.get::<_, i64>(3)? != 0, r.get(4)?, r.get(5)?))
    })
    .unwrap()
    .collect::<Result<_, _>>()
    .unwrap()
}

/// Key columns of an index in declaration order (PRAGMA index_xinfo; the
/// trailing rowid row has key=0 and NULL name - filtered via column 5).
fn index_columns(conn: &Connection, index: &str) -> Vec<String> {
    let mut stmt = conn.prepare(&format!("PRAGMA index_xinfo(`{index}`)")).unwrap();
    let mut rows: Vec<(i64, String)> = stmt
        .query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, Option<String>>(2)?, r.get::<_, i64>(5)?)))
        .unwrap()
        .collect::<Result<Vec<_>, _>>()
        .unwrap()
        .into_iter()
        .filter(|(_, _, key)| *key == 1)
        .map(|(seqno, name, _)| (seqno, name.unwrap()))
        .collect();
    rows.sort_by_key(|(seqno, _)| *seqno);
    rows.into_iter().map(|(_, name)| name).collect()
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

    // --- Full schema tuples (name, type, notnull, default, pk) ---
    let revisions = table_info(&conn, "health_sync_revisions");
    assert_eq!(
        revisions,
        vec![
            ("ownerId".into(), "TEXT".into(), true, None, 1),
            ("recordId".into(), "TEXT".into(), true, None, 2),
            ("revision".into(), "INTEGER".into(), true, None, 0),
            ("payloadHash".into(), "TEXT".into(), false, None, 0),
            ("tombstone".into(), "INTEGER".into(), true, None, 0),
            ("updatedAt".into(), "INTEGER".into(), true, None, 0),
            ("serverRevision".into(), "INTEGER".into(), true, Some("0".into()), 0),
        ],
        "health_sync_revisions full tuple (incl. 7->8 serverRevision default)"
    );
    let outbox = table_info(&conn, "health_sync_outbox");
    assert_eq!(outbox.len(), 14, "12 cols from 5->6 + 2 from 6->7");
    assert_eq!(outbox[0], ("ownerId".into(), "TEXT".into(), true, None, 1));
    assert_eq!(outbox[1], ("operationId".into(), "TEXT".into(), true, None, 2));
    assert!(outbox.contains(&("payloadJson".into(), "TEXT".into(), false, None, 0)));
    assert!(outbox.contains(&("lastError".into(), "TEXT".into(), false, None, 0)));
    assert!(outbox.contains(&("serverReceipt".into(), "TEXT".into(), false, None, 0)));
    assert!(outbox.contains(&("serverRevision".into(), "INTEGER".into(), false, None, 0)));
    let cursors = table_info(&conn, "health_sync_cursors");
    assert_eq!(
        cursors,
        vec![
            ("ownerId".into(), "TEXT".into(), true, None, 1),
            ("changeSeq".into(), "INTEGER".into(), true, None, 0),
        ]
    );
    let conflicts = table_info(&conn, "health_sync_conflicts");
    assert_eq!(conflicts.len(), 12);
    assert_eq!(conflicts[0], ("ownerId".into(), "TEXT".into(), true, None, 1));
    assert_eq!(conflicts[1], ("recordId".into(), "TEXT".into(), true, None, 2));
    assert!(conflicts.contains(&("resolvedAt".into(), "INTEGER".into(), false, None, 0)));
    assert!(conflicts.contains(&("resolution".into(), "TEXT".into(), false, None, 0)));
    assert!(conflicts.contains(&("remoteTombstone".into(), "INTEGER".into(), true, None, 0)));

    // --- Index key columns in declaration order ---
    assert_eq!(
        index_columns(&conn, "index_health_sync_outbox_ownerId_nextAttemptAt"),
        vec!["ownerId".to_string(), "nextAttemptAt".to_string()]
    );
    assert_eq!(
        index_columns(&conn, "index_health_sync_conflicts_ownerId_resolvedAt"),
        vec!["ownerId".to_string(), "resolvedAt".to_string()]
    );

    // --- The pre-7->8 revisions row reads serverRevision = 0 ---
    let srv_rev: i64 = conn
        .query_row("SELECT serverRevision FROM `health_sync_revisions` WHERE ownerId='owner1' AND recordId='rec1'", [], |r| r.get(0))
        .unwrap();
    assert_eq!(srv_rev, 0, "7->8 ALTER default applied to pre-existing row");

    // --- FULL settings row comparison (all 15 post-4->5 columns) ---
    let settings: Vec<rusqlite::types::Value> = conn
        .query_row("SELECT * FROM `cycle_settings` WHERE id = 1", [], |r| {
            Ok((0..15).map(|i| r.get::<_, rusqlite::types::Value>(i).unwrap()).collect())
        })
        .unwrap();
    fn v(x: impl Into<rusqlite::types::Value>) -> rusqlite::types::Value { x.into() }
    fn t(x: &str) -> rusqlite::types::Value { rusqlite::types::Value::Text(x.to_string()) }
    let expect_settings: Vec<rusqlite::types::Value> = vec![
        v(1i64), v(1i64), t("goal_a,goal_b"), t("2026-10-01"), v(29i64), v(6i64),
        v(13i64), v(1i64), v(5i64), v(0i64), v(0i64), t("2027-01-01"), v(1i64),
        v(0i64), v(1i64),
    ];
    assert_eq!(settings, expect_settings, "full settings row diverged");

    // --- FULL profile row comparison (18 cols post-8->9) ---
    let profile: Vec<rusqlite::types::Value> = conn
        .query_row("SELECT * FROM `onboarding_profiles` WHERE id = 1", [], |r| {
            Ok((0..18).map(|i| r.get::<_, rusqlite::types::Value>(i).unwrap()).collect())
        })
        .unwrap();
    let expect_profile: Vec<rusqlite::types::Value> = vec![
        v(1i64), v(1i64), v(1730000000000i64), v(1i64), v(1i64), t("SYNC_ACCOUNT"),
        t("goal_a"), t("2026-09-28"), t(""), t("EXACT"), t(""), v(31i64),
        v(4i64), v(1i64), t("cramps"), t("LOW"), t("POOR"), v(1i64),
    ];
    // Column ORDER after 8->9: ALTER ADD COLUMN appends
    // lastPeriodEndDateString at the END (position 18), despite the entity
    // listing it mid-class - SQLite ALTER appends, entity order is Room's.
    let mut expect_profile_ordered = expect_profile.clone();
    expect_profile_ordered.remove(8);
    expect_profile_ordered.push(t(""));
    assert_eq!(profile, expect_profile_ordered, "full profile row diverged");

    // --- FULL log rows (15 cols x 2) ---
    let logs: Vec<Vec<rusqlite::types::Value>> = {
        let mut stmt = conn.prepare("SELECT * FROM `cycle_day_logs` ORDER BY dateString").unwrap();
        stmt.query_map([], |r| Ok((0..15).map(|i| r.get::<_, rusqlite::types::Value>(i).unwrap()).collect()))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap()
    };
    let expect_log = |d: &str, notes: &str| -> Vec<rusqlite::types::Value> {
        vec![
            t(d), t("MEDIUM"), t("HIGH"), t("CALM"), t("cramps,acne"),
            t(""), t(""), t(""), t("POSITIVE"), v(36.7f64), v(7.5f64),
            t("GOOD"), v(8i64), t(notes), v(3i64),
        ]
    };
    assert_eq!(logs, vec![expect_log("2026-10-01", "first log"), expect_log("2026-10-02", "second log")]);

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

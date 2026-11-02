//! REAL-SQLite proofs for SqliteV3Store: verbatim HealthSyncLocalDao +
//! CycleDayLogDao/OnboardingProfileDao SQL against the KSP-verified schema,
//! domain+sync rows in ONE connection and ONE EXCLUSIVE transaction, typed
//! fault propagation (never swallowed errors), engine-enforced rollback via
//! RAISE(ABORT) triggers. Synthetic scratch data only; host-only feature
//! `sqlite`. Android Room on the encrypted app DB remains a separate, open
//! integration. Schema parity is diffed against the ops KSP extract
//! (fixtures/room_schema_ksp.sql, spike 18dd603; applicability verified by
//! field-level comparison against the entity sources at main 31b88f5;
//! room_master_table identity hash / user version are NOT covered by the
//! extract and are not claimed).
#![cfg(feature = "sqlite")]
use cyclecare_core::sync_codec::{CycleDayLog, CycleSettings, OnboardingProfile};
use cyclecare_core::v3_dao_sqlite::*;
use cyclecare_core::v3_outbox::{CommitOutcome, ConflictRow, OutboxEntry, V3DaoError, V3SyncDao};
use cyclecare_core::v3_pull::*;
use cyclecare_core::v3_sync::{wire_record_id, V3WireChange};
use rusqlite::Connection;

const OWNER: &str = "owner-uid";

fn store() -> SqliteV3Store { SqliteV3Store::open_in_memory().unwrap() }

fn entry(op: &str, record: &str, base: i64, rev: i64, next: i64) -> OutboxEntry {
    OutboxEntry {
        owner: OWNER.into(), operation_id: op.into(), record_id: record.into(), kind: "UPSERT".into(),
        base_revision: base, revision: rev, payload_json: Some("{}".into()), payload_hash: "h".into(),
        consent_generation: 1, attempts: 0, next_attempt_at: next,
    }
}
fn conflict(record: &str) -> ConflictRow {
    ConflictRow {
        owner: OWNER.into(), record_id: record.into(), operation_id: "op-c".into(), local_revision: 2,
        payload_json: Some("{\"local\":1}".into()), kind: "UPSERT".into(), remote_revision: 5,
        remote_payload: Some("{\"remote\":1}".into()), remote_tombstone: false, created_ms: 1_700_000_000_000,
    }
}
fn server_rev(conn: &Connection, record: &str) -> Option<i64> {
    conn.query_row("SELECT serverRevision FROM health_sync_revisions WHERE ownerId=?1 AND recordId=?2",
        rusqlite::params![OWNER, record], |r| r.get(0)).ok()
}
fn count(conn: &Connection, table: &str) -> i64 {
    conn.query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |r| r.get(0)).unwrap()
}

#[test]
fn schema_matches_ksp_generated_ddl_statement_for_statement() {
    // Normalize: strip backticks, collapse whitespace; compare statement sets.
    // Whitespace-free comparison (PK constraint spacing differs between
    // hand-written and generated text; column order/types/constraints carry
    // the meaning). Comment lines are stripped BEFORE splitting on ';' -
    // a comment may itself contain a semicolon.
    let strip_comments = |s: &str| s.lines().filter(|l| !l.trim_start().starts_with("--")).collect::<Vec<_>>().join("\n");
    let norm = |s: &str| s.replace('`', "").split_whitespace().collect::<String>();
    let ksp = strip_comments(include_str!("fixtures/room_schema_ksp.sql"));
    let mine = strip_comments(&format!("{SCHEMA}\n{DOMAIN_SCHEMA}"));
    for stmt in ksp.split(';').map(norm).filter(|s| s.starts_with("CREATE")) {
        assert!(mine.split(';').map(&norm).any(|m| m == stmt),
            "KSP statement missing or different in shipped DDL:\n{stmt}");
    }
    // And the reverse: no extra tables/indices beyond the KSP set.
    let ksp_count = ksp.split(';').map(norm).filter(|s| s.starts_with("CREATE")).count();
    let mine_count = mine.split(';').map(&norm).filter(|s| s.starts_with("CREATE")).count();
    assert_eq!(ksp_count, mine_count, "same number of CREATE statements");
}

#[test]
fn pending_orders_by_next_attempt_then_revision() {
    let mut s = store();
    s.enqueue(&entry("op-b", "r1", 0, 5, 10)).unwrap();
    s.enqueue(&entry("op-a", "r1", 0, 3, 10)).unwrap();
    s.enqueue(&entry("op-c", "r1", 0, 9, 1)).unwrap();
    let ops: Vec<String> = s.pending(OWNER).unwrap().into_iter().map(|e| e.operation_id).collect();
    assert_eq!(ops, ["op-c", "op-a", "op-b"], "ORDER BY nextAttemptAt, revision");
}

#[test]
fn commit_applied_commits_ack_rebase_and_max_only_update() {
    let mut s = store();
    s.conn.execute("INSERT INTO health_sync_revisions (ownerId, recordId, revision, payloadHash, tombstone, updatedAt, serverRevision) VALUES (?1,'r1',4,'h',0,1,2)", rusqlite::params![OWNER]).unwrap();
    s.enqueue(&entry("op-1", "r1", 2, 4, 0)).unwrap();
    s.enqueue(&entry("op-2", "r1", 2, 5, 0)).unwrap();
    let out = s.commit_applied(OWNER, "op-1", "r1", 4, 2, 7, &mut || true);
    assert_eq!(out, CommitOutcome::Committed);
    assert_eq!(server_rev(&s.conn, "r1"), Some(7));
    let left = s.pending(OWNER).unwrap();
    assert_eq!(left.len(), 1, "acknowledgeMatched deleted op-1");
    assert_eq!((left[0].operation_id.as_str(), left[0].base_revision), ("op-2", 7), "rebasePending 2 -> 7");
}

#[test]
fn commit_applied_never_inserts_and_never_lowers() {
    let mut s = store();
    s.enqueue(&entry("op-1", "r-missing", 2, 4, 0)).unwrap();
    assert_eq!(s.commit_applied(OWNER, "op-1", "r-missing", 4, 2, 7, &mut || true), CommitOutcome::Committed);
    assert_eq!(server_rev(&s.conn, "r-missing"), None, "no row inserted");
    s.conn.execute("INSERT INTO health_sync_revisions (ownerId, recordId, revision, payloadHash, tombstone, updatedAt, serverRevision) VALUES (?1,'r2',4,'h',0,1,9)", rusqlite::params![OWNER]).unwrap();
    s.enqueue(&entry("op-3", "r2", 2, 4, 0)).unwrap();
    assert_eq!(s.commit_applied(OWNER, "op-3", "r2", 4, 2, 5, &mut || true), CommitOutcome::Committed);
    assert_eq!(server_rev(&s.conn, "r2"), Some(9), "never lowered");
}

#[test]
fn commit_applied_read_fault_is_db_error_not_missing_row() {
    // A database ERROR on the revision read must roll back and report
    // DbError; treating it as "row missing" would commit ACK+rebase against
    // an unread existing row (the v58 review bug).
    let mut s = store();
    s.conn.execute("INSERT INTO health_sync_revisions (ownerId, recordId, revision, payloadHash, tombstone, updatedAt, serverRevision) VALUES (?1,'r1',4,'h',0,1,2)", rusqlite::params![OWNER]).unwrap();
    s.enqueue(&entry("op-1", "r1", 2, 4, 0)).unwrap();
    s.conn.execute_batch("CREATE TRIGGER fail_rev_read BEFORE UPDATE ON health_sync_revisions BEGIN SELECT RAISE(ABORT, 'injected'); END;").unwrap();
    // Force a real fault through the read path by corrupting via trigger on the UPDATE;
    // the read itself faults when the table is dropped underneath a second handle -
    // here: drop+rename trick is not possible mid-store, so fault the UPDATE and
    // prove rollback; then separately prove a read error maps to DbError.
    assert_eq!(s.commit_applied(OWNER, "op-1", "r1", 4, 2, 7, &mut || true), CommitOutcome::DbError);
    assert_eq!(server_rev(&s.conn, "r1"), Some(2), "UPDATE rolled back by the engine");
    assert_eq!(s.pending(OWNER).unwrap().len(), 1, "ACK rolled back");
}

#[test]
fn commit_applied_revision_read_error_maps_to_db_error() {
    let mut s = store();
    s.enqueue(&entry("op-1", "r1", 2, 4, 0)).unwrap();
    // Make the revision read fail outright (table gone).
    s.conn.execute_batch("DROP TABLE health_sync_revisions;").unwrap();
    assert_eq!(s.commit_applied(OWNER, "op-1", "r1", 4, 2, 7, &mut || true), CommitOutcome::DbError,
        "read error is DbError, never 'row missing'");
    assert_eq!(s.pending(OWNER).unwrap().len(), 1, "nothing acked or rebased");
}

#[test]
fn gate_drift_rolls_back_and_is_distinct_from_db_error() {
    let mut s = store();
    s.conn.execute("INSERT INTO health_sync_revisions (ownerId, recordId, revision, payloadHash, tombstone, updatedAt, serverRevision) VALUES (?1,'r1',4,'h',0,1,2)", rusqlite::params![OWNER]).unwrap();
    s.enqueue(&entry("op-1", "r1", 2, 4, 0)).unwrap();
    assert_eq!(s.commit_applied(OWNER, "op-1", "r1", 4, 2, 7, &mut || false), CommitOutcome::GateDrift);
    assert_eq!(server_rev(&s.conn, "r1"), Some(2));
    assert_eq!(count(&s.conn, "health_sync_outbox"), 1);
}

#[test]
fn retry_and_put_conflict_propagate_write_faults() {
    let mut s = store();
    s.enqueue(&entry("op-1", "r1", 2, 4, 0)).unwrap();
    s.retry(OWNER, "op-1", 1_700_000_001_000, "Backup needs another try").unwrap();
    let (a, n): (i64, i64) = s.conn.query_row(
        "SELECT attempts, nextAttemptAt FROM health_sync_outbox WHERE ownerId=?1 AND operationId='op-1'",
        rusqlite::params![OWNER], |r| Ok((r.get(0)?, r.get(1)?))).unwrap();
    assert_eq!((a, n), (1, 1_700_000_001_000));
    // Write fault: retry and put_conflict surface typed Err, never silent.
    s.conn.execute_batch("CREATE TRIGGER fail_retry BEFORE UPDATE ON health_sync_outbox BEGIN SELECT RAISE(ABORT, 'injected'); END;
                          CREATE TRIGGER fail_conflict BEFORE INSERT ON health_sync_conflicts BEGIN SELECT RAISE(ABORT, 'injected'); END;").unwrap();
    assert_eq!(s.retry(OWNER, "op-1", 5, "x"), Err(V3DaoError::Db));
    assert_eq!(s.put_conflict(conflict("r1")), Err(V3DaoError::Db));
    assert_eq!(count(&s.conn, "health_sync_conflicts"), 0);
}

#[test]
fn pending_read_fault_is_typed_not_empty() {
    let mut s = store();
    s.enqueue(&entry("op-1", "r1", 2, 4, 0)).unwrap();
    s.conn.execute_batch("DROP TABLE health_sync_outbox;").unwrap();
    assert_eq!(s.pending(OWNER), Err(V3DaoError::Db), "read fault is Err, never an empty list");
}

#[test]
fn put_conflict_is_replace_upsert() {
    let mut s = store();
    s.put_conflict(conflict("r1")).unwrap();
    let mut c2 = conflict("r1");
    c2.remote_revision = 6;
    s.put_conflict(c2).unwrap();
    let (n, rr): (i64, i64) = s.conn.query_row(
        "SELECT COUNT(*), MAX(remoteRevision) FROM health_sync_conflicts WHERE ownerId=?1 AND recordId='r1'",
        rusqlite::params![OWNER], |r| Ok((r.get(0)?, r.get(1)?))).unwrap();
    assert_eq!((n, rr), (1, 6));
}

fn wire_change(id: &str, rev: i64, seq: i64) -> V3WireChange {
    let l = CycleDayLog {
        date: id.into(), flow: "LIGHT".into(), energy: "".into(), mood: "".into(), symptoms: "".into(),
        vaginal_health: "".into(), urinary_health: "".into(), cervical_mucus: "".into(),
        ovulation_test: "".into(), bbt_celsius: 0.0, sleep_hours: 0.0, sleep_quality: "".into(),
        water_glasses: 0, notes: "".into(), pain_intensity: 0,
    };
    V3WireChange {
        record_id: wire_record_id(OWNER, id), revision: rev, change_seq: seq,
        payload: Some(cyclecare_core::sync_codec::JsonParser::new(&format!(
            r#"{{"kind":"UPSERT","logicalRecordId":"{id}","value":{}}}"#, cyclecare_core::sync_codec::log_payload(&l))).parse().unwrap()),
        tombstone: false, payload_hash: None, logical_record_id: Some(id.into()),
    }
}
struct Pages(std::collections::VecDeque<Vec<V3WireChange>>);
impl V3ReadTransport for Pages {
    fn read_changes(&mut self, _a: i64, _l: i64, _g: i64, _t: &str) -> Result<Vec<V3WireChange>, ()> {
        Ok(self.0.pop_front().unwrap_or_default())
    }
}

#[test]
fn pull_commits_domain_and_sync_rows_on_one_connection() {
    let mut s = store();
    s.enqueue(&entry("op-p", "2026-11-02", 0, 3, 0)).unwrap(); // pending -> conflict path
    let mut tx = Pages(vec![vec![wire_change("2026-11-01", 5, 1), wire_change("2026-11-02", 6, 2)]].into());
    let n = run_v3_pull(&mut tx, "tok", &mut s, OWNER, 3, 7, &mut || true, &mut || 1_700_000_000_000).unwrap();
    assert_eq!(n, 1);
    // Domain row written through the SAME connection's real table.
    let flow: String = s.conn.query_row(
        "SELECT flowLevel FROM cycle_day_logs WHERE dateString='2026-11-01'", [], |r| r.get(0)).unwrap();
    assert_eq!(flow, "LIGHT");
    let (rev, srv): (i64, i64) = s.conn.query_row(
        "SELECT revision, serverRevision FROM health_sync_revisions WHERE ownerId=?1 AND recordId='2026-11-01'",
        rusqlite::params![OWNER], |r| Ok((r.get(0)?, r.get(1)?))).unwrap();
    assert_eq!((rev, srv), (1, 5));
    assert_eq!(s.cursor(OWNER), Ok(Some(2)));
    let (op, rr): (String, i64) = s.conn.query_row(
        "SELECT operationId, remoteRevision FROM health_sync_conflicts WHERE ownerId=?1 AND recordId='2026-11-02'",
        rusqlite::params![OWNER], |r| Ok((r.get(0)?, r.get(1)?))).unwrap();
    assert_eq!((op.as_str(), rr), ("op-p", 6));
}

#[test]
fn pull_fault_rolls_back_domain_and_sync_rows_together() {
    // THE same-connection atomicity proof: a mid-page engine fault rolls the
    // DOMAIN log row back together with the sync revision/cursor rows.
    let mut s = store();
    s.conn.execute_batch("CREATE TRIGGER fail_cursor BEFORE INSERT ON health_sync_cursors BEGIN SELECT RAISE(ABORT, 'injected'); END;").unwrap();
    let mut tx = Pages(vec![vec![wire_change("2026-11-01", 5, 1)]].into());
    let r = run_v3_pull(&mut tx, "tok", &mut s, OWNER, 3, 7, &mut || true, &mut || 1_700_000_000_000);
    assert_eq!(r, Err(V3PullError::Db));
    assert_eq!(count(&s.conn, "health_sync_revisions"), 0, "sync row rolled back");
    assert_eq!(count(&s.conn, "health_sync_cursors"), 0);
    assert_eq!(count(&s.conn, "cycle_day_logs"), 0, "DOMAIN row rolled back by the same transaction");
}

fn wire_settings_change(rev: i64, seq: i64) -> V3WireChange {
    let v = CycleSettings {
        id: 1, is_onboarding_completed: true, selected_goals_csv: "g".into(),
        last_period_start_date: "2026-10-01".into(), average_cycle_length: 29,
        period_duration: 5, luteal_phase_days: 14, is_app_lock_enabled: false,
        lock_timeout_minutes: 5, is_discreet_notification_mode: false,
        is_pregnancy_mode: false, pregnancy_due_date: "".into(), remind_period: true,
        remind_fertile: false, remind_daily_log: true,
    };
    V3WireChange {
        record_id: wire_record_id(OWNER, "settings"), revision: rev, change_seq: seq,
        payload: Some(cyclecare_core::sync_codec::JsonParser::new(&format!(
            r#"{{"kind":"UPSERT","logicalRecordId":"settings","value":{}}}"#, cyclecare_core::sync_codec::settings_payload(&v))).parse().unwrap()),
        tombstone: false, payload_hash: None, logical_record_id: Some("settings".into()),
    }
}

#[test]
fn domain_settings_read_fault_is_typed_and_never_defaults_or_overwrites() {
    // The .ok().flatten() bug class: a faulted settings read must surface as
    // a typed error with the whole page rolled back - NEVER as an absent row
    // that would merge remote fields over CycleSettings::default() and save.
    let mut s = store();
    // Local row with distinctive local-only content the overwrite would lose.
    s.conn.execute(
        "INSERT OR REPLACE INTO cycle_settings (id, isOnboardingCompleted, selectedGoalsCsv, lastPeriodStartDate,
         averageCycleLength, periodDuration, lutealPhaseDays, isAppLockEnabled, lockTimeoutMinutes,
         isDiscreetNotificationMode, isPregnancyMode, pregnancyDueDate, remindPeriod, remindFertile, remindDailyLog)
         VALUES (1, 1, 'local-only-goals', '2026-09-15', 31, 6, 13, 1, 15, 1, 0, '', 0, 1, 0)",
        []).unwrap();
    // Fault the read: rename the table out from under the DAO SELECT.
    s.conn.execute_batch("ALTER TABLE cycle_settings RENAME TO cycle_settings_away;").unwrap();
    let mut tx = Pages(vec![vec![wire_settings_change(5, 1)]].into());
    let r = run_v3_pull(&mut tx, "tok", &mut s, OWNER, 3, 7, &mut || true, &mut || 1_700_000_000_000);
    assert!(matches!(r, Err(V3PullError::ApplyFailed) | Err(V3PullError::Db)),
        "typed error, not a default-merge save: {r:?}");
    assert_eq!(count(&s.conn, "health_sync_revisions"), 0, "page rolled back");
    assert_eq!(count(&s.conn, "health_sync_cursors"), 0, "cursor not advanced");
    s.conn.execute_batch("ALTER TABLE cycle_settings_away RENAME TO cycle_settings;").unwrap();
    let goals: String = s.conn.query_row(
        "SELECT selectedGoalsCsv FROM cycle_settings WHERE id = 1", [], |r| r.get(0)).unwrap();
    assert_eq!(goals, "local-only-goals", "local row never overwritten by a default merge");
}

#[test]
fn cursor_read_fault_is_typed_not_zero() {
    let mut s = store();
    s.conn.execute_batch("DROP TABLE health_sync_cursors;").unwrap();
    assert_eq!(s.cursor(OWNER), Err(V3PullError::Db), "cursor fault is Err, never a silent 0 re-pull");
}

#[test]
fn scratch_file_store_recovers_across_reopen() {
    let f = std::env::temp_dir().join(format!("cyclecare-dao-{}.db", std::process::id()));
    let _ = std::fs::remove_file(&f);
    {
        let mut s = SqliteV3Store::open_file(&f).unwrap();
        s.enqueue(&entry("op-1", "r1", 2, 4, 0)).unwrap();
        s.conn.execute("INSERT INTO health_sync_cursors (ownerId, changeSeq) VALUES (?1, 42)", rusqlite::params![OWNER]).unwrap();
    }
    {
        let mut s = SqliteV3Store::open_file(&f).unwrap();
        assert_eq!(s.pending(OWNER).unwrap().len(), 1);
        assert_eq!(s.cursor(OWNER), Ok(Some(42)));
    }
    let _ = std::fs::remove_file(&f);
}

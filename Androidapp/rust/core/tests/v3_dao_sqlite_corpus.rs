//! REAL-SQLite proofs for SqliteV3Store: verbatim HealthSyncLocalDao SQL
//! against the production-shaped schema, real BEGIN/COMMIT/ROLLBACK per
//! transaction seam, engine-enforced rollback via RAISE(ABORT) triggers.
//! Synthetic scratch data only; host-only feature `sqlite`. Android Room on
//! the encrypted app DB remains a separate, open integration.
#![cfg(feature = "sqlite")]
use cyclecare_core::sync_codec::{CycleDayLog, CycleSettings, OnboardingProfile};
use cyclecare_core::v3_dao_sqlite::*;
use cyclecare_core::v3_outbox::{CommitOutcome, ConflictRow, OutboxEntry, V3SyncDao};
use cyclecare_core::v3_pull::*;
use cyclecare_core::v3_sync::{wire_record_id, V3WireChange};
use rusqlite::Connection;

const OWNER: &str = "owner-uid";

#[derive(Default)]
struct MemDomain { settings: Option<CycleSettings>, profile: Option<OnboardingProfile>, logs: Vec<CycleDayLog> }
impl DomainStore for MemDomain {
    fn get_settings(&mut self) -> Option<CycleSettings> { self.settings.clone() }
    fn save_settings(&mut self, s: CycleSettings) -> Result<(), String> { self.settings = Some(s); Ok(()) }
    fn get_profile(&mut self) -> Option<OnboardingProfile> { self.profile.clone() }
    fn save_profile(&mut self, p: OnboardingProfile) -> Result<(), String> { self.profile = Some(p); Ok(()) }
    fn upsert_log(&mut self, l: CycleDayLog) -> Result<(), String> { self.logs.retain(|x| x.date != l.date); self.logs.push(l); Ok(()) }
    fn delete_log(&mut self, date: &str) -> Result<(), String> { self.logs.retain(|x| x.date != date); Ok(()) }
}

fn store() -> SqliteV3Store<MemDomain> { SqliteV3Store::open_in_memory(MemDomain::default()).unwrap() }

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
fn schema_matches_room_entity_shapes() {
    let s = store();
    let cols = |t: &str| -> Vec<(String, String, bool, bool)> {
        let mut st = s.conn.prepare(&format!("PRAGMA table_info({t})")).unwrap();
        st.query_map([], |r| Ok((r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, bool>(3)?, r.get::<_, i64>(5)? > 0)))
            .unwrap().map(|r| r.unwrap()).collect()
    };
    let rev = cols("health_sync_revisions");
    assert_eq!(rev.iter().map(|c| c.0.as_str()).collect::<Vec<_>>(),
        ["ownerId", "recordId", "revision", "payloadHash", "tombstone", "updatedAt", "serverRevision"]);
    assert!(rev[0].3 && rev[1].3, "composite PK ownerId+recordId");
    assert!(!rev[3].2, "payloadHash nullable");
    let out = cols("health_sync_outbox");
    assert_eq!(out.iter().map(|c| c.0.as_str()).collect::<Vec<_>>(),
        ["ownerId", "operationId", "recordId", "kind", "baseRevision", "revision", "payloadJson",
         "payloadHash", "consentGeneration", "attempts", "nextAttemptAt", "lastError", "serverReceipt", "serverRevision"]);
    assert!(out[0].3 && out[1].3, "composite PK ownerId+operationId");
    assert!(out[6].3 == false && out[11].3 == false && out[12].3 == false && out[13].3 == false, "nullable columns");
    let con = cols("health_sync_conflicts");
    assert_eq!(con.iter().map(|c| c.0.as_str()).collect::<Vec<_>>(),
        ["ownerId", "recordId", "operationId", "localRevision", "localPayloadJson", "localKind",
         "remoteRevision", "remotePayloadJson", "remoteTombstone", "createdAt", "resolvedAt", "resolution"]);
    let cur = cols("health_sync_cursors");
    assert_eq!(cur.iter().map(|c| c.0.as_str()).collect::<Vec<_>>(), ["ownerId", "changeSeq"]);
    let idx: Vec<String> = { let mut st = s.conn.prepare("SELECT name FROM sqlite_master WHERE type='index' AND name LIKE 'index_health%'").unwrap();
        st.query_map([], |r| r.get(0)).unwrap().map(|r| r.unwrap()).collect() };
    assert!(idx.contains(&"index_health_sync_outbox_ownerId_nextAttemptAt".to_string()));
    assert!(idx.contains(&"index_health_sync_conflicts_ownerId_resolvedAt".to_string()));
}

#[test]
fn pending_orders_by_next_attempt_then_revision() {
    let mut s = store();
    s.enqueue(&entry("op-b", "r1", 0, 5, 10)).unwrap();
    s.enqueue(&entry("op-a", "r1", 0, 3, 10)).unwrap();
    s.enqueue(&entry("op-c", "r1", 0, 9, 1)).unwrap();
    let ops: Vec<String> = s.pending(OWNER).into_iter().map(|e| e.operation_id).collect();
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
    assert_eq!(server_rev(&s.conn, "r1"), Some(7), "serverRevision max-updated");
    let left: Vec<String> = s.pending(OWNER).into_iter().map(|e| e.operation_id).collect();
    assert_eq!(left, ["op-2"], "acknowledgeMatched deleted op-1");
    let rebased = s.pending(OWNER).pop().unwrap();
    assert_eq!(rebased.base_revision, 7, "rebasePending old 2 -> new 7");
}

#[test]
fn commit_applied_never_inserts_and_never_lowers() {
    let mut s = store();
    s.enqueue(&entry("op-1", "r-missing", 2, 4, 0)).unwrap();
    // No revision row: update is a no-op, never an INSERT; ACK+rebase still commit.
    assert_eq!(s.commit_applied(OWNER, "op-1", "r-missing", 4, 2, 7, &mut || true), CommitOutcome::Committed);
    assert_eq!(server_rev(&s.conn, "r-missing"), None, "no row inserted");
    // Existing higher serverRevision: never lowered.
    s.conn.execute("INSERT INTO health_sync_revisions (ownerId, recordId, revision, payloadHash, tombstone, updatedAt, serverRevision) VALUES (?1,'r2',4,'h',0,1,9)", rusqlite::params![OWNER]).unwrap();
    s.enqueue(&entry("op-3", "r2", 2, 4, 0)).unwrap();
    assert_eq!(s.commit_applied(OWNER, "op-3", "r2", 4, 2, 5, &mut || true), CommitOutcome::Committed);
    assert_eq!(server_rev(&s.conn, "r2"), Some(9), "never lowered");
}

#[test]
fn gate_drift_rolls_back_and_is_distinct_from_db_error() {
    let mut s = store();
    s.conn.execute("INSERT INTO health_sync_revisions (ownerId, recordId, revision, payloadHash, tombstone, updatedAt, serverRevision) VALUES (?1,'r1',4,'h',0,1,2)", rusqlite::params![OWNER]).unwrap();
    s.enqueue(&entry("op-1", "r1", 2, 4, 0)).unwrap();
    assert_eq!(s.commit_applied(OWNER, "op-1", "r1", 4, 2, 7, &mut || false), CommitOutcome::GateDrift);
    assert_eq!(server_rev(&s.conn, "r1"), Some(2), "untouched");
    assert_eq!(count(&s.conn, "health_sync_outbox"), 1, "ACK not applied");
    // Engine-enforced DB failure: RAISE(ABORT) on the ACK delete -> DbError,
    // and the serverRevision UPDATE from the SAME transaction rolls back.
    s.conn.execute_batch("CREATE TRIGGER fail_ack BEFORE DELETE ON health_sync_outbox BEGIN SELECT RAISE(ABORT, 'injected'); END;").unwrap();
    assert_eq!(s.commit_applied(OWNER, "op-1", "r1", 4, 2, 7, &mut || true), CommitOutcome::DbError);
    assert_eq!(server_rev(&s.conn, "r1"), Some(2), "UPDATE rolled back by the engine");
    assert_eq!(count(&s.conn, "health_sync_outbox"), 1);
}

#[test]
fn retry_increments_attempts_with_backoff_fields() {
    let mut s = store();
    s.enqueue(&entry("op-1", "r1", 2, 4, 0)).unwrap();
    s.retry(OWNER, "op-1", 1_700_000_001_000, "Backup needs another try");
    let (a, n, e): (i64, i64, String) = s.conn.query_row(
        "SELECT attempts, nextAttemptAt, lastError FROM health_sync_outbox WHERE ownerId=?1 AND operationId='op-1'",
        rusqlite::params![OWNER], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?))).unwrap();
    assert_eq!((a, n, e.as_str()), (1, 1_700_000_001_000, "Backup needs another try"));
}

#[test]
fn put_conflict_is_replace_upsert() {
    let mut s = store();
    s.put_conflict(conflict("r1"));
    let mut c2 = conflict("r1");
    c2.remote_revision = 6;
    s.put_conflict(c2);
    let (n, rr): (i64, i64) = s.conn.query_row(
        "SELECT COUNT(*), MAX(remoteRevision) FROM health_sync_conflicts WHERE ownerId=?1 AND recordId='r1'",
        rusqlite::params![OWNER], |r| Ok((r.get(0)?, r.get(1)?))).unwrap();
    assert_eq!((n, rr), (1, 6), "REPLACE keeps one row per (owner, record)");
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
fn pull_commits_real_rows_and_conflict_path_writes_conflict() {
    let mut s = store();
    s.enqueue(&entry("op-p", "2026-11-02", 0, 3, 0)).unwrap(); // pending -> conflict path
    let mut tx = Pages(vec![vec![wire_change("2026-11-01", 5, 1), wire_change("2026-11-02", 6, 2)]].into());
    let n = run_v3_pull(&mut tx, "tok", &mut s, OWNER, 3, 7, &mut || true, &mut || 1_700_000_000_000).unwrap();
    assert_eq!(n, 1, "only the non-conflicted record applied");
    assert_eq!(s.domain.logs.len(), 1);
    assert_eq!(s.domain.logs[0].date, "2026-11-01");
    let (rev, srv): (i64, i64) = s.conn.query_row(
        "SELECT revision, serverRevision FROM health_sync_revisions WHERE ownerId=?1 AND recordId='2026-11-01'",
        rusqlite::params![OWNER], |r| Ok((r.get(0)?, r.get(1)?))).unwrap();
    assert_eq!((rev, srv), (1, 5));
    assert_eq!(s.cursor(OWNER), Some(2), "cursor row committed");
    let (op, rr): (String, i64) = s.conn.query_row(
        "SELECT operationId, remoteRevision FROM health_sync_conflicts WHERE ownerId=?1 AND recordId='2026-11-02'",
        rusqlite::params![OWNER], |r| Ok((r.get(0)?, r.get(1)?))).unwrap();
    assert_eq!((op.as_str(), rr), ("op-p", 6), "conflict row with pending operationId");
}

#[test]
fn pull_fault_mid_page_rolls_back_real_rows() {
    let mut s = store();
    s.conn.execute_batch("CREATE TRIGGER fail_cursor BEFORE INSERT ON health_sync_cursors BEGIN SELECT RAISE(ABORT, 'injected'); END;").unwrap();
    let mut tx = Pages(vec![vec![wire_change("2026-11-01", 5, 1)]].into());
    let r = run_v3_pull(&mut tx, "tok", &mut s, OWNER, 3, 7, &mut || true, &mut || 1_700_000_000_000);
    assert_eq!(r, Err(V3PullError::Db));
    assert_eq!(count(&s.conn, "health_sync_revisions"), 0, "engine rolled back the revision write");
    assert_eq!(count(&s.conn, "health_sync_cursors"), 0);
    assert!(s.domain.logs.len() <= 1, "domain seam is the adapter's atomicity obligation (documented)");
}

#[test]
fn scratch_file_store_recovers_across_reopen() {
    // Ordinary restart recovery (rename/WAL not crash-fault tested).
    let f = std::env::temp_dir().join(format!("cyclecare-dao-{}.db", std::process::id()));
    let _ = std::fs::remove_file(&f);
    {
        let mut s = SqliteV3Store::open_file(&f, MemDomain::default()).unwrap();
        s.enqueue(&entry("op-1", "r1", 2, 4, 0)).unwrap();
        s.conn.execute("INSERT INTO health_sync_cursors (ownerId, changeSeq) VALUES (?1, 42)", rusqlite::params![OWNER]).unwrap();
    }
    {
        let mut s = SqliteV3Store::open_file(&f, MemDomain::default()).unwrap();
        assert_eq!(s.pending(OWNER).len(), 1);
        assert_eq!(s.cursor(OWNER), Some(42));
    }
    let _ = std::fs::remove_file(&f);
}

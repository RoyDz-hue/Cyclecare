//! REAL scratch-DB rollback proofs (rusqlite in-memory, bundled SQLite) for
//! the V3 transaction seams. Fault injection uses a real SQL trigger that
//! RAISE(ABORT)s mid-transaction, so rollback is exercised by the database
//! engine, not by a model. Run with: cargo test --features sqlite
//! Synthetic data only; no files, no network, no production DB.
#![cfg(feature = "sqlite")]
use cyclecare_core::sync_codec::{log_payload, parse_log_payload, CycleDayLog, CycleSettings, OnboardingProfile};
use cyclecare_core::v3_outbox::*;
use cyclecare_core::v3_pull::*;
use cyclecare_core::v3_sync::{wire_record_id, V3WireChange, V3WireReceipt};
use rusqlite::{params, Connection, OptionalExtension};

const OWNER: &str = "owner-uid";

fn db() -> Connection {
    let c = Connection::open_in_memory().unwrap();
    c.execute_batch(
        "CREATE TABLE cursor(owner TEXT PRIMARY KEY, seq INTEGER NOT NULL);
         CREATE TABLE revisions(record_id TEXT PRIMARY KEY, revision INTEGER NOT NULL, server_revision INTEGER NOT NULL);
         CREATE TABLE logs(date TEXT PRIMARY KEY, payload TEXT NOT NULL);
         CREATE TABLE conflicts(record_id TEXT, operation_id TEXT, remote_revision INTEGER);
         CREATE TABLE pending(operation_id TEXT PRIMARY KEY, record_id TEXT NOT NULL, kind TEXT NOT NULL,
            base_revision INTEGER NOT NULL, revision INTEGER NOT NULL, payload_json TEXT,
            consent_generation INTEGER NOT NULL, attempts INTEGER NOT NULL, next_attempt_at INTEGER NOT NULL);",
    ).unwrap();
    c
}

fn log_value(date: &str, flow: &str) -> String {
    log_payload(&CycleDayLog {
        date: date.into(), flow: flow.into(), energy: "".into(), mood: "".into(), symptoms: "".into(),
        vaginal_health: "".into(), urinary_health: "".into(), cervical_mucus: "".into(),
        ovulation_test: "".into(), bbt_celsius: 0.0, sleep_hours: 0.0, sleep_quality: "".into(),
        water_glasses: 0, notes: "".into(), pain_intensity: 0,
    })
}

fn change(id: &str, rev: i64, seq: i64) -> V3WireChange {
    V3WireChange {
        record_id: wire_record_id(OWNER, id),
        revision: rev,
        change_seq: seq,
        payload: Some(cyclecare_core::sync_codec::JsonParser::new(
            &format!(r#"{{"kind":"UPSERT","logicalRecordId":"{id}","value":{}}}"#, log_value(id, "LIGHT")),
        ).parse().unwrap()),
        tombstone: false,
        payload_hash: None,
        logical_record_id: Some(id.into()),
    }
}

// ----------------------------- pull store ---------------------------------
struct SqlPull { conn: Connection }
struct SqlTx<'a> { tx: &'a rusqlite::Transaction<'a> }

fn dbe<T>(r: rusqlite::Result<T>) -> Result<T, V3PullError> { r.map_err(|_| V3PullError::Db) }

impl DomainStore for SqlTx<'_> {
    // Settings/profile are not tabled in this scratch DB (only logs are
    // exercised); they are inert here by design.
    fn get_settings(&mut self) -> Option<CycleSettings> { None }
    fn save_settings(&mut self, _s: CycleSettings) -> Result<(), String> { Ok(()) }
    fn get_profile(&mut self) -> Option<OnboardingProfile> { None }
    fn save_profile(&mut self, _p: OnboardingProfile) -> Result<(), String> { Ok(()) }
    fn upsert_log(&mut self, l: CycleDayLog) -> Result<(), String> {
        self.tx.execute("INSERT OR REPLACE INTO logs(date,payload) VALUES(?,?)", params![l.date, log_payload(&l)])
            .map(|_| ()).map_err(|e| e.to_string())
    }
    fn delete_log(&mut self, date: &str) -> Result<(), String> {
        self.tx.execute("DELETE FROM logs WHERE date=?", params![date]).map(|_| ()).map_err(|e| e.to_string())
    }
}
impl V3PullTx for SqlTx<'_> {
    fn revision(&mut self, _o: &str, id: &str) -> Result<Option<RevisionRow>, V3PullError> {
        let mut s = dbe(self.tx.prepare("SELECT revision, server_revision FROM revisions WHERE record_id=?"))?;
        let mut rows = dbe(s.query(params![id]))?;
        match dbe(rows.next())? {
            Some(r) => Ok(Some(RevisionRow { revision: dbe(r.get(0))?, server_revision: dbe(r.get(1))? })),
            None => Ok(None),
        }
    }
    fn put_revision(&mut self, _o: &str, id: &str, rev: i64, _h: Option<String>, _t: bool, _n: i64, srv: i64) -> Result<(), V3PullError> {
        dbe(self.tx.execute("INSERT OR REPLACE INTO revisions(record_id,revision,server_revision) VALUES(?,?,?)", params![id, rev, srv])).map(|_| ())
    }
    fn pending_for_record(&mut self, _o: &str, _id: &str) -> Result<Vec<OutboxEntry>, V3PullError> { Ok(vec![]) }
    fn put_conflict(&mut self, c: ConflictRow) -> Result<(), V3PullError> {
        dbe(self.tx.execute("INSERT INTO conflicts VALUES(?,?,?)", params![c.record_id, c.operation_id, c.remote_revision])).map(|_| ())
    }
    fn put_cursor(&mut self, o: &str, seq: i64) -> Result<(), V3PullError> {
        dbe(self.tx.execute("INSERT OR REPLACE INTO cursor(owner,seq) VALUES(?,?)", params![o, seq])).map(|_| ())
    }
}
impl V3PullStore for SqlPull {
    fn cursor(&mut self, o: &str) -> Result<Option<i64>, V3PullError> {
        dbe(self.conn.query_row("SELECT seq FROM cursor WHERE owner=?", params![o], |r| r.get(0)).optional())
    }
    fn transaction(&mut self, body: &mut dyn FnMut(&mut dyn V3PullTx) -> Result<i32, V3PullError>) -> Result<i32, V3PullError> {
        let tx = dbe(self.conn.transaction())?;
        let r = { let mut t = SqlTx { tx: &tx }; body(&mut t) };
        match r {
            Ok(n) => { dbe(tx.commit())?; Ok(n) }
            Err(e) => { drop(tx); Err(e) } // rollback by drop
        }
    }
}

struct Pages(std::collections::VecDeque<Vec<V3WireChange>>);
impl V3ReadTransport for Pages {
    fn read_changes(&mut self, _a: i64, _l: i64, _g: i64, _t: &str) -> Result<Vec<V3WireChange>, ()> {
        Ok(self.0.pop_front().unwrap_or_default())
    }
}
fn count(conn: &Connection, table: &str) -> i64 {
    conn.query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |r| r.get(0)).unwrap()
}

#[test]
fn real_db_pull_commits_a_clean_page() {
    let mut store = SqlPull { conn: db() };
    let mut tx = Pages(vec![vec![change("2026-11-03", 5, 10), change("2026-11-04", 5, 11)]].into());
    let r = run_v3_pull(&mut tx, "tok", &mut store, OWNER, 3, 11, &mut || true, &mut || 1);
    assert_eq!(r, Ok(2));
    assert_eq!(count(&store.conn, "logs"), 2);
    assert_eq!(store.cursor(OWNER), Ok(Some(11)));
}

#[test]
fn real_db_fault_mid_page_rolls_back_domain_revisions_and_cursor() {
    let mut store = SqlPull { conn: db() };
    // Real fault: the SECOND change's revision insert aborts the statement.
    store.conn.execute_batch(
        "CREATE TRIGGER fault BEFORE INSERT ON revisions WHEN NEW.record_id='2026-11-04'
         BEGIN SELECT RAISE(ABORT,'injected_fault'); END;",
    ).unwrap();
    let mut tx = Pages(vec![vec![change("2026-11-03", 5, 10), change("2026-11-04", 5, 11)]].into());
    let r = run_v3_pull(&mut tx, "tok", &mut store, OWNER, 3, 11, &mut || true, &mut || 1);
    assert_eq!(r, Err(V3PullError::Db));
    // Change 1 had fully applied inside the txn; the engine rolled it back.
    assert_eq!(count(&store.conn, "logs"), 0, "first change's log rolled back");
    assert_eq!(count(&store.conn, "revisions"), 0, "first change's revision rolled back");
    assert_eq!(count(&store.conn, "cursor"), 0, "cursor not advanced");
}

// ---------------------------- outbox commit -------------------------------
struct SqlOutbox { conn: Connection }
impl V3SyncDao for SqlOutbox {
    fn pending(&mut self, _o: &str) -> Result<Vec<OutboxEntry>, V3DaoError> {
        let mut s = self.conn.prepare("SELECT operation_id,record_id,kind,base_revision,revision,payload_json,consent_generation,attempts,next_attempt_at FROM pending").map_err(|_| V3DaoError::Db)?;
        let rows = s.query_map([], |r| Ok(OutboxEntry {
            owner: OWNER.into(), operation_id: r.get(0)?, record_id: r.get(1)?, kind: r.get(2)?,
            base_revision: r.get(3)?, revision: r.get(4)?, payload_json: r.get(5)?,
            payload_hash: "h".into(), consent_generation: r.get(6)?, attempts: r.get(7)?, next_attempt_at: r.get(8)?,
        })).map_err(|_| V3DaoError::Db)?;
        rows.collect::<rusqlite::Result<Vec<_>>>().map_err(|_| V3DaoError::Db)
    }
    fn commit_applied(&mut self, _o: &str, op: &str, id: &str, _entry_rev: i64, base: i64, new_rev: i64, gate: &mut dyn FnMut() -> bool) -> CommitOutcome {
        let tx = match self.conn.transaction() { Ok(t) => t, Err(_) => return CommitOutcome::DbError };
        if !gate() { return CommitOutcome::GateDrift; } // drop = rollback
        let run = || -> rusqlite::Result<()> {
            let existing: Option<i64> = tx.query_row("SELECT server_revision FROM revisions WHERE record_id=?", params![id], |r| r.get(0)).ok();
            if let Some(e) = existing {
                tx.execute("UPDATE revisions SET server_revision=? WHERE record_id=?", params![e.max(new_rev), id])?;
            }
            tx.execute("DELETE FROM pending WHERE operation_id=?", params![op])?;
            tx.execute("UPDATE pending SET base_revision=? WHERE record_id=? AND base_revision=?", params![new_rev, id, base])?;
            Ok(())
        };
        match run() {
            Ok(()) => match tx.commit() { Ok(()) => CommitOutcome::Committed, Err(_) => CommitOutcome::DbError },
            Err(_) => CommitOutcome::DbError, // drop = rollback
        }
    }
    fn put_conflict(&mut self, _c: ConflictRow) -> Result<(), V3DaoError> { Ok(()) }
    fn retry(&mut self, _o: &str, op: &str, next: i64, _m: &str) -> Result<(), V3DaoError> {
        self.conn.execute("UPDATE pending SET next_attempt_at=?, attempts=attempts+1 WHERE operation_id=?", params![next, op]).map(|_| ()).map_err(|_| V3DaoError::Db)
    }
}

struct Tx1(Option<V3WireReceipt>);
impl V3Transport for Tx1 {
    fn apply(&mut self, _op: &cyclecare_core::v3_sync::V3WireOperation, _t: &str) -> Result<V3WireReceipt, ()> {
        self.0.take().ok_or(())
    }
}
fn ack(op: &str, rec: &str, rev: i64) -> V3WireReceipt {
    V3WireReceipt {
        status: "applied".into(), revision: Some(rev), change_seq: None, payload_hash: Some("a".repeat(64)),
        reason: None, record_id: Some(wire_record_id(OWNER, rec)), operation_id: Some(op.into()),
        logical_record_id: Some(rec.into()), remote_payload: None, remote_tombstone: false,
    }
}
fn seed(conn: &Connection) {
    for (op, base, rev) in [("op1", 4, 5), ("op2", 4, 6)] {
        conn.execute("INSERT INTO pending VALUES(?,?,?,?,?,?,?,?,?)",
            params![op, "2026-11-05", "UPSERT", base, rev, r#"{"flow":"LIGHT"}"#, 3, 0, 0]).unwrap();
    }
    conn.execute("INSERT INTO revisions VALUES('2026-11-05', 5, 4)", []).unwrap();
}

#[test]
fn real_db_outbox_commit_updates_max_only_and_rebases() {
    let mut dao = SqlOutbox { conn: db() };
    seed(&dao.conn);
    // Existing server_revision 4 < ACK 5: raised to 5; op1 acked; op2 rebased 4->5.
    let n = run_v3_outbox(&mut Tx1(Some(ack("op1", "2026-11-05", 5))), "tok", &mut dao, OWNER, 7, 3, 11, &mut || true, &mut || 1_000);
    assert_eq!(n, Ok(1));
    let srv: i64 = dao.conn.query_row("SELECT server_revision FROM revisions", [], |r| r.get(0)).unwrap();
    assert_eq!(srv, 5);
    assert_eq!(count(&dao.conn, "pending"), 1);
    let base: i64 = dao.conn.query_row("SELECT base_revision FROM pending WHERE operation_id='op2'", [], |r| r.get(0)).unwrap();
    assert_eq!(base, 5);
}

#[test]
fn real_db_outbox_fault_in_rebase_rolls_back_ack_and_revision_then_retries() {
    let mut dao = SqlOutbox { conn: db() };
    seed(&dao.conn);
    // Real fault on the rebase UPDATE of op2 (after the revision UPDATE and
    // op1 DELETE already ran inside the transaction).
    dao.conn.execute_batch(
        "CREATE TRIGGER fault BEFORE UPDATE OF base_revision ON pending
         BEGIN SELECT RAISE(ABORT,'injected_fault'); END;",
    ).unwrap();
    let n = run_v3_outbox(&mut Tx1(Some(ack("op1", "2026-11-05", 5))), "tok", &mut dao, OWNER, 7, 3, 11, &mut || true, &mut || 1_000);
    assert_eq!(n, Ok(0), "not counted");
    let srv: i64 = dao.conn.query_row("SELECT server_revision FROM revisions", [], |r| r.get(0)).unwrap();
    assert_eq!(srv, 4, "revision UPDATE rolled back by the engine");
    assert_eq!(count(&dao.conn, "pending"), 2, "op1 DELETE rolled back");
    // DbError = source catch branch: block + retry bookkeeping on op1.
    let (attempts, next): (i64, i64) = dao.conn.query_row(
        "SELECT attempts,next_attempt_at FROM pending WHERE operation_id='op1'", [], |r| Ok((r.get(0)?, r.get(1)?))).unwrap();
    assert_eq!(attempts, 1);
    assert_eq!(next, 1_000 + 1_000);
}

#[test]
fn real_db_gate_drift_inside_txn_writes_nothing_and_does_not_retry() {
    let mut dao = SqlOutbox { conn: db() };
    seed(&dao.conn);
    let calls = std::cell::Cell::new(0);
    // Gate reads: entry, loop, post-ACK, then IN-TXN (4th) drifts.
    let mut gate = || { calls.set(calls.get() + 1); calls.get() != 4 };
    let n = run_v3_outbox(&mut Tx1(Some(ack("op1", "2026-11-05", 5))), "tok", &mut dao, OWNER, 7, 3, 11, &mut gate, &mut || 1_000);
    assert_eq!(n, Ok(0));
    assert_eq!(count(&dao.conn, "pending"), 2);
    let attempts: i64 = dao.conn.query_row("SELECT attempts FROM pending WHERE operation_id='op1'", [], |r| r.get(0)).unwrap();
    assert_eq!(attempts, 0, "gate drift is not a retry");
}

#[test]
fn real_db_parse_round_trip_sanity() {
    // Confirms the scratch table stores a parseable codec payload.
    let c = db();
    c.execute("INSERT INTO logs VALUES('2026-11-03', ?)", params![log_value("2026-11-03", "LIGHT")]).unwrap();
    let p: String = c.query_row("SELECT payload FROM logs", [], |r| r.get(0)).unwrap();
    assert_eq!(parse_log_payload(&p, "2026-11-03").unwrap().flow, "LIGHT");
}

#[test]
fn real_db_fault_on_domain_write_aborts_page_via_fallible_domain_store() {
    let mut store = SqlPull { conn: db() };
    // Real fault on the DOMAIN table itself (second change's log insert).
    store.conn.execute_batch(
        "CREATE TRIGGER fault BEFORE INSERT ON logs WHEN NEW.date='2026-11-04'
         BEGIN SELECT RAISE(ABORT,'injected_fault'); END;",
    ).unwrap();
    let mut tx = Pages(vec![vec![change("2026-11-03", 5, 10), change("2026-11-04", 5, 11)]].into());
    let r = run_v3_pull(&mut tx, "tok", &mut store, OWNER, 3, 11, &mut || true, &mut || 1);
    assert_eq!(r, Err(V3PullError::ApplyFailed));
    assert_eq!(count(&store.conn, "logs"), 0);
    assert_eq!(count(&store.conn, "revisions"), 0);
    assert_eq!(count(&store.conn, "cursor"), 0);
}

//! Release-gated outbox body, mirrored 1:1 from SupabaseSyncService.kt:61-113
//! with injected transport + DAO seams. Synthetic only.
use cyclecare_core::v3_outbox::*;
use cyclecare_core::v3_sync::{wire_record_id, V3WireReceipt};
use std::cell::RefCell;

const OWNER: &str = "owner-uid";

fn entry(op: &str, record: &str, base: i64, rev: i64, attempts: i64, next_at: i64) -> OutboxEntry {
    OutboxEntry {
        owner: OWNER.into(),
        operation_id: op.into(),
        record_id: record.into(),
        kind: "UPSERT".into(),
        base_revision: base,
        revision: rev,
        payload_json: Some(r#"{"flow":"LIGHT"}"#.into()),
        payload_hash: "h".into(),
        consent_generation: 3,
        attempts,
        next_attempt_at: next_at,
    }
}

#[derive(Default)]
struct MemDao {
    pend: Vec<OutboxEntry>,
    revision_rows: std::collections::HashMap<String, cyclecare_core::v3_pull::RevisionRow>,
    commits: Vec<(String, String, i64, i64, bool)>, // (op, record, new_rev, target_srv, gate)
    fail_commits: bool,
    conflicts: Vec<ConflictRow>,
    retries: Vec<(String, i64)>,
}
impl V3SyncDao for MemDao {
    fn pending(&mut self, owner: &str) -> Vec<OutboxEntry> {
        self.pend.iter().filter(|e| e.owner == owner).cloned().collect()
    }
    fn revision(&mut self, _o: &str, id: &str) -> Option<cyclecare_core::v3_pull::RevisionRow> {
        self.revision_rows.get(id).cloned()
    }
    fn commit_applied(&mut self, _o: &str, op: &str, id: &str, entry_rev: i64, base: i64, new_rev: i64, target_srv: i64, gate: bool) -> bool {
        self.commits.push((op.into(), id.into(), new_rev, target_srv, gate));
        if self.fail_commits || !gate {
            return false; // rollback: nothing visible
        }
        // update-if-exists, never insert, never lower
        if let Some(row) = self.revision_rows.get_mut(id) {
            row.server_revision = row.server_revision.max(target_srv);
        }
        self.pend.retain(|e| e.operation_id != op);
        for e in self.pend.iter_mut().filter(|e| e.record_id == id && e.base_revision == base) {
            e.base_revision = new_rev;
        }
        let _ = entry_rev;
        true
    }
    fn put_conflict(&mut self, c: ConflictRow) { self.conflicts.push(c); }
    fn retry(&mut self, _o: &str, op: &str, next_at: i64, _m: &str) {
        self.retries.push((op.into(), next_at));
        for e in self.pend.iter_mut().filter(|e| e.operation_id == op) { e.next_attempt_at = next_at; }
    }
}

struct ScriptTx {
    script: std::collections::VecDeque<Result<V3WireReceipt, ()>>,
    applied_ops: Vec<(String, String, i64, Option<String>)>, // (op_id, record_id wire, revision, payload json)
}
impl V3Transport for ScriptTx {
    fn apply(&mut self, op: &cyclecare_core::v3_sync::V3WireOperation, _t: &str) -> Result<V3WireReceipt, ()> {
        let payload = op.payload.as_ref().map(cyclecare_core::sync_codec::json_value_to_string);
        self.applied_ops.push((op.operation_id.clone(), op.record_id.clone(), op.revision, payload));
        self.script.pop_front().unwrap_or(Err(()))
    }
}

fn ack(op: &OutboxEntry, revision: i64) -> V3WireReceipt {
    V3WireReceipt {
        status: "applied".into(),
        revision: Some(revision),
        change_seq: None,
        payload_hash: Some("a".repeat(64)),
        reason: None,
        record_id: Some(wire_record_id(OWNER, &op.record_id)),
        operation_id: Some(op.operation_id.clone()),
        logical_record_id: Some(op.record_id.clone()),
        remote_payload: None,
        remote_tombstone: false,
    }
}

fn always_now() -> impl FnMut() -> i64 { || 1_700_000_000_000i64 }
fn live() -> impl FnMut() -> bool { || true }

#[test]
fn release_closed_entry_returns_zero() {
    assert_eq!(execute_v3_outbox(), 0);
}

#[test]
fn applied_ack_commits_and_rebases() {
    let e = entry("op1", "2026-11-03", 4, 5, 0, 0);
    let mut dao = MemDao { pend: vec![e.clone()], ..Default::default() };
    let mut tx = ScriptTx { script: vec![Ok(ack(&e, 5))].into(), applied_ops: vec![] };
    let n = run_v3_outbox(&mut tx, "tok", &mut dao, OWNER, 7, 3, 11, &mut live(), &mut always_now());
    assert_eq!(n, 1);
    // Wire op shape: wire record id, baseRevision+1, envelope payload.
    let (op_id, wire, rev, payload) = &tx.applied_ops[0];
    assert_eq!(op_id, "op1");
    assert_eq!(wire, &wire_record_id(OWNER, "2026-11-03"));
    assert_eq!(*rev, 5);
    assert_eq!(payload.as_deref(), Some(r#"{"kind":"UPSERT","logicalRecordId":"2026-11-03","value":{"flow":"LIGHT"}}"#));
    assert_eq!(dao.commits, vec![("op1".to_string(), "2026-11-03".to_string(), 5, 5, true)]);
    assert!(dao.pend.is_empty(), "ACK removed the entry");
}

#[test]
fn invalid_ack_retries_with_backoff_and_blocks_record() {
    let e = entry("op1", "2026-11-03", 4, 5, 2, 0);
    let e2 = entry("op2", "2026-11-03", 5, 6, 0, 0); // same record, later op
    let mut bad = ack(&e, 5);
    bad.payload_hash = Some("UPPERCASE".repeat(7) + "x"); // fails ^[a-f0-9]{64}$
    let mut dao = MemDao { pend: vec![e.clone(), e2], ..Default::default() };
    let mut tx = ScriptTx { script: vec![Ok(bad)].into(), applied_ops: vec![] };
    let n = run_v3_outbox(&mut tx, "tok", &mut dao, OWNER, 7, 3, 11, &mut live(), &mut always_now());
    assert_eq!(n, 0);
    assert_eq!(dao.retries, vec![("op1".to_string(), 1_700_000_000_000 + 4_000)], "backoff 1000<<2");
    assert_eq!(tx.applied_ops.len(), 1, "op2 skipped: its record is blocked");
}

#[test]
fn conflict_uses_first_max_on_tie_and_remote_value() {
    let e = entry("op1", "settings", 4, 5, 0, 0);
    let remote = cyclecare_core::sync_codec::JsonParser::new(
        r#"{"kind":"UPSERT","logicalRecordId":"settings","value":{"cycleLength":29}}"#,
    ).parse().unwrap();
    let receipt = V3WireReceipt {
        status: "conflict".into(),
        revision: Some(9),
        change_seq: None, payload_hash: None, reason: None,
        record_id: None, operation_id: None, logical_record_id: None,
        remote_payload: Some(remote),
        remote_tombstone: false,
    };
    let mut dao = MemDao { pend: vec![e], ..Default::default() };
    let mut tx = ScriptTx { script: vec![Ok(receipt)].into(), applied_ops: vec![] };
    let n = run_v3_outbox(&mut tx, "tok", &mut dao, OWNER, 7, 3, 11, &mut live(), &mut always_now());
    assert_eq!(n, 0);
    assert_eq!(dao.conflicts.len(), 1);
    let c = &dao.conflicts[0];
    assert_eq!(c.record_id, "settings");
    assert_eq!(c.remote_revision, 9);
    assert_eq!(c.remote_payload.as_deref(), Some(r#"{"cycleLength":29}"#));
    assert_eq!(dao.retries.len(), 0, "conflict does not retry");
}

#[test]
fn gate_drift_breaks_before_dispatch_and_skips_commit() {
    // current() false at entry: nothing sent.
    let e = entry("op1", "2026-11-03", 4, 5, 0, 0);
    let mut dao = MemDao { pend: vec![e.clone()], ..Default::default() };
    let mut tx = ScriptTx { script: vec![Ok(ack(&e, 5))].into(), applied_ops: vec![] };
    let mut dead = || false;
    let n = run_v3_outbox(&mut tx, "tok", &mut dao, OWNER, 7, 3, 11, &mut dead, &mut always_now());
    assert_eq!(n, 0);
    assert!(tx.applied_ops.is_empty());

    // Drift after ACK, before commit: applied not counted, no dao writes.
    let state = RefCell::new(0);
    let mut gate = || { *state.borrow_mut() += 1; *state.borrow() != 4 }; // 4th read = post-ACK commit guard
    let mut dao = MemDao { pend: vec![e.clone()], ..Default::default() };
    let mut tx = ScriptTx { script: vec![Ok(ack(&e, 5))].into(), applied_ops: vec![] };
    let n = run_v3_outbox(&mut tx, "tok", &mut dao, OWNER, 7, 3, 11, &mut gate, &mut always_now());
    assert_eq!(n, 0, "commit skipped on drift");
    assert_eq!(dao.commits.len(), 1, "commit attempted");
    assert!(!dao.commits[0].4, "gate false inside the transaction");
    assert_eq!(dao.pend.len(), 1, "rolled back: entry still pending");
}

#[test]
fn attempts_cap_and_future_retry_skip_and_block() {
    let e = entry("op1", "2026-11-03", 4, 5, 8, 0); // attempts >= 8
    let e2 = entry("op2", "2026-11-04", 4, 5, 0, 1_800_000_000_000); // future nextAttemptAt
    let e3 = entry("op3", "2026-11-05", 4, 5, 0, 0);
    let mut dao = MemDao { pend: vec![e, e2, e3.clone()], ..Default::default() };
    let mut tx = ScriptTx { script: vec![Ok(ack(&e3, 5))].into(), applied_ops: vec![] };
    let n = run_v3_outbox(&mut tx, "tok", &mut dao, OWNER, 7, 3, 11, &mut live(), &mut always_now());
    assert_eq!(n, 1, "only the eligible op runs");
    assert_eq!(tx.applied_ops[0].0, "op3");
    assert_eq!(retry_backoff_ms(0), 1000);
    assert_eq!(retry_backoff_ms(6), 64_000);
    assert_eq!(retry_backoff_ms(9), 64_000, "coerceIn(0,6)");
    assert_eq!(retry_backoff_ms(-2), 1000);
}

#[test]
fn consent_generation_mismatch_skips_without_blocking() {
    let mut e = entry("op1", "2026-11-03", 4, 5, 0, 0);
    e.consent_generation = 99; // != local (3)
    let mut dao = MemDao { pend: vec![e], ..Default::default() };
    let mut tx = ScriptTx { script: vec![].into(), applied_ops: vec![] };
    let n = run_v3_outbox(&mut tx, "tok", &mut dao, OWNER, 7, 3, 11, &mut live(), &mut always_now());
    assert_eq!(n, 0);
    assert!(tx.applied_ops.is_empty());
    assert!(dao.retries.is_empty(), "skip is silent, not a retry");
}

#[test]
fn transport_error_blocks_and_retries() {
    let e = entry("op1", "2026-11-03", 4, 5, 0, 0);
    let mut dao = MemDao { pend: vec![e], ..Default::default() };
    let mut tx = ScriptTx { script: vec![Err(())].into(), applied_ops: vec![] };
    let n = run_v3_outbox(&mut tx, "tok", &mut dao, OWNER, 7, 3, 11, &mut live(), &mut always_now());
    assert_eq!(n, 0);
    assert_eq!(dao.retries.len(), 1);
}

#[test]
fn server_revision_max_only_never_lower_never_insert() {
    // Stored server_revision HIGHER than the ACK: commit keeps the max.
    let e = entry("op1", "2026-11-03", 4, 5, 0, 0);
    let mut dao = MemDao { pend: vec![e.clone()], ..Default::default() };
    dao.revision_rows.insert("2026-11-03".into(), cyclecare_core::v3_pull::RevisionRow { revision: 5, server_revision: 9 });
    let mut tx = ScriptTx { script: vec![Ok(ack(&e, 5))].into(), applied_ops: vec![] };
    let n = run_v3_outbox(&mut tx, "tok", &mut dao, OWNER, 7, 3, 11, &mut live(), &mut always_now());
    assert_eq!(n, 1);
    assert_eq!(dao.commits[0].3, 9, "target is max(stored 9, ack 5)");
    assert_eq!(dao.revision_rows["2026-11-03"].server_revision, 9, "never lowered");

    // Missing row: commit acks/rebases but NEVER inserts a revision row.
    let e = entry("op1", "2026-11-04", 4, 5, 0, 0);
    let mut dao = MemDao { pend: vec![e.clone()], ..Default::default() };
    let mut tx = ScriptTx { script: vec![Ok(ack(&e, 5))].into(), applied_ops: vec![] };
    let n = run_v3_outbox(&mut tx, "tok", &mut dao, OWNER, 7, 3, 11, &mut live(), &mut always_now());
    assert_eq!(n, 1);
    assert!(dao.revision_rows.get("2026-11-04").is_none(), "no insert on missing row");
    assert!(dao.pend.is_empty(), "ack/rebase still happened inside the commit");
}

#[test]
fn fault_injected_commit_rolls_back_atomically() {
    // Seam contract proof: a failing commit exposes NO partial writes.
    let e = entry("op1", "2026-11-03", 4, 5, 0, 0);
    let mut dao = MemDao { pend: vec![e.clone()], fail_commits: true, ..Default::default() };
    dao.revision_rows.insert("2026-11-03".into(), cyclecare_core::v3_pull::RevisionRow { revision: 5, server_revision: 4 });
    let mut tx = ScriptTx { script: vec![Ok(ack(&e, 5))].into(), applied_ops: vec![] };
    let n = run_v3_outbox(&mut tx, "tok", &mut dao, OWNER, 7, 3, 11, &mut live(), &mut always_now());
    assert_eq!(n, 0, "rolled-back commit is not counted");
    assert_eq!(dao.pend.len(), 1, "entry still pending");
    assert_eq!(dao.pend[0].base_revision, 4, "no rebase leaked");
    assert_eq!(dao.revision_rows["2026-11-03"].server_revision, 4, "no server revision leaked");
}

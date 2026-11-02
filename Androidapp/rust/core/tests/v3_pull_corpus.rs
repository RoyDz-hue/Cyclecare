//! Release-gated pull, ONE transaction per page (SupabaseSyncService.kt:150-187)
//! with injected transport + page-transaction seam. Synthetic only. The
//! in-memory store snapshots state and restores on Err (rollback model);
//! the real-DB rollback proof lives in v3_sqlite_txn.rs (feature `sqlite`).
use cyclecare_core::sync_codec::{CycleDayLog, CycleSettings, OnboardingProfile};
use cyclecare_core::v3_outbox::{ConflictRow, OutboxEntry};
use cyclecare_core::v3_pull::*;
use cyclecare_core::v3_sync::{wire_record_id, V3WireChange};
use std::collections::HashMap;

const OWNER: &str = "owner-uid";

fn change(id: &str, rev: i64, seq: i64, tombstone: bool, value: Option<&str>) -> V3WireChange {
    V3WireChange {
        record_id: wire_record_id(OWNER, id),
        revision: rev,
        change_seq: seq,
        payload: value.map(|v| {
            cyclecare_core::sync_codec::JsonParser::new(
                &format!(r#"{{"kind":"UPSERT","logicalRecordId":"{id}","value":{v}}}"#),
            ).parse().unwrap()
        }),
        tombstone,
        payload_hash: None,
        logical_record_id: Some(id.into()),
    }
}

fn log_value(date: &str, flow: &str) -> String {
    let l = CycleDayLog {
        date: date.into(), flow: flow.into(), energy: "".into(), mood: "".into(),
        symptoms: "".into(), vaginal_health: "".into(), urinary_health: "".into(),
        cervical_mucus: "".into(), ovulation_test: "".into(), bbt_celsius: 0.0,
        sleep_hours: 0.0, sleep_quality: "".into(), water_glasses: 0, notes: "".into(),
        pain_intensity: 0,
    };
    cyclecare_core::sync_codec::log_payload(&l)
}

#[derive(Clone, Default)]
struct State {
    cursor: Option<i64>,
    revisions: HashMap<String, RevisionRow>,
    pending: Vec<OutboxEntry>,
    conflicts: Vec<ConflictRow>,
    settings: Option<CycleSettings>,
    profile: Option<OnboardingProfile>,
    logs: Vec<CycleDayLog>,
}

#[derive(Default)]
struct MemStore {
    st: State,
    /// Inject a DB failure on the Nth put_cursor call within a transaction.
    fail_put_cursor_at: Option<usize>,
    cursor_puts: usize,
}
struct MemTx<'a> { st: &'a mut State, fail_at: Option<usize>, puts: &'a mut usize }

impl DomainStore for MemTx<'_> {
    fn get_settings(&mut self) -> Option<CycleSettings> { self.st.settings.clone() }
    fn save_settings(&mut self, s: CycleSettings) -> Result<(), String> { self.st.settings = Some(s); Ok(()) }
    fn get_profile(&mut self) -> Option<OnboardingProfile> { self.st.profile.clone() }
    fn save_profile(&mut self, p: OnboardingProfile) -> Result<(), String> { self.st.profile = Some(p); Ok(()) }
    fn upsert_log(&mut self, l: CycleDayLog) -> Result<(), String> { self.st.logs.retain(|x| x.date != l.date); self.st.logs.push(l); Ok(()) }
    fn delete_log(&mut self, date: &str) -> Result<(), String> { self.st.logs.retain(|x| x.date != date); Ok(()) }
}
impl V3PullTx for MemTx<'_> {
    fn revision(&mut self, _o: &str, id: &str) -> Result<Option<RevisionRow>, V3PullError> { Ok(self.st.revisions.get(id).cloned()) }
    fn put_revision(&mut self, _o: &str, id: &str, rev: i64, _h: Option<String>, _t: bool, _n: i64, srv: i64) -> Result<(), V3PullError> {
        self.st.revisions.insert(id.into(), RevisionRow { revision: rev, server_revision: srv });
        Ok(())
    }
    fn pending_for_record(&mut self, _o: &str, id: &str) -> Result<Vec<OutboxEntry>, V3PullError> {
        Ok(self.st.pending.iter().filter(|e| e.record_id == id).cloned().collect())
    }
    fn put_conflict(&mut self, c: ConflictRow) -> Result<(), V3PullError> { self.st.conflicts.push(c); Ok(()) }
    fn put_cursor(&mut self, _o: &str, seq: i64) -> Result<(), V3PullError> {
        *self.puts += 1;
        if Some(*self.puts) == self.fail_at { return Err(V3PullError::Db); }
        self.st.cursor = Some(seq);
        Ok(())
    }
}
impl V3PullStore for MemStore {
    fn cursor(&mut self, _o: &str) -> Option<i64> { self.st.cursor }
    fn transaction(&mut self, body: &mut dyn FnMut(&mut dyn V3PullTx) -> Result<i32, V3PullError>) -> Result<i32, V3PullError> {
        let snapshot = self.st.clone();
        self.cursor_puts = 0;
        let r = {
            let mut tx = MemTx { st: &mut self.st, fail_at: self.fail_put_cursor_at, puts: &mut self.cursor_puts };
            body(&mut tx)
        };
        if r.is_err() { self.st = snapshot; } // ROLLBACK EVERYTHING
        r
    }
}

struct Pages(std::collections::VecDeque<Result<Vec<V3WireChange>, ()>>);
impl V3ReadTransport for Pages {
    fn read_changes(&mut self, _a: i64, _l: i64, _g: i64, _t: &str) -> Result<Vec<V3WireChange>, ()> {
        self.0.pop_front().unwrap_or(Ok(vec![]))
    }
}
fn pages(v: Vec<Vec<V3WireChange>>) -> Pages { Pages(v.into_iter().map(Ok).collect()) }
fn live() -> impl FnMut() -> bool { || true }
fn now() -> impl FnMut() -> i64 { || 1_700_000_000_000 }
fn run(tx: &mut Pages, store: &mut MemStore) -> Result<i32, V3PullError> {
    run_v3_pull(tx, "tok", store, OWNER, 3, 11, &mut live(), &mut now())
}

#[test]
fn applies_newer_remote_and_advances_cursor() {
    let mut tx = pages(vec![vec![change("2026-11-03", 5, 42, false, Some(&log_value("2026-11-03", "LIGHT")))]]);
    let mut store = MemStore::default();
    assert_eq!(run(&mut tx, &mut store), Ok(1));
    assert_eq!(store.st.logs.len(), 1);
    assert_eq!(store.st.logs[0].flow, "LIGHT");
    assert_eq!(store.st.cursor, Some(42));
    assert_eq!(store.st.revisions["2026-11-03"], RevisionRow { revision: 1, server_revision: 5 });
}

#[test]
fn stale_revision_skipped_but_cursor_advances() {
    let mut store = MemStore::default();
    store.st.revisions.insert("2026-11-03".into(), RevisionRow { revision: 2, server_revision: 5 });
    let mut tx = pages(vec![vec![change("2026-11-03", 5, 42, false, Some(&log_value("2026-11-03", "LIGHT")))]]);
    assert_eq!(run(&mut tx, &mut store), Ok(0));
    assert!(store.st.logs.is_empty());
    assert_eq!(store.st.cursor, Some(42));
}

#[test]
fn pending_local_routes_to_conflict_with_first_max_tie() {
    let mut store = MemStore::default();
    for (op, rev) in [("opA", 3), ("opB", 3)] {
        store.st.pending.push(OutboxEntry {
            owner: OWNER.into(), operation_id: op.into(), record_id: "settings".into(),
            kind: "UPSERT".into(), base_revision: 2, revision: rev,
            payload_json: Some(r#"{"cycleLength":28}"#.into()), payload_hash: "h".into(),
            consent_generation: 3, attempts: 0, next_attempt_at: 0,
        });
    }
    let mut tx = pages(vec![vec![change("settings", 9, 7, false, Some(r#"{"cycleLength":29}"#))]]);
    assert_eq!(run(&mut tx, &mut store), Ok(0));
    assert_eq!(store.st.conflicts.len(), 1);
    assert_eq!(store.st.conflicts[0].operation_id, "opA", "FIRST max on tie");
    assert_eq!(store.st.conflicts[0].remote_revision, 9);
    assert_eq!(store.st.conflicts[0].remote_payload.as_deref(), Some(r#"{"cycleLength":29}"#));
    assert!(store.st.settings.is_none(), "no domain write on conflict");
}

#[test]
fn tombstone_without_pending_deletes_log() {
    let mut store = MemStore::default();
    store.st.logs.push(serde_free_log("2026-11-03"));
    let mut tx = pages(vec![vec![change("2026-11-03", 5, 1, true, None)]]);
    assert_eq!(run(&mut tx, &mut store), Ok(1));
    assert!(store.st.logs.is_empty());
}
fn serde_free_log(date: &str) -> CycleDayLog {
    cyclecare_core::sync_codec::parse_log_payload(&log_value(date, "LIGHT"), date).unwrap()
}

#[test]
fn identity_and_payload_checks_fail_closed() {
    let good = log_value("2026-11-03", "LIGHT");
    let mut bad = change("2026-11-03", 5, 1, false, Some(&good));
    bad.record_id = wire_record_id("other-owner", "2026-11-03");
    assert_eq!(run(&mut pages(vec![vec![bad]]), &mut MemStore::default()), Err(V3PullError::RecordIdentityMismatch));

    let mut bad = change("2026-11-03", 5, 1, false, Some(&good));
    bad.logical_record_id = None;
    assert_eq!(run(&mut pages(vec![vec![bad]]), &mut MemStore::default()), Err(V3PullError::MissingLogicalIdentity));

    let mut bad = change("not a date", 5, 1, false, Some(r#"{"x":1}"#));
    bad.record_id = wire_record_id(OWNER, "not a date");
    assert_eq!(run(&mut pages(vec![vec![bad]]), &mut MemStore::default()), Err(V3PullError::RecordIdentityMismatch));

    let mut bad = change("2026-11-03", 5, 1, false, Some(&good));
    bad.payload = Some(cyclecare_core::sync_codec::JsonParser::new(
        r#"{"kind":"UPSERT","logicalRecordId":"2026-11-04","value":{"flow":"LIGHT"}}"#).parse().unwrap());
    assert_eq!(run(&mut pages(vec![vec![bad]]), &mut MemStore::default()), Err(V3PullError::InvalidRemotePayload));
}

#[test]
fn multi_page_until_short_page() {
    let v = log_value("2026-11-03", "LIGHT");
    let full: Vec<V3WireChange> = (0..100).map(|i| change("2026-11-03", i + 1, i + 1, false, Some(&v))).collect();
    let last = vec![change("2026-11-04", 1, 101, false, Some(&log_value("2026-11-04", "LIGHT")))];
    let mut store = MemStore::default();
    assert_eq!(run(&mut pages(vec![full, last]), &mut store), Ok(101));
    assert_eq!(store.st.cursor, Some(101));
}

#[test]
fn partial_page_failure_rolls_back_cursor_domain_and_revisions() {
    // Change 1 is valid and WOULD apply; change 2 has a foreign wire id.
    // Source: one withTransaction per page -> the whole page rolls back.
    let good = log_value("2026-11-03", "LIGHT");
    let c1 = change("2026-11-03", 5, 10, false, Some(&good));
    let mut c2 = change("2026-11-04", 5, 11, false, Some(&log_value("2026-11-04", "LIGHT")));
    c2.record_id = wire_record_id("other-owner", "2026-11-04");
    let mut store = MemStore::default();
    assert_eq!(run(&mut pages(vec![vec![c1, c2]]), &mut store), Err(V3PullError::RecordIdentityMismatch));
    assert_eq!(store.st.cursor, None, "cursor NOT advanced for a rolled-back page");
    assert!(store.st.logs.is_empty(), "domain write rolled back");
    assert!(store.st.revisions.is_empty(), "revision row rolled back");
}

#[test]
fn db_fault_mid_page_rolls_back_whole_page() {
    let good = log_value("2026-11-03", "LIGHT");
    let c1 = change("2026-11-03", 5, 10, false, Some(&good));
    let c2 = change("2026-11-04", 5, 11, false, Some(&log_value("2026-11-04", "LIGHT")));
    let mut store = MemStore { fail_put_cursor_at: Some(2), ..Default::default() };
    assert_eq!(run(&mut pages(vec![vec![c1, c2]]), &mut store), Err(V3PullError::Db));
    assert_eq!(store.st.cursor, None);
    assert!(store.st.logs.is_empty());
    assert!(store.st.revisions.is_empty());
}

#[test]
fn transport_error_propagates_typed_not_ok_count() {
    let mut tx = Pages(vec![Err(())].into());
    let mut store = MemStore::default();
    assert_eq!(run(&mut tx, &mut store), Err(V3PullError::Transport));
    // After a good first page, a later transport failure still propagates
    // (earlier committed page stays committed).
    let good = log_value("2026-11-03", "LIGHT");
    let full: Vec<V3WireChange> = (0..100).map(|i| change("2026-11-03", i + 1, i + 1, false, Some(&good))).collect();
    let mut tx = Pages(vec![Ok(full), Err(())].into());
    let mut store = MemStore::default();
    assert_eq!(run(&mut tx, &mut store), Err(V3PullError::Transport));
    assert_eq!(store.st.cursor, Some(100), "committed first page persists");
}

#[test]
fn real_codec_apply_rejects_malformed_without_partial_writes_and_page_rolls_back() {
    // Payload value lacks required codec keys -> ApplyFailed; page rolled back.
    let mut tx = pages(vec![vec![change("2026-11-03", 5, 42, false, Some(r#"{"flow":"LIGHT"}"#))]]);
    let mut store = MemStore::default();
    assert_eq!(run(&mut tx, &mut store), Err(V3PullError::ApplyFailed));
    assert_eq!(store.st.cursor, None);
    assert!(store.st.revisions.is_empty() && store.st.logs.is_empty());
}

#[test]
fn settings_tombstone_resets_to_defaults_over_local() {
    let mut store = MemStore::default();
    let mut changed = CycleSettings::default();
    changed.average_cycle_length = 35;
    store.st.settings = Some(changed);
    let mut tx = pages(vec![vec![change("settings", 5, 1, true, None)]]);
    assert_eq!(run(&mut tx, &mut store), Ok(1));
    assert_eq!(store.st.settings.as_ref().unwrap().average_cycle_length, CycleSettings::default().average_cycle_length);
}

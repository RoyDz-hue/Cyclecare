//! Release-gated pull body, mirrored 1:1 (SupabaseSyncService.kt:150-187)
//! with injected transport/DAO/apply seams. Synthetic only.
use cyclecare_core::v3_pull::*;
use cyclecare_core::v3_sync::{wire_record_id, V3WireChange};

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

#[derive(Default)]
struct MemPull {
    cursor: Option<i64>,
    revisions: std::collections::HashMap<String, RevisionRow>,
    pending: Vec<cyclecare_core::v3_outbox::OutboxEntry>,
    conflicts: Vec<cyclecare_core::v3_outbox::ConflictRow>,
    applied: Vec<(String, String)>,
    tombstoned: Vec<String>,
}
impl V3PullDao for MemPull {
    fn cursor(&mut self, _o: &str) -> Option<i64> { self.cursor }
    fn put_cursor(&mut self, _o: &str, seq: i64) { self.cursor = Some(seq); }
    fn revision(&mut self, _o: &str, id: &str) -> Option<RevisionRow> { self.revisions.get(id).cloned() }
    fn put_revision(&mut self, _o: &str, id: &str, rev: i64, _h: Option<String>, _t: bool, _n: i64, srv: i64) {
        self.revisions.insert(id.into(), RevisionRow { revision: rev, server_revision: srv });
    }
    fn pending_for_record(&mut self, _o: &str, id: &str) -> Vec<cyclecare_core::v3_outbox::OutboxEntry> {
        self.pending.iter().filter(|e| e.record_id == id).cloned().collect()
    }
    fn put_conflict(&mut self, c: cyclecare_core::v3_outbox::ConflictRow) { self.conflicts.push(c); }
}

struct Pages(std::collections::VecDeque<Vec<V3WireChange>>);
impl V3ReadTransport for Pages {
    fn read_changes(&mut self, _a: i64, _l: i64, _g: i64, _t: &str) -> Result<Vec<V3WireChange>, ()> {
        Ok(self.0.pop_front().unwrap_or_default())
    }
}

fn live() -> impl FnMut() -> bool { || true }
fn now() -> impl FnMut() -> i64 { || 1_700_000_000_000 }

macro_rules! pull {
    ($tx:expr, $dao:expr) => {{
        let applied = std::cell::RefCell::new(Vec::new());
        let tombs = std::cell::RefCell::new(Vec::new());
        let r = run_v3_pull($tx, "tok", $dao, OWNER, 3, 11, &mut live(), &mut now(),
            &mut |id: &str, v: &str| applied.borrow_mut().push((id.to_string(), v.to_string())),
            &mut |id: &str| tombs.borrow_mut().push(id.to_string()));
        (r, applied.into_inner(), tombs.into_inner())
    }};
}

#[test]
fn applies_newer_remote_and_advances_cursor() {
    let mut tx = Pages(vec![vec![change("2026-11-03", 5, 42, false, Some(r#"{"flow":"LIGHT"}"#))]].into());
    let mut dao = MemPull::default();
    let (r, applied, tombs) = pull!(&mut tx, &mut dao);
    assert_eq!(r, Ok(1));
    assert_eq!(applied, vec![("2026-11-03".to_string(), r#"{"flow":"LIGHT"}"#.to_string())]);
    assert!(tombs.is_empty());
    assert_eq!(dao.cursor, Some(42));
    assert_eq!(dao.revisions["2026-11-03"], RevisionRow { revision: 1, server_revision: 5 });
}

#[test]
fn stale_revision_skipped_but_cursor_advances() {
    let mut dao = MemPull::default();
    dao.revisions.insert("2026-11-03".into(), RevisionRow { revision: 2, server_revision: 5 });
    let mut tx = Pages(vec![vec![change("2026-11-03", 5, 42, false, Some(r#"{"flow":"LIGHT"}"#))]].into());
    let (r, applied, _) = pull!(&mut tx, &mut dao);
    assert_eq!(r, Ok(0));
    assert!(applied.is_empty());
    assert_eq!(dao.cursor, Some(42), "cursor still advances past the stale change");
}

#[test]
fn pending_local_routes_to_conflict_with_first_max_tie() {
    let mut dao = MemPull::default();
    for (op, rev) in [("opA", 3), ("opB", 3)] { // tie: Kotlin maxByOrNull keeps FIRST
        dao.pending.push(cyclecare_core::v3_outbox::OutboxEntry {
            owner: OWNER.into(), operation_id: op.into(), record_id: "settings".into(),
            kind: "UPSERT".into(), base_revision: 2, revision: rev,
            payload_json: Some(r#"{"cycleLength":28}"#.into()), payload_hash: "h".into(),
            consent_generation: 3, attempts: 0, next_attempt_at: 0,
        });
    }
    let mut tx = Pages(vec![vec![change("settings", 9, 7, false, Some(r#"{"cycleLength":29}"#))]].into());
    let (r, applied, _) = pull!(&mut tx, &mut dao);
    assert_eq!(r, Ok(0), "conflict does not count as applied");
    assert!(applied.is_empty());
    assert_eq!(dao.conflicts.len(), 1);
    assert_eq!(dao.conflicts[0].operation_id, "opA", "FIRST max on tie");
    assert_eq!(dao.conflicts[0].remote_revision, 9);
    assert_eq!(dao.conflicts[0].remote_payload.as_deref(), Some(r#"{"cycleLength":29}"#));
}

#[test]
fn tombstone_without_pending_applies_reset() {
    let mut tx = Pages(vec![vec![change("2026-11-03", 5, 1, true, None)]].into());
    let mut dao = MemPull::default();
    let (r, _, tombs) = pull!(&mut tx, &mut dao);
    assert_eq!(r, Ok(1));
    assert_eq!(tombs, vec!["2026-11-03".to_string()]);
}

#[test]
fn identity_and_payload_checks_fail_closed() {
    // Foreign wire record id.
    let mut bad = change("2026-11-03", 5, 1, false, Some(r#"{"flow":"LIGHT"}"#));
    bad.record_id = wire_record_id("other-owner", "2026-11-03");
    let mut tx = Pages(vec![vec![bad]].into());
    let mut dao = MemPull::default();
    let (r, _, _) = pull!(&mut tx, &mut dao);
    assert_eq!(r, Err(V3PullError::RecordIdentityMismatch));

    // Missing logical identity.
    let mut bad = change("2026-11-03", 5, 1, false, Some(r#"{"flow":"LIGHT"}"#));
    bad.logical_record_id = None;
    let mut tx = Pages(vec![vec![bad]].into());
    let mut dao = MemPull::default();
    let (r, _, _) = pull!(&mut tx, &mut dao);
    assert_eq!(r, Err(V3PullError::MissingLogicalIdentity));

    // Invalid logical id shape.
    let mut bad = change("not a date", 5, 1, false, Some(r#"{"x":1}"#));
    bad.record_id = wire_record_id(OWNER, "not a date");
    let mut tx = Pages(vec![vec![bad]].into());
    let mut dao = MemPull::default();
    let (r, _, _) = pull!(&mut tx, &mut dao);
    assert_eq!(r, Err(V3PullError::RecordIdentityMismatch));

    // Non-tombstone with mismatched payload logicalRecordId.
    let mut bad = change("2026-11-03", 5, 1, false, Some(r#"{"flow":"LIGHT"}"#));
    bad.payload = Some(cyclecare_core::sync_codec::JsonParser::new(
        r#"{"kind":"UPSERT","logicalRecordId":"2026-11-04","value":{"flow":"LIGHT"}}"#).parse().unwrap());
    let mut tx = Pages(vec![vec![bad]].into());
    let mut dao = MemPull::default();
    let (r, _, _) = pull!(&mut tx, &mut dao);
    assert_eq!(r, Err(V3PullError::InvalidRemotePayload));
}

#[test]
fn multi_page_until_short_page() {
    let full: Vec<V3WireChange> = (0..100).map(|i| change("2026-11-03", i + 1, i + 1, false, Some(r#"{"flow":"LIGHT"}"#))).collect();
    let last: Vec<V3WireChange> = vec![change("2026-11-04", 1, 101, false, Some(r#"{"x":1}"#))];
    let mut tx = Pages(vec![full, last].into());
    let mut dao = MemPull::default();
    let (r, _, _) = pull!(&mut tx, &mut dao);
    // Each change.revision strictly exceeds the stored server_revision, so
    // every page entry applies (100 + 1).
    assert_eq!(r, Ok(101));
    assert_eq!(dao.cursor, Some(101));
}

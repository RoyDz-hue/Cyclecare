//! End-to-end per-owner composition: outbox, pull and the consent snapshot
//! journal all run THROUGH OwnerScopedStores, resolving the store at call
//! time from the identity sync_all would pass (prefs.user_id at
//! SupabaseSyncService.kt:117). An account switch between operations routes
//! the next operation to the new owner's file; no operation ever touches a
//! prior owner's handle. Synthetic scratch data only; host-only `sqlite`.
#![cfg(feature = "sqlite")]

use cyclecare_core::v3_consent::journal_explicit_snapshot;
use cyclecare_core::v3_dao_sqlite::SqliteV3Store;
use cyclecare_core::v3_outbox::{run_v3_outbox, V3Transport};
use cyclecare_core::v3_owner_router::{OwnerBindError, OwnerScopedStores};
use cyclecare_core::v3_pull::{run_v3_pull, V3ReadTransport};
use cyclecare_core::v3_sync::{wire_record_id, V3WireChange, V3WireOperation, V3WireReceipt};

const SESSION: i64 = 7;
const LOCAL: i64 = 3;
const SERVER: i64 = 11;

fn tmpdir(tag: &str) -> std::path::PathBuf {
    let d = std::env::temp_dir().join(format!("cyclecare-e2e-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    d
}

fn insert_log(s: &SqliteV3Store, date: &str, notes: &str) {
    s.conn.execute(
        "INSERT OR REPLACE INTO cycle_day_logs (dateString, flowLevel, energyLevel, moodLevel, symptomsCsv,
         vaginalHealthCsv, urinaryHealthCsv, cervicalMucus, ovulationTest, bbtCelsius, sleepHours,
         sleepQuality, waterGlasses, notes, painIntensity)
         VALUES (?1, 'MODERATE', '', '', '', '', '', '', '', 0.0, 0.0, '', 0, ?2, 0)",
        rusqlite::params![date, notes]).unwrap();
}

fn count(s: &SqliteV3Store, table: &str) -> i64 {
    s.conn.query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |r| r.get(0)).unwrap()
}

struct Acks;
impl V3Transport for Acks {
    fn apply(&mut self, op: &V3WireOperation, _t: &str) -> Result<V3WireReceipt, ()> {
        Ok(V3WireReceipt {
            status: "applied".into(), revision: Some(op.revision), change_seq: None,
            payload_hash: Some("a".repeat(64)), reason: None,
            record_id: Some(op.record_id.clone()), // already the wire id
            operation_id: Some(op.operation_id.clone()),
            logical_record_id: op.logical_record_id.clone(), remote_payload: None, remote_tombstone: false,
        })
    }
}

struct NoChanges;
impl V3ReadTransport for NoChanges {
    fn read_changes(&mut self, _a: i64, _l: i64, _g: i64, _t: &str) -> Result<Vec<V3WireChange>, ()> {
        Ok(vec![])
    }
}

#[test]
fn full_sync_cycle_routes_per_owner_through_the_provider() {
    let d = tmpdir("cycle");
    let mut p = OwnerScopedStores::new(&d);
    let alice = || Some("alice".to_string());

    // 1. Alice's domain data lives in alice's file only.
    insert_log(p.current(&mut { alice }).unwrap(), "2026-11-01", "alice-cycle");
    insert_log(p.current(&mut { alice }).unwrap(), "2026-11-02", "alice-cycle-2");

    // 2. Consent snapshot journals through alice's resolved store.
    let mut n = 0i64;
    journal_explicit_snapshot(
        "alice", SESSION, LOCAL, SERVER, p.current(&mut { alice }).unwrap(),
        &mut || true, &mut || { n += 1; format!("uuid-{n}") }, &mut || 1_000,
    ).unwrap();
    {
        let s = p.current(&mut { alice }).unwrap();
        assert_eq!(count(s, "health_sync_outbox"), 2, "2 logs journaled for alice");
        assert_eq!(count(s, "health_sync_revisions"), 2);
    }

    // 3. Outbox drains through alice's resolved store (all acks applied).
    let sent = run_v3_outbox(
        &mut Acks, "tok", p.current(&mut { alice }).unwrap(), "alice",
        SESSION, LOCAL, SERVER, &mut || true, &mut || 2_000,
    ).unwrap();
    assert_eq!(sent, 2);
    assert_eq!(count(p.current(&mut { alice }).unwrap(), "health_sync_outbox"), 0, "alice outbox drained");
    let srv: i64 = p.current(&mut { alice }).unwrap().conn.query_row(
        "SELECT serverRevision FROM health_sync_revisions WHERE ownerId='alice' AND recordId='2026-11-01'",
        [], |r| r.get(0)).unwrap();
    assert_eq!(srv, 1, "ack revision committed");

    // 4. Pull as alice advances alice's cursor only.
    run_v3_pull(&mut NoChanges, "tok", p.current(&mut { alice }).unwrap(), "alice", LOCAL, SERVER, &mut || true, &mut || 3_000).unwrap();

    // 5. Account switch: the VERY NEXT call resolves bob's file. Bob's pull
    // creates bob's file with bob's own cursor; alice's rows never leak.
    let bob = || Some("bob".to_string());
    run_v3_pull(&mut NoChanges, "tok", p.current(&mut { bob }).unwrap(), "bob", LOCAL, SERVER, &mut || true, &mut || 4_000).unwrap();
    {
        let b = p.current(&mut { bob }).unwrap();
        assert_eq!(count(b, "cycle_day_logs"), 0, "bob sees none of alice's domain rows");
        assert_eq!(count(b, "health_sync_revisions"), 0, "bob sees none of alice's sync state");
    }
    {
        let a = p.current(&mut { alice }).unwrap();
        assert_eq!(count(a, "cycle_day_logs"), 2, "alice's data intact after bob's activity");
        assert_eq!(count(a, "health_sync_revisions"), 2);
    }

    // 6. Sign-out resolves the guest file, which is empty of owner data.
    let guest = || None;
    assert_eq!(count(p.current(&mut { guest }).unwrap(), "cycle_day_logs"), 0);
    let _ = std::fs::remove_dir_all(&d);
}

fn remote_log_change(owner: &str, date: &str, rev: i64, seq: i64, notes: &str) -> V3WireChange {
    let payload = format!(
        r#"{{"date":"{date}","flow":"LIGHT","energy":"","mood":"","symptoms":"","vaginal_health":"","urinary_health":"","cervical_mucus":"","ovulation_test":"NONE","bbt_celsius":0,"sleep_hours":0,"sleep_quality":"","water_glasses":0,"notes":"{notes}","pain_intensity":0}}"#
    );
    V3WireChange {
        record_id: wire_record_id(owner, date), revision: rev, change_seq: seq,
        payload: Some(cyclecare_core::sync_codec::JsonParser::new(&format!(
            r#"{{"kind":"UPSERT","logicalRecordId":"{date}","value":{payload}}}"#)).parse().unwrap()),
        tombstone: false, payload_hash: None, logical_record_id: Some(date.into()),
    }
}

struct OnePage(std::collections::VecDeque<Vec<V3WireChange>>);
impl V3ReadTransport for OnePage {
    fn read_changes(&mut self, _a: i64, _l: i64, _g: i64, _t: &str) -> Result<Vec<V3WireChange>, ()> {
        Ok(self.0.pop_front().unwrap_or_default())
    }
}

#[test]
fn bound_runner_refuses_blank_owners_and_binds_store_to_operation_owner() {
    let d = tmpdir("bound");
    let mut p = OwnerScopedStores::new(&d);
    // Blank owners can never reach the guest database with an owner argument.
    for blank in ["", "   ", "\u{00A0}", "\u{2007}"] {
        let r: Result<(), OwnerBindError> = p.run_for_owner(blank, |_| ());
        assert!(matches!(r, Err(OwnerBindError::Mismatch { .. })), "blank owner {blank:?} refused");
    }
    assert!(std::fs::read_dir(&d).unwrap().next().is_none(), "refusals perform no I/O");
    // Real remote-domain writes through the binding land in the owner's own
    // file: the owner argument resolves the store AND scopes the operation.
    let mut alice_page = OnePage(vec![vec![remote_log_change("alice", "2026-11-06", 5, 1, "alice-remote")]].into());
    p.run_for_owner("alice", |s| {
        run_v3_pull(&mut alice_page, "tok", s, "alice", LOCAL, SERVER, &mut || true, &mut || 5_000)
    }).unwrap().unwrap();
    let mut bob_page = OnePage(vec![vec![remote_log_change("bob", "2026-11-06", 3, 1, "bob-remote")]].into());
    p.run_for_owner("bob", |s| {
        run_v3_pull(&mut bob_page, "tok", s, "bob", LOCAL, SERVER, &mut || true, &mut || 6_000)
    }).unwrap().unwrap();
    let notes = |p: &mut OwnerScopedStores, owner: &'static str| -> Option<String> {
        p.run_for_owner(owner, |s| {
            s.conn.query_row("SELECT notes FROM cycle_day_logs WHERE dateString='2026-11-06'", [], |r| r.get(0)).ok()
        }).unwrap()
    };
    assert_eq!(notes(&mut p, "alice").as_deref(), Some("alice-remote"));
    assert_eq!(notes(&mut p, "bob").as_deref(), Some("bob-remote"));
    drop(p);
    // Reopen isolation: a fresh provider over the same directory sees the
    // same two isolated files; nothing leaked across owners on disk.
    let mut p2 = OwnerScopedStores::new(&d);
    assert_eq!(notes(&mut p2, "alice").as_deref(), Some("alice-remote"), "alice data persists across reopen");
    assert_eq!(notes(&mut p2, "bob").as_deref(), Some("bob-remote"), "bob data persists across reopen");
    let files: Vec<String> = std::fs::read_dir(&d).unwrap().map(|e| e.unwrap().file_name().into_string().unwrap()).collect();
    assert_eq!(files.len(), 2, "exactly the two owner files, no guest file was ever created");
    let _ = std::fs::remove_dir_all(&d);
}

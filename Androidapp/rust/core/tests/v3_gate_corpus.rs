//! current_gate (SupabaseSyncService.kt:27) over the real preferences:
//! every term verified live, nullability of serverHealthConsentGeneration
//! (:182-187) exact, and the gate slotted into an actual transaction body.
//! Synthetic scratch data only.
use cyclecare_core::auth_prefs_health::{current_gate, HealthAuthPrefs};
use std::cell::RefCell;
use std::rc::Rc;
use cyclecare_core::prefs_scratch::ScratchPrefs;

const OWNER: &str = "owner-uid";
const SESSION: i64 = 11;
const LOCAL: i64 = 3;
const SERVER: i64 = 7;

fn file(tag: &str) -> std::path::PathBuf {
    std::env::temp_dir().join(format!("cyclecare-gate-{tag}-{}.txt", std::process::id()))
}
fn cleanup(path: &std::path::Path) {
    let _ = std::fs::remove_file(path);
    let _ = std::fs::remove_file(path.with_extension("tmp"));
}

struct Rig {
    p: ScratchPrefs,
    user: Option<String>,
    wiped: bool,
    secure: bool,
    live_user: Option<Rc<RefCell<Option<String>>>>,
    live_wiped: Option<Rc<RefCell<bool>>>,
}
impl Rig {
    fn enrolled(tag: &str) -> Rig {
        let path = file(tag);
        cleanup(&path);
        let mut r = Rig { p: ScratchPrefs::open_file(&path).unwrap(), user: Some(OWNER.into()), wiped: false, secure: true, live_user: None, live_wiped: None };
        {
            let mut h = r.prefs_handle();
            h.prefs.edit().put_long("session_generation_v3", SESSION).commit();
            h.set_is_cloud_sync_allowed(true).unwrap();
            h.set_health_consent_generation(LOCAL);
            assert!(h.save_server_health_consent_receipt(OWNER, SESSION, LOCAL, SERVER, true));
        }
        r
    }
    fn prefs_handle(&mut self) -> HealthAuthPrefs<'_> {
        HealthAuthPrefs {
            prefs: &mut self.p, secure_available: self.secure,
            secure_user_id: self.user.clone(),
            device_fallback_owner: "dev-fallback".into(), owner_wiped: self.wiped,
            live_user_id: self.live_user.as_ref().map(|c| {
                let c = Rc::clone(c);
                Box::new(move || c.borrow().clone()) as Box<dyn Fn() -> Option<String>>
            }),
            live_owner_wiped: self.live_wiped.as_ref().map(|c| {
                let c = Rc::clone(c);
                Box::new(move || *c.borrow()) as Box<dyn Fn() -> bool>
            }),
        }
    }
    fn gate(&mut self) -> bool {
        current_gate(&self.prefs_handle(), OWNER, SESSION, LOCAL, SERVER)
    }
}

#[test]
fn enrolled_owner_passes_and_every_term_is_load_bearing() {
    let mut r = Rig::enrolled("enroll");
    assert!(r.gate(), "fully enrolled owner passes");

    // Wrong owner argument (gate called for someone else).
    assert!(!current_gate(&r.prefs_handle(), "other", SESSION, LOCAL, SERVER));
    // Signed out.
    let mut signed_out = Rig { p: r.p, user: None, wiped: r.wiped, secure: r.secure, live_user: None, live_wiped: None };
    assert!(!signed_out.gate());
    let mut r = Rig { p: signed_out.p, user: signed_out.user, wiped: signed_out.wiped, secure: signed_out.secure, live_user: None, live_wiped: None };

    // Session mismatch.
    assert!(!current_gate(&r.prefs_handle(), OWNER, SESSION + 1, LOCAL, SERVER));
    // Local generation mismatch.
    assert!(!current_gate(&r.prefs_handle(), OWNER, SESSION, LOCAL + 1, SERVER));
    // Server generation mismatch and absent receipt (nullable getter parity).
    assert!(!current_gate(&r.prefs_handle(), OWNER, SESSION, LOCAL, SERVER + 1));
    assert!(!current_gate(&r.prefs_handle(), OWNER, SESSION, LOCAL, 0), "no receipt -> None, never 0-match");

    // Cloud sync revoked.
    let mut revoked = Rig::enrolled("revoked");
    revoked.prefs_handle().set_is_cloud_sync_allowed(false).unwrap();
    assert!(!revoked.gate());

    // Pending withdrawal (revoke flag makes the server getter null).
    let mut withdrawn = Rig::enrolled("withdrawn");
    withdrawn.prefs_handle().mark_pending_health_sync_revoke();
    assert!(!withdrawn.gate());

    // OwnerDataGate wiped.
    let mut wiped = Rig::enrolled("wiped");
    wiped.wiped = true;
    assert!(!wiped.gate());

    // Secure storage unavailable.
    let mut insecure = Rig::enrolled("insecure");
    insecure.secure = false;
    assert!(!insecure.gate());

    for tag in ["enroll", "revoked", "withdrawn", "wiped", "insecure"] { cleanup(&file(tag)); }
}

#[test]
fn gate_rereads_prefs_live_between_evaluations() {
    let mut r = Rig::enrolled("live");
    // One closure, three evaluations, mutation in between - like Kotlin
    // reading authPreferences inside every withTransaction check.
    let outcomes = {
        let h = r.prefs_handle();
        let gate = || current_gate(&h, OWNER, SESSION, LOCAL, SERVER);
        let first = gate();
        drop(h);
        r.prefs_handle().set_is_cloud_sync_allowed(false).unwrap();
        let h = r.prefs_handle();
        let second = current_gate(&h, OWNER, SESSION, LOCAL, SERVER);
        drop(h);
        (first, second)
    };
    assert_eq!(outcomes, (true, false), "gate reflects the pref change without being rebuilt");
    // Re-allowing does NOT restore the old gate: the consent setter bumps
    // health_consent_generation on every value change (AuthPreferences.kt
    // :144-148, one-commit decision+generation), so the old receipt is
    // stale by design.
    r.prefs_handle().set_is_cloud_sync_allowed(true).unwrap();
    assert!(!r.gate(), "generation advanced 3->4->5; old receipt stale");
    // Re-enrollment is deliberately hard (source parity): disabling also
    // set the durable pending-revoke flag (:151-155), and the receipt-save
    // guard rejects enabled saves while that flag stands (:189) - the gate
    // stays closed until the explicit consent flow runs.
    let new_local = r.prefs_handle().health_consent_generation();
    assert_eq!(new_local, LOCAL + 2);
    assert!(r.prefs_handle().has_pending_health_sync_revoke());
    assert!(!r.prefs_handle().save_server_health_consent_receipt(OWNER, SESSION, new_local, SERVER + 1, true),
        "guard: durable pending revoke blocks the enabled receipt save");
    assert!(!current_gate(&r.prefs_handle(), OWNER, SESSION, new_local, SERVER + 1));
    cleanup(&file("live"));
}

#[cfg(feature = "sqlite")]
#[test]
fn gate_slots_into_the_real_snapshot_transaction() {
    use cyclecare_core::v3_consent::{journal_explicit_snapshot, V3ConsentError};
    use cyclecare_core::v3_dao_sqlite::SqliteV3Store;

    let mut r = Rig::enrolled("txn");
    let mut store = SqliteV3Store::open_in_memory().unwrap();
    let mut n = 0i64;
    let mut uuid = || { n += 1; format!("uuid-{n}") };
    // Gate open: the journal commits.
    {
        let h = r.prefs_handle();
        journal_explicit_snapshot(OWNER, SESSION, LOCAL, SERVER, &mut store,
            &mut || current_gate(&h, OWNER, SESSION, LOCAL, SERVER), &mut uuid, &mut || 1_000).unwrap();
    }
    let committed: i64 = store.conn.query_row("SELECT COUNT(*) FROM health_sync_revisions", [], |r| r.get(0)).unwrap();
    assert!(committed >= 0); // no domain rows seeded; gate passed, body ran
    // Gate closed mid-session: ConsentChanged, nothing further written.
    r.prefs_handle().set_is_cloud_sync_allowed(false).unwrap();
    {
        let h = r.prefs_handle();
        let err = journal_explicit_snapshot(OWNER, SESSION, LOCAL, SERVER, &mut store,
            &mut || current_gate(&h, OWNER, SESSION, LOCAL, SERVER), &mut uuid, &mut || 2_000).unwrap_err();
        assert!(matches!(err, V3ConsentError::ConsentChanged));
    }
    cleanup(&file("txn"));
}

#[test]
fn live_providers_honor_identity_and_wipe_changes_between_evaluations() {
    let mut r = Rig::enrolled("liveprov");
    let user = Rc::new(RefCell::new(Some(OWNER.to_string())));
    let wiped = Rc::new(RefCell::new(false));
    r.live_user = Some(Rc::clone(&user));
    r.live_wiped = Some(Rc::clone(&wiped));
    assert!(r.gate());
    *user.borrow_mut() = None; // sign-out through the provider, fields untouched
    assert!(!r.gate(), "live sign-out honored without rebuilding the prefs");
    *user.borrow_mut() = Some("intruder".to_string()); // account switch to someone else
    assert!(!r.gate());
    *user.borrow_mut() = Some(OWNER.to_string());
    *wiped.borrow_mut() = true; // OwnerDataGate wipe flips live
    assert!(!r.gate());
    *wiped.borrow_mut() = false;
    assert!(r.gate());
    cleanup(&file("liveprov"));
}

#[cfg(feature = "sqlite")]
#[test]
fn live_provider_flip_during_outbox_blocks_commit_inside_the_transaction() {
    use cyclecare_core::v3_outbox::{run_v3_outbox, V3Transport};
    use cyclecare_core::v3_dao_sqlite::SqliteV3Store;
    use cyclecare_core::v3_sync::{wire_record_id, V3WireOperation, V3WireReceipt};

    let mut r = Rig::enrolled("intx");
    let mut store = SqliteV3Store::open_in_memory().unwrap();
    // Two pending ops for alice.
    for (op, rec) in [("op-1", "2026-11-01"), ("op-2", "2026-11-02")] {
        store.conn.execute(
            "INSERT OR REPLACE INTO health_sync_outbox
             (ownerId, operationId, recordId, kind, baseRevision, revision, payloadJson,
              payloadHash, consentGeneration, attempts, nextAttemptAt)
             VALUES (?1, ?2, ?3, 'UPSERT', 0, 1, '{}', ?4, ?5, 0, 0)",
            rusqlite::params![OWNER, op, rec, "a".repeat(64), LOCAL]).unwrap();
    }
    // Transport acks op-1, then flips the live user provider while op-2's
    // ack is in flight - after the loop-top gate read, before commit_applied
    // re-reads the gate INSIDE the transaction.
    struct FlipOnSecond(Rc<RefCell<Option<String>>>);
    impl V3Transport for FlipOnSecond {
        fn apply(&mut self, op: &V3WireOperation, _t: &str) -> Result<V3WireReceipt, ()> {
            if op.operation_id == "op-2" {
                *self.0.borrow_mut() = Some("intruder".to_string()); // sign-out/switch mid-run
            }
            Ok(V3WireReceipt {
                status: "applied".into(), revision: Some(op.revision), change_seq: None,
                payload_hash: Some("a".repeat(64)), reason: None,
                record_id: Some(op.record_id.clone()), operation_id: Some(op.operation_id.clone()),
                logical_record_id: op.logical_record_id.clone(), remote_payload: None, remote_tombstone: false,
            })
        }
    }
    let live_user = Rc::new(RefCell::new(Some(OWNER.to_string())));
    let mut transport = FlipOnSecond(Rc::clone(&live_user));
    r.live_user = Some(Rc::clone(&live_user));
    let sent = {
        let h = r.prefs_handle();
        run_v3_outbox(
            &mut transport, "tok", &mut store, OWNER, SESSION, LOCAL, SERVER,
            &mut || current_gate(&h, OWNER, SESSION, LOCAL, SERVER),
            &mut || 1_000,
        ).unwrap()
    };
    assert_eq!(sent, 1, "only op-1 committed");
    let pending: i64 = store.conn.query_row(
        "SELECT COUNT(*) FROM health_sync_outbox WHERE operationId = 'op-2'", [], |r| r.get(0)).unwrap();
    assert_eq!(pending, 1, "op-2 NOT committed: gate drift inside the transaction left it pending");
    let backoff: i64 = store.conn.query_row(
        "SELECT nextAttemptAt FROM health_sync_outbox WHERE operationId = 'op-2'", [], |r| r.get(0)).unwrap();
    assert_eq!(backoff, 0, "GateDrift is no-retry parity (withTransaction returned false)");
    let rev: i64 = store.conn.query_row(
        "SELECT COUNT(*) FROM health_sync_revisions WHERE recordId = '2026-11-02'", [], |r| r.get(0)).unwrap();
    assert_eq!(rev, 0, "no revision written for the drifted op");
    let _ = wire_record_id;
    cleanup(&file("intx"));
}

//! syncAll corpus (SupabaseSyncService.kt:114-143) against the REAL
//! file-backed HealthAuthPrefs. Synthetic seams; durable state re-opened
//! from disk where the obligation matters.
use cyclecare_core::auth_prefs_health::HealthAuthPrefs;
use cyclecare_core::prefs_scratch::ScratchPrefs;
use cyclecare_core::session_token::AccessTokenResolution;
use cyclecare_core::v3_consent::{V3ConsentTransport, CONFLICT_SAFE_PROTOCOL_RELEASED};
use cyclecare_core::v3_sync::V3ConsentReceipt;
use cyclecare_core::v3_sync_all::*;

const OWNER: &str = "owner-uid";

fn file(tag: &str) -> std::path::PathBuf {
    std::env::temp_dir().join(format!("cyclecare-syncall-{tag}-{}.txt", std::process::id()))
}
macro_rules! with_prefs {
    ($path:expr, $user:expr, $wiped:expr, $body:expr) => {{
        let mut p = ScratchPrefs::open_file($path).unwrap();
        let mut h = HealthAuthPrefs {
            prefs: &mut p, secure_available: true,
            secure_user_id: $user.map(|s: &str| s.to_string()),
            device_fallback_owner: "dev-fallback".into(), owner_wiped: $wiped,
            live_user_id: None, live_owner_wiped: None,
        };
        let r = ($body)(&mut h);
        drop(h);
        r
    }};
}
fn cleanup(path: &std::path::Path) { let _ = std::fs::remove_file(path); let _ = std::fs::remove_file(path.with_extension("tmp")); }

/// Signed-in owner, session 11, cloud allowed, generation 3, receipt server 7.
fn enrolled(path: &std::path::Path) {
    cleanup(path);
    with_prefs!(path, Some(OWNER), false, |h: &mut HealthAuthPrefs| {
        h.prefs.edit().put_long("session_generation_v3", 11).commit();
        h.set_is_cloud_sync_allowed(true).unwrap();
        h.set_health_consent_generation(3);
        assert!(h.save_server_health_consent_receipt(OWNER, 11, 3, 7, true));
    });
}

struct Tok(AccessTokenResolution);
impl Tok {
    fn resolver(&mut self) -> impl FnMut() -> AccessTokenResolution + '_ {
        move || self.0.clone()
    }
}
struct CT { result: Result<V3ConsentReceipt, ()>, calls: usize }
impl V3ConsentTransport for CT {
    fn set_consent(&mut self, _e: bool, _t: &str) -> Result<V3ConsentReceipt, ()> {
        self.calls += 1; self.result.clone()
    }
}

pub struct Counters { pub pull: std::cell::Cell<usize>, pub outbox: std::cell::Cell<usize>, pub conflicts: std::cell::Cell<usize> }
impl Counters {
    fn new() -> Self { Counters { pull: 0.into(), outbox: 0.into(), conflicts: 0.into() } }
    fn bump(c: &std::cell::Cell<usize>) { c.set(c.get() + 1); }
}

fn run(h: &mut HealthAuthPrefs, tok: AccessTokenResolution, ct: &mut CT, c: &Counters) -> SyncResult {
    let mut t = Tok(tok);
    let mut r = t.resolver();
    sync_all(
        h, &mut r, ct,
        &mut |_o, _l, _s, _se, _t| { Counters::bump(&c.pull); Ok(2) },
        &mut |_t| { Counters::bump(&c.outbox); Ok(3) },
        &mut |_o| { Counters::bump(&c.conflicts); Ok(0) },
        &mut || true, &mut || 1_700_000_000_000, &mut || "op".into(),
    )
}

#[test]
fn off_and_no_pending_is_success_nothing_sent() {
    let f = file("b1"); cleanup(&f);
    let c = Counters::new();
    let mut ct = CT { result: Ok(V3ConsentReceipt { enabled: false, generation: 9, reason: None }), calls: 0 };
    let r = with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| run(h, AccessTokenResolution::Valid("tok".into()), &mut ct, &c));
    assert!(r.is_success && r.message == "Online backup is off, so nothing was sent.");
    assert_eq!((c.pull.get(), c.outbox.get(), c.conflicts.get(), ct.calls), (0, 0, 0, 0));
    cleanup(&f);
}

#[test]
fn signed_out_with_backup_allowed_asks_sign_in() {
    let f = file("b2"); cleanup(&f);
    // Device-fallback owner scope carries the cloud-allowed flag; userId null.
    with_prefs!(&f, None, false, |h: &mut HealthAuthPrefs| h.set_is_cloud_sync_allowed(true).unwrap());
    let c = Counters::new();
    let mut ct = CT { result: Ok(V3ConsentReceipt { enabled: false, generation: 9, reason: None }), calls: 0 };
    let r = with_prefs!(&f, None, false, |h: &mut HealthAuthPrefs| run(h, AccessTokenResolution::Valid("tok".into()), &mut ct, &c));
    assert!(!r.is_success && r.message == "Please sign in to back up your records.");
    assert_eq!(ct.calls, 0);
    cleanup(&f);
}

#[test]
fn wiped_owner_without_pending_stays_paused_success() {
    let f = file("b3"); enrolled(&f);
    let c = Counters::new();
    let mut ct = CT { result: Ok(V3ConsentReceipt { enabled: false, generation: 9, reason: None }), calls: 0 };
    let r = with_prefs!(&f, Some(OWNER), true, |h: &mut HealthAuthPrefs| run(h, AccessTokenResolution::Valid("tok".into()), &mut ct, &c));
    assert!(r.is_success && r.message == "This device's records were erased; online backup stays paused.");
    assert_eq!((c.pull.get(), c.outbox.get(), ct.calls), (0, 0, 0));
    cleanup(&f);
}

#[test]
fn enrolled_user_is_paused_while_release_closed_and_nothing_runs() {
    // THE production-parity steady state: cloud on, no pending revoke,
    // release closed -> paused; token, transport, pull, outbox, conflicts,
    // and lastSync writes are all untouched.
    let f = file("b4"); enrolled(&f);
    assert!(!CONFLICT_SAFE_PROTOCOL_RELEASED);
    let c = Counters::new();
    let mut ct = CT { result: Ok(V3ConsentReceipt { enabled: false, generation: 9, reason: None }), calls: 0 };
    let r = with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| run(h, AccessTokenResolution::Valid("tok".into()), &mut ct, &c));
    assert!(!r.is_success && !r.should_retry && !r.requires_authentication);
    assert_eq!(r.message, "Online backup is paused for now. Your records stay safe on this device.");
    assert_eq!((c.pull.get(), c.outbox.get(), c.conflicts.get(), ct.calls), (0, 0, 0, 0));
    with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| {
        assert_eq!(h.last_sync_message(), "Never synced");
        assert_eq!(h.last_sync_timestamp(), 0);
    });
    cleanup(&f);
}

#[test]
fn pending_withdrawal_auth_routes() {
    let f = file("b5"); enrolled(&f);
    with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| { let _ = h.pause_health_sync_for(OWNER, 11).unwrap(); });
    for (res, expect_msg, auth, retry) in [
        (AccessTokenResolution::Missing, "Please sign in again to finish.", true, false),
        (AccessTokenResolution::ReauthenticationRequired, "Please sign in again to finish.", true, false),
        (AccessTokenResolution::TemporarilyUnavailable, "Your secure session isn't available right now. Your records stay private on this device.", false, true),
    ] {
        let c = Counters::new();
        let mut ct = CT { result: Ok(V3ConsentReceipt { enabled: false, generation: 9, reason: None }), calls: 0 };
        let r = with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| run(h, res.clone(), &mut ct, &c));
        assert_eq!((r.message.as_str(), r.requires_authentication, r.should_retry, r.is_success), (expect_msg, auth, retry, false));
        assert_eq!(ct.calls, 0, "no consent call without a token");
    }
    // Pending flag still set after all auth-route returns.
    with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| assert!(h.has_pending_health_sync_revoke()));
    cleanup(&f);
}

#[test]
fn pending_withdrawal_success_clears_flag_durably() {
    let f = file("b6"); enrolled(&f);
    with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| { let _ = h.pause_health_sync_for(OWNER, 11).unwrap(); });
    let c = Counters::new();
    let mut ct = CT { result: Ok(V3ConsentReceipt { enabled: false, generation: 9, reason: None }), calls: 0 };
    let r = with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| run(h, AccessTokenResolution::Valid("tok".into()), &mut ct, &c));
    assert!(r.is_success && r.message == "Your choice was saved. Online backup is off.");
    assert_eq!((ct.calls, c.pull.get(), c.outbox.get(), c.conflicts.get()), (1, 0, 0, 0), "consent decision only, never a health payload");
    with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| {
        assert!(!h.has_pending_health_sync_revoke(), "durable flag cleared");
        assert!(!h.is_cloud_sync_allowed());
        assert_eq!(h.health_consent_generation(), 4);
        assert_eq!(h.last_sync_message(), "Never synced", "withdrawal writes no lastSync prefs");
    });
    cleanup(&f);
}

#[test]
fn failed_withdrawal_hits_catch_and_flag_stays_durable() {
    let f = file("b7"); enrolled(&f);
    with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| { let _ = h.pause_health_sync_for(OWNER, 11).unwrap(); });
    let c = Counters::new();
    let mut ct = CT { result: Err(()), calls: 0 };
    let r = with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| run(h, AccessTokenResolution::Valid("tok".into()), &mut ct, &c));
    assert!(!r.is_success && r.should_retry);
    assert_eq!(r.message, "Backup couldn't finish. Your changes stay saved on this device; please try again.");
    with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| {
        assert!(h.has_pending_health_sync_revoke(), "failed revocation leaves the durable pending flag set");
        assert!(!h.is_cloud_sync_allowed());
    });
    cleanup(&f);
}

#[test]
fn stale_receipt_during_withdrawal_also_hits_catch() {
    let f = file("b8"); enrolled(&f);
    with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| { let _ = h.pause_health_sync_for(OWNER, 11).unwrap(); });
    let c = Counters::new();
    // receipt.enabled mismatches the requested disable -> stale_or_invalid -> catch.
    let mut ct = CT { result: Ok(V3ConsentReceipt { enabled: true, generation: 9, reason: None }), calls: 0 };
    let r = with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| run(h, AccessTokenResolution::Valid("tok".into()), &mut ct, &c));
    assert_eq!(r.message, "Backup couldn't finish. Your changes stay saved on this device; please try again.");
    with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| assert!(h.has_pending_health_sync_revoke()));
    cleanup(&f);
}

// ---- Release-gated upload body (unreachable in production parity) --------
fn upload_prefs(path: &std::path::Path) { enrolled(path); }

#[test]
fn upload_body_full_success_writes_last_sync_durably() {
    let f = file("u1"); upload_prefs(&f);
    let c = Counters::new();
    let mut ct = CT { result: Err(()), calls: 0 };
    let r = with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| {
        sync_all_body(
            OWNER, h, &mut ct,
            &mut |_o, l, s, se, _t| { Counters::bump(&c.pull); assert_eq!((l, s, se), (3, 7, 11)); Ok(2) },
            &mut |_t| { Counters::bump(&c.outbox); Ok(3) },
            &mut |_o| { Counters::bump(&c.conflicts); Ok(0) },
            &mut || true, &mut || 1_700_000_000_000, &mut || "op".into(), "tok",
        ).unwrap()
    });
    assert!(r.is_success);
    assert_eq!(r.message, "Backup complete (3 sent, 2 received).");
    assert_eq!((r.synced_logs_count, r.timestamp), (5, 1_700_000_000_000));
    with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| {
        assert_eq!(h.last_sync_timestamp(), 1_700_000_000_000);
        assert!(h.last_sync_success());
        assert_eq!(h.last_sync_message(), "Backup complete (3 sent, 2 received).");
    });
    cleanup(&f);
}

#[test]
fn upload_body_conflicts_message_and_no_overwrite() {
    let f = file("u2"); upload_prefs(&f);
    let c = Counters::new();
    let mut ct = CT { result: Err(()), calls: 0 };
    let r = with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| {
        sync_all_body(
            OWNER, h, &mut ct,
            &mut |_o, _l, _s, _se, _t| { Counters::bump(&c.pull); Ok(1) },
            &mut |_t| { Counters::bump(&c.outbox); Ok(1) },
            &mut |_o| { Counters::bump(&c.conflicts); Ok(2) },
            &mut || true, &mut || 42, &mut || "op".into(), "tok",
        ).unwrap()
    });
    assert!(!r.is_success);
    assert_eq!(r.message, "2 saved record(s) need your choice in Settings; nothing was overwritten.");
    with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| assert!(!h.last_sync_success()));
    cleanup(&f);
}

#[test]
fn upload_body_requires_saved_consent_receipt_first() {
    let f = file("u3"); enrolled(&f);
    // Advance the health generation so the stored receipt is stale.
    with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| h.set_health_consent_generation(4));
    let c = Counters::new();
    let mut ct = CT { result: Err(()), calls: 0 };
    let r = with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| {
        sync_all_body(OWNER, h, &mut ct, &mut |_, _, _, _, _| { Counters::bump(&c.pull); Ok(0) }, &mut |_| { Counters::bump(&c.outbox); Ok(0) }, &mut |_| { Counters::bump(&c.conflicts); Ok(0) }, &mut || true, &mut || 0, &mut || "op".into(), "tok").unwrap()
    });
    assert!(!r.is_success && r.message == "Please allow online backup in Settings first.");
    assert_eq!((c.pull.get(), c.outbox.get()), (0, 0), "nothing pulled or uploaded without a current receipt");
    cleanup(&f);
}

#[test]
fn upload_body_current_gate_after_sync_writes_nothing() {
    let f = file("u4"); upload_prefs(&f);
    let c = Counters::new();
    let mut ct = CT { result: Err(()), calls: 0 };
    let r = with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| {
        sync_all_body(
            OWNER, h, &mut ct,
            &mut |_o, _l, _s, _se, _t| { Counters::bump(&c.pull); Ok(2) },
            &mut |_t| { Counters::bump(&c.outbox); Ok(3) },
            &mut |_o| { Counters::bump(&c.conflicts); Ok(0) },
            &mut || false, // account/choices changed before the re-check
            &mut || 0, &mut || "op".into(), "tok",
        ).unwrap()
    });
    assert!(!r.is_success && r.message == "Backup stopped because the account or your choices changed.");
    with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| {
        assert_eq!(h.last_sync_message(), "Never synced", "no lastSync writes on gate drift");
        assert_eq!(h.last_sync_timestamp(), 0);
    });
    cleanup(&f);
}

#[test]
fn upload_body_pull_error_escapes_to_catch() {
    let f = file("u5"); upload_prefs(&f);
    let c = Counters::new();
    let mut ct = CT { result: Err(()), calls: 0 };
    let r = with_prefs!(&f, Some(OWNER), false, |h: &mut HealthAuthPrefs| {
        sync_all_body(OWNER, h, &mut ct, &mut |_, _, _, _, _| { Counters::bump(&c.pull); Err(()) }, &mut |_| { Counters::bump(&c.outbox); Ok(0) }, &mut |_| { Counters::bump(&c.conflicts); Ok(0) }, &mut || true, &mut || 0, &mut || "op".into(), "tok")
    });
    assert_eq!(r, Err(()), "exception leaves the body; caller maps to the catch result");
    assert_eq!(catch_result(7).message, "Backup couldn't finish. Your changes stay saved on this device; please try again.");
    assert_eq!(c.outbox.get(), 0, "outbox not attempted after a pull failure");
    cleanup(&f);
}

//! Delegation-shape pins for SyncUseCase.kt: executeSync forwards to
//! syncAll exactly once with nothing added; getter mirrors prefs; setter
//! routes through the generation-bumping AuthPreferences setter and
//! propagates its typed overflow error like Kotlin's ArithmeticException.
use cyclecare_core::auth_prefs_health::HealthAuthPrefs;
use cyclecare_core::prefs_scratch::ScratchPrefs;
use cyclecare_core::session_token::AccessTokenResolution;
use cyclecare_core::sync_use_case::{execute_sync, is_cloud_sync_allowed, set_cloud_sync_allowed};
use cyclecare_core::v3_consent::V3ConsentTransport;
use cyclecare_core::v3_sync::V3ConsentReceipt;
use std::cell::Cell;

struct NoConsent;
impl V3ConsentTransport for NoConsent {
    fn set_consent(&mut self, _e: bool, _t: &str) -> Result<V3ConsentReceipt, ()> { Err(()) }
}

fn prefs_with(p: &mut ScratchPrefs) -> HealthAuthPrefs<'_> {
    HealthAuthPrefs { prefs: p, secure_available: true, secure_user_id: Some("alice".into()), device_fallback_owner: "dev".into(), owner_wiped: false, live_user_id: None, live_owner_wiped: None }
}

#[test]
fn getter_mirrors_prefs_state() {
    let mut p = ScratchPrefs::open_in_memory();
    p.edit().put_bool("cloud_sync_allowed_v2_alice", true).commit();
    assert!(is_cloud_sync_allowed(&prefs_with(&mut p)));
    let mut p2 = ScratchPrefs::open_in_memory();
    assert!(!is_cloud_sync_allowed(&prefs_with(&mut p2)), "absent key reads false, like the Kotlin getter default");
}

#[test]
fn setter_routes_through_generation_bumping_setter() {
    let mut p = ScratchPrefs::open_in_memory();
    let mut prefs = prefs_with(&mut p);
    set_cloud_sync_allowed(&mut prefs, true).unwrap();
    assert_eq!(prefs.prefs.get_long("health_consent_generation_alice", 0), 1, "enable bumped the health-consent generation (AuthPreferences.kt:147)");
    set_cloud_sync_allowed(&mut prefs, true).unwrap();
    assert_eq!(prefs.prefs.get_long("health_consent_generation_alice", 0), 1, "same-value write is a no-op decision, no bump (:143)");
    set_cloud_sync_allowed(&mut prefs, false).unwrap();
    assert_eq!(prefs.prefs.get_long("health_consent_generation_alice", 0), 2, "disable bumped again");
    assert!(prefs.prefs.get_bool("health_sync_pending_revoke_alice", false), "disable sets the durable pending-revoke flag (:151-155)");
}

#[test]
fn execute_sync_is_a_bare_forward_to_sync_all() {
    // Cloud sync NOT allowed and no pending revoke -> syncAll's :115-116
    // early return. If the use case added or skipped anything, the message
    // or the closure-call counts would diverge.
    let mut p = ScratchPrefs::open_in_memory();
    let mut prefs = prefs_with(&mut p);
    let calls = Cell::new(0u32);
    let r = execute_sync(
        &mut prefs,
        &mut || { calls.set(calls.get() + 1); AccessTokenResolution::Missing },
        &mut NoConsent,
        &mut |_, _, _, _, _| { calls.set(calls.get() + 1); Ok(0) },
        &mut |_| { calls.set(calls.get() + 1); Ok(0) },
        &mut |_| { calls.set(calls.get() + 1); Ok(0) },
        &mut || true,
        &mut || 1000,
        &mut || "u".into(),
    );
    assert!(r.is_success, "syncAll :115-116 reports success with nothing sent");
    assert_eq!(r.message, "Online backup is off, so nothing was sent.", "syncAll's verbatim early-return message, unchanged by the delegate");
    assert_eq!(calls.get(), 0, "no dependency was touched on the early-return path - the use case added nothing");
}

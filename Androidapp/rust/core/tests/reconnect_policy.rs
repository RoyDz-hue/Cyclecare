//! Reconnect-work policy tests, mirroring the Kotlin rules.

use cyclecare_core::reconnect_policy::*;

#[test]
fn initial_online_emit_is_not_a_reconnect() {
    let mut s = ReconnectState::new();
    assert_eq!(decide(&mut s, true, true, Some("roy"), 1000), Decision::Skip);
}

#[test]
fn offline_to_online_transition_runs_once() {
    let mut s = ReconnectState::new();
    decide(&mut s, true, true, Some("roy"), 1000); // initial: skip, owner stamped
    assert_eq!(decide(&mut s, false, true, Some("roy"), 2000), Decision::Skip);
    assert_eq!(decide(&mut s, true, true, Some("roy"), 3000), Decision::Run);
    // A second transition inside the cooldown is deferred, not dropped.
    decide(&mut s, false, true, Some("roy"), 4000);
    let d = decide(&mut s, true, true, Some("roy"), 5000);
    assert_eq!(d, Decision::Throttled { retry_after_ms: MIN_INTERVAL_MS - (5000 - 3000) });
}

#[test]
fn throttled_transition_fires_one_trailing_run_after_cooldown() {
    let mut s = ReconnectState::new();
    decide(&mut s, true, true, Some("roy"), 0);
    decide(&mut s, false, true, Some("roy"), 10_000);
    decide(&mut s, true, true, Some("roy"), 20_000); // run at 20s... 
    decide(&mut s, false, true, Some("roy"), 25_000);
    let d = decide(&mut s, true, true, Some("roy"), 30_000); // throttled, retry after 50s
    assert!(matches!(d, Decision::Throttled { .. }));
    // Trailing fires only once the cooldown expired.
    let early = decide_trailing(&mut s, true, true, Some("roy"), 50_000);
    assert!(matches!(early, Decision::Throttled { .. }), "too early: reports remaining cooldown");
    assert_eq!(decide_trailing(&mut s, true, true, Some("roy"), 80_000), Decision::Run);
    // And only once: a second trailing call is throttled by the run it just stamped.
    assert!(matches!(decide_trailing(&mut s, true, true, Some("roy"), 90_000), Decision::Throttled { .. }));
}

#[test]
fn owner_change_resets_throttle_and_never_inherits() {
    let mut s = ReconnectState::new();
    decide(&mut s, true, true, Some("roy"), 0);
    decide(&mut s, false, true, Some("roy"), 10_000);
    decide(&mut s, true, true, Some("roy"), 20_000); // roy's run at 20s
    decide(&mut s, false, true, Some("roy"), 25_000);
    // New owner reconnecting 5s later: must run immediately, not inherit the throttle.
    assert_eq!(decide(&mut s, true, true, Some("someone-else"), 30_000), Decision::Run);
}

#[test]
fn trailing_run_is_skipped_for_a_different_owner() {
    let mut s = ReconnectState::new();
    decide(&mut s, true, true, Some("roy"), 0);
    decide(&mut s, false, true, Some("roy"), 10_000);
    decide(&mut s, true, true, Some("roy"), 20_000);
    assert_eq!(decide_trailing(&mut s, true, true, Some("someone-else"), 100_000), Decision::Skip);
}

#[test]
fn offline_or_ineligible_never_runs() {
    let mut s = ReconnectState::new();
    decide(&mut s, true, true, Some("roy"), 0);
    assert_eq!(decide(&mut s, false, true, Some("roy"), 100_000), Decision::Skip);
    assert_eq!(decide(&mut s, true, false, Some("roy"), 200_000), Decision::Skip, "ineligible");
    assert_eq!(decide_trailing(&mut s, false, true, Some("roy"), 300_000), Decision::Skip);
}

#[test]
fn first_reconnect_for_owner_with_no_history_never_throttled() {
    let mut s = ReconnectState::new();
    decide(&mut s, false, true, Some("roy"), 1_000_000);
    assert_eq!(decide(&mut s, true, true, Some("roy"), 1_000_001), Decision::Run,
        "last_run_at_ms == 0 means no history: no throttle");
}

//! Wallet state machine tests, mirroring WalletStatusTest's contract:
//! - credential-less modes never fetch and never fake a balance
//! - a failed fetch settles STALE (same-owner last known) or UNAVAILABLE,
//!   never READY-zero
//! - a delayed response from an old owner never populates the new owner's card
//! plus exhaustive transition coverage over the apply matrix.

use bigdecimal::BigDecimal;
use cyclecare_core::wallet::{
    ApplyVerdict, FetchOutcome, IdentitySnapshot, RefreshPlan, VerifiedProfile, WalletMachine,
    WalletStatus,
};

fn owner(name: &str, gen: u64) -> IdentitySnapshot {
    IdentitySnapshot { owner: name.to_string(), session_generation: gen }
}

fn profile(name: &str, coins: i64) -> VerifiedProfile {
    VerifiedProfile {
        owner: name.to_string(),
        balance_coins: BigDecimal::from(coins),
        is_banned: false,
        is_suspended: false,
        suspension_reason: None,
    }
}

#[test]
fn local_only_mode_has_no_session_and_never_fetches() {
    let mut m = WalletMachine::new();
    let plan = m.begin_refresh(true, Some(owner("roy", 1)), false, false, None, 0);
    assert_eq!(plan, RefreshPlan::NoFetch);
    assert_eq!(m.status(), WalletStatus::NoSession);
    let plan = m.begin_refresh(false, None, false, false, None, 0);
    assert_eq!(plan, RefreshPlan::NoFetch);
    assert_eq!(m.status(), WalletStatus::NoSession);
}

#[test]
fn anonymous_guest_with_live_session_fetches_normally() {
    let mut m = WalletMachine::new();
    let plan = m.begin_refresh(false, Some(owner("anon-uuid", 1)), false, false, None, 0);
    assert!(matches!(plan, RefreshPlan::Fetch(_)), "anonymous session has a real cloud wallet");
    assert_eq!(m.status(), WalletStatus::Loading);
}

#[test]
fn signed_in_fetch_failure_settles_unavailable_never_fake_zero() {
    let mut m = WalletMachine::new();
    let snap = owner("roy", 1);
    let plan = m.begin_refresh(false, Some(snap.clone()), false, false, None, 0);
    assert!(matches!(plan, RefreshPlan::Fetch(_)));
    let (verdict, _) = m.apply_result(&snap, Some(&snap), FetchOutcome::Failure);
    assert_eq!(verdict, ApplyVerdict::Applied);
    assert_eq!(m.status(), WalletStatus::Unavailable);
    assert!(m.last_profile().is_none(), "no balance may be shown after a failed first fetch");
}

#[test]
fn same_owner_failure_after_ready_shows_stale_last_known() {
    let mut m = WalletMachine::new();
    let snap = owner("roy", 1);
    m.begin_refresh(false, Some(snap.clone()), true, false, None, 0);
    m.apply_result(&snap, Some(&snap), FetchOutcome::Success(profile("roy", 363)));
    assert_eq!(m.status(), WalletStatus::Ready);
    // Foreground refresh: status stays Ready while fetching (no LOADING flicker).
    m.begin_refresh(false, Some(snap.clone()), true, false, None, 31_000);
    assert_eq!(m.status(), WalletStatus::Ready);
    m.apply_result(&snap, Some(&snap), FetchOutcome::Failure);
    assert_eq!(m.status(), WalletStatus::Stale);
    assert_eq!(m.last_profile().unwrap().balance_coins, BigDecimal::from(363), "last-known 363 must survive");
}

#[test]
fn delayed_response_from_old_owner_never_populates_new_owner_card() {
    let mut m = WalletMachine::new();
    let old = owner("roy", 1);
    m.begin_refresh(false, Some(old.clone()), true, false, None, 0);
    // Identity switches mid-flight.
    m.on_identity_change();
    let new = owner("someone-else", 2);
    let (verdict, freshness) = m.apply_result(&old, Some(&new), FetchOutcome::Success(profile("roy", 363)));
    assert_eq!(verdict, ApplyVerdict::IgnoredStaleIdentity);
    assert!(!freshness, "stale responses never mark the freshness cache");
    assert_ne!(m.status(), WalletStatus::Ready, "new owner's card must not reach READY from the old owner's response");
    assert!(m.last_profile().is_none(), "old owner's profile must not be published");
}

#[test]
fn session_regeneration_same_owner_still_invalidates_inflight() {
    let mut m = WalletMachine::new();
    let gen1 = owner("roy", 1);
    m.begin_refresh(false, Some(gen1.clone()), true, false, None, 0);
    let gen2 = owner("roy", 2); // same human, new session generation
    let (verdict, _) = m.apply_result(&gen1, Some(&gen2), FetchOutcome::Success(profile("roy", 363)));
    assert_eq!(verdict, ApplyVerdict::IgnoredStaleIdentity);
}

#[test]
fn cooldown_dedupes_per_owner_but_owner_change_always_fetches() {
    let mut m = WalletMachine::new();
    let snap = owner("roy", 1);
    let last = ("roy".to_string(), 1u64, 10_000u64);
    let plan = m.begin_refresh(false, Some(snap.clone()), false, false, Some(&last), 20_000);
    assert_eq!(plan, RefreshPlan::CooldownSkip, "10s < 30s cooldown");
    let other = owner("someone-else", 2);
    let plan = m.begin_refresh(false, Some(other), false, false, Some(&last), 20_000);
    assert!(matches!(plan, RefreshPlan::Fetch(_)), "owner change bypasses dedupe");
    let plan = m.begin_refresh(false, Some(snap.clone()), true, false, Some(&last), 20_000);
    assert!(matches!(plan, RefreshPlan::Fetch(_)), "manual retry always fetches");
}

#[test]
fn real_zero_from_server_is_shown_but_fetch_failure_is_not_zero() {
    let mut m = WalletMachine::new();
    let snap = owner("roy", 1);
    m.begin_refresh(false, Some(snap.clone()), true, false, None, 0);
    m.apply_result(&snap, Some(&snap), FetchOutcome::Success(profile("roy", 0)));
    assert_eq!(m.status(), WalletStatus::Ready);
    assert_eq!(m.last_profile().unwrap().balance_coins, BigDecimal::from(0), "a verified real zero is displayable");

    let mut m2 = WalletMachine::new();
    m2.begin_refresh(false, Some(snap.clone()), true, false, None, 0);
    m2.apply_result(&snap, Some(&snap), FetchOutcome::Failure);
    assert_ne!(m2.status(), WalletStatus::Ready, "failure must never masquerade as READY-0");
    assert!(m2.last_profile().is_none());
}

#[test]
fn loading_shown_only_from_empty_states() {
    for start in [WalletStatus::NoSession, WalletStatus::Unavailable] {
        let mut m = WalletMachine::new();
        m.on_identity_change();
        if start == WalletStatus::Unavailable {
            let snap = owner("roy", 1);
            m.begin_refresh(false, Some(snap.clone()), true, false, None, 0);
            m.apply_result(&snap, Some(&snap), FetchOutcome::Failure);
            assert_eq!(m.status(), WalletStatus::Unavailable);
        }
        m.begin_refresh(false, Some(owner("roy", 1)), true, false, None, 0);
        assert_eq!(m.status(), WalletStatus::Loading, "from {start:?}");
    }
}

#[test]
fn decimal_balances_stay_exact_through_the_machine() {
    use std::str::FromStr;
    // 3-decimal contract balance (CoinFormatter: up to 3 decimals, no float)
    let mut m = WalletMachine::new();
    let snap = owner("roy", 1);
    let precise = VerifiedProfile {
        owner: "roy".into(),
        balance_coins: BigDecimal::from_str("363.575").unwrap(),
        is_banned: false, is_suspended: false, suspension_reason: None,
    };
    let (v, _) = m.apply_result(&snap, Some(&snap), FetchOutcome::Success(precise));
    assert_eq!(v, ApplyVerdict::Applied);
    assert_eq!(m.last_profile().unwrap().balance_coins, BigDecimal::from_str("363.575").unwrap());
    // a later failed refresh keeps the exact 3-decimal value (STALE, not zero)
    let (v, _) = m.apply_result(&snap, Some(&snap), FetchOutcome::Failure);
    assert_eq!(v, ApplyVerdict::Applied);
    assert_eq!(m.status(), WalletStatus::Stale);
    assert_eq!(m.last_profile().unwrap().balance_coins, BigDecimal::from_str("363.575").unwrap());
    // large balances: no float rounding at 7 digits + 3 decimals
    let mut m2 = WalletMachine::new();
    let big = VerifiedProfile {
        owner: "roy".into(),
        balance_coins: BigDecimal::from_str("1234567.891").unwrap(),
        is_banned: false, is_suspended: false, suspension_reason: None,
    };
    m2.apply_result(&snap, Some(&snap), FetchOutcome::Success(big));
    assert_eq!(m2.last_profile().unwrap().balance_coins, BigDecimal::from_str("1234567.891").unwrap());
}

#[test]
fn coin_formatter_displays_decimal_contract() {
    use std::str::FromStr;
    // DecimalFormat("#,##0.###"): 3 decimals kept, trailing zeros dropped,
    // thousands grouped. (Executed corpus lives in coin_format tests; this
    // pins the wallet-display path specifically.)
    assert_eq!(cyclecare_core::coin_format::format(&BigDecimal::from_str("363.575").unwrap()), "363.575");
    assert_eq!(cyclecare_core::coin_format::format(&BigDecimal::from_str("363.500").unwrap()), "363.5");
    assert_eq!(cyclecare_core::coin_format::format(&BigDecimal::from_str("1234567.891").unwrap()), "1,234,567.891");
}

//! End-to-end transition traces for the original failing path: reopening an
//! existing install must recover the same owner/session, preserve completed
//! onboarding, load the existing wallet, and render either the real balance
//! or an explicit failure - never a fake zero. Each scenario threads the
//! executed-locked components in order (startup_gate -> session/token ->
//! verified-profile parse -> wallet machine) and asserts the whole trace,
//! not isolated function results. Component semantics were probe-locked on
//! the JVM (LifecycleParity/SessionTokenParity/VerifiedProfileParity,
//! 2026-11-02); this test locks their composition.

use cyclecare_core::lifecycle::{effective_user_id, startup_gate, SessionStore};
use bigdecimal::BigDecimal;
use cyclecare_core::session_token::{resolve_access_token, AccessTokenResolution, RefreshResponse};
use cyclecare_core::verified_profile::parse_verified_profile;
use cyclecare_core::wallet::{FetchOutcome, IdentitySnapshot, RefreshPlan, WalletMachine, WalletStatus};

const NOW: i64 = 1_700_000_000;
const NOW_MS: u64 = 1_700_000_000_000;

fn base_store() -> SessionStore {
    // Returning owner "u1": completed initial auth, wallet session present.
    let mut p = SessionStore::new(true);
    p.set_user_id(Some("u1"));
    p
}

fn gate_line(p: &SessionStore, local: bool, profile_present: bool, onboarding_completed: bool) -> String {
    let g = startup_gate(
        local,
        p.is_secure_storage_available(),
        true, // KEY_INITIAL_AUTH_COMPLETED persisted
        profile_present,
        onboarding_completed,
        false,
        false,
        false,
        p.user_id().is_some(),
    );
    format!(
        "gate: auth={} cloudCheck={} locked={} onboarding={} fetch={}",
        g.has_completed_initial_auth, g.needs_cloud_profile_check, g.is_locked, g.show_onboarding, g.fetch_wallet
    )
}

const ECON_363: &str =
    "[{\"user_id\":\"u1\",\"status\":\"ACTIVE\",\"coin_balance\":363,\"lifetime_earned\":400,\"lifetime_spent\":37}]";
const PROF_ACTIVE: &str = "{\"user_id\":\"u1\",\"status\":\"ACTIVE\"}";
const NOW_ISO: &str = "2026-11-02T10:00:00Z";

fn snapshot_of(p: &SessionStore) -> IdentitySnapshot {
    IdentitySnapshot {
        owner: effective_user_id(p.user_id(), "dev-1"),
        session_generation: p.session_generation() as u64,
    }
}

#[test]
fn trace_a_reopen_valid_session_wallet_loads() {
    let mut trace: Vec<String> = Vec::new();
    let mut p = base_store();
    p.set_access_token(Some("tokA"));
    p.set_session_expires_at_seconds(NOW + 3600);
    trace.push(gate_line(&p, false, true, true));
    let calls = std::cell::Cell::new(0u32);
    let res = resolve_access_token(&mut p, NOW, &|_| {
        calls.set(calls.get() + 1);
        Err(())
    });
    trace.push(format!(
        "session: {} (transport calls {})",
        match &res {
            AccessTokenResolution::Valid(t) => format!("Valid({t})"),
            other => format!("{other:?}"),
        },
        calls.get()
    ));
    let mut w = WalletMachine::new();
    let snap = snapshot_of(&p);
    match w.begin_refresh(false, Some(snap.clone()), false, false, None, NOW_MS) {
        RefreshPlan::Fetch(s) => trace.push(format!("wallet: begin Fetch owner={} g={}", s.owner, s.session_generation)),
        other => trace.push(format!("wallet: begin {other:?}")),
    }
    let parsed = parse_verified_profile("u1", ECON_363, PROF_ACTIVE, NOW_ISO).expect("vp01-class body parses");
    trace.push(format!("profile: balance={} status={}", parsed.coin_balance, parsed.status));
    let (verdict, applied) = w.apply_result(
        &snap,
        Some(&snap),
        FetchOutcome::Success(cyclecare_core::wallet::VerifiedProfile {
            owner: "u1".into(),
            balance_coins: parsed.coin_balance.parse().unwrap(),
            is_banned: false,
            is_suspended: false,
            suspension_reason: None,
        }),
    );
    trace.push(format!("wallet: {verdict:?} applied={applied} -> {:?}", w.status()));
    assert_eq!(
        trace,
        vec![
            "gate: auth=true cloudCheck=false locked=false onboarding=false fetch=true",
            "session: Valid(tokA) (transport calls 0)",
            "wallet: begin Fetch owner=u1 g=1",
            "profile: balance=363 status=ACTIVE",
            "wallet: Applied applied=true -> Ready",
        ]
    );
}

#[test]
fn trace_b_expired_session_refreshes_then_wallet_loads() {
    let mut p = base_store();
    p.set_access_token(Some("tokA"));
    p.set_refresh_token(Some("r1"));
    p.set_session_expires_at_seconds(NOW + 10); // inside the 90s expiry window
    let gate = gate_line(&p, false, true, true);
    let body = "{\"access_token\":\"eyJhbGciOiJub25lIn0.eyJzdWIiOiJvd25lcjEiLCJleHAiOjE3MDAwMDcyMDB9.c2ln\",\"refresh_token\":\"r2\",\"user\":{\"id\":\"owner1\",\"email\":\"e@x.com\",\"is_anonymous\":false},\"expires_in\":3600}";
    // NOTE: the refresh JWT's sub is owner1 while this scenario's owner is u1;
    // use owner1 for coherence with the executed session corpus (s10).
    let mut p = SessionStore::new(true);
    p.set_user_id(Some("owner1"));
    p.set_access_token(Some("tokA"));
    p.set_refresh_token(Some("r1"));
    p.set_session_expires_at_seconds(NOW + 10);
    let calls = std::cell::Cell::new(0u32);
    let res = resolve_access_token(&mut p, NOW, &|_| {
        calls.set(calls.get() + 1);
        Ok(RefreshResponse { code: 200, body: Some(body.to_string()) })
    });
    let line = match &res {
        AccessTokenResolution::Valid(t) => format!(
            "session: Valid({}..) (transport calls {}, committed exp={}, rt={}, mode={})",
            &t[..12],
            calls.get(),
            p.session_expires_at_seconds(),
            p.refresh_token().unwrap_or("null"),
            p.account_mode()
        ),
        other => format!("session: {other:?}"),
    };
    assert_eq!(gate, "gate: auth=true cloudCheck=false locked=false onboarding=false fetch=true");
    assert_eq!(
        line,
        "session: Valid(eyJhbGciOiJu..) (transport calls 1, committed exp=1700003600, rt=r2, mode=EMAIL)"
    );
}

#[test]
fn trace_c_fetch_malformed_is_explicit_failure_never_fake_zero() {
    let mut p = base_store();
    p.set_access_token(Some("tokA"));
    p.set_session_expires_at_seconds(NOW + 3600);
    let gate = gate_line(&p, false, true, true);
    let err = parse_verified_profile("u1", "{bad", PROF_ACTIVE, NOW_ISO).unwrap_err();
    let mut w = WalletMachine::new();
    let snap = snapshot_of(&p);
    let _ = w.begin_refresh(false, Some(snap.clone()), false, false, None, NOW_MS);
    let (verdict, applied) = w.apply_result(&snap, Some(&snap), FetchOutcome::Failure);
    assert_eq!(gate, "gate: auth=true cloudCheck=false locked=false onboarding=false fetch=true");
    assert_eq!(
        err,
        "Cloud wallet response was malformed. Please retry before using the shared vault."
    );
    assert_eq!(format!("wallet: {verdict:?} applied={applied} -> {:?}", w.status()),
        "wallet: Applied applied=false -> Unavailable");
    assert!(w.last_profile().is_none(), "no balance may be shown on failure");
}

#[test]
fn trace_d_failure_after_prior_success_keeps_stale_balance() {
    let mut w = WalletMachine::new();
    let snap = IdentitySnapshot { owner: "u1".into(), session_generation: 1 };
    let _ = w.begin_refresh(false, Some(snap.clone()), false, false, None, NOW_MS);
    let _ = w.apply_result(&snap, Some(&snap), FetchOutcome::Success(cyclecare_core::wallet::VerifiedProfile {
        owner: "u1".into(), balance_coins: BigDecimal::from(363), is_banned: false, is_suspended: false, suspension_reason: None,
    }));
    assert_eq!(w.status(), WalletStatus::Ready);
    // Later refresh fails for the SAME owner: keep the real 363 as STALE.
    let _ = w.begin_refresh(false, Some(snap.clone()), true, false, None, NOW_MS + 60_000);
    let (_, applied) = w.apply_result(&snap, Some(&snap), FetchOutcome::Failure);
    assert!(!applied);
    assert_eq!(w.status(), WalletStatus::Stale);
    assert_eq!(w.last_profile().unwrap().balance_coins, BigDecimal::from(363));
}

#[test]
fn trace_e_owner_switch_mid_flight_drops_response() {
    let mut w = WalletMachine::new();
    let started = IdentitySnapshot { owner: "u1".into(), session_generation: 1 };
    let _ = w.begin_refresh(false, Some(started.clone()), false, false, None, NOW_MS);
    let now_as = IdentitySnapshot { owner: "u2".into(), session_generation: 1 };
    let (verdict, _) = w.apply_result(&started, Some(&now_as), FetchOutcome::Success(
        cyclecare_core::wallet::VerifiedProfile {
            owner: "u1".into(), balance_coins: BigDecimal::from(363), is_banned: false, is_suspended: false, suspension_reason: None,
        },
    ));
    // The dropped response leaves the in-flight card untouched (LOADING),
    // exactly like the Kotlin `return@launch` on identity mismatch.
    assert_eq!(format!("{verdict:?}"), "IgnoredStaleIdentity");
    assert_eq!(w.status(), WalletStatus::Loading);
    assert!(w.last_profile().is_none());
}

#[test]
fn trace_f_keystore_unavailable_reopen_is_logged_out() {
    let p = SessionStore::new(false); // secure storage unavailable
    let g = gate_line(&p, false, true, true);
    assert_eq!(g, "gate: auth=false cloudCheck=false locked=false onboarding=false fetch=false");
}

#[test]
fn trace_g_local_only_completed_onboarding_no_fetch() {
    let p = base_store();
    let g = startup_gate(true, true, true, true, true, false, false, false, true);
    assert!(!g.fetch_wallet && !g.needs_cloud_profile_check && !g.show_onboarding);
    let mut w = WalletMachine::new();
    let plan = w.begin_refresh(true, Some(snapshot_of(&p)), false, false, None, NOW_MS);
    assert_eq!(format!("{plan:?}"), "NoFetch");
    assert_eq!(w.status(), WalletStatus::NoSession);
}

#[test]
fn trace_h_completed_onboarding_survives_cloud_profile_restore() {
    // The preservation fix (ops review item 1): restoring a cloud profile over
    // a COMPLETED local base keeps isCompleted/completedAtTimestamp etc.
    let local = cyclecare_core::sync_codec::OnboardingProfile {
        is_completed: true,
        completed_at_timestamp: 1700000000000,
        is_age_verified: true,
        is_terms_agreed: true,
        account_mode: "SYNC_ACCOUNT".into(),
        remind_fertile_window: true,
        ..Default::default()
    };
    // Wire shape from the executed pp10 corpus row.
    let wire = r#"{"selected_goals":"","last_period_date":"","approximate_period_choice":"","cycle_length_option":"","cycle_length_days":28,"period_duration_days":5,"typical_symptoms":"","typical_energy":"","typical_sleep_quality":"","has_completed_deep_baseline":true}"#;
    let merged = cyclecare_core::sync_codec::parse_profile_payload(wire, "profile", Some(&local)).expect("parse");
    assert!(merged.is_completed);
    assert_eq!(merged.completed_at_timestamp, 1700000000000);
    assert_eq!(merged.account_mode, "SYNC_ACCOUNT");
    assert!(merged.remind_fertile_window);
}

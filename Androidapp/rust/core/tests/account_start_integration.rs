//! Executable account-start vertical slice over REAL scratch storage
//! (rusqlite file DB + plaintext scratch session file — NOT SQLCipher /
//! NOT EncryptedSharedPrefs; host persistence glue remains unported).
//!
//! Slice per the prioritized readiness requirement: reopen app -> same
//! owner/session resolves through the executed session-machine port ->
//! completed onboarding profile still present after process death -> wallet
//! loads via the WalletMachine -> Hub gets the fetched balance, or an
//! explicit failure state (never a fake zero).

use cyclecare_core::account_start::{decide, AccountStartDestination, LocalDataState};
use bigdecimal::BigDecimal;
use cyclecare_core::auth_link::AuthPrefsState;
use cyclecare_core::auth_session::resolve_valid_access_token;
use cyclecare_core::pkce::b64url_no_pad;
use cyclecare_core::session_token::RefreshResponse;
use cyclecare_core::storage_db::StorageDb;
use cyclecare_core::sync_codec::OnboardingProfile;
use cyclecare_core::wallet::{
    ApplyVerdict, FetchOutcome, IdentitySnapshot, RefreshPlan, VerifiedProfile, WalletMachine,
    WalletStatus,
};

fn jwt(sub: &str, exp: i64) -> String {
    let h = b64url_no_pad(br#"{"alg":"none"}"#);
    let p = b64url_no_pad(format!(r#"{{"sub":"{sub}","exp":{exp}}}"#).as_bytes());
    format!("{h}.{p}.ss")
}

/// Scratch persistence of the prefs surface the slice needs. Plaintext on
/// purpose: this mirrors the EncryptedSharedPrefs KEYS ONLY; the real
/// encrypted store is unported host glue and nothing here claims otherwise.
fn persist_prefs(prefs: &AuthPrefsState, path: &std::path::Path) {
    let body = format!(
        "user_id={}\nuser_email={}\naccess_token={}\nrefresh_token={}\nsession_expires_at_seconds={}\naccount_mode={}\nsession_generation={}\n",
        prefs.user_id.as_deref().unwrap_or(""),
        prefs.user_email.as_deref().unwrap_or(""),
        prefs.access_token.as_deref().unwrap_or(""),
        prefs.refresh_token.as_deref().unwrap_or(""),
        prefs.session_expires_at_seconds,
        prefs.account_mode.as_deref().unwrap_or(""),
        prefs.session_generation,
    );
    std::fs::write(path, body).unwrap();
}

fn restore_prefs(path: &std::path::Path) -> AuthPrefsState {
    let body = std::fs::read_to_string(path).unwrap();
    let kv = |key: &str| -> Option<String> {
        body.lines()
            .find_map(|l| l.strip_prefix(&format!("{key}=")))
            .map(|v| v.to_string())
            .filter(|v| !v.is_empty())
    };
    let mut prefs = AuthPrefsState::fresh();
    prefs.access_token = kv("access_token");
    prefs.refresh_token = kv("refresh_token");
    prefs.user_email = kv("user_email");
    prefs.session_expires_at_seconds =
        kv("session_expires_at_seconds").unwrap().parse().unwrap();
    prefs.account_mode = kv("account_mode");
    if let Some(uid) = kv("user_id") {
        prefs.set_user_id(Some(uid));
    }
    prefs
}

fn first_run(dir: &std::path::Path, token_exp: i64) {
    let db = StorageDb::open_file(&dir.join("owner.db")).unwrap();
    let mut profile = OnboardingProfile::default();
    profile.is_completed = true;
    db.insert_or_update_profile(&profile).unwrap();
    let mut prefs = AuthPrefsState::fresh();
    prefs.access_token = Some(jwt("u1", token_exp));
    prefs.refresh_token = Some("R1".into());
    prefs.user_email = Some("roy@x.y".into());
    prefs.session_expires_at_seconds = token_exp;
    prefs.account_mode = Some("EMAIL".into());
    prefs.set_user_id(Some("u1".into()));
    persist_prefs(&prefs, &dir.join("session.kv"));
    drop(db);
}

struct ReopenedSlice {
    destination: AccountStartDestination,
    token: Option<String>,
    onboarding_preserved: bool,
    wallet_status: WalletStatus,
    hub_balance: Option<bigdecimal::BigDecimal>,
    prefs: AuthPrefsState,
}

fn reopen(
    dir: &std::path::Path,
    now_secs: i64,
    fetch_ok: bool,
    refresh: &dyn Fn(&str) -> Result<RefreshResponse, ()>,
) -> ReopenedSlice {
    let mut prefs = restore_prefs(&dir.join("session.kv"));
    let db = StorageDb::open_file(&dir.join("owner.db")).unwrap();
    let onboarding_preserved = db.get_profile().unwrap().map(|p| p.is_completed).unwrap_or(false);
    let token = resolve_valid_access_token(&mut prefs, now_secs, refresh);
    let destination = decide(onboarding_preserved, LocalDataState::Clean, None);

    let mut wallet = WalletMachine::new();
    let identity = token.as_ref().map(|_| IdentitySnapshot {
        owner: prefs.user_id.clone().unwrap(),
        session_generation: prefs.session_generation as u64,
    });
    if let RefreshPlan::Fetch(started) =
        wallet.begin_refresh(false, identity, true, false, None, 1_000_000)
    {
        let outcome = if fetch_ok {
            FetchOutcome::Success(VerifiedProfile {
                owner: "u1".into(),
                balance_coins: BigDecimal::from(363),
                is_banned: false,
                is_suspended: false,
                suspension_reason: None,
            })
        } else {
            FetchOutcome::Failure
        };
        let current = IdentitySnapshot {
            owner: prefs.user_id.clone().unwrap(),
            session_generation: prefs.session_generation as u64,
        };
        let (verdict, _) = wallet.apply_result(&started, Some(&current), outcome);
        assert_eq!(verdict, ApplyVerdict::Applied);
    }
    let hub_balance = if wallet.status() == WalletStatus::Ready {
        wallet.last_profile().map(|p| p.balance_coins.clone())
    } else {
        None // Hub shows the explicit status, never a fabricated number
    };
    ReopenedSlice {
        destination,
        token,
        onboarding_preserved,
        wallet_status: wallet.status(),
        hub_balance,
        prefs,
    }
}

#[test]
fn reopen_same_owner_session_onboarding_and_wallet() {
    let dir = std::env::temp_dir().join(format!("cc-reopen-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    first_run(&dir, 4_000_000_000);
    let no_refresh = |_: &str| -> Result<RefreshResponse, ()> {
        panic!("no refresh expected for a live token")
    };
    let slice = reopen(&dir, 1_000, true, &no_refresh);
    assert_eq!(slice.destination, AccountStartDestination::Home);
    assert!(slice.onboarding_preserved, "completed onboarding survives process death");
    assert!(slice.token.is_some(), "same owner/session resolves without refresh");
    assert_eq!(slice.wallet_status, WalletStatus::Ready);
    assert_eq!(slice.hub_balance, Some(bigdecimal::BigDecimal::from(363)));
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn reopen_wallet_fetch_failure_is_explicit_never_zero() {
    let dir = std::env::temp_dir().join(format!("cc-reopen-fail-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    first_run(&dir, 4_000_000_000);
    let no_refresh = |_: &str| -> Result<RefreshResponse, ()> { panic!("no refresh") };
    let slice = reopen(&dir, 1_000, false, &no_refresh);
    assert_eq!(slice.wallet_status, WalletStatus::Unavailable);
    assert_eq!(slice.hub_balance, None, "a failed fetch must not surface as 0 coins");
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn reopen_expired_session_refreshes_and_commits_same_owner() {
    let dir = std::env::temp_dir().join(format!("cc-reopen-refresh-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    first_run(&dir, 100); // expired
    let renewed = jwt("u1", 4_000_000_000);
    let body = format!(
        r#"{{"access_token":"{renewed}","refresh_token":"R2","expires_in":3600,"user":{{"id":"u1","email":"roy@x.y","is_anonymous":false}}}}"#
    );
    let refresh = move |_: &str| -> Result<RefreshResponse, ()> {
        Ok(RefreshResponse { code: 200, body: Some(body.clone()) })
    };
    let slice = reopen(&dir, 1_000, true, &refresh);
    assert!(slice.token.is_some(), "refresh yields a usable token");
    assert_eq!(slice.prefs.refresh_token.as_deref(), Some("R2"), "rotation committed");
    assert_eq!(slice.prefs.user_id.as_deref(), Some("u1"), "same owner preserved");
    assert_eq!(slice.wallet_status, WalletStatus::Ready);
    assert_eq!(slice.hub_balance, Some(bigdecimal::BigDecimal::from(363)));
    let _ = std::fs::remove_dir_all(&dir);
}

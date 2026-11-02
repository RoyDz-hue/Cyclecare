//! Bridge between the auth-flow preference model (auth_link::AuthPrefsState)
//! and the executed session-manager port (session_token::resolve_access_token,
//! which operates on the raw key-value lifecycle::SessionStore).
//!
//! Field mapping mirrors the real AuthPreferences keys: user_id, user_email,
//! access_token, refresh_token, session_expires_at, account_mode,
//! session_generation_v3, secure flag. A refresh commit inside
//! resolve_access_token lands in the SessionStore view; apply the store back
//! to persist it (mirrors the SharedPreferences-backed real class where the
//! write is immediate).
//!
//! This is the production-grade adapter for FlowSeams.valid_access_token —
//! unlike the raw-token stub used to lock the flow corpus, this runs the real
//! expiry > now+90 check, the session-consistency guard, and the refresh
//! path (whose semantics are corpus-locked in session_token.rs).

use crate::auth_link::AuthPrefsState;
use crate::lifecycle::SessionStore;
use crate::session_token::{resolve_access_token, AccessTokenResolution, RefreshResponse};

/// Build the raw key-value view the session manager expects.
pub fn session_store_view(prefs: &AuthPrefsState) -> SessionStore {
    let mut store = SessionStore::new(prefs.is_secure_storage_available);
    store.set_user_id(prefs.user_id.as_deref());
    store.set_user_email(prefs.user_email.as_deref());
    store.set_access_token(prefs.access_token.as_deref());
    store.set_refresh_token(prefs.refresh_token.as_deref());
    store.set_session_expires_at_seconds(prefs.session_expires_at_seconds);
    store.set_account_mode(prefs.account_mode.as_deref().unwrap_or("ANONYMOUS"));
    // generation: SessionStore::new starts at 0; mirror via set_user_id bumps
    // is wrong here — the store tracks generation internally, so set it by
    // replaying the current value through its accessor contract.
    store.set_session_generation_for_bridge(prefs.session_generation);
    store
}

/// Write a (possibly refresh-committed) store back into the preference model.
pub fn apply_session_store(prefs: &mut AuthPrefsState, store: &SessionStore) {
    prefs.user_id = store.user_id().map(|s| s.to_string());
    prefs.user_email = store.user_email().map(|s| s.to_string());
    prefs.access_token = store.access_token().map(|s| s.to_string());
    prefs.refresh_token = store.refresh_token().map(|s| s.to_string());
    prefs.session_expires_at_seconds = store.session_expires_at_seconds();
    prefs.account_mode = Some(store.account_mode().to_string());
    prefs.session_generation = store.session_generation();
}

/// Production-grade validAccessToken: run the executed session-manager state
/// machine over a store view and commit its writes back.
pub fn resolve_valid_access_token(
    prefs: &mut AuthPrefsState,
    now_secs: i64,
    refresh_transport: &dyn Fn(&str) -> Result<RefreshResponse, ()>,
) -> Option<String> {
    let mut store = session_store_view(prefs);
    let resolution = resolve_access_token(&mut store, now_secs, refresh_transport);
    apply_session_store(prefs, &store);
    match resolution {
        AccessTokenResolution::Valid(token) => Some(token),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pkce::b64url_no_pad;

    fn jwt(sub: &str, exp: i64) -> String {
        let header = b64url_no_pad(br#"{"alg":"none"}"#);
        let payload = b64url_no_pad(format!(r#"{{"sub":"{sub}","exp":{exp}}}"#).as_bytes());
        format!("{header}.{payload}.{0}{0}", "s")
    }

    #[test]
    fn valid_token_round_trips_through_bridge() {
        let mut prefs = AuthPrefsState::fresh();
        let token = jwt("u1", 4_000_000_000);
        prefs.access_token = Some(token.clone());
        prefs.refresh_token = Some("R1".into());
        prefs.set_user_id(Some("u1".into()));
        prefs.session_expires_at_seconds = 4_000_000_000;
        let got = resolve_valid_access_token(&mut prefs, 1_000, &|_| {
            panic!("no refresh expected for a live token")
        });
        assert_eq!(got, Some(token));
        // unchanged state
        assert_eq!(prefs.user_id.as_deref(), Some("u1"));
        assert_eq!(prefs.session_expires_at_seconds, 4_000_000_000);
    }

    #[test]
    fn expired_token_refresh_commits_back_to_prefs() {
        let mut prefs = AuthPrefsState::fresh();
        let old = jwt("u1", 100);
        let renewed = jwt("u1", 4_000_000_000);
        prefs.access_token = Some(old);
        prefs.refresh_token = Some("R1".into());
        prefs.set_user_id(Some("u1".into()));
        prefs.session_expires_at_seconds = 100;
        let body = format!(
            r#"{{"access_token":"{renewed}","refresh_token":"R2","expires_in":3600,"user":{{"id":"u1","email":"e@x.y","is_anonymous":false}}}}"#
        );
        let got = resolve_valid_access_token(&mut prefs, 1_000, &|rt| {
            assert_eq!(rt, "R1");
            Ok(RefreshResponse { code: 200, body: Some(body.clone()) })
        });
        assert_eq!(got, Some(renewed));
        assert_eq!(prefs.refresh_token.as_deref(), Some("R2"));
        assert_eq!(prefs.user_email.as_deref(), Some("e@x.y"));
        assert_eq!(prefs.account_mode.as_deref(), Some("EMAIL"));
        assert!(prefs.session_expires_at_seconds > 1_000);
    }

    #[test]
    fn missing_token_maps_to_none_without_calls() {
        let mut prefs = AuthPrefsState::fresh();
        let got = resolve_valid_access_token(&mut prefs, 1_000, &|_| {
            panic!("no refresh without a token")
        });
        assert_eq!(got, None);
    }
}

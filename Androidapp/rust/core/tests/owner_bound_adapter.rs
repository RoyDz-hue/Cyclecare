//! Owner-bound seam composition, synthetic only: AuthPrefsState ->
//! session_store_view -> community service over a Fake transport, with the
//! resolved ClientSettings feeding CircleServiceConfig. No live writes.
use cyclecare_core::auth_link::AuthPrefsState;
use cyclecare_core::auth_session::{session_store_view, resolve_valid_access_token};
use cyclecare_core::community_guides::CircleStatus;
use cyclecare_core::community_service::*;
use cyclecare_core::community_transport::TransportRequest;
use cyclecare_core::supabase_config::*;

struct Fake { script: std::collections::VecDeque<HttpOutcome>, captured: Vec<(String, String, String, Option<String>)> }
impl Fake { fn new(v: Vec<HttpOutcome>) -> Self { Self { script: v.into(), captured: vec![] } } }
impl HttpTransport for Fake {
    fn execute(&mut self, full_url: &str, req: &TransportRequest, bearer: &str) -> HttpOutcome {
        self.captured.push((full_url.into(), bearer.into(), req.method.into(), req.body.clone()));
        self.script.pop_front().unwrap_or(HttpOutcome::OtherError)
    }
}

/// The config seam: a resolved ClientSettings feeds the service config.
fn config_from(settings: &ClientSettings) -> CircleServiceConfig {
    CircleServiceConfig {
        rest_url: settings.rest_url(),
        functions_url: settings.url.clone(),
        anon_key: settings.api_key.clone(),
        configured: settings.is_configured(),
    }
}

fn owner_prefs() -> AuthPrefsState {
    let mut p = AuthPrefsState::fresh();
    p.is_secure_storage_available = true;
    p.user_id = Some("owner-uid".into());
    p.access_token = Some("stored-owner-token".into());
    p.session_expires_at_seconds = 4_000_000_000; // far future: no refresh
    p.account_mode = Some("AUTHENTICATED".into());
    p
}

const NOW: i64 = 1_700_000_000;
fn no_refresh() -> impl Fn(&str) -> Result<cyclecare_core::session_token::RefreshResponse, ()> { |_| Err(()) }
const TOPICS_OK: &str = r#"[{"id":"t1","title":"Prenatal care","description":"d"}]"#;

#[test]
fn stored_owner_session_flows_to_http_bearer() {
    let settings = resolve_client_settings(Some("https://abc.supabase.co"), Some("sb_publishable_12345678901234567890"));
    assert!(settings.is_configured());
    let cfg = config_from(&settings);
    let prefs = owner_prefs();
    let mut store = session_store_view(&prefs);
    assert_eq!(store.user_id(), Some("owner-uid"));

    let mut f = Fake::new(vec![HttpOutcome::Response { status: 200, body: TOPICS_OK.as_bytes().to_vec() }]);
    let (topics, status) = fetch_topics_with_status(&mut store, NOW, &no_refresh(), &cfg, &mut f);
    assert_eq!(status, CircleStatus::Ok);
    assert_eq!(topics.len(), 1);
    let (url, bearer, method, _) = &f.captured[0];
    assert_eq!(bearer, "stored-owner-token", "stored owner token is the bearer");
    assert_eq!(method, "GET");
    assert_eq!(url, &format!("{}/chat_topics?select=id,title,description&is_active=eq.true&order=created_at.asc&limit=20", settings.rest_url()));
}

#[test]
fn config_gate_absence_on_fetch_is_production_parity() {
    // CommunityGuidesService.kt: fetchTopicsWithStatus (line 82) has NO
    // isConfigured gate - only the pulse does (line 256, verbatim cached
    // source). The disabled seam still attempts the fetch with the stored
    // owner bearer; the pulse swallows to false without any HTTP call.
    let settings = resolve_client_settings(Some("your_project_ref"), Some("sb_publishable_12345678901234567890"));
    assert!(!settings.is_configured());
    let cfg = config_from(&settings);
    assert!(!cfg.configured);
    let prefs = owner_prefs();
    let mut store = session_store_view(&prefs);
    let mut f = Fake::new(vec![HttpOutcome::Response { status: 200, body: TOPICS_OK.as_bytes().to_vec() }]);
    let (topics, status) = fetch_topics_with_status(&mut store, NOW, &no_refresh(), &cfg, &mut f);
    assert_eq!(status, CircleStatus::Ok, "fetch has no configured gate in production either (parity)");
    assert_eq!(topics.len(), 1);
    assert_eq!(f.captured.len(), 1);
    assert_eq!(f.captured[0].1, "stored-owner-token");

    // Pulse IS gated (CommunityGuidesService.kt:256): no HTTP, false.
    let before = f.captured.len();
    let ok = community_pulse(Some("stored-owner-token"), &cfg, &mut f);
    assert!(!ok);
    assert_eq!(f.captured.len(), before, "gated pulse must not call HTTP");
}

#[test]
fn insecure_storage_fail_closed_for_owner_session() {
    let cfg = config_from(&resolve_client_settings(None, None)); // production-default public pair
    let mut prefs = owner_prefs();
    prefs.is_secure_storage_available = false;
    let mut store = session_store_view(&prefs);
    let mut f = Fake::new(vec![]);
    let (_, status) = fetch_topics_with_status(&mut store, NOW, &no_refresh(), &cfg, &mut f);
    assert_eq!(status, CircleStatus::SignInUnconfirmed, "insecure storage -> reauth required -> sign-in unconfirmed");
    assert!(f.captured.is_empty());
}

#[test]
fn token_resolution_commits_back_into_prefs_view() {
    let mut prefs = owner_prefs();
    let tok = resolve_valid_access_token(&mut prefs, NOW, &no_refresh());
    assert_eq!(tok.as_deref(), Some("stored-owner-token"));
    let again = session_store_view(&prefs);
    assert_eq!(again.access_token(), Some("stored-owner-token"));
    assert_eq!(again.session_generation(), prefs.session_generation);
}

// ---- Owner-binding rejection tests (main 01:42 review) ----
// The fast path does NOT re-check the stored token's sub (source parity:
// SupabaseSessionManager.kt:74-77 returns the stored token on expiry +
// withCurrentSession(owner, session) alone). JWT owner binding is validated
// on the REFRESH path (lines 94-95: user.id == owner && validTokenForOwner).
// These tests lock that boundary and the concurrent-session guards.

/// b64url (no padding) of a JSON payload; pieces join into a structurally
/// valid JWT for the synthetic refresh body.
fn jwt(sub: &str, exp: i64) -> String {
    // AOSP-decoder-compatible base64url, produced via the core's own
    // encoder-free table (test-local, mirrors session_token corpus style).
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let enc = |bytes: &[u8]| -> String {
        let mut out = String::new();
        for chunk in bytes.chunks(3) {
            let b = [chunk[0], *chunk.get(1).unwrap_or(&0), *chunk.get(2).unwrap_or(&0)];
            let n = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
            out.push(T[(n >> 18) as usize & 63] as char);
            out.push(T[(n >> 12) as usize & 63] as char);
            if chunk.len() > 1 { out.push(T[(n >> 6) as usize & 63] as char); }
            if chunk.len() > 2 { out.push(T[n as usize & 63] as char); }
        }
        out
    };
    format!("{}.{}.c2ln", enc(br#"{"alg":"HS256"}"#), enc(format!(r#"{{"sub":"{sub}","exp":{exp}}}"#).as_bytes()))
}

fn expired_owner_prefs() -> AuthPrefsState {
    let mut p = owner_prefs();
    p.session_expires_at_seconds = NOW - 100; // forces the refresh path
    p.refresh_token = Some("stored-refresh".into());
    p
}

#[test]
fn mismatched_owner_refresh_user_id_rejected() {
    // Refresh body with user.id = a DIFFERENT user: ReauthenticationRequired,
    // nothing committed, no bearer ever reaches HTTP.
    let foreign = jwt("owner-uid", NOW + 3600); // token sub matches, user block does not
    let body = format!(r#"{{"access_token":"{foreign}","refresh_token":"rotated","expires_at":1700003600,"user":{{"id":"other-uid"}}}}"#);
    let refresh = move |_: &str| Ok(cyclecare_core::session_token::RefreshResponse { code: 200, body: Some(body.clone()) });
    let mut prefs = expired_owner_prefs();
    let res = resolve_valid_access_token(&mut prefs, NOW, &refresh);
    assert_eq!(res, None, "foreign user.id must reject");
    assert_eq!(prefs.access_token.as_deref(), Some("stored-owner-token"), "no commit of foreign session");
    assert_eq!(prefs.refresh_token.as_deref(), Some("stored-refresh"));

    let cfg = config_from(&resolve_client_settings(None, None));
    let mut store = session_store_view(&prefs);
    let mut f = Fake::new(vec![]);
    let (_, status) = fetch_topics_with_status(&mut store, NOW, &no_refresh(), &cfg, &mut f);
    // Store still holds the expired session; a failing refresh maps to
    // Unavailable (not Ok) and, critically, no bearer reaches HTTP.
    assert_ne!(status, CircleStatus::Ok);
    assert!(f.captured.is_empty());
}

#[test]
fn foreign_sub_renewed_jwt_rejected() {
    // user.id matches but the renewed JWT's sub claim belongs to someone else.
    let foreign = jwt("other-uid", NOW + 3600);
    let body = format!(r#"{{"access_token":"{foreign}","refresh_token":"rotated","expires_at":1700003600,"user":{{"id":"owner-uid"}}}}"#);
    let refresh = move |_: &str| Ok(cyclecare_core::session_token::RefreshResponse { code: 200, body: Some(body.clone()) });
    let mut prefs = expired_owner_prefs();
    assert_eq!(resolve_valid_access_token(&mut prefs, NOW, &refresh), None);
    assert_eq!(prefs.access_token.as_deref(), Some("stored-owner-token"));
}

#[test]
fn well_bound_refresh_commits() {
    // Control: matching user.id AND sub -> renewed token commits.
    let good = jwt("owner-uid", NOW + 3600);
    let body = format!(r#"{{"access_token":"{good}","refresh_token":"rotated","expires_at":1700003600,"user":{{"id":"owner-uid"}}}}"#);
    let refresh = move |_: &str| Ok(cyclecare_core::session_token::RefreshResponse { code: 200, body: Some(body.clone()) });
    let mut prefs = expired_owner_prefs();
    let tok = resolve_valid_access_token(&mut prefs, NOW, &refresh);
    assert_eq!(tok.as_deref(), Some(good.as_str()));
    assert_eq!(prefs.refresh_token.as_deref(), Some("rotated"));
}

#[test]
fn concurrent_session_change_rejects_store_guard() {
    // The seam's consistency primitive: with_current_session re-reads
    // user_id AND generation; a concurrent sign-in (set_user_id bumps the
    // generation) invalidates any previously captured (owner, session).
    let prefs = owner_prefs();
    let mut store = session_store_view(&prefs);
    let captured_owner = "owner-uid";
    let captured_gen = store.session_generation();
    // Concurrent change: another sign-in replaces the owner mid-flight.
    store.set_user_id(Some("other-uid"));
    assert_eq!(store.with_current_session(captured_owner, captured_gen, |_| true), None,
        "stale owner must be rejected after concurrent change");
    assert_eq!(store.with_current_session("other-uid", captured_gen, |_| true), None,
        "stale generation must be rejected even for the new owner");
    let new_gen = store.session_generation();
    assert_ne!(new_gen, captured_gen, "sign-in bumps the generation");
    assert_eq!(store.with_current_session("other-uid", new_gen, |_| true), Some(true));
}

#[test]
fn fast_path_returns_stored_token_without_sub_recheck_is_parity() {
    // Documents (not approves) the source boundary: an opaque stored token
    // passes the fast path on expiry + session consistency alone
    // (SupabaseSessionManager.kt:74-77). JWT sub binding is enforced on
    // refresh only. Recorded here so the seam label stays precise.
    let prefs = owner_prefs(); // opaque "stored-owner-token", far-future expiry
    let mut store = session_store_view(&prefs);
    match cyclecare_core::session_token::resolve_access_token(&mut store, NOW, &no_refresh()) {
        cyclecare_core::session_token::AccessTokenResolution::Valid(t) => assert_eq!(t, "stored-owner-token"),
        other => panic!("fast path must return the stored token (parity), got {other:?}"),
    }
}

// ---- PROPOSED hardened seam (main 01:44 review) - unwired ----
// Fake transport must receive ZERO requests on invalid origin or
// mismatched owner.

use cyclecare_core::supabase_config::{validated_origin, EndpointOrigin};

fn bound_owner_prefs() -> AuthPrefsState {
    // Fast-path stored token that IS structurally bound to the owner.
    let mut p = owner_prefs();
    p.access_token = Some(jwt("owner-uid", 4_000_000_000));
    p.session_expires_at_seconds = 4_000_000_000;
    p
}

fn remote_settings() -> ClientSettings {
    resolve_client_settings(Some("https://abc.supabase.co"), Some("sb_publishable_12345678901234567890"))
}

#[test]
fn hardened_refuses_relative_or_invalid_origin_with_zero_http() {
    let prefs = bound_owner_prefs();
    let mut store = session_store_view(&prefs);
    let mut f = Fake::new(vec![HttpOutcome::Response { status: 200, body: TOPICS_OK.as_bytes().to_vec() }]);
    // Disabled seam (relative rest_url in the parity chain).
    let disabled = resolve_client_settings(Some("your_project_ref"), Some("sb_publishable_12345678901234567890"));
    let (_, st) = hardened_fetch_topics_with_status(&mut store, NOW, &no_refresh(), &disabled, &mut f);
    assert_eq!(st, CircleStatus::Unavailable);
    assert!(f.captured.is_empty(), "zero requests on invalid origin");
    // Arbitrary localhost variant is NOT a source-approved build endpoint.
    let variant = resolve_client_settings(Some("http://localhost:54322"), Some("sb_publishable_12345678901234567890"));
    let (_, st) = hardened_fetch_topics_with_status(&mut store, NOW, &no_refresh(), &variant, &mut f);
    assert_eq!(st, CircleStatus::Unavailable);
    assert!(f.captured.is_empty());
    // Evil host.
    let evil = resolve_client_settings(Some("https://evil.com"), Some("sb_publishable_12345678901234567890"));
    let (_, st) = hardened_fetch_topics_with_status(&mut store, NOW, &no_refresh(), &evil, &mut f);
    assert_eq!(st, CircleStatus::Unavailable);
    assert!(f.captured.is_empty());
}

#[test]
fn hardened_rejects_mismatched_owner_token_with_zero_http() {
    // Stored fast-path token structurally bound to a DIFFERENT subject.
    let mut prefs = owner_prefs();
    prefs.access_token = Some(jwt("other-uid", 4_000_000_000));
    prefs.session_expires_at_seconds = 4_000_000_000;
    let mut store = session_store_view(&prefs);
    let mut f = Fake::new(vec![HttpOutcome::Response { status: 200, body: TOPICS_OK.as_bytes().to_vec() }]);
    // Parity chain WOULD send it (recorded gap):
    let (_, parity_st) = fetch_topics_with_status(&mut store, NOW, &no_refresh(), &config_from(&remote_settings()), &mut f);
    assert_eq!(parity_st, CircleStatus::Ok, "parity fast path leaks the foreign token (recorded)");
    assert_eq!(f.captured.len(), 1);
    // Hardened seam refuses, zero additional requests:
    let mut store = session_store_view(&prefs);
    let (_, st) = hardened_fetch_topics_with_status(&mut store, NOW, &no_refresh(), &remote_settings(), &mut f);
    assert_eq!(st, CircleStatus::SignInUnconfirmed);
    assert_eq!(f.captured.len(), 1, "hardened seam added zero requests");
}

#[test]
fn hardened_refuses_insecure_or_ownerless_store_with_zero_http() {
    let mut prefs = bound_owner_prefs();
    prefs.is_secure_storage_available = false;
    let mut store = session_store_view(&prefs);
    let mut f = Fake::new(vec![]);
    let (_, st) = hardened_fetch_topics_with_status(&mut store, NOW, &no_refresh(), &remote_settings(), &mut f);
    assert_eq!(st, CircleStatus::SignInUnconfirmed);
    assert!(f.captured.is_empty());
    let mut prefs = bound_owner_prefs();
    prefs.user_id = None; // corrupt/ownerless
    let mut store = session_store_view(&prefs);
    let (_, st) = hardened_fetch_topics_with_status(&mut store, NOW, &no_refresh(), &remote_settings(), &mut f);
    assert_eq!(st, CircleStatus::SignInUnconfirmed);
    assert!(f.captured.is_empty());
}

#[test]
fn hardened_bound_owner_flows_to_validated_absolute_url() {
    let prefs = bound_owner_prefs();
    let mut store = session_store_view(&prefs);
    let mut f = Fake::new(vec![HttpOutcome::Response { status: 200, body: TOPICS_OK.as_bytes().to_vec() }]);
    let (topics, st) = hardened_fetch_topics_with_status(&mut store, NOW, &no_refresh(), &remote_settings(), &mut f);
    assert_eq!(st, CircleStatus::Ok);
    assert_eq!(topics.len(), 1);
    assert_eq!(f.captured.len(), 1);
    let (url, bearer, _, _) = &f.captured[0];
    assert_eq!(url, "https://abc.supabase.co/rest/v1/chat_topics?select=id,title,description&is_active=eq.true&order=created_at.asc&limit=20");
    assert_eq!(bearer, &jwt("owner-uid", 4_000_000_000));
    // Exact local build endpoint is accepted; URL is absolute and matched.
    let local = resolve_client_settings(Some("http://127.0.0.1:54321"), Some("sb_publishable_12345678901234567890"));
    let mut f2 = Fake::new(vec![HttpOutcome::Response { status: 200, body: TOPICS_OK.as_bytes().to_vec() }]);
    let mut store = session_store_view(&prefs);
    let (_, st) = hardened_fetch_topics_with_status(&mut store, NOW, &no_refresh(), &local, &mut f2);
    assert_eq!(st, CircleStatus::Ok);
    assert!(f2.captured[0].0.starts_with("http://127.0.0.1:54321/rest/v1/"));
}

#[test]
fn origin_matching_is_structural_not_prefix() {
    let origin = validated_origin(&remote_settings()).unwrap();
    assert!(origin.allows("https://abc.supabase.co/rest/v1/chat_topics"));
    // userinfo injection
    assert!(!origin.allows("https://abc.supabase.co@evil.com/rest/v1"));
    // alternate port
    assert!(!origin.allows("https://abc.supabase.co:8443/rest/v1"));
    // scheme downgrade
    assert!(!origin.allows("http://abc.supabase.co/rest/v1"));
    // case-normalized host still matches
    assert!(origin.allows("https://ABC.supabase.co/rest/v1"));
    // empty authority / relative / garbage
    assert!(!origin.allows("https:///rest/v1"));
    assert!(!origin.allows("/rest/v1/chat_topics"));
    assert!(!origin.allows("not a url"));
    // local build exact
    let local = resolve_client_settings(Some("http://localhost:54321"), Some("sb_publishable_12345678901234567890"));
    let lorigin = validated_origin(&local).unwrap();
    assert!(matches!(lorigin, EndpointOrigin::LocalBuild("http://localhost:54321")));
    assert!(lorigin.allows("http://localhost:54321/rest/v1/x"));
    assert!(!lorigin.allows("http://localhost:54322/rest/v1/x"));
    assert!(!lorigin.allows("http://localhost.evil.com:54321/x"));
}

// ---- Shared hardened boundary (main 01:48 review) - unwired ----
// Drift injection: HardenedFake mutates the store INSIDE execute,
// simulating an async transport spanning a sign-out / account switch.

struct HardenedFake {
    script: std::collections::VecDeque<HttpOutcome>,
    captured: Vec<(String, String)>,
    /// Mid-flight session mutation, applied during the NEXT execute.
    drift: Option<Box<dyn FnOnce(&mut cyclecare_core::lifecycle::SessionStore)>>,
}
impl HardenedFake {
    fn new(v: Vec<HttpOutcome>) -> Self { Self { script: v.into(), captured: vec![], drift: None } }
}
impl HardenedTransport for HardenedFake {
    fn execute(&mut self, store: &mut cyclecare_core::lifecycle::SessionStore, full_url: &str, req: &TransportRequest, bearer: &str) -> HttpOutcome {
        self.captured.push((full_url.into(), bearer.into()));
        if let Some(d) = self.drift.take() { d(store); }
        let _ = req;
        self.script.pop_front().unwrap_or(HttpOutcome::OtherError)
    }
}

const MESSAGES_OK: &str = r#"[{"id":"m1","user_id":"owner-uid","content":"hello","created_at":"2026-11-03T01:00:00Z"}]"#;
const POST_OK: &str = r#"[{"id":"srv1","created_at":"2026-11-03T01:00:00Z"}]"#;

fn signout_drift() -> Box<dyn FnOnce(&mut cyclecare_core::lifecycle::SessionStore)> {
    Box::new(|s| { s.set_user_id(None); })
}
fn switch_drift() -> Box<dyn FnOnce(&mut cyclecare_core::lifecycle::SessionStore)> {
    Box::new(|s| { s.set_user_id(Some("intruder-uid")); })
}

#[test]
fn hardened_boundary_all_ops_zero_http_on_invalid_origin() {
    let settings = resolve_client_settings(Some("your_project_ref"), Some("sb_publishable_12345678901234567890"));
    let prefs = bound_owner_prefs();
    let mut f = HardenedFake::new(vec![]);
    let mut store = session_store_view(&prefs);
    assert_eq!(hardened_ensure_membership(&mut store, NOW, &no_refresh(), &settings, &mut f, "t1"), cyclecare_core::community_service::HardenedWriteOutcome::FailedBeforeSend);
    let mut store = session_store_view(&prefs);
    assert!(hardened_fetch_messages(&mut store, NOW, &no_refresh(), &settings, &mut f, "t1").is_empty());
    let mut store = session_store_view(&prefs);
    assert!(matches!(hardened_post_message(&mut store, NOW, NOW*1000, &no_refresh(), &settings, &mut f, "t1", "hi").outcome, cyclecare_core::community_transport::PostOutcome::Failed));
    let mut store = session_store_view(&prefs);
    assert_eq!(hardened_flag_message(&mut store, NOW, &no_refresh(), &settings, &mut f, "m1"), cyclecare_core::community_service::HardenedWriteOutcome::FailedBeforeSend);
    let mut store = session_store_view(&prefs);
    assert!(!hardened_community_pulse(Some(&mut store), NOW, &no_refresh(), &settings, &mut f));
    assert!(!hardened_community_pulse(None, NOW, &no_refresh(), &settings, &mut f));
    assert!(f.captured.is_empty(), "zero requests across all hardened ops on invalid origin");
}

#[test]
fn signout_mid_read_discards_result() {
    let prefs = bound_owner_prefs();
    let mut f = HardenedFake::new(vec![HttpOutcome::Response { status: 200, body: MESSAGES_OK.as_bytes().to_vec() }]);
    f.drift = Some(signout_drift());
    let mut store = session_store_view(&prefs);
    let msgs = hardened_fetch_messages(&mut store, NOW, &no_refresh(), &remote_settings(), &mut f, "t1");
    assert!(msgs.is_empty(), "result discarded when the session changed mid-flight");
    assert_eq!(f.captured.len(), 1, "request did leave (parity with async span)");
}

#[test]
fn signout_mid_post_is_unconfirmed_never_confirmed() {
    let prefs = bound_owner_prefs();
    let mut f = HardenedFake::new(vec![HttpOutcome::Response { status: 201, body: POST_OK.as_bytes().to_vec() }]);
    f.drift = Some(signout_drift());
    let mut store = session_store_view(&prefs);
    let res = hardened_post_message(&mut store, NOW, NOW*1000, &no_refresh(), &remote_settings(), &mut f, "t1", "hi there");
    assert!(matches!(res.outcome, cyclecare_core::community_transport::PostOutcome::SoftEchoUnconfirmed),
        "post-dispatch drift -> unconfirmed, never confirmed");
    assert!(res.message.is_none(), "no echo attributed after drift");
}

#[test]
fn account_switch_mid_membership_and_flag_not_applied() {
    let prefs = bound_owner_prefs();
    let mut f = HardenedFake::new(vec![HttpOutcome::Response { status: 201, body: vec![] }]);
    f.drift = Some(switch_drift());
    let mut store = session_store_view(&prefs);
    assert_eq!(hardened_ensure_membership(&mut store, NOW, &no_refresh(), &remote_settings(), &mut f, "t1"),
        cyclecare_core::community_service::HardenedWriteOutcome::UnconfirmedAfterSend,
        "membership after account switch: may have landed, never confirmed, never failed-before-send");

    let prefs = bound_owner_prefs();
    let mut f = HardenedFake::new(vec![HttpOutcome::Response { status: 200, body: b"true".to_vec() }]);
    f.drift = Some(switch_drift());
    let mut store = session_store_view(&prefs);
    assert_eq!(hardened_flag_message(&mut store, NOW, &no_refresh(), &remote_settings(), &mut f, "m1"),
        cyclecare_core::community_service::HardenedWriteOutcome::UnconfirmedAfterSend);
}

#[test]
fn stable_session_applies_results_across_ops() {
    let settings = remote_settings();
    let prefs = bound_owner_prefs();
    let mut f = HardenedFake::new(vec![
        HttpOutcome::Response { status: 200, body: MESSAGES_OK.as_bytes().to_vec() },
        HttpOutcome::Response { status: 201, body: vec![] },
        HttpOutcome::Response { status: 200, body: b" true ".to_vec() },
        HttpOutcome::Response { status: 200, body: vec![] },
    ]);
    let mut store = session_store_view(&prefs);
    let msgs = hardened_fetch_messages(&mut store, NOW, &no_refresh(), &settings, &mut f, "t1");
    assert_eq!(msgs.len(), 1);
    assert!(msgs[0].is_self, "owner-authored message via ctx.owner");
    assert_eq!(hardened_ensure_membership(&mut store, NOW, &no_refresh(), &settings, &mut f, "t1"),
        cyclecare_core::community_service::HardenedWriteOutcome::ConfirmedApplied);
    assert_eq!(hardened_flag_message(&mut store, NOW, &no_refresh(), &settings, &mut f, "m1"),
        cyclecare_core::community_service::HardenedWriteOutcome::ConfirmedApplied);
    assert!(hardened_community_pulse(Some(&mut store), NOW, &no_refresh(), &settings, &mut f));
    assert_eq!(f.captured.len(), 4);
    // Bearer on every call is the bound owner token, not the anon key.
    for (_, bearer) in &f.captured {
        assert_eq!(bearer, &jwt("owner-uid", 4_000_000_000));
    }
    // Anonymous pulse: origin-validated, anon-key bearer.
    let mut f2 = HardenedFake::new(vec![HttpOutcome::Response { status: 200, body: vec![] }]);
    assert!(hardened_community_pulse(None, NOW, &no_refresh(), &settings, &mut f2));
    assert_eq!(f2.captured[0].1, "sb_publishable_12345678901234567890");
}

#[test]
fn refresh_rotation_mid_begin_does_not_invalidate_generation() {
    // Expired session -> refresh inside hardened_begin; the refresh commit
    // rotates tokens but must NOT bump the session generation (sign-in
    // does). The pre/post-dispatch checks still pass.
    let good = jwt("owner-uid", NOW + 3600);
    let body = format!(r#"{{"access_token":"{good}","refresh_token":"rotated","expires_at":{},"user":{{"id":"owner-uid"}}}}"#, NOW + 3600);
    let refresh = move |_: &str| Ok(cyclecare_core::session_token::RefreshResponse { code: 200, body: Some(body.clone()) });
    let mut prefs = owner_prefs();
    prefs.access_token = Some(jwt("owner-uid", NOW - 10));
    prefs.session_expires_at_seconds = NOW - 10;
    prefs.refresh_token = Some("stored-refresh".into());
    let mut store = session_store_view(&prefs);
    let gen_before = store.session_generation();
    let mut f = HardenedFake::new(vec![HttpOutcome::Response { status: 200, body: MESSAGES_OK.as_bytes().to_vec() }]);
    let msgs = hardened_fetch_messages(&mut store, NOW, &refresh, &remote_settings(), &mut f, "t1");
    assert_eq!(msgs.len(), 1, "valid refresh keeps the op alive");
    assert_eq!(store.session_generation(), gen_before, "refresh rotation preserves account generation");
    assert_eq!(f.captured[0].1, good, "bearer is the renewed token");
}

#[test]
fn transport_error_after_dispatch_is_unconfirmed_never_failed() {
    // Membership/flag: IoError after dispatch -> UnconfirmedAfterSend.
    let settings = remote_settings();
    let prefs = bound_owner_prefs();
    let mut f = HardenedFake::new(vec![HttpOutcome::IoError]);
    let mut store = session_store_view(&prefs);
    assert_eq!(hardened_ensure_membership(&mut store, NOW, &no_refresh(), &settings, &mut f, "t1"),
        cyclecare_core::community_service::HardenedWriteOutcome::UnconfirmedAfterSend);
    let mut store = session_store_view(&prefs);
    assert_eq!(hardened_flag_message(&mut store, NOW, &no_refresh(), &settings, &mut f, "m1"),
        cyclecare_core::community_service::HardenedWriteOutcome::UnconfirmedAfterSend);
    // Post: IoError after dispatch -> SoftEchoUnconfirmed with NO echo
    // message; the UI must render an unconfirmed post without a bubble.
    let mut f = HardenedFake::new(vec![HttpOutcome::IoError]);
    let mut store = session_store_view(&prefs);
    let res = hardened_post_message(&mut store, NOW, NOW*1000, &no_refresh(), &settings, &mut f, "t1", "hi there");
    assert!(matches!(res.outcome, cyclecare_core::community_transport::PostOutcome::SoftEchoUnconfirmed));
    assert!(res.message.is_none());
    // 5xx after send: may have landed -> UnconfirmedAfterSend (main 01:57).
    let mut f = HardenedFake::new(vec![HttpOutcome::Response { status: 500, body: vec![] }]);
    let mut store = session_store_view(&prefs);
    assert_eq!(hardened_ensure_membership(&mut store, NOW, &no_refresh(), &settings, &mut f, "t1"),
        cyclecare_core::community_service::HardenedWriteOutcome::UnconfirmedAfterSend);
    // 4xx is a definitive rejection.
    let mut f = HardenedFake::new(vec![HttpOutcome::Response { status: 404, body: vec![] }]);
    let mut store = session_store_view(&prefs);
    assert_eq!(hardened_ensure_membership(&mut store, NOW, &no_refresh(), &settings, &mut f, "t1"),
        cyclecare_core::community_service::HardenedWriteOutcome::ConfirmedRejected);
    // Flag: 2xx with UNREADABLE body -> UnconfirmedAfterSend; readable
    // non-"true" body -> ConfirmedRejected; 5xx -> UnconfirmedAfterSend.
    // Oversize (>96KiB) body on 2xx: boundedBody -> null, answer unknowable.
    // (Invalid UTF-8 decodes to replacement chars per source - NOT None.)
    let mut f = HardenedFake::new(vec![HttpOutcome::Response { status: 200, body: vec![b't'; 97 * 1024] }]);
    let mut store = session_store_view(&prefs);
    assert_eq!(hardened_flag_message(&mut store, NOW, &no_refresh(), &settings, &mut f, "m1"),
        cyclecare_core::community_service::HardenedWriteOutcome::UnconfirmedAfterSend);
    let mut f = HardenedFake::new(vec![HttpOutcome::Response { status: 200, body: b"false".to_vec() }]);
    let mut store = session_store_view(&prefs);
    assert_eq!(hardened_flag_message(&mut store, NOW, &no_refresh(), &settings, &mut f, "m1"),
        cyclecare_core::community_service::HardenedWriteOutcome::ConfirmedRejected);
    let mut f = HardenedFake::new(vec![HttpOutcome::Response { status: 502, body: b"true".to_vec() }]);
    let mut store = session_store_view(&prefs);
    assert_eq!(hardened_flag_message(&mut store, NOW, &no_refresh(), &settings, &mut f, "m1"),
        cyclecare_core::community_service::HardenedWriteOutcome::UnconfirmedAfterSend);
}

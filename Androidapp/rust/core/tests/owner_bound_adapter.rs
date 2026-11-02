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

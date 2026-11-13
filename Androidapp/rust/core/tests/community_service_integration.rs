//! Injected-transport integration tests for the community service adapter.
//! Synthetic responses only; no live writes. Exercises the real composition:
//! session token resolution -> request shape -> response interpretation.
use cyclecare_core::community_service::*;
use cyclecare_core::community_guides::CircleStatus;
use cyclecare_core::community_transport::PostOutcome;
use cyclecare_core::lifecycle::SessionStore;
use cyclecare_core::session_token::RefreshResponse;

struct Fake {
    script: std::collections::VecDeque<HttpOutcome>,
    captured: Vec<(String, String, String, Option<String>)>,
}
impl Fake {
    fn new(outcomes: Vec<HttpOutcome>) -> Self {
        Self { script: outcomes.into(), captured: vec![] }
    }
}
impl HttpTransport for Fake {
    fn execute(&mut self, full_url: &str, req: &cyclecare_core::community_transport::TransportRequest, bearer: &str) -> HttpOutcome {
        self.captured.push((full_url.to_string(), bearer.to_string(), req.method.to_string(), req.body.clone()));
        self.script.pop_front().unwrap_or(HttpOutcome::OtherError)
    }
}

fn config() -> CircleServiceConfig {
    CircleServiceConfig {
        rest_url: "https://syn.test/rest/v1".into(),
        functions_url: "https://syn.test".into(),
        anon_key: "ANON".into(),
        configured: true,
    }
}

/// Secure store with a valid session: stored expiry far future, so resolve
/// short-circuits without refresh.
fn valid_store() -> SessionStore {
    let mut s = SessionStore::new(true);
    s.set_user_id(Some("u1"));
    s.set_access_token(Some("tok"));
    s.set_session_expires_at_seconds(4_000_000_000);
    s
}

const NOW: i64 = 1_700_000_000;

fn no_refresh() -> impl Fn(&str) -> Result<RefreshResponse, ()> {
    |_| Err(())
}

#[test]
fn topics_status_chain_end_to_end() {
    // Missing token (fresh store) -> SIGN_IN_UNCONFIRMED, no HTTP call.
    let mut s = SessionStore::new(true);
    let mut f = Fake::new(vec![]);
    let (_, st) = fetch_topics_with_status(&mut s, NOW, &no_refresh(), &config(), &mut f);
    assert_eq!(st, CircleStatus::SignInUnconfirmed);
    assert!(f.captured.is_empty());

    // Insecure storage -> ReauthenticationRequired -> SIGN_IN_UNCONFIRMED.
    let mut s = valid_store();
    // simulate insecure by constructing a fresh insecure store with creds
    let mut ins = SessionStore::new(false);
    ins.set_user_id(Some("u1"));
    ins.set_access_token(Some("tok"));
    let (_, st) = fetch_topics_with_status(&mut ins, NOW, &no_refresh(), &config(), &mut Fake::new(vec![]));
    assert_eq!(st, CircleStatus::SignInUnconfirmed);

    // IO error -> NETWORK; other error -> UNAVAILABLE.
    let (_, st) = fetch_topics_with_status(&mut s, NOW, &no_refresh(), &config(), &mut Fake::new(vec![HttpOutcome::IoError]));
    assert_eq!(st, CircleStatus::Network);
    let (_, st) = fetch_topics_with_status(&mut s, NOW, &no_refresh(), &config(), &mut Fake::new(vec![HttpOutcome::OtherError]));
    assert_eq!(st, CircleStatus::Unavailable);

    // 401/403 -> SIGN_IN_UNCONFIRMED; 500 -> UNAVAILABLE.
    for code in [401u16, 403] {
        let (_, st) = fetch_topics_with_status(&mut s, NOW, &no_refresh(), &config(),
            &mut Fake::new(vec![HttpOutcome::Response { status: code, body: b"[]".to_vec() }]));
        assert_eq!(st, CircleStatus::SignInUnconfirmed, "{code}");
    }
    let (_, st) = fetch_topics_with_status(&mut s, NOW, &no_refresh(), &config(),
        &mut Fake::new(vec![HttpOutcome::Response { status: 500, body: b"[]".to_vec() }]));
    assert_eq!(st, CircleStatus::Unavailable);

    // Over-limit body -> UNAVAILABLE.
    let big = vec![b'x'; 96 * 1024 + 1];
    let (_, st) = fetch_topics_with_status(&mut s, NOW, &no_refresh(), &config(),
        &mut Fake::new(vec![HttpOutcome::Response { status: 200, body: big }]));
    assert_eq!(st, CircleStatus::Unavailable);

    // Malformed JSON -> UNAVAILABLE; "[]" -> EMPTY; rows -> OK.
    let (_, st) = fetch_topics_with_status(&mut s, NOW, &no_refresh(), &config(),
        &mut Fake::new(vec![HttpOutcome::Response { status: 200, body: b"not json".to_vec() }]));
    assert_eq!(st, CircleStatus::Unavailable);
    let (_, st) = fetch_topics_with_status(&mut s, NOW, &no_refresh(), &config(),
        &mut Fake::new(vec![HttpOutcome::Response { status: 200, body: b"[]".to_vec() }]));
    assert_eq!(st, CircleStatus::Empty);
    let mut f = Fake::new(vec![HttpOutcome::Response { status: 200,
        body: br#"[{"id":"t1","title":"Rest","description":"d"}]"#.to_vec() }]);
    let (topics, st) = fetch_topics_with_status(&mut s, NOW, &no_refresh(), &config(), &mut f);
    assert_eq!(st, CircleStatus::Ok);
    assert_eq!(topics.len(), 1);
    // URL = rest_url + path; bearer is the trimmed resolved token.
    let (url, bearer, method, _) = &f.captured[0];
    assert_eq!(url, "https://syn.test/rest/v1/chat_topics?select=id,title,description&is_active=eq.true&order=created_at.asc&limit=20");
    assert_eq!(bearer, "tok");
    assert_eq!(method, "GET");
}

#[test]
fn membership_and_flag_flows() {
    let mut s = valid_store();
    // No user id -> false, no request.
    let mut anon = SessionStore::new(true);
    anon.set_access_token(Some("tok"));
    anon.set_session_expires_at_seconds(4_000_000_000);
    let mut f = Fake::new(vec![]);
    assert!(!ensure_membership(&mut anon, NOW, &no_refresh(), &config(), &mut f, "t1"));
    assert!(f.captured.is_empty());
    // 409 idempotent success; body shape exact.
    let mut f = Fake::new(vec![HttpOutcome::Response { status: 409, body: vec![] }]);
    assert!(ensure_membership(&mut s, NOW, &no_refresh(), &config(), &mut f, "t1"));
    assert_eq!(f.captured[0].3.as_deref(), Some("{\"user_id\":\"u1\",\"topic_id\":\"t1\"}"));
    // 500 -> false.
    assert!(!ensure_membership(&mut s, NOW, &no_refresh(), &config(),
        &mut Fake::new(vec![HttpOutcome::Response { status: 500, body: vec![] }]), "t1"));
    // flag: body semantics.
    assert!(flag_message(&mut s, NOW, &no_refresh(), &config(),
        &mut Fake::new(vec![HttpOutcome::Response { status: 200, body: b" true ".to_vec() }]), "m1"));
    assert!(!flag_message(&mut s, NOW, &no_refresh(), &config(),
        &mut Fake::new(vec![HttpOutcome::Response { status: 200, body: b"false".to_vec() }]), "m1"));
    assert!(!flag_message(&mut s, NOW, &no_refresh(), &config(),
        &mut Fake::new(vec![HttpOutcome::Response { status: 500, body: b"true".to_vec() }]), "m1"));
}

#[test]
fn fetch_messages_degrades_to_empty() {
    let mut s = valid_store();
    let msgs = fetch_messages(&mut s, NOW, &no_refresh(), &config(),
        &mut Fake::new(vec![HttpOutcome::Response { status: 500, body: vec![] }]), "t1");
    assert!(msgs.is_empty());
    let msgs = fetch_messages(&mut s, NOW, &no_refresh(), &config(),
        &mut Fake::new(vec![HttpOutcome::Response { status: 200, body: b"broken".to_vec() }]), "t1");
    assert!(msgs.is_empty());
    let body = br#"[{"id":"m1","user_id":"u1","content":"hi","created_at":"2026-11-03T00:00:00Z","is_flagged":false}]"#.to_vec();
    let msgs = fetch_messages(&mut s, NOW, &no_refresh(), &config(),
        &mut Fake::new(vec![HttpOutcome::Response { status: 200, body }]), "t1");
    assert_eq!(msgs.len(), 1);
    assert_eq!(msgs[0].channel_id, "t1");
    assert!(msgs[0].timestamp_ms > 0);
    assert!(msgs[0].is_self);
}

#[test]
fn post_message_outcomes() {
    let mut s = valid_store();
    // Blank content: rejected before any request.
    let mut f = Fake::new(vec![]);
    let r = post_message(&mut s, NOW, 1_762_000_000_000, &no_refresh(), &config(), &mut f, "t1", "   ");
    assert_eq!(r.outcome, PostOutcome::Failed);
    assert!(f.captured.is_empty());
    // 501 UTF-16 units: rejected.
    let long = "x".repeat(501);
    let r = post_message(&mut s, NOW, 1_762_000_000_000, &no_refresh(), &config(), &mut Fake::new(vec![]), "t1", &long);
    assert_eq!(r.outcome, PostOutcome::Failed);
    // Confirmed: representation row carries id + created_at.
    let mut f = Fake::new(vec![HttpOutcome::Response { status: 201,
        body: br#"[{"id":"srv-1","created_at":"2026-11-03T00:00:00Z"}]"#.to_vec() }]);
    let r = post_message(&mut s, NOW, 1_762_000_000_000, &no_refresh(), &config(), &mut f, "t1", " hello ");
    match &r.outcome {
        PostOutcome::Confirmed { remote_id, timestamp_ms } => {
            assert_eq!(remote_id.as_deref(), Some("srv-1"));
            assert!(*timestamp_ms > 0);
        }
        other => panic!("expected Confirmed, got {other:?}"),
    }
    // Request body uses the JVM-locked alias for "u1" and the trimmed content.
    let body = f.captured[0].3.clone().unwrap();
    assert!(body.contains("\"sender_alias\":\"Golden Star\""), "{body}");
    assert!(body.contains("\"content\":\"hello\""), "{body}");
    let m = r.message.unwrap();
    assert!(m.is_self && m.remote_id.as_deref() == Some("srv-1") && m.channel_id == "t1");
    // Soft echo: 2xx with unparseable body -> UNCONFIRMED, echo carries host now_ms.
    let r = post_message(&mut s, NOW, 1_762_000_000_000, &no_refresh(), &config(),
        &mut Fake::new(vec![HttpOutcome::Response { status: 201, body: b"oops".to_vec() }]), "t1", "hi");
    assert_eq!(r.outcome, PostOutcome::SoftEchoUnconfirmed);
    let m = r.message.unwrap();
    assert_eq!(m.timestamp_ms, 1_762_000_000_000);
    assert!(m.remote_id.is_none() && m.is_self);
    // Non-2xx: Failed, no message.
    let r = post_message(&mut s, NOW, 1_762_000_000_000, &no_refresh(), &config(),
        &mut Fake::new(vec![HttpOutcome::Response { status: 500, body: vec![] }]), "t1", "hi");
    assert_eq!(r.outcome, PostOutcome::Failed);
    assert!(r.message.is_none());
}

#[test]
fn pulse_gate_and_bearer() {
    // Not configured: false, no request.
    let mut c = config();
    c.configured = false;
    let mut f = Fake::new(vec![]);
    assert!(!community_pulse(Some("tok"), &c, &mut f));
    assert!(f.captured.is_empty());
    // Bearer selection: blank token falls back to anon key.
    let c = config();
    let mut f = Fake::new(vec![HttpOutcome::Response { status: 200, body: vec![] }]);
    assert!(community_pulse(Some("  "), &c, &mut f));
    assert_eq!(f.captured[0].1, "ANON");
    assert_eq!(f.captured[0].0, "https://syn.test/functions/v1/community-pulse");
    // Non-2xx swallowed to false.
    assert!(!community_pulse(Some("tok"), &c,
        &mut Fake::new(vec![HttpOutcome::Response { status: 500, body: vec![] }])));
    assert!(!community_pulse(Some("tok"), &c, &mut Fake::new(vec![HttpOutcome::IoError])));
}

#[test]
fn expired_token_refresh_failures_map_status() {
    let mut s = SessionStore::new(true);
    s.set_user_id(Some("u1"));
    s.set_access_token(Some("old"));
    s.set_refresh_token(Some("r1"));
    s.set_session_expires_at_seconds(100); // expired
    // Refresh 401 -> ReauthenticationRequired -> SIGN_IN_UNCONFIRMED.
    let refresh_401 = |_: &str| Ok(RefreshResponse { code: 401, body: None });
    let (_, st) = fetch_topics_with_status(&mut s, NOW, &refresh_401, &config(), &mut Fake::new(vec![]));
    assert_eq!(st, CircleStatus::SignInUnconfirmed);
    // Refresh transport error -> TemporarilyUnavailable -> UNAVAILABLE.
    let (_, st) = fetch_topics_with_status(&mut s, NOW, &no_refresh(), &config(), &mut Fake::new(vec![]));
    assert_eq!(st, CircleStatus::Unavailable);
}

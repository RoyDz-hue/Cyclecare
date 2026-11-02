//! JVM-executed transport decisions (TransportProbe.kt, 2026-11-02).
use cyclecare_core::community_transport::*;

#[test]
fn current_user_id_table() {
    assert_eq!(current_user_id(None), None);
    assert_eq!(current_user_id(Some("")), None);
    assert_eq!(current_user_id(Some("  ")), None);
    assert_eq!(current_user_id(Some(" u1 ")).as_deref(), Some("u1"));
    assert_eq!(current_user_id(Some("u1")).as_deref(), Some("u1"));
}

#[test]
fn pulse_bearer_table() {
    assert_eq!(pulse_bearer(None, "ANON"), "ANON");
    assert_eq!(pulse_bearer(Some("  "), "ANON"), "ANON");
    assert_eq!(pulse_bearer(Some(" tok "), "ANON"), "tok");
    assert_eq!(pulse_request().body.as_deref(), Some("{}"));
    assert_eq!(pulse_request().path, "/functions/v1/community-pulse");
}

#[test]
fn membership_codes() {
    for ok in [200u16, 201, 204, 409] {
        assert!(membership_success(ok));
    }
    for bad in [400u16, 401, 403, 500] {
        assert!(!membership_success(bad));
    }
    let r = ensure_membership_request("u1", "t1");
    assert_eq!(r.method, "POST");
    assert_eq!(r.path, "/chat_memberships");
    assert_eq!(r.headers[0], ("Prefer", "resolution=ignore-duplicates".to_string()));
    assert_eq!(r.body.as_deref(), Some("{\"user_id\":\"u1\",\"topic_id\":\"t1\"}"));
}

#[test]
fn flag_body_equality() {
    for yes in ["true", " true ", "TRUE", "True", "true\n"] {
        assert!(flag_success(200, yes), "{yes:?}");
    }
    for no in ["1", "", "false"] {
        assert!(!flag_success(200, no), "{no:?}");
    }
    assert!(!flag_success(500, "true"));
    let r = flag_message_request("m1");
    assert_eq!(r.path, "/rpc/flag_chat_message");
    assert_eq!(r.body.as_deref(), Some("{\"p_message_id\":\"m1\"}"));
}

#[test]
fn post_validation_utf16_units() {
    assert!(!post_content_allowed(""));
    assert!(post_content_allowed(&"x".repeat(500)));
    assert!(!post_content_allowed(&"x".repeat(501)));
    // 250 emoji = 500 UTF-16 units: allowed; 251 = 502: rejected.
    assert!(post_content_allowed(&"\u{1F338}".repeat(250)));
    assert!(!post_content_allowed(&"\u{1F338}".repeat(251)));
    let r = post_message_request("t1", "u1", "Soft Dawn", "hi");
    assert_eq!(r.path, "/chat_messages");
    assert_eq!(r.headers[0], ("Prefer", "return=representation".to_string()));
    assert_eq!(
        r.body.as_deref(),
        Some("{\"topic_id\":\"t1\",\"user_id\":\"u1\",\"sender_alias\":\"Soft Dawn\",\"content\":\"hi\"}")
    );
}

#[test]
fn topics_and_messages_request_shapes() {
    let t = fetch_topics_request();
    assert_eq!(t.method, "GET");
    assert_eq!(t.path, "/chat_topics?select=id,title,description&is_active=eq.true&order=created_at.asc&limit=20");
    let m = fetch_messages_request("t1");
    assert_eq!(m.path, "/chat_messages?select=id,user_id,persona_id,sender_alias,content,created_at,is_flagged&topic_id=eq.t1&order=created_at.asc&limit=100");
    assert_eq!(MAX_RESPONSE_BYTES, 96 * 1024);
    assert!(bounded_body(96 * 1024).is_some());
    assert!(bounded_body(96 * 1024 + 1).is_none());
}

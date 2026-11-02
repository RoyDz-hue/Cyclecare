use cyclecare_core::community_guides::*;

#[test]
fn soft_alias_jvm_probe_locked() {
    // JVM probe 2026-11-02 (kotlinc 2.1.0 / JRE 21), verbatim softAliasFor.
    assert_eq!(soft_alias_for("u1"), "Golden Star");
    assert_eq!(soft_alias_for("host-test-store-owner"), "Radiant Dawn");
    assert_eq!(soft_alias_for("550e8400-e29b-41d4-a716-446655440000"), "Soft Dawn");
    assert_eq!(soft_alias_for("a"), "Kind Leaf");
    assert_eq!(soft_alias_for(""), "Gentle Dawn");
    assert_eq!(soft_alias_for("user_\u{1F338}_with_emoji"), "Calm Dawn");
    assert_eq!(
        soft_alias_for("VERYLONGUSERID0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"),
        "Radiant Leaf"
    );
    assert_eq!(soft_alias_for("zzz"), "Golden Willow");
}

#[test]
fn topics_parse_skips_bad_rows() {
    let raw = r#"[
        {"id":"t1","title":"Rest","description":"  gentle  "},
        {"id":"","title":"No id"},
        {"id":"t2","title":"  "},
        "not-an-object",
        {"id":"t3","title":"Energy"},
        {"id":42,"title":"Numeric id"}
    ]"#;
    let topics = parse_topics(raw).unwrap();
    assert_eq!(topics.len(), 3);
    assert_eq!(topics[0].description, "gentle");
    assert_eq!(topics[2].id, "42"); // optString stringifies non-strings
    assert!(parse_topics("not json").is_none());
    assert!(parse_topics("{}").is_none());
}

#[test]
fn messages_mapping_verbatim() {
    let raw = r#"[
        {"id":"m1","user_id":"me","sender_alias":"","content":"  hello  ","persona_id":"","is_flagged":false},
        {"id":"m2","user_id":"other","sender_alias":"","content":"guide words","persona_id":" LUNA ","is_flagged":"true"},
        {"id":"m3","user_id":"ME","sender_alias":"Case","content":"case matters","is_flagged":true},
        {"id":"m4","user_id":"x","sender_alias":" ","content":"   "}
    ]"#;
    let msgs = parse_circle_messages(raw, Some("me")).unwrap();
    assert_eq!(msgs.len(), 3); // empty content row skipped
    assert!(msgs[0].is_self && msgs[0].sender_alias == "Member" && msgs[0].content == "hello");
    assert_eq!(msgs[1].sender_alias, "Community Guide");
    assert_eq!(msgs[1].persona_id.as_deref(), Some("luna"));
    assert!(msgs[1].is_flagged); // string "true" counts (Boolean.parseBoolean)
    assert!(!msgs[2].is_self); // "ME" != "me" (case-sensitive Kotlin ==)
    assert_eq!(msgs[2].sender_alias, "Case");
}

#[test]
fn message_content_take_500_utf16() {
    let long = "🌸".repeat(600); // 1200 UTF-16 units
    let raw = format!(r#"[{{"id":"m","user_id":"u","content":"{long}"}}]"#);
    let msgs = parse_circle_messages(&raw, None).unwrap();
    assert_eq!(msgs[0].content, "🌸".repeat(250)); // take(500) units = 250 emoji
}

#[test]
fn status_chain_verbatim_order() {
    use TokenResolution::*; use TransportOutcome::*; use CircleStatus::*;
    assert_eq!(topics_status(MissingOrReauth, None, None), SignInUnconfirmed);
    assert_eq!(topics_status(TemporarilyUnavailable, None, None), Unavailable);
    assert_eq!(topics_status(Valid, Some(IoError), None), Network);
    assert_eq!(topics_status(Valid, Some(OtherError), None), Unavailable);
    assert_eq!(topics_status(Valid, Some(Http(401)), None), SignInUnconfirmed);
    assert_eq!(topics_status(Valid, Some(Http(403)), None), SignInUnconfirmed);
    assert_eq!(topics_status(Valid, Some(Http(500)), None), Unavailable);
    assert_eq!(topics_status(Valid, Some(Http(200)), Some(0)), Empty);
    assert_eq!(topics_status(Valid, Some(Http(200)), Some(3)), Ok);
    assert_eq!(topics_status(Valid, Some(Http(200)), None), Unavailable);
}

use cyclecare_core::guided_chats::*;

#[test]
fn fallback_channels_verbatim() {
    assert_eq!(FALLBACK_CHANNELS.len(), 3);
    assert_eq!(FALLBACK_CHANNELS[0].id, "circle_luteal_rest");
    assert_eq!(FALLBACK_CHANNELS[0].title, "Luteal Phase Softness & Reflection");
    assert_eq!(
        FALLBACK_CHANNELS[0].topic_guide,
        "How might you create space for rest when your energy turns inward?"
    );
    assert_eq!(FALLBACK_CHANNELS[1].id, "circle_ovulation_vitality");
    assert_eq!(FALLBACK_CHANNELS[2].id, "circle_sovereign_sanctuary");
}

#[test]
fn validation_boundaries() {
    assert_eq!(validate_message("   ").unwrap_err(), "Message cannot be empty");
    assert_eq!(validate_message("").unwrap_err(), "Message cannot be empty");
    assert_eq!(validate_message(&"a".repeat(501)).unwrap_err(), "Message exceeds 500 characters");
    assert!(validate_message(&"a".repeat(500)).is_ok());
    // UTF-16 units: 250 emoji (2 units each) = 500 -> accepted; 251 -> rejected.
    assert!(validate_message(&"🌸".repeat(250)).is_ok());
    assert_eq!(validate_message(&"🌸".repeat(251)).unwrap_err(), "Message exceeds 500 characters");
    // Trimmed content is what gets stored.
    assert_eq!(validate_message("  hello  ").unwrap(), "hello");
}

#[test]
fn composer_cap_is_utf16() {
    assert!(composer_accepts(&"a".repeat(500)));
    assert!(!composer_accepts(&"a".repeat(501)));
    assert!(composer_accepts(&"🌸".repeat(250)));
    assert!(!composer_accepts(&"🌸".repeat(251)));
}

#[test]
fn posted_message_is_self_you() {
    let m = post_message("circle_luteal_rest", "Feeling slower this week.").unwrap();
    assert_eq!(m.sender_alias, "You");
    assert!(m.is_self);
    assert_eq!(m.channel_id, "circle_luteal_rest");
    assert_eq!(m.content, "Feeling slower this week.");
}

#[test]
fn offline_empty_copy_verbatim() {
    assert_eq!(
        offline_empty_copy(CircleServiceStatus::Unavailable),
        "The live circle is not available right now. Reflections you write stay on this device; please try again in a little while."
    );
    assert!(offline_empty_copy(CircleServiceStatus::Network).starts_with("The live circle could not be reached."));
    assert!(offline_empty_copy(CircleServiceStatus::SignInUnconfirmed).starts_with("The live circle needs a confirmed sign-in,"));
}

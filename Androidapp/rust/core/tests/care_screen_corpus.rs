//! JVM-executed oracle replay for care_screen (fixtures/care_screen).
use cyclecare_core::care_screen::*;

#[test]
fn care_screen_rules_match_jvm() {
    let expected = include_str!("fixtures/care_screen/jvm_expected.txt");
    let mut lines = expected.lines();

    // assistant_detail: 5 JVM rows
    let join = |v: Vec<&str>| assistant_detail([v[0], v[1], v[2], v[3], v[4]]).replace('\n', "\\n");
    assert_eq!(join(vec!["a", "b", "c", "d", "e"]), lines.next().unwrap());
    assert_eq!(join(vec!["", "b", "", "d", ""]), lines.next().unwrap());
    assert_eq!(join(vec!["a", "b", "a", "b", "a"]), lines.next().unwrap());
    assert_eq!(join(vec![" ", "  ", "x", "x", "\t"]), lines.next().unwrap());
    assert_eq!(join(vec!["", "", "", "", ""]), lines.next().unwrap());
    assert_eq!(lines.next(), Some("---send---"));

    // canSend: 9 JVM rows
    let cases: Vec<(String, bool)> = vec![
        ("hello".into(), false),
        ("   ".into(), false),
        ("hello".into(), true),
        ("x".repeat(1200), false),
        ("x".repeat(1201), false),
        (format!(" {}", "x".repeat(1199)), false),
        (format!(" {}", "x".repeat(1200)), false),
        ("\u{1F600}".repeat(600), false),
        ("\u{1F600}".repeat(601), false),
    ];
    for (q, loading) in cases {
        assert_eq!(can_send(&q, loading).to_string(), lines.next().unwrap(), "canSend {q:?}");
    }
    assert_eq!(lines.next(), Some("---onsend---"));

    // onSend: 4 JVM rows (4th is the trimmed-1200 send + a blank line)
    assert_eq!(on_send_question("  hello  ", false).unwrap_or("NO-SEND".into()), lines.next().unwrap());
    assert_eq!(on_send_question("   ", false).unwrap_or("NO-SEND".into()), lines.next().unwrap());
    assert_eq!(on_send_question("hello", true).unwrap_or("NO-SEND".into()), lines.next().unwrap());
    let sent = on_send_question(&format!(" {}", "x".repeat(1200)), false).unwrap_or("NO-SEND".into());
    assert_eq!(sent, lines.next().unwrap());
    assert_eq!(lines.next(), Some("")); // probe's extra blank line
    assert_eq!(lines.next(), Some("---providers---"));

    // provider lines: 5 JVM rows
    let pl = |n, m, e, c, ps: Vec<String>, r| provider_line(n, m, e, c, &ps, r);
    assert_eq!(pl("Claude", "opus-4", true, true, vec!["Acme".into(), "Sub Co".into()], ""), lines.next().unwrap());
    assert_eq!(pl("Claude", "opus-4", true, true, vec![], ""), lines.next().unwrap());
    assert_eq!(pl("Gemini", "g-2", false, true, vec![], "quota reached"), lines.next().unwrap());
    assert_eq!(pl("Gemini", "g-2", true, false, vec![], ""), lines.next().unwrap());
    // JVM reason "  " is isNotBlank -> shows ":   "; match probe exactly
    assert_eq!(pl("Grok", "x-1", false, false, vec![], "  "), lines.next().unwrap());
    assert_eq!(lines.next(), Some("---style---"));

    assert_eq!(style_tab_label(true, "Botanical"), lines.next().unwrap());
    assert_eq!(style_tab_label(false, "Botanical"), lines.next().unwrap());
    assert!(lines.next().is_none());
}

#[test]
fn care_misc_rules() {
    assert_eq!(manifest_state(true, true), ManifestState::Ready);
    assert_eq!(manifest_state(true, false), ManifestState::Ready);
    assert_eq!(manifest_state(false, true), ManifestState::Checking);
    assert_eq!(manifest_state(false, false), ManifestState::Unavailable);
    assert_eq!(manifest_state_text(ManifestState::Checking), "Checking who can answer\u{2026}");
    assert!(allow_enabled(true));
    assert!(!allow_enabled(false));
    assert!(premium_or_admin(true, false));
    assert!(premium_or_admin(false, true));
    assert!(!premium_or_admin(false, false));
    assert!(policy_provider_visible(true, true));
    assert!(!policy_provider_visible(true, false));
    assert_eq!(policy_label("Acme"), "Acme privacy policy");
    assert_eq!(
        draft_items_text(&[("Mood".into(), "Calm".into()), ("Flow".into(), "Light".into())]),
        "Mood: Calm \u{b7} Flow: Light"
    );
    assert_eq!(cap_1200(&"\u{1F600}".repeat(700)).chars().count(), 600); // UTF-16 cap: 600 emoji = 1200 units
    assert_eq!(cap_1200("abc"), "abc");
}

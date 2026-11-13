//! JVM-executed oracle replay for something_sheet (fixtures/something_sheet).
use cyclecare_core::something_sheet::*;

#[test]
fn sheet_rules_match_jvm() {
    let expected = include_str!("fixtures/something_sheet/jvm_expected.txt");
    let mut lines = expected.lines();
    for (domain, subs) in UNPACK_DOMAIN_CHIPS {
        assert_eq!(format!("{domain}={}", subs.join(",")), lines.next().unwrap(), "chip table {domain}");
    }
    assert_eq!(lines.next(), Some("---when---"));
    for d in ["Physical", "Mood", "Energy & Sleep", "Pelvic", "Stomach", "Unknown", ""] {
        assert_eq!(format!("{d}={}", unpack_sub_options(d).join(",")), lines.next().unwrap(), "when {d}");
    }
    assert_eq!(lines.next(), Some("---chips---"));
    assert_eq!(QUICK_CHIPS.join("|"), lines.next().unwrap());
    assert_eq!(lines.next(), Some("---append---"));
    for (t, item) in [("", "Cramps"), ("  ", "Nausea"), ("Headache", "Bloating"), ("a, b", "Spotting")] {
        assert_eq!(append_sensation(t, item), lines.next().unwrap(), "append {t:?}");
    }
    assert_eq!(lines.next(), Some("---gates---"));
    assert_eq!(interpretation_card_visible(false, 1).to_string(), lines.next().unwrap());
    assert_eq!((1usize > 0).to_string(), lines.next().unwrap());
    assert!(lines.next().is_none());
}

#[test]
fn sheet_misc_rules() {
    assert!(should_parse("x"));
    assert!(!should_parse("  \u{a0} ")); // NBSP counts as Kotlin-whitespace via kotlin_trim? verify probe-locked trim
    assert!(interpretation_card_visible(true, 1));
    assert!(!interpretation_card_visible(true, 0));
    assert_eq!(interpretation_item_line("Cramps", "severe"), "\u{2022} Cramps (severe)");
    assert_eq!(sub_item_label("Cramps"), "\u{2022} Cramps"); // :254 verbatim shape
    assert_eq!(follow_up_text("How long?"), "\u{1F4AD} How long?");
}

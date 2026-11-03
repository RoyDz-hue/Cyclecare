//! JVM-executed oracle replay for cycle_screen (fixtures/cycle_screen).
use cyclecare_core::cycle_screen::*;

#[test]
fn cycle_screen_rules_match_jvm() {
    let expected = include_str!("fixtures/cycle_screen/jvm_expected.txt");
    let mut lines = expected.lines();
    for (p, avg, day) in [(true, 28, 3), (false, 28, 10), (false, 28, 28), (false, 20, 25), (false, 20, 15), (false, 50, 45)] {
        assert_eq!(days_left_chip(p, avg, day).to_string(), lines.next().unwrap(), "chip {p} {avg} {day}");
    }
    assert_eq!(lines.next(), Some("---"));
    assert_eq!(selected_day_title(Some("Follicular"), true), lines.next().unwrap());
    assert_eq!(selected_day_title(None, true), lines.next().unwrap());
    assert_eq!(selected_day_title(None, false), lines.next().unwrap());
    assert_eq!(lines.next(), Some("---"));
    let cases: Vec<(&str, Option<bool>, Option<bool>, Option<bool>, bool, bool)> = vec![
        ("5", None, None, None, false, false),
        ("6", Some(true), Some(true), Some(true), true, true),
        ("7", Some(false), Some(true), Some(true), false, false),
        ("8", Some(false), Some(false), Some(true), true, false),
        ("9", Some(false), Some(false), Some(false), false, true),
    ];
    for (b, p, o, f, l, t) in cases {
        assert_eq!(day_content_description(b, p, o, f, l, t), lines.next().unwrap(), "desc {b}");
    }
    assert!(lines.next().is_none());
}

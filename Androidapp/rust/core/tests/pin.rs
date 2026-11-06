//! Port fidelity for the quick-unlock passcode shape.

use cyclecare_core::pin::is_complete_pin;

#[test]
fn exactly_four_ascii_digits_is_complete() {
    assert!(is_complete_pin("1234"));
    assert!(is_complete_pin("0000"));
}

#[test]
fn partial_drafts_are_never_valid() {
    assert!(!is_complete_pin(""));
    assert!(!is_complete_pin("1"));
    assert!(!is_complete_pin("12"));
    assert!(!is_complete_pin("123"));
}

#[test]
fn longer_and_non_digit_shapes_are_rejected() {
    assert!(!is_complete_pin("12345"));
    assert!(!is_complete_pin("12a4"));
    assert!(!is_complete_pin("12 4"));
    // Superscript two is Unicode category No, not Nd: Kotlin isDigit rejects it.
    assert!(!is_complete_pin("12\u{00B2}4"));
}

#[test]
fn bmp_non_ascii_nd_digits_are_accepted_like_kotlin() {
    // ARABIC-INDIC digits 1,2,3,4: category Nd, reachable via IME/paste.
    assert!(is_complete_pin("\u{0661}\u{0662}\u{0663}\u{0664}"));
}

#[test]
fn supplementary_plane_nd_digits_are_rejected_like_kotlin_utf16_semantics() {
    // Osmanya digits (U+104A0.., category Nd, supplementary plane). Kotlin
    // Char.isDigit sees surrogate halves and rejects; a naive Rust scalar
    // iteration would wrongly accept. Exact port must reject.
    assert!(!is_complete_pin("\u{104A1}\u{104A2}\u{104A3}\u{104A4}"));
    // Mixed BMP/supplementary is also rejected.
    assert!(!is_complete_pin("12\u{104A1}4"));
}

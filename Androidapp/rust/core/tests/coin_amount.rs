//! Mirrors CoinAmountInputTest (Kotlin) case for case.

use cyclecare_core::coin_amount::sanitize_coin_amount_input;

#[test]
fn plain_integer_and_decimal_are_accepted() {
    assert_eq!(sanitize_coin_amount_input("50"), ("50".to_string(), None));
    assert_eq!(sanitize_coin_amount_input("12.5"), ("12.5".to_string(), None));
    assert_eq!(sanitize_coin_amount_input("12.500"), ("12.500".to_string(), None));
    assert_eq!(sanitize_coin_amount_input(" 10 "), ("10".to_string(), None));
}

#[test]
fn three_decimals_accepted_four_rejected() {
    assert_eq!(sanitize_coin_amount_input("0.001").1, None);
    assert!(sanitize_coin_amount_input("0.0001").1.is_some());
}

#[test]
fn negative_input_kept_as_typed_with_rejection() {
    let (text, rejection) = sanitize_coin_amount_input("-1");
    assert_eq!(text, "-1");
    assert!(rejection.is_some());
}

#[test]
fn multiple_dots_kept_as_typed_with_rejection() {
    let (text, rejection) = sanitize_coin_amount_input("1.2.3");
    assert_eq!(text, "1.2.3");
    assert!(rejection.is_some());
}

#[test]
fn comma_kept_as_typed_with_rejection() {
    let (text, rejection) = sanitize_coin_amount_input("1,5");
    assert_eq!(text, "1,5");
    assert!(rejection.is_some());
}

#[test]
fn pasted_text_kept_as_typed_with_rejection() {
    let (text, rejection) = sanitize_coin_amount_input("1a2");
    assert_eq!(text, "1a2");
    assert!(rejection.is_some());
}

#[test]
fn leading_dot_gets_specific_feedback() {
    let (text, rejection) = sanitize_coin_amount_input(".5");
    assert_eq!(text, ".5");
    assert_eq!(rejection, Some("Start the amount with a digit, for example 0.5."));
}

#[test]
fn empty_input_is_accepted_as_empty() {
    assert_eq!(sanitize_coin_amount_input("").1, None);
    assert_eq!(sanitize_coin_amount_input("   ").1, None);
}

#[test]
fn overlong_input_gets_length_feedback() {
    let long = "1".repeat(17);
    let (_, rejection) = sanitize_coin_amount_input(&long);
    assert_eq!(rejection, Some("Amount is too long."));
}

#[test]
fn trailing_dot_matches_kotlin_regex_shape() {
    // Kotlin ^\d*(\.\d{0,3})?$ accepts "12." - parity, not judgment.
    assert_eq!(sanitize_coin_amount_input("12.").1, None);
}

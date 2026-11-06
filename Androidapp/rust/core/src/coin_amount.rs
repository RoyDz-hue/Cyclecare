//! Coin amount input sanitizing.
//! Port of sanitizeCoinAmountInput in ui/screens/CoinTransferDialog.kt
//! (main@31b88f5), messages verbatim. Coins support up to 3 decimals.
//! Rejected input is KEPT AS TYPED with a rejection message, never silently
//! rewritten (a user who typed "-1" must see their text and the complaint).
use crate::kotlin_text::{kotlin_trim};

/// Shape equivalent to the Kotlin regex ^\d*(\.\d{0,3})?$
fn matches_coin_shape(s: &str) -> bool {
    if s.is_empty() {
        return true; // handled by the empty check upstream; regex would match
    }
    let (int_part, frac_part) = match s.split_once('.') {
        Some((i, f)) => (i, Some(f)),
        None => (s, None),
    };
    if !int_part.chars().all(|c| c.is_ascii_digit()) {
        return false;
    }
    match frac_part {
        None => true,
        Some(f) => {
            // a second dot makes f contain '.', which is not a digit -> false
            f.len() <= 3 && f.chars().all(|c| c.is_ascii_digit())
        }
    }
}

/// Returns (kept_text, rejection_message). None = accepted.
pub fn sanitize_coin_amount_input(raw: &str) -> (String, Option<&'static str>) {
    let trimmed = kotlin_trim(raw);
    if trimmed.is_empty() {
        return (String::new(), None);
    }
    // Kotlin String.length counts UTF-16 code units (a supplementary-plane
    // char counts 2), so mirror that, not byte length and not char count.
    if trimmed.encode_utf16().count() > 16 {
        return (trimmed.to_string(), Some("Amount is too long."));
    }
    if !matches_coin_shape(trimmed) {
        return (
            trimmed.to_string(),
            Some("Use digits and at most one decimal point, for example 12.5."),
        );
    }
    if trimmed.starts_with('.') {
        return (
            trimmed.to_string(),
            Some("Start the amount with a digit, for example 0.5."),
        );
    }
    (trimmed.to_string(), None)
}

#[cfg(test)]
mod probe_locked_precedence_tests {
    use super::*;

    #[test]
    fn utf16_length_and_message_precedence() {
        // JVM probe: 9 fullwidth digits = 9 UTF-16 units -> passes the length
        // check, then fails the shape check ("Use digits..."), NOT "too long".
        let (_, msg) = sanitize_coin_amount_input("１２３４５６７８９");
        assert_eq!(msg.as_deref(), Some("Use digits and at most one decimal point, for example 12.5."));
        // 17 fullwidth digits = 17 UTF-16 units -> "Amount is too long."
        let (_, msg2) = sanitize_coin_amount_input("１２３４５６７８９０１２３４５６７");
        assert_eq!(msg2.as_deref(), Some("Amount is too long."));
        // NBSP is Kotlin-whitespace: trimmed before shape check (probe-locked).
        let (text, msg3) = sanitize_coin_amount_input(" 12.5");
        assert_eq!(text, "12.5");
        assert_eq!(msg3, None);
    }
}

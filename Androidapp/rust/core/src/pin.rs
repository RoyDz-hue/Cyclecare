//! Quick-unlock passcode shape.
//! Port of isCompletePin in ui/screens/SettingsScreen.kt (main@31b88f5).
//! A complete passcode is exactly 4 digits. Partial drafts are never valid.
//!
//! Digit semantics match Kotlin's Char.isDigit, which operates on UTF-16 code
//! units: Unicode category Nd AND basic-multilingual-plane only. Paste, IME or
//! accessibility input CAN deliver non-ASCII BMP digits (e.g. Arabic-Indic),
//! and the source accepts them, so this port accepts them too. A supplementary-
//! plane Nd digit (e.g. Osmanya U+104A0) reaches Kotlin as a surrogate pair
//! whose halves are not digits, so the source REJECTS it; a naive Rust scalar
//! iteration would wrongly accept, so BMP-only is enforced explicitly here.

use unicode_general_category::{get_general_category, GeneralCategory};

pub fn is_complete_pin(pin: &str) -> bool {
    pin.chars().count() == 4 && pin.chars().all(is_kotlin_is_digit)
}

/// Kotlin Char.isDigit on a UTF-16 code unit = Nd category + BMP only.
fn is_kotlin_is_digit(c: char) -> bool {
    (c as u32) <= 0xFFFF && get_general_category(c) == GeneralCategory::DecimalNumber
}

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

use crate::kotlin_text::kotlin_is_digit;

pub fn is_complete_pin(pin: &str) -> bool {
    pin.chars().count() == 4 && pin.chars().all(kotlin_is_digit)
}



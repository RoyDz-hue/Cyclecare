//! Decision-core port of the money-surface dialogs' VALIDATION RULES ONLY:
//! ui/screens/CoinTransferDialog.kt :146-161, InsufficientCoinsDialog.kt :61,
//! MpesaStkPushDialog.kt :142/:221/:230 (ref 31b88f5). All money actions
//! (send transfer, STK push, checkout) stay noncommitting inert gaps under
//! the standing money boundary - this module returns decisions and display
//! strings, it never performs a payment step.
//!
//! Exact-decimal contract: BigDecimal everywhere, never f64 for coins.
//! QUIRK (ported 1:1, labeled): onSendTransfer receives the RAW
//! recipientEmail (untrimmed); only the gate reads it.

use bigdecimal::BigDecimal;
use bigdecimal::Zero;
use std::str::FromStr;

use crate::coin_format;
use crate::kotlin_text::kotlin_trim;

/// Outcome of the CoinTransferDialog confirm chain (:146-161).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TransferDecision {
    /// One of the three verbatim error strings.
    Error(String),
    /// The source would invoke onSendTransfer(recipientEmail, amount) here;
    /// the action itself stays an inert gap (money boundary).
    Send { recipient_email: String, amount: BigDecimal },
}

/// Kotlin Regex ^\d+(\.\d{1,3})?$ with ASCII \d.
fn submission_shape_matches(amount_text: &str) -> bool {
    let (int_part, frac_part) = match amount_text.split_once('.') {
        Some((i, f)) => (i, Some(f)),
        None => (amount_text, None),
    };
    if int_part.is_empty() || !int_part.bytes().all(|b| b.is_ascii_digit()) {
        return false;
    }
    match frac_part {
        None => true,
        Some(f) => !f.is_empty() && f.len() <= 3 && f.bytes().all(|b| b.is_ascii_digit()),
    }
}

pub fn transfer_confirm_decision(
    recipient_email: &str,
    transfer_amount_text: &str,
    current_balance: &BigDecimal,
) -> TransferDecision {
    let amount_text = kotlin_trim(transfer_amount_text);
    if kotlin_trim(recipient_email).is_empty() || !recipient_email.contains('@') {
        return TransferDecision::Error(
            "Please enter a valid registered email address.".to_string(),
        );
    }
    if !submission_shape_matches(amount_text) {
        return TransferDecision::Error(
            "Enter an amount with up to 3 decimal places.".to_string(),
        );
    }
    // Shape-gated, so this parse cannot fail on the accepted grammar.
    let amount = match BigDecimal::from_str(amount_text) {
        Ok(a) => a,
        Err(_) => {
            return TransferDecision::Error(
                "Enter an amount with up to 3 decimal places.".to_string(),
            )
        }
    };
    if amount <= BigDecimal::zero() {
        return TransferDecision::Error("Please enter an amount greater than zero.".to_string());
    }
    if amount > *current_balance {
        return TransferDecision::Error(format!(
            "Amount exceeds your available balance ({} coins).",
            coin_format::format(current_balance)
        ));
    }
    TransferDecision::Send {
        recipient_email: recipient_email.to_string(), // RAW, 1:1 quirk
        amount,
    }
}

/// InsufficientCoinsDialog :61 - exact decimal, floored at 0.001.
pub fn missing_coins(item_price: i64, user_coins: &BigDecimal) -> BigDecimal {
    let diff = BigDecimal::from(item_price) - user_coins;
    let floor = BigDecimal::from_str("0.001").expect("constant");
    if diff < floor {
        floor
    } else {
        diff
    }
}

/// MpesaStkPushDialog :221 send gate. Kotlin trim().length: UTF-16 units
/// of the TRIMMED text.
pub fn mpesa_send_enabled(phone_number: &str, price_kes: i64) -> bool {
    let trimmed = kotlin_trim(phone_number);
    trimmed.chars().map(|c| c.len_utf16()).sum::<usize>() >= 10 && price_kes > 0
}

/// MpesaStkPushDialog :142 price label.
pub fn mpesa_price_label(price_kes: i64) -> String {
    if price_kes > 0 {
        format!("KES {price_kes}")
    } else {
        "M-Pesa price unavailable".to_string()
    }
}

/// MpesaStkPushDialog :230 confirm button label (rendered when enabled).
pub fn mpesa_send_button_label(price_kes: i64) -> String {
    format!("Send payment prompt \u{b7} KES {price_kes}")
}

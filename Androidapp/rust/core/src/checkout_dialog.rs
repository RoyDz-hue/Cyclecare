//! Port of the decision logic in `ui/screens/UnifiedGamifiedCheckoutDialog.kt`
//! (763 lines, ref 31b88f5). Everything here is inert display/gate logic:
//! the source dialog itself never executes a payment (it calls `onProcess*`
//! callbacks), and with the production-default `DisabledPaymentReleasePolicy`
//! `releasedCheckoutOptions` returns an empty list and the dialog renders the
//! "Checkout unavailable" path (:102-113). Porting these gates 1:1 preserves
//! that exact inert behavior; it adds no payment capability. Compose
//! rendering, the 2 s connectivity poll (:124-129, `NetworkHelper.isOnline`
//! host glue), and the `RemoteLocalPricing.cached(...)` charge-line delegate
//! (:192-196) stay platform seams in the full implementation inventory.
//!
//! Line citations verified by grep against the fetched source.

use crate::kotlin_text::kotlin_is_blank;
use crate::payment_models::PaymentOptionType;
use crate::payment_safety::{PaymentOperation, PaymentProvider};
use crate::payment_validation::validate_kenyan_phone_number;

/// `releasedCheckoutOptions` (:82-87). `allows` is the release-policy
/// predicate (`PaymentReleasePolicy.allows`); the production default
/// (`DisabledPaymentReleasePolicy`, PaymentSafety.kt :13) answers false for
/// every pair, yielding an empty list. Order is MPESA then PAYPAL; CARD and
/// CRYPTO are never released here.
pub fn released_checkout_options(
    is_east_africa_region: bool,
    price_kes: i64,
    allows: impl Fn(PaymentProvider, PaymentOperation) -> bool,
) -> Vec<PaymentOptionType> {
    let mut out = Vec::new();
    if is_east_africa_region
        && price_kes > 0
        && allows(PaymentProvider::Payhero, PaymentOperation::Initiate)
    {
        out.push(PaymentOptionType::Mpesa);
    }
    if allows(PaymentProvider::PaypalStandard, PaymentOperation::Initiate) {
        out.push(PaymentOptionType::Paypal);
    }
    out
}

/// :102-113 the empty-options branch shows the unavailable dialog instead of
/// any checkout UI. Returns the (title, body, button) literal triple.
pub fn checkout_unavailable_dialog() -> (&'static str, &'static str, &'static str) {
    (
        "Checkout unavailable",
        "Payments aren't open just yet. Nothing will be charged, and no payment details are needed.",
        "Close",
    )
}

/// :117 `selectedOption` initializes to `availableOptions.first()`.
pub fn initial_selected_option(options: &[PaymentOptionType]) -> Option<PaymentOptionType> {
    options.first().copied()
}

/// :138-149 the `nextStep` when-chain, arms in source order. Kotlin
/// `contains("timeout", ignoreCase = true)` is matched on the lowercased
/// status (ASCII case fold matches Kotlin's invariant lowercase here).
pub fn next_step(
    is_online: bool,
    is_processing: bool,
    selected_option: PaymentOptionType,
    status_message: Option<&str>,
    phone_is_valid: bool,
) -> &'static str {
    if !is_online {
        "Reconnect to continue securely."
    } else if is_processing && selected_option == PaymentOptionType::Mpesa {
        "Follow the prompt on your phone and enter your PIN there."
    } else if is_processing {
        "Approve the payment in PayPal, then return here."
    } else if status_message
        .map(|m| m.to_lowercase().contains("timeout"))
        .unwrap_or(false)
    {
        "Do not retry a charge until its status and your wallet have been verified."
    } else if status_message.is_some() {
        "Review the message above, then try again."
    } else if selected_option == PaymentOptionType::Mpesa && phone_is_valid {
        "Next: send the prompt, then confirm on your phone."
    } else if selected_option == PaymentOptionType::Mpesa {
        "Next: enter your Kenyan mobile number."
    } else {
        "Next: continue to PayPal to approve your payment."
    }
}

/// :224 options-count label.
pub fn option_count_label(option_count: usize) -> &'static str {
    if option_count == 1 {
        "1 verified option"
    } else {
        "Verified options"
    }
}

/// :400-402 `PackageSummary` amount: for M-Pesa, "KES {priceKes}" when the
/// price is positive, else "Price unavailable"; otherwise the USD string.
pub fn summary_amount(
    selected_option: PaymentOptionType,
    price_kes: i64,
    price_usd: &str,
) -> String {
    if selected_option == PaymentOptionType::Mpesa {
        if price_kes > 0 {
            format!("KES {price_kes}")
        } else {
            "Price unavailable".to_string()
        }
    } else {
        price_usd.to_string()
    }
}

/// :434-437 coin count line with optional bonus suffix (Long arithmetic).
pub fn coins_line(coins: i64, bonus_coins: i64) -> String {
    let mut s = format!("{} coins", coins + bonus_coins);
    if bonus_coins > 0 {
        s.push_str(&format!(" \u{b7} includes {bonus_coins} bonus"));
    }
    s
}

/// :447 the M-Pesa alternative-price line shows only when a non-M-Pesa option
/// is selected, the region is East Africa and the KES price is positive.
pub fn show_mpesa_alt_line(
    selected_option: PaymentOptionType,
    is_east_africa_region: bool,
    price_kes: i64,
) -> bool {
    selected_option != PaymentOptionType::Mpesa && is_east_africa_region && price_kes > 0
}

/// :449 the alt line itself.
pub fn mpesa_alt_line(price_kes: i64) -> String {
    format!("M-Pesa KES {price_kes}")
}

/// :474-479 per-option description in `PaymentMethodChoice`; CARD and CRYPTO
/// are deliberately blank here (they are never released).
pub fn payment_method_description(option: PaymentOptionType) -> &'static str {
    match option {
        PaymentOptionType::Mpesa => "A payment prompt arrives on your Kenyan phone.",
        PaymentOptionType::Paypal => "Approve securely with your PayPal account.",
        PaymentOptionType::DebitCreditCard | PaymentOptionType::Crypto => "",
    }
}

/// :533 trailing-icon content description.
pub fn method_icon_content_description(selected: bool, option: PaymentOptionType) -> String {
    if selected {
        "Selected".to_string()
    } else {
        format!("Select {}", option.ui_label())
    }
}

/// :524 `payment_method_${option.name.lowercase()}` test tag.
pub fn payment_method_test_tag(option: PaymentOptionType) -> String {
    format!("payment_method_{}", option.kotlin_name().to_lowercase())
}

/// :590-593 phone-field supporting text: validation message or the format
/// hint (ellipsis characters are U+2026 in source).
pub fn phone_supporting_text(validation_message: Option<&str>) -> String {
    validation_message
        .map(|m| m.to_string())
        .unwrap_or_else(|| "Use 07\u{2026}, 01\u{2026} or +254\u{2026}".to_string())
}

/// :603-605 the M-Pesa submit gate, 1:1 including the redundant-but-real
/// `validationMessage == null` and `phone.isNotBlank()` arms. Kotlin
/// `isNotBlank` rejects all-whitespace.
pub fn mpesa_submit_enabled(
    is_online: bool,
    amount_kes: f64,
    validation_message: Option<&str>,
    phone: &str,
) -> bool {
    is_online
        && amount_kes > 0.0
        && validation_message.is_none()
        && !kotlin_is_blank(phone)
        && validate_kenyan_phone_number(phone).is_valid()
}

/// :613 button label. `amountKes` is `priceKes.toDouble()` in source and
/// `${amountKes.toInt()}` truncates toward zero like Kotlin Double.toInt()
/// (values here are whole shillings; the truncation is ported for fidelity).
pub fn mpesa_button_label(amount_kes: f64) -> String {
    format!("Send payment prompt \u{b7} KES {}", amount_kes as i32)
}

/// :639 PayPal button label (`price` is the package's USD string).
pub fn paypal_button_label(price_usd: &str) -> String {
    format!("Continue to PayPal \u{b7} {price_usd}")
}

/// :321-326 security footer, selected-option dependent (apostrophe in
/// "PayPal’s" is U+2019 in source).
pub fn secure_footer(selected_option: PaymentOptionType) -> &'static str {
    if selected_option == PaymentOptionType::Mpesa {
        "Your M-Pesa PIN is entered on your phone, never in CycleCare."
    } else {
        "Payment approval is completed in PayPal\u{2019}s secure checkout."
    }
}

/// :695-697 processing panel title.
pub fn processing_title(selected_option: PaymentOptionType) -> &'static str {
    if selected_option == PaymentOptionType::Mpesa {
        "Waiting for your phone"
    } else {
        "Securing your PayPal payment"
    }
}

/// :698-702 processing panel body: the live status message when present,
/// else the per-option default.
pub fn processing_body(
    selected_option: PaymentOptionType,
    status_message: Option<&str>,
) -> String {
    if let Some(m) = status_message {
        return m.to_string();
    }
    if selected_option == PaymentOptionType::Mpesa {
        "Approve the prompt on your phone to finish.".to_string()
    } else {
        "Please wait while we verify your order.".to_string()
    }
}

/// :724-728 success panel body: the live status message when present, else
/// the credited-coin count.
pub fn success_body(coins: i64, bonus_coins: i64, status_message: Option<&str>) -> String {
    status_message
        .map(|m| m.to_string())
        .unwrap_or_else(|| format!("{} coins are ready to use.", coins + bonus_coins))
}

/// :148, :156 both the dialog-dismiss request and the backdrop click are
/// gated on `!isProcessing`.
pub fn dialog_can_dismiss(is_processing: bool) -> bool {
    !is_processing
}

/// Panel dispatch (:202-215): success beats processing beats form.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CheckoutPanel {
    Success,
    Processing,
    Form,
}

pub fn active_panel(is_success: bool, is_processing: bool) -> CheckoutPanel {
    if is_success {
        CheckoutPanel::Success
    } else if is_processing {
        CheckoutPanel::Processing
    } else {
        CheckoutPanel::Form
    }
}

/// Form dispatch (:291-300): CARD and CRYPTO map to `Unit` in source
/// ("deliberately omitted from the verified choices").
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CheckoutForm {
    Mpesa,
    Paypal,
    Omitted,
}

pub fn active_form(selected_option: PaymentOptionType) -> CheckoutForm {
    match selected_option {
        PaymentOptionType::Mpesa => CheckoutForm::Mpesa,
        PaymentOptionType::Paypal => CheckoutForm::Paypal,
        PaymentOptionType::DebitCreditCard | PaymentOptionType::Crypto => CheckoutForm::Omitted,
    }
}

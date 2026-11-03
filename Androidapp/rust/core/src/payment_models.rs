//! Port of `data/payment/PaymentModels.kt` (enum tables only, ref 31b88f5).
//! `PaymentOptionType` carries the UI label, the model description, the
//! East-Africa-only flag and (on Android) a drawable resource; the drawable
//! resource is a host concern and stays unported. `PaymentProvider`,
//! `PaymentOperation` and `PaymentStatus` live in `payment_safety.rs`
//! alongside the release policy and status verifier that consume them.

/// `PaymentOptionType` (:7-36). Variants in declaration order.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum PaymentOptionType {
    Mpesa,
    DebitCreditCard,
    Paypal,
    Crypto,
}

impl PaymentOptionType {
    /// Enum declaration name (used by `payment_method_${option.name.lowercase()}` test tags).
    pub fn kotlin_name(&self) -> &'static str {
        match self {
            PaymentOptionType::Mpesa => "MPESA",
            PaymentOptionType::DebitCreditCard => "DEBIT_CREDIT_CARD",
            PaymentOptionType::Paypal => "PAYPAL",
            PaymentOptionType::Crypto => "CRYPTO",
        }
    }

    pub fn ui_label(&self) -> &'static str {
        match self {
            PaymentOptionType::Mpesa => "M-Pesa",
            PaymentOptionType::DebitCreditCard => "Debit/Credit Card",
            PaymentOptionType::Paypal => "PayPal",
            PaymentOptionType::Crypto => "Crypto",
        }
    }

    /// Model `description` field (distinct from the checkout dialog's own
    /// per-option description table, which overrides CARD/CRYPTO to "").
    pub fn model_description(&self) -> &'static str {
        match self {
            PaymentOptionType::Mpesa => "Instant Mobile Money (East Africa)",
            PaymentOptionType::DebitCreditCard => "Visa, Mastercard, Amex (PayPal ACDC)",
            PaymentOptionType::Paypal => "Express PayPal Wallet Checkout",
            PaymentOptionType::Crypto => "USDT, BTC, ETH via OKX Pay",
        }
    }

    pub fn is_east_africa_only(&self) -> bool {
        matches!(self, PaymentOptionType::Mpesa)
    }
}

//! Port of the pure decision logic in data/payment/PaymentSafety.kt +
//! data/payment/PaymentModels.kt (main@31b88f5). Verbatim: status vocabulary,
//! authoritative-terminal set, verifier branch order, history mapping,
//! store/clear semantics, URL encoding. Platform glue NOT ported (host
//! concern): EncryptedCheckoutRequestStore (Android Keystore /
//! EncryptedSharedPreferences) - the trait seam is the boundary.
//!
//! org.json scope note: capture_status mirrors org.json.JSONObject opt
//! semantics (optString ""-default, optBoolean "true"-string parse).
//! Executed parity runs the JVM org.json (Maven 20240303); the Android
//! framework build is documented-identical for these two accessors.

use crate::kotlin_text::kotlin_trim;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PaymentProvider {
    Payhero,
    PaypalAcdc,
    PaypalStandard,
    Okx,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PaymentOperation {
    Initiate,
    Capture,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PaymentStatus {
    Pending,
    Completed,
    Failed,
    Cancelled,
    Refunded,
    Reversed,
    Ambiguous,
}

impl PaymentStatus {
    pub fn is_authoritative_terminal(&self) -> bool {
        matches!(
            self,
            PaymentStatus::Completed
                | PaymentStatus::Failed
                | PaymentStatus::Cancelled
                | PaymentStatus::Refunded
                | PaymentStatus::Reversed
        )
    }
    pub fn kotlin_name(&self) -> &'static str {
        match self {
            PaymentStatus::Pending => "PENDING",
            PaymentStatus::Completed => "COMPLETED",
            PaymentStatus::Failed => "FAILED",
            PaymentStatus::Cancelled => "CANCELLED",
            PaymentStatus::Refunded => "REFUNDED",
            PaymentStatus::Reversed => "REVERSED",
            PaymentStatus::Ambiguous => "AMBIGUOUS",
        }
    }
}

/// CheckoutRequestStore seam. Production default on Android is
/// EncryptedCheckoutRequestStore (Keystore-backed); Unavailable store
/// mirrors DisabledPaymentReleasePolicy semantics: never plaintext fallback.
pub trait CheckoutRequestStore {
    fn read(&self, owner_id: &str, package_id: &str) -> Option<String>;
    fn write(&mut self, owner_id: &str, package_id: &str, checkout_request_id: &str) -> bool;
    fn clear(&mut self, owner_id: &str, package_id: &str, checkout_request_id: &str) -> bool;
}

#[derive(Default)]
pub struct InMemoryCheckoutRequestStore {
    values: std::collections::HashMap<(String, String), String>,
}

impl CheckoutRequestStore for InMemoryCheckoutRequestStore {
    fn read(&self, owner_id: &str, package_id: &str) -> Option<String> {
        self.values.get(&(owner_id.to_string(), package_id.to_string())).cloned()
    }
    fn write(&mut self, owner_id: &str, package_id: &str, checkout_request_id: &str) -> bool {
        self.values.insert(
            (owner_id.to_string(), package_id.to_string()),
            checkout_request_id.to_string(),
        );
        true
    }
    fn clear(&mut self, owner_id: &str, package_id: &str, checkout_request_id: &str) -> bool {
        let key = (owner_id.to_string(), package_id.to_string());
        if self.values.get(&key).map(|s| s.as_str()) != Some(checkout_request_id) {
            return false;
        }
        self.values.remove(&key);
        true
    }
}

/// CheckoutRequestIdManager. UUID generation is a seam: the caller supplies a
/// generator (the source calls UUID.randomUUID()).
pub struct CheckoutRequestIdManager<S: CheckoutRequestStore> {
    pub store: S,
}

impl<S: CheckoutRequestStore> CheckoutRequestIdManager<S> {
    pub fn get_or_create(
        &mut self,
        owner_id: &str,
        package_id: &str,
        new_uuid: impl FnOnce() -> String,
    ) -> Option<String> {
        if owner_id.is_empty() || kotlin_trim(owner_id).is_empty()
            || package_id.is_empty() || kotlin_trim(package_id).is_empty()
        {
            return None;
        }
        if let Some(existing) = self.store.read(owner_id, package_id) {
            return Some(existing);
        }
        let id = new_uuid();
        if self.store.write(owner_id, package_id, &id) {
            Some(id)
        } else {
            None
        }
    }

    pub fn clear_after_authoritative_terminal(
        &mut self,
        owner_id: &str,
        package_id: &str,
        checkout_request_id: &str,
        status: PaymentStatus,
    ) -> bool {
        if status.is_authoritative_terminal() {
            self.store.clear(owner_id, package_id, checkout_request_id)
        } else {
            false
        }
    }
}

// --- org.json opt semantics -----------------------------------------------

/// org.json.optString(key, ""): "" when absent or JSONObject.NULL; the
/// string itself when String; String.valueOf(value) otherwise.
fn opt_string(obj: &serde_json::Value, key: &str) -> String {
    match obj.get(key) {
        None | Some(serde_json::Value::Null) => String::new(),
        Some(serde_json::Value::String(s)) => s.clone(),
        Some(other) => json_tostring(other),
    }
}

/// JSONObject's number/boolean toString. JSON 1.0 -> "1.0" in org.json
/// (Double.toString); integers stay plain; booleans "true"/"false".
fn json_tostring(v: &serde_json::Value) -> String {
    match v {
        serde_json::Value::Bool(b) => b.to_string(),
        serde_json::Value::Number(n) => n.to_string(),
        other => other.to_string(),
    }
}

/// org.json.optBoolean(key, false): Boolean value as-is; string parsed with
/// "true".equalsIgnoreCase; everything else false.
fn opt_boolean(obj: &serde_json::Value, key: &str) -> bool {
    match obj.get(key) {
        Some(serde_json::Value::Bool(b)) => *b,
        Some(serde_json::Value::String(s)) => s.eq_ignore_ascii_case("true"),
        _ => false,
    }
}

pub struct PaymentStatusVerifier;

impl PaymentStatusVerifier {
    pub fn capture_status(http_successful: bool, body: &str) -> PaymentStatus {
        if !http_successful {
            return PaymentStatus::Ambiguous;
        }
        let obj: serde_json::Value = match serde_json::from_str::<serde_json::Value>(body) {
            Ok(v) if v.is_object() => v,
            _ => return PaymentStatus::Ambiguous,
        };
        let status = kotlin_trim(&opt_string(&obj, "status")).to_lowercase();
        if status == "refunded" {
            return PaymentStatus::Refunded;
        }
        if status == "reversed" {
            return PaymentStatus::Reversed;
        }
        if opt_boolean(&obj, "success")
            && (status == "success" || status == "settled" || opt_boolean(&obj, "settled"))
        {
            return PaymentStatus::Completed;
        }
        match status.as_str() {
            "failed" | "cancelled" | "canceled" | "expired" => PaymentStatus::Failed,
            _ => PaymentStatus::Ambiguous,
        }
    }

    pub fn history_status(status: &str) -> String {
        let s = kotlin_trim(status).to_lowercase();
        match s.as_str() {
            "success" | "completed" | "paid" | "settled" => "success".to_string(),
            "pending" | "processing" | "initiated" => "pending".to_string(),
            "failed" | "cancelled" | "canceled" | "expired" | "refunded" | "reversed"
            | "ambiguous" => s,
            _ => "ambiguous".to_string(),
        }
    }
}

/// java.net.URLEncoder.encode(s, UTF-8): [a-zA-Z0-9.-*_] verbatim, space -> '+',
/// every other byte %XX uppercase.
pub fn url_encode_utf8(s: &str) -> String {
    let mut out = String::new();
    for b in s.as_bytes() {
        match *b {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => {
                out.push(*b as char)
            }
            b' ' => out.push('+'),
            other => out.push_str(&format!("%{other:02X}")),
        }
    }
    out
}

pub fn encoded_transaction_status_url(rest_url: &str, external_reference: &str) -> String {
    format!(
        "{rest_url}/transactions?external_reference=eq.{}&select=status",
        url_encode_utf8(external_reference)
    )
}

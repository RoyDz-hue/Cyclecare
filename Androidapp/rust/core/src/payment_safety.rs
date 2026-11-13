//! Port of the pure decision logic in data/payment/PaymentSafety.kt +
//! data/payment/PaymentModels.kt (main@31b88f5). Verbatim: status vocabulary,
//! authoritative-terminal set, verifier branch order, history mapping,
//! store/clear semantics, URL encoding. Platform glue NOT ported (host
//! concern): EncryptedCheckoutRequestStore (Android Keystore /
//! EncryptedSharedPreferences) - the trait seam is the boundary.
//!
//! org.json scope note: capture_status parses with the AOSP-faithful
//! sync_codec::JsonParser (lenient tokener) and mirrors optString/optBoolean
//! semantics (numberToString for integral doubles). Executed parity runs the
//! compiled AOSP android-14.0.0_r1 org.json classes (Maven superseded);
//! corpus cs21-cs29 locks lenient/typing edges.

use crate::kotlin_text::kotlin_trim;
use crate::sync_codec::JsonValue;

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
/// org.json JSONObject.optString(key, ""): absent/JSONObject.NULL -> "";
/// String as-is; Boolean -> toString; Number -> numberToString
/// (integral doubles strip ".0"); nested JSONObject/JSONArray -> toString.
fn opt_string(obj: &JsonValue, key: &str) -> String {
    let v = match obj {
        JsonValue::Object(pairs) => pairs.iter().find(|(k, _)| k == key).map(|(_, v)| v),
        _ => None,
    };
    match v {
        None | Some(JsonValue::Null) => String::new(),
        Some(JsonValue::Str(s)) => s.clone(),
        Some(JsonValue::Bool(b)) => b.to_string(),
        Some(JsonValue::Int(i)) => i.to_string(),
        Some(JsonValue::Long(l)) => l.to_string(),
        Some(JsonValue::Double(d)) => crate::sync_codec::java_double_to_string(*d),
        Some(other) => crate::sync_codec::json_value_to_string(other),
    }
}

/// org.json.optBoolean(key, false): Boolean value as-is; string parsed with
/// "true".equalsIgnoreCase; everything else false.
/// org.json optBoolean(key, false): Boolean value as-is; String parsed
/// with "true".equalsIgnoreCase; everything else (incl. Numbers) false.
fn opt_boolean(obj: &JsonValue, key: &str) -> bool {
    let v = match obj {
        JsonValue::Object(pairs) => pairs.iter().find(|(k, _)| k == key).map(|(_, v)| v),
        _ => None,
    };
    match v {
        Some(JsonValue::Bool(b)) => *b,
        Some(JsonValue::Str(s)) => s.eq_ignore_ascii_case("true"),
        _ => false,
    }
}

pub struct PaymentStatusVerifier;

impl PaymentStatusVerifier {
    pub fn capture_status(http_successful: bool, body: &str) -> PaymentStatus {
        if !http_successful {
            return PaymentStatus::Ambiguous;
        }
        // org.json.JSONObject(body): AOSP lenient tokener (single quotes,
        // unquoted literals, hex/octal, comments); trailing content after the
        // object is tolerated; parse failure -> runCatching null -> AMBIGUOUS.
        let obj = match crate::sync_codec::JsonParser::new(body).parse() {
            Ok(v @ JsonValue::Object(_)) => v,
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

#[cfg(test)]
mod tests {
    use super::PaymentStatus as PS;
    use super::PaymentStatusVerifier as V;

    /// Wired-surface pins (NEW IMPLEMENTATION, 2026-11-13): the verdicts the
    /// Kotlin twin pins assert against real org.json, asserted here against
    /// the port. Written, NOT executed in this workspace.
    #[test]
    fn capture_status_pins() {
        // HTTP failure / malformed / non-object roots -> Ambiguous.
        assert_eq!(V::capture_status(false, "{\"status\":\"success\",\"success\":true}"), PS::Ambiguous);
        assert_eq!(V::capture_status(true, "not json"), PS::Ambiguous);
        assert_eq!(V::capture_status(true, "[1,2]"), PS::Ambiguous);
        // refunded/reversed branch order wins over the success combo.
        assert_eq!(V::capture_status(true, "{\"status\":\"refunded\",\"success\":true}"), PS::Refunded);
        assert_eq!(V::capture_status(true, "{\"status\":\"reversed\",\"settled\":true}"), PS::Reversed);
        // Kotlin trim (Unicode) + lowercase: NBSP-padded "refunded" matches.
        assert_eq!(V::capture_status(true, "{\"status\":\"\u{00A0}refunded\u{00A0}\"}"), PS::Refunded);
        // COMPLETED requires optBoolean success AND (status success/settled
        // OR optBoolean settled); strings parse, numbers do not.
        assert_eq!(V::capture_status(true, "{\"status\":\"success\",\"success\":true}"), PS::Completed);
        assert_eq!(V::capture_status(true, "{\"status\":\"anything\",\"success\":\"TRUE\",\"settled\":\"true\"}"), PS::Completed);
        assert_eq!(V::capture_status(true, "{\"status\":\"success\"}"), PS::Ambiguous);
        assert_eq!(V::capture_status(true, "{\"status\":\"anything\",\"success\":\"true\",\"settled\":1}"), PS::Ambiguous);
        // Failed vocabulary; everything else Ambiguous.
        for s in ["failed", "cancelled", "canceled", "expired"] {
            assert_eq!(V::capture_status(true, &format!("{{\"status\":\"{s}\"}}")), PS::Failed);
        }
        assert_eq!(V::capture_status(true, "{\"status\":\"pending\"}"), PS::Ambiguous);
        assert_eq!(V::capture_status(true, "{}"), PS::Ambiguous);
        // optString coercion of a numeric status; duplicate keys last-wins;
        // explicit null -> "".
        assert_eq!(V::capture_status(true, "{\"status\":200}"), PS::Ambiguous);
        assert_eq!(V::capture_status(true, "{\"status\":\"pending\",\"status\":\"refunded\"}"), PS::Refunded);
        assert_eq!(V::capture_status(true, "{\"status\":null}"), PS::Ambiguous);
    }
}

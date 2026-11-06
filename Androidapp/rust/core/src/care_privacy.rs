//! Port of domain/ai/CarePrivacyService.kt (main@31b88f5): the pure pieces of
//! the privacy RPC - consent payload construction, RPC path building, attempt
//! guards, and the raw-opt() success checks. HTTP stays host glue.
//!
//! Locked by PrivacyParity (parity/ai/careai, results_privacy.tsv) executed
//! against the VERBATIM CarePrivacyService.kt with AOSP org.json classes and
//! a programmable OkHttp stub: u01-u06, d01-d05.
//!
//! Key executed semantics: the success checks are RAW opt() == true (Kotlin
//! equals on Any?), so a JSON string "true" does NOT pass - only a real
//! Boolean does (u02, d03). Blank token / blank config short-circuit before
//! any HTTP call (u05/d05: zero recorded requests). Responses over 8192 bytes
//! fail closed (u06).

use crate::kotlin_text::kotlin_is_blank;
use crate::sync_codec::JsonValue;

pub const MAX_RESPONSE_BYTES: usize = 8 * 1024;

/// updateConsents body - exact key insertion order (AOSP LinkedHashMap).
pub fn update_consents_body(ai: bool, cloud: bool, image: bool) -> JsonValue {
    JsonValue::Object(vec![
        ("p_ai_context_enabled".to_string(), JsonValue::Bool(ai)),
        ("p_cloud_sync_enabled".to_string(), JsonValue::Bool(cloud)),
        ("p_image_generation_enabled".to_string(), JsonValue::Bool(image)),
    ])
}

/// "${baseUrl.trimEnd('/')}/rest/v1/rpc/$function" - Kotlin trimEnd trims ALL
/// trailing chars in the set.
pub fn rpc_url(base_url: &str, function: &str) -> String {
    format!("{}/rest/v1/rpc/{}", base_url.trim_end_matches('/'), function)
}

/// post() guards: no request is made when any of these is blank.
pub fn should_attempt(access_token: Option<&str>, base_url: &str, anon_key: &str) -> bool {
    match access_token {
        Some(t) => !kotlin_is_blank(t) && !kotlin_is_blank(base_url) && !kotlin_is_blank(anon_key),
        None => false,
    }
}

/// Bounded-response gate: contentLength > MAX or buffered bytes > MAX fails
/// closed (production checks both before parsing).
pub fn response_within_bound(content_length: i64, buffered_bytes: usize) -> bool {
    content_length <= MAX_RESPONSE_BYTES as i64 && buffered_bytes <= MAX_RESPONSE_BYTES
}

/// Raw opt(key) == true: Boolean true only. A JSON string "true" fails.
fn raw_opt_is_true(v: &JsonValue, key: &str) -> bool {
    if let JsonValue::Object(pairs) = v {
        for (k, val) in pairs {
            if k == key {
                return matches!(val, JsonValue::Bool(true));
            }
        }
    }
    false
}

/// updateConsents result: receipt.opt("success") == true.
pub fn is_update_success(receipt: &JsonValue) -> bool {
    raw_opt_is_true(receipt, "success")
}

/// deleteCloudHealthCopy result: both flags raw-opt true.
pub fn is_delete_success(receipt: &JsonValue) -> bool {
    raw_opt_is_true(receipt, "success") && raw_opt_is_true(receipt, "health_data_deleted")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sync_codec::{json_value_to_string, JsonParser};

    fn parse(s: &str) -> JsonValue {
        JsonParser::new(s).parse().unwrap()
    }

    #[test]
    fn consents_body_and_url_locked() {
        // u01: exact payload string and RPC path from the executed stub capture.
        assert_eq!(
            json_value_to_string(&update_consents_body(true, false, true)),
            r#"{"p_ai_context_enabled":true,"p_cloud_sync_enabled":false,"p_image_generation_enabled":true}"#
        );
        assert_eq!(
            rpc_url("https://probe.invalid/", "set_my_privacy_consents"),
            "https://probe.invalid/rest/v1/rpc/set_my_privacy_consents"
        );
        assert_eq!(rpc_url("https://x.co//", "f"), "https://x.co/rest/v1/rpc/f"); // trimEnd all slashes
    }

    #[test]
    fn guards_locked() {
        assert!(!should_attempt(Some("  "), "https://x", "k")); // u05
        assert!(!should_attempt(None, "https://x", "k")); // d05
        assert!(!should_attempt(Some("t"), "", "k"));
        assert!(!should_attempt(Some("t"), "https://x", " "));
        assert!(should_attempt(Some("t"), "https://x", "k"));
        assert!(!response_within_bound(8193, 0)); // u06 contentLength branch
        assert!(!response_within_bound(-1, 8193)); // buffered branch
        assert!(response_within_bound(8192, 8192));
    }

    #[test]
    fn success_checks_locked() {
        // u-rows
        assert!(is_update_success(&parse(r#"{"success":true}"#))); // u01
        assert!(!is_update_success(&parse(r#"{"success":"true"}"#))); // u02 raw opt
        assert!(!is_update_success(&parse(r#"{}"#))); // u03
        // d-rows
        assert!(is_delete_success(&parse(r#"{"success":true,"health_data_deleted":true}"#))); // d01
        assert!(!is_delete_success(&parse(r#"{"success":true}"#))); // d02
        assert!(!is_delete_success(&parse(r#"{"success":true,"health_data_deleted":"true"}"#))); // d03
        assert!(!is_delete_success(&parse(r#"{"success":false,"health_data_deleted":true}"#)));
        // d04: production wraps JSONObject(...) which throws unless the value is an object; either way the result is false.
        let d04 = JsonParser::new("not json").parse().ok().map(|v| is_delete_success(&v)).unwrap_or(false);
        assert!(!d04);
    }
}

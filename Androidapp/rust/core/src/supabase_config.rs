//! Supabase client config validation (data/supabase/SupabaseConfig.kt,
//! main@31b88f5). "Validates the public client pair without ever falling
//! back to production": blank/blank is the intentional production default;
//! any non-empty-but-invalid setting DISABLES the client rather than
//! silently redirecting to prod.
//!
//! All tables executed on the portable JVM against the compiled AOSP
//! Base64/JSONObject classes (ConfigProbe + ConfigProbe2, 2026-11-03) and
//! locked in supabase_config_corpus. PROJECT_REF/DEFAULT_URL/
//! DEFAULT_PUBLISHABLE_KEY are the app's committed PUBLIC client pair
//! (anon key), not secrets.
//!
//! legacyPayload decode flags: URL_SAFE | NO_WRAP (PADDING ALLOWED) -
//! deliberately different from the session token's NO_PADDING combo.

use crate::kotlin_text::{kotlin_is_blank, kotlin_trim};

pub const PROJECT_REF: &str = "apjqcocxtylsofgxhetb";
pub const DEFAULT_URL: &str = "https://apjqcocxtylsofgxhetb.supabase.co";
pub const DEFAULT_PUBLISHABLE_KEY: &str = "sb_publishable_ziAlyPmKSv7kQZjbpbIuwA_6er-NufM";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClientSettings {
    pub url: String,
    pub api_key: String,
}

impl ClientSettings {
    pub fn is_configured(&self) -> bool {
        !kotlin_is_blank(&self.url) && !kotlin_is_blank(&self.api_key)
    }
    pub fn rest_url(&self) -> String {
        format!("{}/rest/v1", self.url)
    }
    pub fn auth_url(&self) -> String {
        format!("{}/auth/v1", self.url)
    }
}

fn disabled() -> ClientSettings {
    ClientSettings { url: String::new(), api_key: String::new() }
}

/// isTemplatePlaceholder: trim + lowercase, then the five verbatim checks.
pub fn is_template_placeholder(value: &str) -> bool {
    let v = kotlin_trim(value).to_lowercase();
    v.contains("your_project_ref")
        || v.contains("your-project-ref")
        || v.starts_with("replace_with")
        || v.starts_with("replace-with")
        || v.contains("placeholder")
        || v.contains("_here")
}

/// parseEndpoint: three local constants matched case-INSENSITIVELY and
/// returned as the canonical constant; otherwise a case-SENSITIVE
/// "https://" prefix requirement, full-match host regex, lowercase output.
pub fn parse_endpoint(value: &str) -> Option<String> {
    const LOCAL: [&str; 3] = [
        "http://localhost:54321",
        "http://127.0.0.1:54321",
        "http://10.0.2.2:54321",
    ];
    for l in LOCAL {
        if l.eq_ignore_ascii_case(value) {
            return Some(l.to_string());
        }
    }
    let host = value.strip_prefix("https://")?;
    if !host_is_supabase(host) {
        return None;
    }
    Some(format!("https://{}", host.to_lowercase()))
}

/// Regex [a-z0-9][a-z0-9-]*\.supabase\.co IGNORE_CASE, FULL match.
fn host_is_supabase(host: &str) -> bool {
    let h = host.as_bytes();
    if h.len() < 2 || !h[0].is_ascii_alphanumeric() {
        return false;
    }
    let suffix = b".supabase.co";
    if h.len() <= suffix.len() || !host[host.len() - suffix.len()..].eq_ignore_ascii_case(".supabase.co") {
        return false;
    }
    let label = &h[..h.len() - suffix.len()];
    !label.is_empty()
        && label[0].is_ascii_alphanumeric()
        && label.iter().all(|b| b.is_ascii_alphanumeric() || *b == b'-')
        && h[0].is_ascii_alphanumeric()
}

/// legacyPayload: payload segment Base64-decoded (AOSP decoder, padding
/// allowed), lossy UTF-8, AOSP JSONObject parse; None on any failure.
fn legacy_payload(value: &str) -> Option<crate::sync_codec::JsonValue> {
    let payload = value.split('.').nth(1)?;
    let bytes = crate::session_token::b64url_decode(payload)?;
    let text = String::from_utf8_lossy(&bytes);
    crate::sync_codec::JsonParser::new(&text).parse().ok()
}

/// optString(key, "") on the payload via the central AOSP codec (explicit
/// JSON null -> literal "null").
fn payload_opt_string(payload: &crate::sync_codec::JsonValue, key: &str) -> String {
    crate::sync_codec::aosp_opt_string(payload, key, "")
}

/// parsePublicApiKey: placeholder/secret rejection, sb_publishable_ with
/// [A-Za-z0-9_-]{20,} suffix, or a legacy 3-segment JWT whose payload
/// role == "anon".
pub fn parse_public_api_key(value: Option<&str>) -> Option<String> {
    let candidate = kotlin_trim(value.unwrap_or("")).to_string();
    let lc = candidate.to_lowercase();
    if candidate.is_empty()
        || lc.contains("replace")
        || lc.contains("placeholder")
        || lc.contains("your-")
        || lc.contains("your_")
        || lc.contains("_here")
        || lc.starts_with("sb_secret_")
        || lc.starts_with("service_role")
    {
        return None;
    }
    if let Some(suffix) = candidate.strip_prefix("sb_publishable_") {
        let ok = suffix.len() >= 20
            && suffix.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-');
        return if ok { Some(candidate) } else { None };
    }
    let segments: Vec<&str> = candidate.split('.').collect();
    if !candidate.starts_with("eyJ") || segments.len() != 3 || segments.iter().any(|s| s.is_empty()) {
        return None;
    }
    match legacy_payload(&candidate) {
        Some(p) if payload_opt_string(&p, "role") == "anon" => Some(candidate),
        _ => None,
    }
}

/// resolveClientSettings: the full decision chain, verbatim order.
pub fn resolve_client_settings(url: Option<&str>, api_key: Option<&str>) -> ClientSettings {
    let raw_url = kotlin_trim(url.unwrap_or("")).strip_suffix('/').map(|s| s.to_string())
        .unwrap_or_else(|| kotlin_trim(url.unwrap_or("")).to_string());
    let raw_key = kotlin_trim(api_key.unwrap_or("")).to_string();
    let candidate_url = if is_template_placeholder(&raw_url) { String::new() } else { raw_url };
    let candidate_key = if is_template_placeholder(&raw_key) { String::new() } else { raw_key };
    if kotlin_is_blank(&candidate_url) && kotlin_is_blank(&candidate_key) {
        return ClientSettings {
            url: DEFAULT_URL.to_string(),
            api_key: DEFAULT_PUBLISHABLE_KEY.to_string(),
        };
    }
    let parsed_key = match parse_public_api_key(Some(&candidate_key)) {
        Some(k) => k,
        None => return disabled(),
    };
    let endpoint = match parse_endpoint(&candidate_url) {
        Some(e) => e,
        None => return disabled(),
    };
    // The production project's key is never accepted for another endpoint.
    if endpoint != DEFAULT_URL && parsed_key == DEFAULT_PUBLISHABLE_KEY {
        return disabled();
    }
    // Legacy JWTs expose a project ref; it must match the endpoint.
    if parsed_key.starts_with("eyJ") {
        let project_ref = legacy_payload(&parsed_key)
            .map(|p| payload_opt_string(&p, "ref"))
            .unwrap_or_default();
        if !kotlin_is_blank(&project_ref) && endpoint != format!("https://{}.supabase.co", project_ref) {
            return disabled();
        }
    }
    ClientSettings { url: endpoint, api_key: parsed_key }
}

/// resolvePublicApiKey: safe public-key default helper.
pub fn resolve_public_api_key(value: Option<&str>) -> String {
    parse_public_api_key(value).unwrap_or_else(|| DEFAULT_PUBLISHABLE_KEY.to_string())
}

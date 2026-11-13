//! Community Guides transport contract (CommunityGuidesService.kt +
//! CommunityPulsePinger, data/economy/CommunityGuidesService.kt,
//! main@31b88f5). Pure request/response SHAPES: no I/O, no credentials; an
//! adapter supplies base URL, anon key and the resolved bearer. All decision
//! expressions JVM-executed (TransportProbe.kt, kotlinc 2.1.0 / JRE 21,
//! 2026-11-02) and locked in community_transport_corpus.
//!
//! WIRE NOTES:
//! - org.json key ORDER differs between flavors (Maven jar: HashMap order;
//!   production AOSP: insertion order). This contract fixes payload CONTENT;
//!   key order is not semantically load-bearing for PostgREST.
//! - boundedBody caps responses at MAX_RESPONSE_BYTES (96 KiB); over-limit
//!   yields null which fetchTopics maps to UNAVAILABLE and message paths map
//!   to empty/null.
//! - postMessage parse failure after a successful response yields a SOFT
//!   LOCAL ECHO (isSelf=true, no remoteId): "the write may still have
//!   landed" - a confirmed-success ambiguity ported as an explicit outcome.
//! - Timeouts are adapter obligations: 8s connect/read/write, 10s call, no
//!   redirects (pulse: 6s connect, 20s read, 25s call). The bearer trim
//!   (Bearer ${token.trim()}) is likewise an adapter obligation; descriptors
//!   carry no credentials.
//! - bounded_body here is a LENGTH-ONLY equivalent for buffered bodies; the
//!   source streams source.request(MAX+1) and rejects when the buffer
//!   exceeds MAX. A streaming adapter must enforce the same cap mid-stream,
//!   not read-then-measure.
//! - SOURCE-PARITY CONCERN: fetchMessages interpolates topic_id RAW into the
//!   query string (no percent-encoding). A topic id containing '&' or '='
//!   would alter the query. Parity is preserved in fetch_messages_request;
//!   the proposed safe variant (percent-encode) is kept separate below and
//!   NOT wired in pending a decision.

/// MAX_RESPONSE_BYTES = 96 * 1024.
pub const MAX_RESPONSE_BYTES: usize = 96 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TransportRequest {
    pub method: &'static str,
    /// Path+query relative to SupabaseConfig.restUrl (or url for functions).
    pub path: String,
    /// Extra headers beyond apikey/Authorization/Accept, in source order.
    pub headers: Vec<(&'static str, String)>,
    pub body: Option<String>,
}

/// currentUserId: userId?.trim()?.takeIf { it.isNotEmpty() } (Kotlin trim
/// semantics: kotlin_trim, NBSP included - locked in kotlin_text corpus).
pub fn current_user_id(user_id: Option<&str>) -> Option<String> {
    let t = crate::kotlin_text::kotlin_trim(user_id?);
    if t.is_empty() {
        None
    } else {
        Some(t.to_string())
    }
}

/// GET chat_topics?select=id,title,description&is_active=eq.true&order=created_at.asc&limit=20
pub fn fetch_topics_request() -> TransportRequest {
    TransportRequest {
        method: "GET",
        path: "/chat_topics?select=id,title,description&is_active=eq.true&order=created_at.asc&limit=20".to_string(),
        headers: vec![],
        body: None,
    }
}

/// POST chat_memberships {user_id, topic_id}, Prefer: resolution=ignore-duplicates.
/// Payload key content fixed (org.json insertion order on AOSP).
pub fn ensure_membership_request(user_id: &str, topic_id: &str) -> TransportRequest {
    TransportRequest {
        method: "POST",
        path: "/chat_memberships".to_string(),
        headers: vec![
            ("Prefer", "resolution=ignore-duplicates".to_string()),
            ("Content-Type", "application/json".to_string()),
        ],
        body: Some(format!(
            "{{\"user_id\":{},\"topic_id\":{}}}",
            serde_json::to_string(user_id).unwrap_or_else(|_| "\"\"".to_string()),
            serde_json::to_string(topic_id).unwrap_or_else(|_| "\"\"".to_string())
        )),
    }
}

/// ensureMembership result: isSuccessful || code == 409 (idempotent join).
pub fn membership_success(status_code: u16) -> bool {
    (200..300).contains(&status_code) || status_code == 409
}

/// GET chat_messages?select=id,user_id,persona_id,sender_alias,content,created_at,is_flagged&topic_id=eq.<id>&order=created_at.asc&limit=100
pub fn fetch_messages_request(topic_id: &str) -> TransportRequest {
    TransportRequest {
        method: "GET",
        path: format!(
            "/chat_messages?select=id,user_id,persona_id,sender_alias,content,created_at,is_flagged&topic_id=eq.{}&order=created_at.asc&limit=100",
            topic_id
        ),
        headers: vec![],
        body: None,
    }
}

/// postMessage validation: trimmed must be non-empty and <= 500 UTF-16
/// units (JVM-locked: 500 ok, 501 rejected, 250 emoji = 500 units ok).
pub fn post_content_allowed(trimmed: &str) -> bool {
    !trimmed.is_empty() && trimmed.encode_utf16().count() <= 500
}

/// POST chat_messages {topic_id, user_id, sender_alias, content},
/// Prefer: return=representation.
pub fn post_message_request(
    topic_id: &str,
    user_id: &str,
    sender_alias: &str,
    content_trimmed: &str,
) -> TransportRequest {
    TransportRequest {
        method: "POST",
        path: "/chat_messages".to_string(),
        headers: vec![
            ("Prefer", "return=representation".to_string()),
            ("Content-Type", "application/json".to_string()),
        ],
        body: Some(format!(
            "{{\"topic_id\":{},\"user_id\":{},\"sender_alias\":{},\"content\":{}}}",
            serde_json::to_string(topic_id).unwrap_or_else(|_| "\"\"".to_string()),
            serde_json::to_string(user_id).unwrap_or_else(|_| "\"\"".to_string()),
            serde_json::to_string(sender_alias).unwrap_or_else(|_| "\"\"".to_string()),
            serde_json::to_string(content_trimmed).unwrap_or_else(|_| "\"\"".to_string())
        )),
    }
}

/// Outcome of interpreting a postMessage response (the ambiguity is ported,
/// not smoothed over).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PostOutcome {
    /// 2xx with a parseable representation row: confirmed remote id/time.
    Confirmed { remote_id: Option<String>, timestamp_ms: i64 },
    /// 2xx but unparseable/empty body: the write may still have landed.
    /// Source returns a soft local echo (isSelf=true, no remoteId).
    SoftEchoUnconfirmed,
    /// Transport failure or non-2xx: caller falls back (local-only path).
    Failed,
}

/// POST rpc/flag_chat_message {p_message_id}.
pub fn flag_message_request(remote_message_id: &str) -> TransportRequest {
    TransportRequest {
        method: "POST",
        path: "/rpc/flag_chat_message".to_string(),
        headers: vec![("Content-Type", "application/json".to_string())],
        body: Some(format!(
            "{{\"p_message_id\":{}}}",
            serde_json::to_string(remote_message_id).unwrap_or_else(|_| "\"\"".to_string())
        )),
    }
}

/// flagMessage result: isSuccessful && body.trim().equals("true", IGNORE CASE).
pub fn flag_success(status_code: u16, body: &str) -> bool {
    (200..300).contains(&status_code) && body.trim().eq_ignore_ascii_case("true")
}

/// CommunityPulsePinger bearer: accessToken?.trim().takeUnless{isNullOrEmpty}
/// ?: anonKey.
pub fn pulse_bearer<'a>(access_token: Option<&'a str>, anon_key: &'a str) -> &'a str {
    match access_token.map(crate::kotlin_text::kotlin_trim) {
        Some(t) if !t.is_empty() => t,
        _ => anon_key,
    }
}

/// POST {SupabaseConfig.url}/functions/v1/community-pulse with body "{}".
pub fn pulse_request() -> TransportRequest {
    TransportRequest {
        method: "POST",
        path: "/functions/v1/community-pulse".to_string(),
        headers: vec![("Content-Type", "application/json".to_string())],
        body: Some("{}".to_string()),
    }
}

/// boundedBody: over MAX_RESPONSE_BYTES -> None (callers map to their
/// failure state). Mirrors source.request(max+1) + buffer.size check.
pub fn bounded_body(len: usize) -> Option<()> {
    if len > MAX_RESPONSE_BYTES {
        None
    } else {
        Some(())
    }
}

/// PROPOSED SAFE VARIANT (not source parity, not wired): percent-encode the
/// topic id in the messages query. Source parity interpolates raw.
pub fn fetch_messages_request_encoded(topic_id: &str) -> TransportRequest {
    let mut enc = String::new();
    for b in topic_id.bytes() {
        if b.is_ascii_alphanumeric() || b"-._~".contains(&b) {
            enc.push(b as char);
        } else {
            enc.push_str(&format!("%{:02X}", b));
        }
    }
    TransportRequest {
        method: "GET",
        path: format!(
            "/chat_messages?select=id,user_id,persona_id,sender_alias,content,created_at,is_flagged&topic_id=eq.{}&order=created_at.asc&limit=100",
            enc
        ),
        headers: vec![],
        body: None,
    }
}

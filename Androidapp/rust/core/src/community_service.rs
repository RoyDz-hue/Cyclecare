//! Community Guides service adapter: the real composition seam between
//! session token resolution (session_token), transport request shapes
//! (community_transport) and response parsing (community_guides).
//! CommunityGuidesService.kt + CommunityPulsePinger, main@31b88f5.
//!
//! Transport and refresh are INJECTED traits/closures: no I/O, no sockets,
//! no credentials in this module. Integration tests drive it with synthetic
//! responses. An Android/host adapter supplies the real HTTP client and the
//! Supabase config (anon key/base URLs flow from the app's own BuildConfig
//! route, never from user-pasted secrets).
//!
//! HONESTY INVARIANTS (ported, not smoothed):
//! - A post whose 2xx body won't parse is SoftEchoUnconfirmed: never counted
//!   as a confirmed server post and never silently retried (no idempotency
//!   token; a retry could double-post).
//! - fetchMessages degrades every failure to an EMPTY list (no status).
//! - All pulse failures are swallowed to false.

use crate::community_guides::{
    parse_circle_messages, parse_topics, soft_alias_for, CircleMessage, CircleStatus, ServerTopic,
};
use crate::community_transport as t;
use crate::lifecycle::SessionStore;
use crate::session_token::{resolve_access_token, AccessTokenResolution, RefreshResponse};

/// Supabase client config supplied by the platform adapter (from the app's
/// own compiled config route; never user-pasted).
pub struct CircleServiceConfig {
    pub rest_url: String,
    pub functions_url: String,
    pub anon_key: String,
    /// SupabaseConfig.isConfigured (anon key + URL present).
    pub configured: bool,
}

/// Injected HTTP outcome for one request.
pub enum HttpOutcome {
    Response { status: u16, body: Vec<u8> },
    IoError,
    OtherError,
}

/// Injected HTTP executor. `full_url` = base + request.path; `bearer` is the
/// resolved (trimmed) token; the adapter must add apikey/Authorization/
/// Accept headers exactly as authedRequest does.
pub trait HttpTransport {
    fn execute(&mut self, full_url: &str, req: &t::TransportRequest, bearer: &str) -> HttpOutcome;
}

/// boundedBody + Java UTF-8 decode: over-limit -> None (callers map to their
/// failure state); malformed bytes decode with the REPLACEMENT CHAR
/// (Java String(bytes, UTF_8) semantics), never a hard failure.
fn body_to_string(body: Vec<u8>) -> Option<String> {
    if body.len() > t::MAX_RESPONSE_BYTES {
        return None;
    }
    Some(String::from_utf8_lossy(&body).into_owned())
}

/// fetchTopicsWithStatus: real status chain, verbatim branch order.
pub fn fetch_topics_with_status(
    store: &mut SessionStore,
    now_secs: i64,
    refresh: &dyn Fn(&str) -> Result<RefreshResponse, ()>,
    config: &CircleServiceConfig,
    http: &mut dyn HttpTransport,
) -> (Vec<ServerTopic>, CircleStatus) {
    let token = match resolve_access_token(store, now_secs, refresh) {
        AccessTokenResolution::Valid(tok) => tok,
        AccessTokenResolution::Missing | AccessTokenResolution::ReauthenticationRequired => {
            return (vec![], CircleStatus::SignInUnconfirmed)
        }
        AccessTokenResolution::TemporarilyUnavailable => return (vec![], CircleStatus::Unavailable),
    };
    let req = t::fetch_topics_request();
    let url = format!("{}{}", config.rest_url, req.path);
    let outcome = http.execute(&url, &req, token.trim());
    let (status, body) = match outcome {
        HttpOutcome::IoError => return (vec![], CircleStatus::Network),
        HttpOutcome::OtherError => return (vec![], CircleStatus::Unavailable),
        HttpOutcome::Response { status, body } => (status, body),
    };
    if status == 401 || status == 403 {
        return (vec![], CircleStatus::SignInUnconfirmed);
    }
    if !(200..300).contains(&status) {
        return (vec![], CircleStatus::Unavailable);
    }
    let raw = match body_to_string(body) {
        Some(r) => r,
        None => return (vec![], CircleStatus::Unavailable),
    };
    match parse_topics(&raw) {
        None => (vec![], CircleStatus::Unavailable),
        Some(topics) if topics.is_empty() => (topics, CircleStatus::Empty),
        Some(topics) => (topics, CircleStatus::Ok),
    }
}

/// ensureMembership: idempotent join; failure non-fatal (false).
pub fn ensure_membership(
    store: &mut SessionStore,
    now_secs: i64,
    refresh: &dyn Fn(&str) -> Result<RefreshResponse, ()>,
    config: &CircleServiceConfig,
    http: &mut dyn HttpTransport,
    topic_id: &str,
) -> bool {
    let user_id = match t::current_user_id(store.user_id()) {
        Some(u) => u,
        None => return false,
    };
    let token = match resolve_access_token(store, now_secs, refresh) {
        AccessTokenResolution::Valid(tok) => tok,
        _ => return false,
    };
    let req = t::ensure_membership_request(&user_id, topic_id);
    let url = format!("{}{}", config.rest_url, req.path);
    match http.execute(&url, &req, token.trim()) {
        HttpOutcome::Response { status, .. } => t::membership_success(status),
        _ => false,
    }
}

/// fetchMessages: every failure degrades to an empty list.
pub fn fetch_messages(
    store: &mut SessionStore,
    now_secs: i64,
    refresh: &dyn Fn(&str) -> Result<RefreshResponse, ()>,
    config: &CircleServiceConfig,
    http: &mut dyn HttpTransport,
    topic_id: &str,
) -> Vec<CircleMessage> {
    let self_id = t::current_user_id(store.user_id());
    let token = match resolve_access_token(store, now_secs, refresh) {
        AccessTokenResolution::Valid(tok) => tok,
        _ => return vec![],
    };
    let req = t::fetch_messages_request(topic_id);
    let url = format!("{}{}", config.rest_url, req.path);
    match http.execute(&url, &req, token.trim()) {
        HttpOutcome::Response { status, body } if (200..300).contains(&status) => {
            match body_to_string(body).as_deref().map(|r| parse_circle_messages(r, topic_id, self_id.as_deref())) {
                Some(Some(msgs)) => msgs,
                _ => vec![],
            }
        }
        _ => vec![],
    }
}

pub struct PostResult {
    pub outcome: t::PostOutcome,
    /// The echo/confirmed message when the source would surface one.
    pub message: Option<CircleMessage>,
}

/// postMessage: trim/validate, post, interpret. Source boundaries
/// (CommunityGuidesService.kt:182-215), verbatim:
/// - oversize boundedBody -> null -> FAILED (NOT echo).
/// - JSONArray(raw) THROWS (malformed, or valid non-array like "{}") ->
///   catch -> SOFT ECHO ("the write may still have landed"): unconfirmed,
///   echo carries host now_ms, isSelf, no remoteId.
/// - JSONArray ok but optJSONObject(0) null (empty array, or first element
///   not an object) -> null -> FAILED.
/// - Object row -> CONFIRMED with AOSP optString semantics (numeric id 42
///   -> "42"; missing/null/blank -> None; created_at unparseable -> 0).
/// now_ms: host wall clock for the soft echo's timestampMs default
/// (System.currentTimeMillis() in source); NOT used on the confirmed path.
pub fn post_message(
    store: &mut SessionStore,
    now_secs: i64,
    now_ms: i64,
    refresh: &dyn Fn(&str) -> Result<RefreshResponse, ()>,
    config: &CircleServiceConfig,
    http: &mut dyn HttpTransport,
    topic_id: &str,
    content: &str,
) -> PostResult {
    let fail = |outcome: t::PostOutcome, message| PostResult { outcome, message };
    let user_id = match t::current_user_id(store.user_id()) {
        Some(u) => u,
        None => return fail(t::PostOutcome::Failed, None),
    };
    let trimmed = crate::kotlin_text::kotlin_trim(content).to_string();
    if !t::post_content_allowed(&trimmed) {
        return fail(t::PostOutcome::Failed, None);
    }
    let alias = soft_alias_for(&user_id);
    let token = match resolve_access_token(store, now_secs, refresh) {
        AccessTokenResolution::Valid(tok) => tok,
        _ => return fail(t::PostOutcome::Failed, None),
    };
    let req = t::post_message_request(topic_id, &user_id, &alias, &trimmed);
    let url = format!("{}{}", config.rest_url, req.path);
    let (status, body) = match http.execute(&url, &req, token.trim()) {
        HttpOutcome::Response { status, body } => (status, body),
        _ => return fail(t::PostOutcome::Failed, None),
    };
    if !(200..300).contains(&status) {
        return fail(t::PostOutcome::Failed, None);
    }
    // boundedBody null (oversize) -> source returns null: FAILED, not echo.
    let raw = match body_to_string(body) {
        Some(r) => r,
        None => return fail(t::PostOutcome::Failed, None),
    };
    // Source boundaries (CommunityGuidesService.kt:182-215), executed against
    // the compiled AOSP14 org.json classes (PostProbe, 2026-11-03):
    // - JSONArray(raw) THROWS (malformed, or valid non-array like "{}") ->
    //   catch -> SOFT ECHO ("the write may still have landed").
    // - JSONArray ok but optJSONObject(0) null (empty array, or first element
    //   not an object) -> return null -> FAILED.
    // - Object row: AOSP optString - numeric 42 -> "42", boolean -> "true",
    //   EXPLICIT JSON NULL -> literal "null" (accepted as remoteId "null"!
    //   parity quirk, safety-review labeled), missing/blank -> None.
    match crate::sync_codec::JsonParser::new(&raw).parse() {
        Err(_) => soft_echo(topic_id, alias, trimmed, now_ms),
        Ok(v) => match &v {
            crate::sync_codec::JsonValue::Array(rows) => match rows.first() {
                Some(row @ crate::sync_codec::JsonValue::Object(_)) => {
                    let id_raw = crate::kotlin_text::kotlin_trim(
                        &crate::sync_codec::aosp_opt_string(row, "id", ""),
                    ).to_string();
                    let remote_id = if id_raw.is_empty() { None } else { Some(id_raw) };
                    let created_raw = crate::sync_codec::aosp_opt_string(row, "created_at", "");
                    let timestamp_ms = crate::notifications::parse_iso_timestamp(Some(&created_raw));
                    PostResult {
                        outcome: t::PostOutcome::Confirmed { remote_id: remote_id.clone(), timestamp_ms },
                        message: Some(CircleMessage {
                            channel_id: topic_id.to_string(),
                            sender_alias: alias,
                            content: trimmed,
                            timestamp_ms,
                            is_self: true,
                            persona_id: None,
                            remote_id,
                            is_flagged: false,
                        }),
                    }
                }
                _ => PostResult { outcome: t::PostOutcome::Failed, message: None },
            },
            _ => soft_echo(topic_id, alias, trimmed, now_ms),
        },
    }
}

fn soft_echo(topic_id: &str, alias: String, trimmed: String, now_ms: i64) -> PostResult {
    PostResult {
        outcome: t::PostOutcome::SoftEchoUnconfirmed,
        message: Some(CircleMessage {
            channel_id: topic_id.to_string(),
            sender_alias: alias,
            content: trimmed,
            timestamp_ms: now_ms,
            is_self: true,
            persona_id: None,
            remote_id: None,
            is_flagged: false,
        }),
    }
}

/// flagMessage: true only on 2xx + body trim equalsIgnoreCase "true".
pub fn flag_message(
    store: &mut SessionStore,
    now_secs: i64,
    refresh: &dyn Fn(&str) -> Result<RefreshResponse, ()>,
    config: &CircleServiceConfig,
    http: &mut dyn HttpTransport,
    remote_message_id: &str,
) -> bool {
    let token = match resolve_access_token(store, now_secs, refresh) {
        AccessTokenResolution::Valid(tok) => tok,
        _ => return false,
    };
    let req = t::flag_message_request(remote_message_id);
    let url = format!("{}{}", config.rest_url, req.path);
    match http.execute(&url, &req, token.trim()) {
        HttpOutcome::Response { status, body } => match body_to_string(body) {
            Some(b) => t::flag_success(status, &b),
            None => false,
        },
        _ => false,
    }
}

/// CommunityPulsePinger.ping: isConfigured gate; bearer falls back to anon
/// key; every failure swallowed to false.
pub fn community_pulse(
    access_token: Option<&str>,
    config: &CircleServiceConfig,
    http: &mut dyn HttpTransport,
) -> bool {
    if !config.configured {
        return false;
    }
    let req = t::pulse_request();
    let url = format!("{}{}", config.functions_url, req.path);
    let bearer = t::pulse_bearer(access_token, &config.anon_key).to_string();
    match http.execute(&url, &req, &bearer) {
        HttpOutcome::Response { status, .. } => (200..300).contains(&status),
        _ => false,
    }
}

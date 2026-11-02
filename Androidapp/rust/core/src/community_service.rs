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

// ============================ PROPOSED hardened seam ============================
// main 01:44 review: NOT wired anywhere; the parity functions above are
// unchanged. Adds, over the parity path:
//   1. validated configured ABSOLUTE origin (supabase_config::validated_origin)
//      - a disabled/relative seam is refused BEFORE any transport call;
//   2. owner re-binding on the resolved token: validTokenForOwner structure
//      (sub == store owner, exp future) re-checked even when the fast path
//      returned the opaque stored token (parity gap recorded in
//      owner_bound_adapter::fast_path_returns_stored_token_without_sub_recheck_is_parity);
//   3. explicit refusal on unavailable (insecure) or ownerless/corrupt store;
//   4. per-request origin re-validation: the bearer is only handed to the
//      transport when the exact request URL structurally matches the
//      validated origin, so a redirect/origin change cannot receive it.
// ADAPTER OBLIGATION for any real transport: disable redirect following or
// re-run EndpointOrigin::allows on every hop before attaching credentials.

use crate::supabase_config::{validated_origin, EndpointOrigin};

/// Hardened fetchTopicsWithStatus proposal. Same response interpretation;
/// stricter preconditions. `store` is refused when insecure or when the
/// owner identity is absent/blank.
pub fn hardened_fetch_topics_with_status(
    store: &mut SessionStore,
    now_secs: i64,
    refresh: &dyn Fn(&str) -> Result<RefreshResponse, ()>,
    settings: &crate::supabase_config::ClientSettings,
    http: &mut dyn HttpTransport,
) -> (Vec<crate::community_guides::ServerTopic>, CircleStatus) {
    // (3) unavailable/corrupt store refusal, before anything else.
    if !store.is_secure_storage_available() {
        return (vec![], CircleStatus::SignInUnconfirmed);
    }
    let owner = match store.user_id() {
        Some(u) if !crate::kotlin_text::kotlin_is_blank(u) => u.to_string(),
        _ => return (vec![], CircleStatus::SignInUnconfirmed),
    };
    // (1) validated configured absolute origin.
    let origin = match validated_origin(settings) {
        Some(o) => o,
        None => return (vec![], CircleStatus::Unavailable),
    };
    // (2) owner-bound resolution: parity resolve, then a structural
    // owner-binding re-check on whatever token came back (fast-path
    // included).
    let token = match resolve_access_token(store, now_secs, refresh) {
        AccessTokenResolution::Valid(t) => t,
        AccessTokenResolution::Missing | AccessTokenResolution::ReauthenticationRequired => {
            return (vec![], CircleStatus::SignInUnconfirmed)
        }
        AccessTokenResolution::TemporarilyUnavailable => return (vec![], CircleStatus::Unavailable),
    };
    if !crate::session_token::valid_token_for_owner(&token, &owner, now_secs) {
        return (vec![], CircleStatus::SignInUnconfirmed);
    }
    // (4) build from the validated origin and re-validate the exact URL
    // before the bearer leaves the process.
    let req = t::fetch_topics_request();
    let url = format!("{}{}", origin.rest_url(), req.path);
    if !origin_allows(&origin, &url) {
        return (vec![], CircleStatus::Unavailable);
    }
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
    match crate::community_guides::parse_topics(&raw) {
        None => (vec![], CircleStatus::Unavailable),
        Some(topics) if topics.is_empty() => (topics, CircleStatus::Empty),
        Some(topics) => (topics, CircleStatus::Ok),
    }
}

fn origin_allows(origin: &EndpointOrigin, absolute_url: &str) -> bool {
    origin.allows(absolute_url)
}

// ============ PROPOSED shared hardened owner-effect boundary ============
// main 01:48 review: NOT wired; parity functions and the reviewed
// hardened_fetch_topics_with_status above are unchanged. Shared boundary
// for ALL owner effects (membership, messages, post, flag, pulse):
//   - begin: insecure/ownerless store refusal, validated absolute origin,
//     parity resolve + owner re-binding (validTokenForOwner structure,
//     exp > now per source; the fast path's 90s margin is a resolve-level
//     distinction, not claimed here);
//   - pre-dispatch AND pre-apply owner+generation re-checks: an async
//     transport can span a sign-out or account switch;
//   - post-dispatch drift on a WRITE yields unconfirmed (never Confirmed):
//     the write may still have landed;
//   - every URL is built from the validated origin and re-checked
//     structurally before the bearer leaves (defense-in-depth; the REAL
//     redirect protection remains the transport adapter's obligation:
//     disable redirect following or re-validate every hop).
// HardenedTransport receives the store during execute: production adapters
// ignore it; tests inject mid-flight session changes through it.

pub trait HardenedTransport {
    fn execute(
        &mut self,
        store: &mut SessionStore,
        full_url: &str,
        req: &t::TransportRequest,
        bearer: &str,
    ) -> HttpOutcome;
}

/// A begun owner-effect boundary: validated origin, owner-bound token,
/// captured (owner, generation) for drift checks.
pub struct HardenedOwnerContext {
    pub owner: String,
    pub generation: i64,
    pub origin: EndpointOrigin,
    pub token: String,
}

pub enum HardenedBegin {
    Ready(HardenedOwnerContext),
    SignInUnconfirmed,
    Unavailable,
}

pub fn hardened_begin(
    store: &mut SessionStore,
    now_secs: i64,
    refresh: &dyn Fn(&str) -> Result<RefreshResponse, ()>,
    settings: &crate::supabase_config::ClientSettings,
) -> HardenedBegin {
    if !store.is_secure_storage_available() {
        return HardenedBegin::SignInUnconfirmed;
    }
    let owner = match store.user_id() {
        Some(u) if !crate::kotlin_text::kotlin_is_blank(u) => u.to_string(),
        _ => return HardenedBegin::SignInUnconfirmed,
    };
    let origin = match validated_origin(settings) {
        Some(o) => o,
        None => return HardenedBegin::Unavailable,
    };
    let token = match resolve_access_token(store, now_secs, refresh) {
        AccessTokenResolution::Valid(t) => t,
        AccessTokenResolution::Missing | AccessTokenResolution::ReauthenticationRequired => {
            return HardenedBegin::SignInUnconfirmed
        }
        AccessTokenResolution::TemporarilyUnavailable => return HardenedBegin::Unavailable,
    };
    if !crate::session_token::valid_token_for_owner(&token, &owner, now_secs) {
        return HardenedBegin::SignInUnconfirmed;
    }
    let generation = store.session_generation();
    HardenedBegin::Ready(HardenedOwnerContext { owner, generation, origin, token })
}

/// Owner+generation re-check (withCurrentSession parity): true only when
/// the store still holds the captured owner at the captured generation.
pub fn hardened_session_intact(store: &mut SessionStore, ctx: &HardenedOwnerContext) -> bool {
    store.with_current_session(&ctx.owner, ctx.generation, |_| true) == Some(true)
}

/// Build + structurally re-validate a request URL against the origin.
/// base_rest: true for /rest/v1 paths, false for function paths.
fn hardened_url(ctx: &HardenedOwnerContext, base_rest: bool, path: &str) -> Option<String> {
    let base = if base_rest { ctx.origin.rest_url() } else { ctx.origin.base() };
    let url = format!("{base}{path}");
    if ctx.origin.allows(&url) { Some(url) } else { None }
}

pub fn hardened_ensure_membership(
    store: &mut SessionStore,
    now_secs: i64,
    refresh: &dyn Fn(&str) -> Result<RefreshResponse, ()>,
    settings: &crate::supabase_config::ClientSettings,
    http: &mut dyn HardenedTransport,
    topic_id: &str,
) -> bool {
    let ctx = match hardened_begin(store, now_secs, refresh, settings) {
        HardenedBegin::Ready(c) => c,
        _ => return false,
    };
    let user_id = match t::current_user_id(Some(&ctx.owner)) { Some(u) => u, None => return false };
    let req = t::ensure_membership_request(&user_id, topic_id);
    let Some(url) = hardened_url(&ctx, true, &req.path) else { return false };
    if !hardened_session_intact(store, &ctx) { return false; }
    let outcome = http.execute(store, &url, &req, ctx.token.trim());
    if !hardened_session_intact(store, &ctx) { return false; }
    match outcome {
        HttpOutcome::Response { status, .. } => t::membership_success(status),
        _ => false,
    }
}

pub fn hardened_fetch_messages(
    store: &mut SessionStore,
    now_secs: i64,
    refresh: &dyn Fn(&str) -> Result<RefreshResponse, ()>,
    settings: &crate::supabase_config::ClientSettings,
    http: &mut dyn HardenedTransport,
    topic_id: &str,
) -> Vec<CircleMessage> {
    let ctx = match hardened_begin(store, now_secs, refresh, settings) {
        HardenedBegin::Ready(c) => c,
        _ => return vec![],
    };
    let req = t::fetch_messages_request(topic_id);
    let Some(url) = hardened_url(&ctx, true, &req.path) else { return vec![] };
    if !hardened_session_intact(store, &ctx) { return vec![]; }
    let outcome = http.execute(store, &url, &req, ctx.token.trim());
    if !hardened_session_intact(store, &ctx) { return vec![]; }
    match outcome {
        HttpOutcome::Response { status, body } if (200..300).contains(&status) => {
            match body_to_string(body).as_deref().map(|r| parse_circle_messages(r, topic_id, Some(&ctx.owner))) {
                Some(Some(msgs)) => msgs,
                _ => vec![],
            }
        }
        _ => vec![],
    }
}

/// Post drift semantics: a session change BEFORE dispatch cancels the write
/// (Failed); a change AFTER dispatch means the write may have landed ->
/// SoftEchoUnconfirmed, NEVER Confirmed.
pub fn hardened_post_message(
    store: &mut SessionStore,
    now_secs: i64,
    now_ms: i64,
    refresh: &dyn Fn(&str) -> Result<RefreshResponse, ()>,
    settings: &crate::supabase_config::ClientSettings,
    http: &mut dyn HardenedTransport,
    topic_id: &str,
    content: &str,
) -> PostResult {
    let fail = |outcome: t::PostOutcome, message| PostResult { outcome, message };
    let ctx = match hardened_begin(store, now_secs, refresh, settings) {
        HardenedBegin::Ready(c) => c,
        _ => return fail(t::PostOutcome::Failed, None),
    };
    let trimmed = crate::kotlin_text::kotlin_trim(content).to_string();
    if !t::post_content_allowed(&trimmed) {
        return fail(t::PostOutcome::Failed, None);
    }
    let alias = soft_alias_for(&ctx.owner);
    let req = t::post_message_request(topic_id, &ctx.owner, &alias, &trimmed);
    let Some(url) = hardened_url(&ctx, true, &req.path) else { return fail(t::PostOutcome::Failed, None) };
    if !hardened_session_intact(store, &ctx) { return fail(t::PostOutcome::Failed, None); }
    let outcome = http.execute(store, &url, &req, ctx.token.trim());
    if !hardened_session_intact(store, &ctx) {
        // The request already left; confirmation can no longer be trusted
        // to belong to the still-current owner session.
        return PostResult { outcome: t::PostOutcome::SoftEchoUnconfirmed, message: None };
    }
    let (status, body) = match outcome {
        HttpOutcome::Response { status, body } => (status, body),
        _ => return fail(t::PostOutcome::Failed, None),
    };
    if !(200..300).contains(&status) {
        return fail(t::PostOutcome::Failed, None);
    }
    let raw = match body_to_string(body) {
        Some(r) => r,
        None => return fail(t::PostOutcome::Failed, None),
    };
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

pub fn hardened_flag_message(
    store: &mut SessionStore,
    now_secs: i64,
    refresh: &dyn Fn(&str) -> Result<RefreshResponse, ()>,
    settings: &crate::supabase_config::ClientSettings,
    http: &mut dyn HardenedTransport,
    remote_message_id: &str,
) -> bool {
    let ctx = match hardened_begin(store, now_secs, refresh, settings) {
        HardenedBegin::Ready(c) => c,
        _ => return false,
    };
    let req = t::flag_message_request(remote_message_id);
    let Some(url) = hardened_url(&ctx, true, &req.path) else { return false };
    if !hardened_session_intact(store, &ctx) { return false; }
    let outcome = http.execute(store, &url, &req, ctx.token.trim());
    if !hardened_session_intact(store, &ctx) { return false; }
    match outcome {
        HttpOutcome::Response { status, body } => match body_to_string(body) {
            Some(b) => t::flag_success(status, &b),
            None => false,
        },
        _ => false,
    }
}

/// Hardened pulse. Token present -> full owner-effect boundary (pre-dispatch
/// check only; the pulse carries no result to apply). Token absent ->
/// anon-key pulse still requires a validated configured absolute origin
/// (the parity gate is `configured` only).
pub fn hardened_community_pulse(
    store: Option<&mut SessionStore>,
    now_secs: i64,
    refresh: &dyn Fn(&str) -> Result<RefreshResponse, ()>,
    settings: &crate::supabase_config::ClientSettings,
    http: &mut dyn HardenedTransport,
) -> bool {
    let req = t::pulse_request();
    match store {
        Some(store) => {
            let ctx = match hardened_begin(store, now_secs, refresh, settings) {
                HardenedBegin::Ready(c) => c,
                _ => return false,
            };
            let Some(url) = hardened_url(&ctx, false, &req.path) else { return false };
            if !hardened_session_intact(store, &ctx) { return false; }
            let bearer = t::pulse_bearer(Some(&ctx.token), &settings.api_key).to_string();
            matches!(http.execute(store, &url, &req, &bearer), HttpOutcome::Response { status, .. } if (200..300).contains(&status))
        }
        None => {
            let origin = match validated_origin(settings) { Some(o) => o, None => return false };
            let ctx = HardenedOwnerContext {
                owner: String::new(),
                generation: 0,
                origin,
                token: String::new(),
            };
            let Some(url) = hardened_url(&ctx, false, &req.path) else { return false };
            let bearer = t::pulse_bearer(None, &settings.api_key).to_string();
            // No store for the anonymous pulse: pass a throwaway insecure
            // store; production adapters ignore it, tests must not mutate.
            let mut scratch = SessionStore::new(false);
            matches!(http.execute(&mut scratch, &url, &req, &bearer), HttpOutcome::Response { status, .. } if (200..300).contains(&status))
        }
    }
}

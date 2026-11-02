//! CommunityGuidesService pure cores (main@31b88f5): the stable circle alias,
//! server topic/message row parsing, and the live-circle status decision
//! chain. Network/auth are seams; these are the adapter contracts the future
//! live transport must satisfy. All string handling mirrors org.json
//! (AOSP) and Kotlin semantics exactly (UTF-16 lengths, optString/optBoolean).

use crate::kotlin_text::kotlin_trim;

const ALIAS_ADJECTIVES: [&str; 10] = [
    "Gentle", "Quiet", "Warm", "Bright", "Calm", "Soft", "Golden", "Kind", "Serene", "Radiant",
];
const ALIAS_NOUNS: [&str; 10] = [
    "Dawn", "River", "Meadow", "Bloom", "Willow", "Amber", "Moon", "Star", "Breeze", "Leaf",
];

/// softAliasFor (verbatim, JVM-probed 2026-11-02): hash = fold over UTF-16
/// code units with WRAPPING i32 multiply-add, masked non-negative each step.
pub fn soft_alias_for(user_id: &str) -> String {
    let mut hash: i32 = 0;
    for unit in user_id.encode_utf16() {
        hash = (hash.wrapping_mul(31).wrapping_add(unit as i32)) & i32::MAX;
    }
    let adj = ALIAS_ADJECTIVES[(hash as usize) % ALIAS_ADJECTIVES.len()];
    let noun = ALIAS_NOUNS[((hash / ALIAS_ADJECTIVES.len() as i32) as usize) % ALIAS_NOUNS.len()];
    format!("{adj} {noun}")
}

/// ServerTopic (verbatim data shape).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServerTopic {
    pub id: String,
    pub title: String,
    pub description: String,
}

/// org.json optString: present string -> itself; present non-string -> its
/// JSON text (String.valueOf); missing/null -> "".
fn opt_string(v: Option<&serde_json::Value>) -> String {
    match v {
        Some(serde_json::Value::String(s)) => s.clone(),
        Some(serde_json::Value::Null) | None => String::new(),
        // AOSP String.valueOf for JSON numbers/booleans is their plain text
        // ("42", "true"); arrays/objects render via their JSON toString.
        Some(serde_json::Value::Bool(b)) => b.to_string(),
        Some(serde_json::Value::Number(n)) => n.to_string(),
        Some(other) => other.to_string(),
    }
}

/// fetchTopics row loop (verbatim): optString+trim id/title/description;
/// skip rows with empty id or title; non-object rows skipped (optJSONObject
/// returns null).
pub fn parse_topics(raw: &str) -> Option<Vec<ServerTopic>> {
    let arr: serde_json::Value = serde_json::from_str(raw).ok()?;
    let arr = arr.as_array()?;
    let mut out = Vec::new();
    for row in arr {
        let Some(obj) = row.as_object() else { continue };
        let id = kotlin_trim(&opt_string(obj.get("id"))).to_string();
        let title = kotlin_trim(&opt_string(obj.get("title"))).to_string();
        if id.is_empty() || title.is_empty() {
            continue;
        }
        out.push(ServerTopic {
            id,
            title,
            description: kotlin_trim(&opt_string(obj.get("description"))).to_string(),
        });
    }
    Some(out)
}

/// A fetched circle message (fetchMessages row mapping, verbatim).
#[derive(Debug, Clone, PartialEq, Eq)]
/// NOTE (GuidedChatMessage.kt): the source data class also carries a local
/// `id` defaulting to a FRESH RANDOM UUID and a `timestampMs` defaulting to
/// the wall-clock now at construction. Those are host-runtime values, not
/// wire data; the adapter assigns presentation ids, and the service's soft
/// echo takes now_ms from the host clock.
pub struct CircleMessage {
    pub channel_id: String,
    pub sender_alias: String,
    pub content: String,
    pub timestamp_ms: i64,
    pub is_self: bool,
    pub persona_id: Option<String>,
    pub remote_id: Option<String>,
    pub is_flagged: bool,
}

/// Kotlin String.take(500): at most 500 UTF-16 code units, surrogate-safe.
fn take_500_utf16(s: &str) -> String {
    crate::kotlin_text::take_utf16(s, 500)
}

/// org.json optBoolean(v, false): Boolean true, or string "true"
/// (case-insensitive per Boolean.parseBoolean); everything else false.
fn opt_boolean_false(v: Option<&serde_json::Value>) -> bool {
    match v {
        Some(serde_json::Value::Bool(b)) => *b,
        Some(serde_json::Value::String(s)) => s.eq_ignore_ascii_case("true"),
        _ => false,
    }
}

/// fetchMessages row loop (verbatim): skip empty trimmed content; persona_id
/// lowercase-trim-ifBlank; alias fallback "Community Guide"/"Member";
/// content take(500); isSelf = case-sensitive id equality; is_flagged
/// optBoolean false default. Malformed payload -> None (service returns
/// emptyList, caller distinction lives in the transport seam).
/// channel_id populates the source's channelId = serverTopicId; timestamp_ms
/// is parse_iso_timestamp(created_at) (unparseable -> 0, sorts oldest).
pub fn parse_circle_messages(raw: &str, channel_id: &str, self_id: Option<&str>) -> Option<Vec<CircleMessage>> {
    let arr: serde_json::Value = serde_json::from_str(raw).ok()?;
    let arr = arr.as_array()?;
    let mut out = Vec::new();
    for row in arr {
        let Some(obj) = row.as_object() else { continue };
        let content = kotlin_trim(&opt_string(obj.get("content"))).to_string();
        if content.is_empty() {
            continue;
        }
        let persona_raw = kotlin_trim(&opt_string(obj.get("persona_id"))).to_lowercase();
        let persona_id = if persona_raw.is_empty() { None } else { Some(persona_raw) };
        let alias_raw = opt_string(obj.get("sender_alias"));
        let sender_alias = if alias_raw.is_empty() {
            if persona_id.is_some() { "Community Guide" } else { "Member" }.to_string()
        } else {
            alias_raw
        };
        let author_raw = kotlin_trim(&opt_string(obj.get("user_id"))).to_string();
        let author_id = if author_raw.is_empty() { None } else { Some(author_raw) };
        let remote_raw = kotlin_trim(&opt_string(obj.get("id"))).to_string();
        let created_raw = opt_string(obj.get("created_at"));
        out.push(CircleMessage {
            channel_id: channel_id.to_string(),
            timestamp_ms: crate::notifications::parse_iso_timestamp(Some(&created_raw)),
            sender_alias,
            content: take_500_utf16(&content),
            is_self: author_id.is_some() && author_id.as_deref() == self_id,
            persona_id,
            remote_id: if remote_raw.is_empty() { None } else { Some(remote_raw) },
            is_flagged: opt_boolean_false(obj.get("is_flagged")),
        });
    }
    Some(out)
}

/// CircleServiceStatus (all five cases; the offline-empty UI copy maps the
/// three non-OK ones through guided_chats::offline_empty_copy).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CircleStatus {
    Ok,
    SignInUnconfirmed,
    Network,
    Unavailable,
    Empty,
}

/// Access-token resolution outcomes that fetchTopicsWithStatus maps
/// (SupabaseSessionManager.resolveAccessToken is the seam).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TokenResolution {
    Valid,
    MissingOrReauth,
    TemporarilyUnavailable,
}

/// Transport outcome classification for the status decision chain.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportOutcome {
    /// HTTP response received with this status code.
    Http(u16),
    /// java.io.IOException on execute.
    IoError,
    /// Any other exception on execute.
    OtherError,
}

/// fetchTopicsWithStatus decision chain (verbatim order):
/// token -> SIGN_IN_UNCONFIRMED (missing/reauth) / UNAVAILABLE (temporary);
/// IOException -> NETWORK; other exception -> UNAVAILABLE; 401/403 ->
/// SIGN_IN_UNCONFIRMED; other non-2xx -> UNAVAILABLE; parsed empty -> EMPTY,
/// else OK. (Body-bound/parse failures -> UNAVAILABLE happen downstream of
/// this mapping and are represented by the caller passing None topics.)
pub fn topics_status(
    token: TokenResolution,
    transport: Option<TransportOutcome>,
    parsed_topic_count: Option<usize>,
) -> CircleStatus {
    match token {
        TokenResolution::MissingOrReauth => return CircleStatus::SignInUnconfirmed,
        TokenResolution::TemporarilyUnavailable => return CircleStatus::Unavailable,
        TokenResolution::Valid => {}
    }
    match transport {
        Some(TransportOutcome::IoError) => return CircleStatus::Network,
        Some(TransportOutcome::OtherError) => return CircleStatus::Unavailable,
        Some(TransportOutcome::Http(code)) => {
            if code == 401 || code == 403 {
                return CircleStatus::SignInUnconfirmed;
            }
            if !(200..300).contains(&code) {
                return CircleStatus::Unavailable;
            }
        }
        None => return CircleStatus::Unavailable,
    }
    match parsed_topic_count {
        Some(0) => CircleStatus::Empty,
        Some(_) => CircleStatus::Ok,
        None => CircleStatus::Unavailable,
    }
}

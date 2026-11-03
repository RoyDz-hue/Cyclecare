//! Decision-core port of ui/screens/SettingsScreen.kt (ref 31b88f5).
//! Pure rules only; Compose rendering, contentResolver export IO, and the
//! actual PIN/consent/erase actions stay Android seams (several gated by
//! the standing money/account-mutation boundaries - inert here).
//!
//! Covered:
//!  - boundedConflictPreview (:989-1002) incl. conflictPreviewSkipKeys (:987)
//!  - SyncConflictReviewRow gates (:1004-1031): localMissing / keepDisabled /
//!    remoteValid / record label
//!  - "Backup check" status text chain (:366-378)
//!  - cycleLengthIsSet (:143-146) and the stepper guards (:510-538)
//!  - PIN draft filter (:680) and isCompletePin (:1073, reused from pin.rs)
//!
//! Kotlin semantics preserved: AOSP org.json insertion order + opt().toString(),
//! UTF-16 take() caps, Kotlin lowercase()/replaceFirstChar, "NULL"->lowercase
//! "null" filtered quirk (ported 1:1), equals(ignoreCase=true).

use crate::kotlin_text::{kotlin_equals_ignore_case, kotlin_trim, take_utf16};
use crate::sync_codec::{json_value_to_string, JsonParser, JsonValue};

/// :987 conflictPreviewSkipKeys, verbatim.
pub const CONFLICT_PREVIEW_SKIP_KEYS: [&str; 10] = [
    "owner",
    "owner_id",
    "user_id",
    "revision",
    "server_revision",
    "hash",
    "payload_hash",
    "updated_at",
    "created_at",
    "device_id",
];

/// :990 getOrElse fallback. Contains U+2019 exactly as the source.
pub const DETAILS_UNSAFE_MESSAGE: &str = "(details can\u{2019}t be shown safely)";
/// :1000 ifBlank fallback.
pub const NO_READABLE_DETAILS_MESSAGE: &str = "(no readable details)";

/// Port of boundedConflictPreview (:989-1002).
/// org.json.opt(key)?.toString(): strings as-is, numbers via numberToString,
/// nested objects/arrays as JSON text, JSONObject.NULL as "null" (which the
/// later filter then drops, as does a literal "NULL" string after lowercase -
/// both quirks ported 1:1).
pub fn bounded_conflict_preview(payload: &str) -> String {
    (|| {
        let parsed = JsonParser::new(payload).parse().ok()?;
        let obj = match parsed {
            JsonValue::Object(entries) => entries,
            // JSONObject(payload) only accepts an object top level.
            _ => return None,
        };
        let mut lines: Vec<String> = Vec::new();
        for (key, value) in &obj {
            if lines.len() >= 12 {
                break;
            }
            if CONFLICT_PREVIEW_SKIP_KEYS.contains(&key.as_str()) {
                continue;
            }
            let label = replace_first_char_upper(&key.replace('_', " "));
            let raw = opt_to_string(value);
            let v = take_utf16(&raw.replace('_', " ").to_lowercase(), 120);
            if !v.is_empty() && !kotlin_trim(&v).is_empty() && v != "null" {
                lines.push(format!("{label}: {v}"));
            }
        }
        let joined = lines.join("\n");
        let joined = if joined.trim().is_empty() {
            NO_READABLE_DETAILS_MESSAGE.to_string()
        } else {
            joined
        };
        Some(take_utf16(&joined, 1000))
    })()
    .unwrap_or_else(|| DETAILS_UNSAFE_MESSAGE.to_string())
}

/// Kotlin replaceFirstChar { it.uppercase() }: full-char uppercase of the
/// first character only.
fn replace_first_char_upper(s: &str) -> String {
    let mut chars = s.chars();
    match chars.next() {
        None => String::new(),
        Some(first) => first.to_uppercase().collect::<String>() + chars.as_str(),
    }
}

/// org.json opt(key).toString() semantics: the raw object's own toString -
/// Double.toString for doubles (1.0 stays "1.0", 1e10 -> "1.0E10"; probe-
/// locked against AOSP, NOT JSONObject.numberToString's integer collapse,
/// which only applies on the JSONStringer render path), Long/Int toString,
/// nested containers' JSON text.
fn opt_to_string(v: &JsonValue) -> String {
    match v {
        JsonValue::Str(s) => s.clone(),
        JsonValue::Null => "null".to_string(),
        JsonValue::Bool(b) => b.to_string(),
        JsonValue::Int(i) => i.to_string(),
        JsonValue::Long(l) => l.to_string(),
        JsonValue::Double(d) => crate::sync_codec::java_double_to_string(*d),
        other => json_value_to_string(other),
    }
}

/// Gates for SyncConflictReviewRow (:1006-1031). Pure decision result; the
/// composable only reads these flags.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConflictReviewGates {
    pub local_missing: bool,
    pub keep_disabled: bool,
    pub remote_valid: bool,
    pub record_label: String,
    pub show_remote_tombstone_warning: bool,
    pub show_local_missing_warning: bool,
    pub show_remote_only_warning: bool,
    pub show_remote_invalid_warning: bool,
}

pub fn conflict_review_gates(
    record_id: &str,
    local_kind: &str,
    local_payload_json: Option<&str>,
    remote_tombstone: bool,
    remote_revision: i64,
    remote_payload_json: Option<&str>,
) -> ConflictReviewGates {
    let local_missing = kotlin_equals_ignore_case(local_kind, "UPSERT")
        && local_payload_json.map_or(true, |p| kotlin_trim(p).is_empty());
    let remote_only = kotlin_equals_ignore_case(local_kind, "REMOTE");
    let keep_disabled = remote_tombstone || local_missing || remote_only;
    let remote_parses_nonempty = (|| {
        let payload = remote_payload_json?;
        let parsed = JsonParser::new(payload).parse().ok()?;
        match parsed {
            JsonValue::Object(entries) => Some(!entries.is_empty()),
            _ => Some(false),
        }
    })()
    .unwrap_or(false);
    let remote_valid = remote_revision > 0 && (remote_tombstone || remote_parses_nonempty);
    let record_label = if record_id == "settings" {
        "Cycle settings".to_string()
    } else if record_id == "profile" {
        "Your profile".to_string()
    } else {
        format!("Saved day \u{b7} {record_id}")
    };
    ConflictReviewGates {
        local_missing,
        keep_disabled,
        remote_valid,
        record_label,
        show_remote_tombstone_warning: remote_tombstone,
        show_local_missing_warning: local_missing,
        show_remote_only_warning: remote_only,
        show_remote_invalid_warning: !remote_valid,
    }
}

/// "Backup check" status line (:366-369), verbatim strings.
pub fn backup_status_message(is_online_backup_active: bool, last_sync_success: bool) -> &'static str {
    if !is_online_backup_active {
        "Online backup is off in this version. Your records stay on this device."
    } else if last_sync_success {
        "Everything looks good."
    } else {
        "Backup is paused or needs attention. CycleCare never overwrites one of your records without asking you first."
    }
}

/// :374 - stale past result hidden while backup is off.
pub fn show_last_sync_message(is_online_backup_active: bool) -> bool {
    is_online_backup_active
}

/// :378 - "Check again" only while backup active and last sync failed.
pub fn show_check_again_button(is_online_backup_active: bool, last_sync_success: bool) -> bool {
    is_online_backup_active && !last_sync_success
}

/// cycleLengthIsSet (:143-146). Input is the raw onboarding cycleLengthOption;
/// trim + lowercase happen here exactly as the source. Both apostrophe forms
/// ("don't" ASCII and "don\u{2019}t" U+2019) excluded, verbatim.
pub fn cycle_length_is_set(cycle_length_option: Option<&str>) -> bool {
    let choice = kotlin_trim(cycle_length_option.unwrap_or("")).to_lowercase();
    !choice.is_empty()
        && choice != "unknown"
        && !choice.contains("don't know")
        && !choice.contains("don\u{2019}t know")
        && !choice.contains("changes")
}

/// Stepper guards (:510-538): the onClick bodies no-op outside the bounds,
/// so the ported helpers return None when the source would do nothing.
pub fn decrease_cycle_length(v: i32) -> Option<i32> {
    if v > 21 {
        Some(v - 1)
    } else {
        None
    }
}
pub fn increase_cycle_length(v: i32) -> Option<i32> {
    if v < 45 {
        Some(v + 1)
    } else {
        None
    }
}
pub fn decrease_period_duration(v: i32) -> Option<i32> {
    if v > 2 {
        Some(v - 1)
    } else {
        None
    }
}
pub fn increase_period_duration(v: i32) -> Option<i32> {
    if v < 10 {
        Some(v + 1)
    } else {
        None
    }
}

/// PIN draft filter (:680): keep Kotlin-isDigit chars, take(4). All Kotlin
/// digits are BMP, so UTF-16 take(4) equals 4 chars after filtering.
pub fn pin_draft_filter(raw: &str) -> String {
    raw.chars()
        .filter(|&c| crate::kotlin_text::kotlin_is_digit(c))
        .take(4)
        .collect()
}

/// Re-export the existing 1:1 port of isCompletePin (:1073).
pub use crate::pin::is_complete_pin;

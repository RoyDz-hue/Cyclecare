//! V3 health-sync identity layer (data/supabase/SupabaseSyncService.kt,
//! main@31b88f5). Pure identity/validation pieces; the pull/outbox adapter
//! with its DAO seam is a later milestone.
//!
//! All tables JVM-executed (V3Probe/V3Probe2 on JRE 21: java.util.UUID and
//! java.time.LocalDate are JDK classes) and corpus-locked in
//! v3_sync_corpus. conflictSafeProtocolReleased() = false in production:
//! syncAll/pull uploads NOTHING while the release policy is closed -
//! is_online_backup_active mirrors that constant.

use crate::lifecycle::name_uuid_from_bytes;
use crate::sync_codec::JsonValue;

/// isOnlineBackupActive: false while the conflict-safe wire protocol is
/// unreleased. Kotlin: `private fun conflictSafeProtocolReleased() = false`.
pub fn is_online_backup_active() -> bool {
    false
}

/// wireRecordId: UUID.nameUUIDFromBytes("cyclecare-v3:$owner:$logicalId"
/// UTF-8). Deterministic per (owner, logical id); case-sensitive.
pub fn wire_record_id(owner: &str, logical_id: &str) -> String {
    name_uuid_from_bytes(format!("cyclecare-v3:{owner}:{logical_id}").as_bytes())
}

/// validLogicalId: "profile"/"settings" or LocalDate.parse(id) success.
/// ISO_LOCAL_DATE grammar as executed (V3Probe2):
///   year: exactly 4 digits unsigned; or '-' + 4..=9 digits; or '+' +
///   5..=9 digits ("+9999" REJECTED, "+10000" accepted; 10 digits rejected);
///   month/day: exactly 2 ASCII digits each; month 01-12; day valid for the
///   month under the PROLEPTIC leap rule (floorMod): year 0 and -2024 are
///   leap, 0100 is not, 0400 is. No whitespace, no time part, ASCII only.
pub fn valid_logical_id(id: &str) -> bool {
    if id == "profile" || id == "settings" {
        return true;
    }
    parse_iso_local_date(id).is_some()
}

/// Returns the proleptic year on success (value unused by callers today).
fn parse_iso_local_date(s: &str) -> Option<i64> {
    let b = s.as_bytes();
    let (negative, year_start) = match b.first() {
        Some(b'-') => (true, 1),
        Some(b'+') => (false, 1),
        _ => (false, 0),
    };
    // Find the two separator hyphens after the year digits.
    let rest = &s[year_start..];
    let digits: usize = rest.bytes().take_while(|c| c.is_ascii_digit()).count();
    let unsigned_ok = year_start == 0 && digits == 4;
    let neg_ok = negative && (4..=9).contains(&digits);
    let pos_ok = !negative && year_start == 1 && (5..=9).contains(&digits);
    if !(unsigned_ok || neg_ok || pos_ok) {
        return None;
    }
    let year_digits: i64 = rest[..digits].parse().ok()?;
    let year = if negative { -year_digits } else { year_digits };
    let tail = &rest[digits..];
    let tb = tail.as_bytes();
    if tb.len() != 6 || tb[0] != b'-' || tb[3] != b'-' {
        return None;
    }
    let month: u32 = tail[1..3].parse().ok().filter(|_| tail[1..3].bytes().all(|c| c.is_ascii_digit()))?;
    let day: u32 = tail[4..6].parse().ok().filter(|_| tail[4..6].bytes().all(|c| c.is_ascii_digit()))?;
    if !(1..=12).contains(&month) {
        return None;
    }
    let leap = year.rem_euclid(4) == 0 && (year.rem_euclid(100) != 0 || year.rem_euclid(400) == 0);
    let max_day = match month {
        2 => if leap { 29 } else { 28 },
        4 | 6 | 9 | 11 => 30,
        _ => 31,
    };
    if day == 0 || day > max_day {
        return None;
    }
    Some(year)
}

/// envelope: JSONObject().put("kind","UPSERT").put("logicalRecordId",id)
/// .put("value",value) - insertion-ordered, exactly these three keys.
pub fn envelope(id: &str, value: JsonValue) -> JsonValue {
    JsonValue::Object(vec![
        ("kind".to_string(), JsonValue::Str("UPSERT".to_string())),
        ("logicalRecordId".to_string(), JsonValue::Str(id.to_string())),
        ("value".to_string(), value),
    ])
}

/// The current() gate predicate (SupabaseSyncService.kt:27): every
/// captured generation/consent value must still match live preferences AND
/// cloud sync must be allowed AND the owner must not be wiped.
/// is_wiped is the OwnerDataGate seam (context/DB-backed in production);
/// injected here so the pure predicate stays portable.
pub fn sync_gate_current(
    live_user_id: Option<&str>,
    live_session_generation: i64,
    live_health_consent_generation: i64,
    live_server_health_consent_generation: Option<i64>,
    is_cloud_sync_allowed: bool,
    is_wiped: bool,
    captured_owner: &str,
    captured_session: i64,
    captured_local: i64,
    captured_server: i64,
) -> bool {
    live_user_id == Some(captured_owner)
        && live_session_generation == captured_session
        && live_health_consent_generation == captured_local
        && live_server_health_consent_generation == Some(captured_server)
        && is_cloud_sync_allowed
        && !is_wiped
}

/// Wire types (HealthSyncV3Transport.kt:14-17), payload as AOSP JsonValue.
#[derive(Debug, Clone, PartialEq)]
pub struct V3WireOperation {
    pub operation_id: String,
    pub record_id: String,
    pub base_revision: i64,
    pub revision: i64,
    pub kind: String,
    pub payload: Option<JsonValue>,
    pub consent_generation: i64,
    pub logical_record_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct V3WireReceipt {
    pub status: String,
    pub revision: Option<i64>,
    pub change_seq: Option<i64>,
    pub payload_hash: Option<String>,
    pub reason: Option<String>,
    pub record_id: Option<String>,
    pub operation_id: Option<String>,
    pub logical_record_id: Option<String>,
    pub remote_payload: Option<JsonValue>,
    pub remote_tombstone: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct V3WireChange {
    pub record_id: String,
    pub revision: i64,
    pub change_seq: i64,
    pub payload: Option<JsonValue>,
    pub tombstone: bool,
    pub payload_hash: Option<String>,
    pub logical_record_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct V3ConsentReceipt {
    pub enabled: bool,
    pub generation: i64,
    pub reason: Option<String>,
}

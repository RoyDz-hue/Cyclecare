//! Port of the pure parse/merge blocks in
//! SupabaseEconomyService.fetchVerifiedProfile (main@31b88f5, lines 83-195):
//! economy_profiles row validation, my_account_start_state validation,
//! verifiedCoinAmount typing, restriction merge (effectiveStatus / strongest /
//! finalReason / finalUntil), tier/rank/unlocked/gifted extraction, and the
//! exact error vocabulary (Rejected vs generic catch). Network calls,
//! stillOwns re-checks, and the ensure-RPC are transport concerns; the
//! ensure response check is ensure_setup_ok. Executed JVM locks:
//! /downloads/parity/economy/VerifiedProfileParity.kt corpus vp01-vp21 and
//! es01-es05 (AOSP org.json android-14.0.0_r1 classes, 2026-11-02 13:54 EAT).

use crate::kotlin_text::{kotlin_equals_ignore_case, kotlin_is_blank, kotlin_trim};
use crate::sync_codec::{aosp_opt_string, JsonParser, JsonValue};
use std::str::FromStr;

pub const VALID_ACCOUNT_STATUSES: [&str; 3] = ["ACTIVE", "SUSPENDED", "BANNED"];
const GENERIC_ERROR: &str =
    "Your wallet could not be verified. Refresh it before spending or retrying a payment.";

enum Fail {
    Rejected(String),
    Generic,
}

fn rejected(msg: &str) -> Fail {
    Fail::Rejected(msg.to_string())
}

impl Fail {
    fn into_user_message(self) -> String {
        match self {
            Fail::Rejected(m) => format!("{m}. Please retry before using the shared vault."),
            Fail::Generic => GENERIC_ERROR.to_string(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VerifiedProfile {
    pub user_id: String,
    pub tier: String,
    pub rank: String,
    pub coin_balance: String,
    pub lifetime_earned: String,
    pub lifetime_spent: String,
    pub active_frame: Option<String>,
    pub active_artifact: String,
    pub active_avatar_art: Option<String>,
    pub active_theme: String,
    pub last_mined_date: Option<String>,
    pub unlocked_item_ids: Vec<String>, // set semantics; rendered sorted
    pub status: String,
    pub suspension_reason: Option<String>,
    pub suspended_until: Option<String>,
    pub gifted_artifacts: Vec<String>,
}

// ---------- java.time.Instant (ISO_INSTANT parse / compare / toString) ----

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
struct JavaInstant {
    secs: i64,
    nanos: u32,
}

/// Howard Hinnant days_from_civil (proleptic Gregorian, negative years OK).
fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

/// java.time.Instant.parse (DateTimeFormatter.ISO_INSTANT): strict
/// YYYY-MM-DDTHH:MM:SS[.fraction(1-9 digits)](Z|+HH:MM|-HH:MM).
fn java_instant_parse(s: &str) -> Option<JavaInstant> {
    let b = s.as_bytes();
    if b.len() < 20 || b.get(4) != Some(&b'-') || b.get(7) != Some(&b'-') || b.get(10) != Some(&b'T')
        || b.get(13) != Some(&b':') || b.get(16) != Some(&b':')
    {
        return None;
    }
    let num = |lo: usize, hi: usize| -> Option<i64> {
        let t = s.get(lo..hi)?;
        if t.bytes().all(|c| c.is_ascii_digit()) && !t.is_empty() {
            t.parse().ok()
        } else {
            None
        }
    };
    let (y, m, d) = (num(0, 4)?, num(5, 7)?, num(8, 10)?);
    let (hh, mm, ss) = (num(11, 13)?, num(14, 16)?, num(17, 19)?);
    if !(1..=12).contains(&m) || !(1..=31).contains(&d) || hh > 24 || mm > 59 || ss > 59 {
        return None;
    }
    let mut idx = 19;
    let mut nanos: u32 = 0;
    if b.get(idx) == Some(&b'.') {
        idx += 1;
        let start = idx;
        while idx < b.len() && b[idx].is_ascii_digit() && idx - start < 9 {
            idx += 1;
        }
        if idx == start {
            return None;
        }
        let frac: u32 = s.get(start..idx)?.parse().ok()?;
        nanos = frac * 10u32.pow(9 - (idx - start) as u32);
    }
    let offset_secs: i64 = match b.get(idx) {
        Some(&b'Z') => {
            idx += 1;
            0
        }
        Some(sign @ (&b'+' | &b'-')) => {
            let oh = num(idx + 1, idx + 3)?;
            if b.get(idx + 3) != Some(&b':') {
                return None;
            }
            let om = num(idx + 4, idx + 6)?;
            idx += 6;
            if oh > 18 || om > 59 {
                return None;
            }
            let v = oh * 3600 + om * 60;
            if *sign == b'-' {
                -v
            } else {
                v
            }
        }
        _ => return None,
    };
    if idx != b.len() {
        return None;
    }
    let days = days_from_civil(y, m, d);
    Some(JavaInstant {
        secs: days * 86400 + hh * 3600 + mm * 60 + ss - offset_secs,
        nanos,
    })
}

/// java.time.Instant.toString: UTC, fraction in 3/6/9-digit groups.
fn java_instant_to_string(i: &JavaInstant) -> String {
    let days = i.secs.div_euclid(86400);
    let sod = i.secs.rem_euclid(86400);
    // civil_from_days
    let z = days + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    let (hh, mm, ss) = (sod / 3600, sod % 3600 / 60, sod % 60);
    let mut out = format!("{y:04}-{m:02}-{d:02}T{hh:02}:{mm:02}:{ss:02}");
    if i.nanos != 0 {
        let digits = format!("{:09}", i.nanos);
        let trimmed = digits.trim_end_matches('0');
        let groups = trimmed.len().div_ceil(3) * 3;
        out.push('.');
        out.push_str(&digits[..groups]);
    }
    out.push('Z');
    out
}

// ---------- org.json typed accessors over sync_codec::JsonValue ----------

fn obj_get<'a>(obj: &'a JsonValue, key: &str) -> Option<&'a JsonValue> {
    match obj {
        JsonValue::Object(pairs) => pairs.iter().find(|(k, _)| k == key).map(|(_, v)| v),
        _ => None,
    }
}

/// org.json getString: present String or JSONException (-> Generic here).
fn get_string<'a>(obj: &'a JsonValue, key: &str) -> Result<&'a str, Fail> {
    match obj_get(obj, key) {
        Some(JsonValue::Str(s)) => Ok(s),
        _ => Err(Fail::Generic),
    }
}

/// verifiedCoinAmount: org.json typing - Int/Long/Double only; String or
/// other JSON types are "not a number". BigDecimal(value.toString()) keeps
/// Double.toString scale (5.0 -> "5.0"); negative rejected.
fn verified_coin_amount(obj: &JsonValue, field: &str) -> Result<String, Fail> {
    let bd = match obj_get(obj, field) {
        Some(JsonValue::Int(i)) => bigdecimal::BigDecimal::from_str(&i.to_string()),
        Some(JsonValue::Long(l)) => bigdecimal::BigDecimal::from_str(&l.to_string()),
        Some(JsonValue::Double(d)) => {
            bigdecimal::BigDecimal::from_str(&crate::sync_codec::java_double_to_string(*d))
        }
        _ => return Err(rejected(&format!("Cloud wallet {field} was not a number"))),
    }
    .map_err(|_| Fail::Generic)?;
    if bd.sign() == bigdecimal::num_bigint::Sign::Minus {
        return Err(rejected(&format!("Cloud wallet {field} was negative")));
    }
    Ok(crate::economy_status::bigdecimal_to_plain_string(&bd))
}

fn until_of(record: &JsonValue) -> Option<&str> {
    match obj_get(record, "suspended_until") {
        Some(JsonValue::Str(s)) if !kotlin_is_blank(s) => Some(s),
        _ => None,
    }
}

/// effectiveStatus: getString(status).trim().uppercase(); a SUSPENDED record
/// whose suspended_until parses and is before `now` reads as ACTIVE.
fn effective_status(record: &JsonValue, now: &JavaInstant) -> Result<String, Fail> {
    let status = get_string(record, "status")?;
    let status = kotlin_trim(status).to_uppercase();
    let ended = until_of(record)
        .and_then(|u| java_instant_parse(u))
        .map(|until| *now > until)
        .unwrap_or(false);
    Ok(if status == "SUSPENDED" && ended {
        "ACTIVE".to_string()
    } else {
        status
    })
}

/// ensure_my_economy_profile response gate:
/// runCatching { JSONObject(body).optBoolean("success", false) }.getOrDefault(false)
pub fn ensure_setup_ok(body: &str) -> bool {
    match JsonParser::new(body).parse() {
        Ok(v @ JsonValue::Object(_)) => match obj_get(&v, "success") {
            Some(JsonValue::Bool(b)) => *b,
            Some(JsonValue::Str(s)) => s.eq_ignore_ascii_case("true"),
            _ => false,
        },
        _ => false,
    }
}

/// The pure parse/merge core of fetchVerifiedProfile after both bodies are
/// in hand. Errors carry the exact post-catch user-facing vocabulary.
pub fn parse_verified_profile(
    owner: &str,
    econ_body: &str,
    prof_body: &str,
    now_iso: &str,
) -> Result<VerifiedProfile, String> {
    parse_inner(owner, econ_body, prof_body, now_iso).map_err(Fail::into_user_message)
}

fn parse_inner(
    owner: &str,
    econ_body: &str,
    prof_body: &str,
    now_iso: &str,
) -> Result<VerifiedProfile, Fail> {
    let econ = JsonParser::new(econ_body)
        .parse()
        .map_err(|_| rejected("Cloud wallet response was malformed"))?;
    let rows = match &econ {
        JsonValue::Array(items) => items,
        _ => return Err(rejected("Cloud wallet response was malformed")),
    };
    if rows.len() != 1 {
        return Err(rejected("Cloud wallet record is missing or ambiguous"));
    }
    let obj = match &rows[0] {
        v @ JsonValue::Object(_) => v,
        _ => return Err(rejected("Cloud wallet record was malformed")),
    };
    if kotlin_trim(&aosp_opt_string(obj, "user_id", "")) != owner {
        return Err(rejected("Cloud wallet owner mismatch"));
    }
    let econ_status = aosp_opt_string(obj, "status", "");
    let econ_status = kotlin_trim(&econ_status).to_uppercase();
    if !VALID_ACCOUNT_STATUSES.contains(&econ_status.as_str()) {
        return Err(rejected("Cloud wallet account status could not be verified"));
    }
    let balance = verified_coin_amount(obj, "coin_balance")?;
    let earned = verified_coin_amount(obj, "lifetime_earned")?;
    let spent = verified_coin_amount(obj, "lifetime_spent")?;

    let p_obj = JsonParser::new(prof_body)
        .parse()
        .map_err(|_| rejected("Account status response was malformed"))?;
    match &p_obj {
        JsonValue::Object(_) => {}
        _ => return Err(rejected("Account status response was malformed")),
    }
    if kotlin_trim(&aosp_opt_string(&p_obj, "user_id", "")) != owner {
        return Err(rejected("Account status owner mismatch"));
    }
    let profile_status = aosp_opt_string(&p_obj, "status", "");
    let profile_status = kotlin_trim(&profile_status).to_uppercase();
    if !VALID_ACCOUNT_STATUSES.contains(&profile_status.as_str()) {
        return Err(rejected("Account status could not be verified"));
    }

    let now = java_instant_parse(now_iso).ok_or(Fail::Generic)?;
    let tier_str = aosp_opt_string(obj, "tier", "FREE");
    let rank_str = aosp_opt_string(obj, "rank", "LUMEN");
    let mut unlocked: Vec<String> = vec!["cycle_coin".to_string()];
    if let Some(JsonValue::Array(items)) = obj_get(obj, "unlocked_items") {
        for value in items {
            let raw_item = match value {
                JsonValue::Str(s) => s.clone(),
                o @ JsonValue::Object(_) => {
                    let id = aosp_opt_string(o, "id", "");
                    if kotlin_is_blank(&id) {
                        aosp_opt_string(o, "name", "")
                    } else {
                        id
                    }
                }
                _ => String::new(),
            };
            // EconomyCatalog.findItem(raw)?.id ?: raw - the catalog maps known
            // aliases to ids; unknown ids pass through unchanged (corpus scope).
            if !kotlin_is_blank(&raw_item) {
                let id = crate::economy_catalog::find_item(&raw_item)
                    .map(|it| it.id.to_string())
                    .unwrap_or(raw_item);
                if !unlocked.contains(&id) {
                    unlocked.push(id);
                }
            }
        }
    }
    let records = [&p_obj, obj];
    let mut statuses = Vec::new();
    for r in records {
        statuses.push(effective_status(r, &now)?);
    }
    let restrictions: Vec<usize> = [0usize, 1]
        .into_iter()
        .filter(|&i| statuses[i] != "ACTIVE")
        .collect();
    let active_status = if restrictions.iter().any(|&i| statuses[i] == "BANNED") {
        "BANNED"
    } else if !restrictions.is_empty() {
        "SUSPENDED"
    } else {
        "ACTIVE"
    };
    let strongest: Vec<usize> = restrictions
        .into_iter()
        .filter(|&i| statuses[i] == active_status)
        .collect();
    let final_reason = strongest
        .iter()
        .filter_map(|&i| match obj_get(records[i], "suspension_reason") {
            Some(JsonValue::Str(s)) if !kotlin_is_blank(s) => Some(s.clone()),
            _ => None,
        })
        .next();
    let expiries: Vec<Option<JavaInstant>> = strongest
        .iter()
        .map(|&i| until_of(records[i]).and_then(java_instant_parse))
        .collect();
    let final_until = if active_status == "SUSPENDED"
        && !expiries.is_empty()
        && expiries.iter().all(Option::is_some)
    {
        expiries.iter().flatten().max().map(java_instant_to_string)
    } else {
        None
    };
    let mut gifted = Vec::new();
    if let Some(JsonValue::Array(items)) = obj_get(obj, "artifacts") {
        for a in items {
            let raw = match a {
                o @ JsonValue::Object(_) => {
                    let id = aosp_opt_string(o, "id", "");
                    if kotlin_is_blank(&id) {
                        aosp_opt_string(o, "name", "")
                    } else {
                        id
                    }
                }
                _ => String::new(),
            };
            if !kotlin_is_blank(&raw) {
                // EconomyCatalog mapping: known alias -> item (name + id),
                // unknown -> raw name only (corpus scope: unknown ids).
                match crate::economy_catalog::find_item(&raw) {
                    Some(item) => {
                        gifted.push(item.name.to_string());
                        if !unlocked.contains(&item.id.to_string()) {
                            unlocked.push(item.id.to_string());
                        }
                    }
                    None => gifted.push(raw),
                }
            }
        }
    }
    let nonblank = |v: String| if kotlin_is_blank(&v) { None } else { Some(v) };
    Ok(VerifiedProfile {
        user_id: owner.to_string(),
        tier: ["FREE", "SUPPORTER", "PREMIUM"]
            .iter()
            .find(|t| kotlin_equals_ignore_case(t, &tier_str))
            .map(|s| s.to_string())
            .unwrap_or_else(|| "FREE".to_string()),
        rank: ["LUMEN", "AURELIA", "CELESTIA", "SOLARA", "NOCTURNE"]
            .iter()
            .find(|r| kotlin_equals_ignore_case(r, &rank_str))
            .map(|s| s.to_string())
            .unwrap_or_else(|| "LUMEN".to_string()),
        coin_balance: balance,
        lifetime_earned: earned,
        lifetime_spent: spent,
        active_frame: nonblank(aosp_opt_string(obj, "active_frame", "")),
        active_artifact: aosp_opt_string(obj, "active_artifact", "cycle_coin"),
        active_avatar_art: nonblank(aosp_opt_string(obj, "active_avatar_art", "")),
        active_theme: aosp_opt_string(obj, "active_theme", "SOOTHING_ROSE"),
        last_mined_date: nonblank(aosp_opt_string(obj, "last_mined_date", "")),
        unlocked_item_ids: unlocked,
        status: active_status.to_string(),
        suspension_reason: final_reason,
        suspended_until: final_until,
        gifted_artifacts: gifted,
    })
}

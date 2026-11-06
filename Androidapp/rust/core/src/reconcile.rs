//! Port of the pure decision core of
//! SupabaseEconomyService.reconcilePendingOfflineClaims (main@31b88f5,
//! lines 199-256). Transport, the pending-flag mutation and the
//! fetchVerifiedProfile delegation are host concerns. Executed JVM locks:
//! /downloads/parity/economy/ReconcileParity.kt corpus rc01-rc12 (AOSP
//! org.json android-14.0.0_r1 classes, 2026-11-02 13:57 EAT).

use crate::kotlin_text::{kotlin_equals_ignore_case, kotlin_is_blank};
use crate::sync_codec::{JsonParser, JsonValue};
use bigdecimal::BigDecimal;
use std::str::FromStr;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReconcileOutcome {
    /// success=true: applied server numbers (fallbacks to current), the
    /// reconciled profile earns "Your offline daily reward is ready."
    Applied {
        coin_balance: String,
        lifetime_earned: String,
        rank: String,
        mined_date: String,
    },
    /// success=false: pending flag cleared, refetch drives the rolled-back
    /// profile; message "Your daily reward was already recorded."
    AlreadyRecorded,
    /// Non-2xx or blank body: falls through to fetchVerifiedProfile.
    Refetch,
    /// Any exception: EconomyResult.Success(currentProfile, "Your reward
    /// will sync when you're back online.")
    OfflineKept,
}

/// optCoinAmount(obj, key, fallback): org.json opt -> Number only converts,
/// via its toString (Double keeps scale); anything else falls back.
fn opt_coin_amount(obj: &JsonValue, key: &str, fallback: &BigDecimal) -> BigDecimal {
    match obj {
        JsonValue::Object(pairs) => match pairs.iter().find(|(k, _)| k == key).map(|(_, v)| v) {
            Some(JsonValue::Int(i)) => {
                BigDecimal::from_str(&i.to_string()).unwrap_or_else(|_| fallback.clone())
            }
            Some(JsonValue::Long(l)) => {
                BigDecimal::from_str(&l.to_string()).unwrap_or_else(|_| fallback.clone())
            }
            Some(JsonValue::Double(d)) => BigDecimal::from_str(
                &crate::sync_codec::java_double_to_string(*d),
            )
            .unwrap_or_else(|_| fallback.clone()),
            _ => fallback.clone(),
        },
        _ => fallback.clone(),
    }
}

const RANKS: [&str; 6] = ["LUMEN", "AURELIA", "CELESTIA", "SOLARA", "NOCTURNE", "FOUNDER"];

pub fn reconcile_offline_claim_response(
    http_successful: bool,
    body: &str,
    current_balance: &BigDecimal,
    current_earned: &BigDecimal,
    current_rank_name: &str,
    pending_date: &str,
) -> ReconcileOutcome {
    if http_successful && !kotlin_is_blank(body) {
        match JsonParser::new(body).parse() {
            Ok(v @ JsonValue::Object(_)) => {
                let success = match obj_get(&v, "success") {
                    Some(JsonValue::Bool(b)) => *b,
                    Some(JsonValue::Str(s)) => s.eq_ignore_ascii_case("true"),
                    _ => false,
                };
                if success {
                    let new_balance = opt_coin_amount(&v, "new_balance", current_balance);
                    let new_lifetime = opt_coin_amount(&v, "lifetime_earned", current_earned);
                    let rank_str = crate::sync_codec::aosp_opt_string(&v, "new_rank", current_rank_name);
                    let new_rank = RANKS
                        .iter()
                        .find(|r| kotlin_equals_ignore_case(r, &rank_str))
                        .map(|s| s.to_string())
                        .unwrap_or_else(|| {
                            crate::economy_rules::rank_kotlin_name(crate::economy_rules::calculate_rank_for_lifetime_coins(&new_lifetime)).to_string()
                        });
                    let mined_date = crate::sync_codec::aosp_opt_string(&v, "mined_date", pending_date);
                    return ReconcileOutcome::Applied {
                        coin_balance: crate::economy_status::bigdecimal_to_plain_string(&new_balance),
                        lifetime_earned: crate::economy_status::bigdecimal_to_plain_string(&new_lifetime),
                        rank: new_rank,
                        mined_date,
                    };
                }
                return ReconcileOutcome::AlreadyRecorded;
            }
            // JSONObject(body) throws on non-object/malformed -> catch.
            _ => return ReconcileOutcome::OfflineKept,
        }
    }
    ReconcileOutcome::Refetch
}

fn obj_get<'a>(obj: &'a JsonValue, key: &str) -> Option<&'a JsonValue> {
    match obj {
        JsonValue::Object(pairs) => pairs.iter().find(|(k, _)| k == key).map(|(_, v)| v),
        _ => None,
    }
}

/// hasRemoteProfile response check (SupabaseEconomyService.kt:69-81): 2xx +
/// parseable object + optString("user_id") == owner + has("exists") ->
/// getBoolean("exists"). getBoolean accepts Boolean or the strings
/// "true"/"false" (ignoreCase); any other type throws -> null.
pub fn has_remote_profile_response(http_successful: bool, body: &str, owner: &str) -> Option<bool> {
    if !http_successful {
        return None;
    }
    let state = JsonParser::new(body).parse().ok()?;
    if !matches!(state, JsonValue::Object(_)) {
        return None;
    }
    if crate::sync_codec::aosp_opt_string(&state, "user_id", "") != owner {
        return None;
    }
    match obj_get(&state, "exists") {
        Some(JsonValue::Bool(b)) => Some(*b),
        Some(JsonValue::Str(s)) if s.eq_ignore_ascii_case("true") => Some(true),
        Some(JsonValue::Str(s)) if s.eq_ignore_ascii_case("false") => Some(false),
        _ => None,
    }
}

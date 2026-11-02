//! Pure economy verification contracts extracted from
//! data/economy/SupabaseEconomyService.kt (main@31b88f5). NO network, NO
//! checkout, NO charge, NO wallet creation - these are the read-side
//! verification rules only: numeric balance acceptance, account-status
//! vocabulary, suspension-expiry semantics, and the two-source restriction
//! merge. Strings and thresholds verbatim.
//!
//! Clock seam: the source reads Instant.now() inside effectiveStatus; this
//! port takes `now` from the caller (parse failures treat the suspension as
//! NOT expired, verbatim).
use crate::kotlin_text::{kotlin_trim};

use chrono::{DateTime, Utc};

pub const MAX_RESPONSE_BYTES: usize = 64 * 1024;
pub const VALID_ACCOUNT_STATUSES: [&str; 3] = ["ACTIVE", "SUSPENDED", "BANNED"];

/// Tier names from EconomyModels.kt; parse is case-insensitive, default FREE.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UserEconomyTier {
    Free,
    Supporter,
    Premium,
}

pub fn parse_tier(raw: &str) -> UserEconomyTier {
    if raw.eq_ignore_ascii_case("SUPPORTER") {
        UserEconomyTier::Supporter
    } else if raw.eq_ignore_ascii_case("PREMIUM") {
        UserEconomyTier::Premium
    } else {
        UserEconomyTier::Free // also the explicit "FREE" and any unknown value
    }
}

/// Rank names from EconomyModels.kt; parse is case-insensitive, default LUMEN.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IdentityRank {
    Lumen,
    Aurelia,
    Celestia,
    Solara,
    Nocturne,
    Founder,
}

pub fn parse_rank(raw: &str) -> IdentityRank {
    if raw.eq_ignore_ascii_case("AURELIA") {
        IdentityRank::Aurelia
    } else if raw.eq_ignore_ascii_case("CELESTIA") {
        IdentityRank::Celestia
    } else if raw.eq_ignore_ascii_case("SOLARA") {
        IdentityRank::Solara
    } else if raw.eq_ignore_ascii_case("NOCTURNE") {
        IdentityRank::Nocturne
    } else if raw.eq_ignore_ascii_case("FOUNDER") {
        IdentityRank::Founder
    } else {
        IdentityRank::Lumen
    }
}

/// A JSON number as classified by the org.json layer: integer (Int/Long) or
/// decimal (Double). Any other JSON type must arrive as `NotANumber`.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum WalletNumber {
    Integer(i64),
    Decimal(f64),
    NotANumber,
}

/// verifiedCoinAmount: the JSON value must be a number; its decimal text is
/// preserved exactly via the parsed value's toString (BigDecimal keeps the
/// server's value - no binary float reparse of user-visible text); negative
/// is rejected. Returns the exact decimal string on success.
///
/// KNOWN DIVERGENCE (documented, edge-only): for extreme magnitudes Java
/// Double.toString uses scientific notation ("1.0E22") while Rust Display
/// prints full digits. Balances up to 3 decimal places never reach that
/// range; tracked, not silently claimed exact.
pub fn verified_coin_amount(field: &str, value: WalletNumber) -> Result<String, String> {
    match value {
        WalletNumber::NotANumber => Err(format!("Cloud wallet {field} was not a number")),
        WalletNumber::Integer(i) => {
            if i < 0 {
                Err(format!("Cloud wallet {field} was negative"))
            } else {
                Ok(i.to_string())
            }
        }
        WalletNumber::Decimal(f) => {
            if f.is_sign_negative() && !f.is_nan() {
                Err(format!("Cloud wallet {field} was negative"))
            } else if f.is_nan() || f.is_infinite() {
                // org.json rejects NaN/Infinity at parse time; they cannot
                // arrive as Double here, so reject defensively.
                Err(format!("Cloud wallet {field} was not a number"))
            } else {
                Ok(format!("{f}"))
            }
        }
    }
}

/// One restriction record, as read from a JSON row.
#[derive(Debug, Clone)]
pub struct RestrictionRecord {
    /// Raw status; uppercased+trimmed before use (verbatim).
    pub status: String,
    /// suspended_until; blank treated as absent.
    pub suspended_until: Option<String>,
    /// suspension_reason; blank filtered later (verbatim).
    pub suspension_reason: Option<String>,
}

/// effectiveStatus: SUSPENDED with an already-ended suspended_until reads as
/// ACTIVE. Unparseable or absent expiry means NOT ended.
pub fn effective_status(record: &RestrictionRecord, now: DateTime<Utc>) -> String {
    let status = kotlin_trim(&record.status).to_uppercase();
    let ended = record
        .suspended_until
        .as_deref()
        .filter(|s| !kotlin_trim(s).is_empty())
        .and_then(|s| DateTime::parse_from_rfc3339(s).ok())
        .map(|until| now > until.with_timezone(&Utc))
        .unwrap_or(false);
    if status == "SUSPENDED" && ended {
        "ACTIVE".to_string()
    } else {
        status
    }
}

/// Merged restriction across the profile and wallet sources (verbatim):
/// BANNED wins over SUSPENDED wins over ACTIVE; the reported reason is the
/// first non-blank suspension_reason among the strongest records; the
/// reported expiry exists only when EVERY strongest record has one (one
/// expired suspension must never lift the other source's indefinite or later
/// restriction) and is then the latest.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MergedRestriction {
    pub active_status: String,
    pub final_reason: Option<String>,
    pub final_until: Option<String>,
}

pub fn merge_restrictions(
    records: &[&RestrictionRecord],
    now: DateTime<Utc>,
) -> MergedRestriction {
    let restrictions: Vec<&&RestrictionRecord> = records
        .iter()
        .filter(|r| effective_status(r, now) != "ACTIVE")
        .collect();
    let active_status = if restrictions
        .iter()
        .any(|r| effective_status(r, now) == "BANNED")
    {
        "BANNED"
    } else if !restrictions.is_empty() {
        "SUSPENDED"
    } else {
        "ACTIVE"
    };
    let strongest: Vec<&&&RestrictionRecord> = restrictions
        .iter()
        .filter(|r| effective_status(r, now) == active_status)
        .collect();
    let final_reason = strongest
        .iter()
        .filter_map(|r| r.suspension_reason.clone())
        .find(|s| !kotlin_trim(s).is_empty());
    // Kotlin: expiries = strongest.map { until?.let { runCatching {
    // Instant.parse(it) }.getOrNull() } }; finalUntil only when every entry
    // parsed, then maxOrNull() on INSTANTS (not strings) then toString().
    // Probe: lexicographic max picks "2026-11-02T09:00:00Z" over
    // "2026-11-02T09:00:00.5Z"; Instant max correctly picks .500Z.
    let expiries: Vec<Option<DateTime<Utc>>> = strongest
        .iter()
        .map(|r| {
            r.suspended_until
                .as_deref()
                .filter(|s| !kotlin_trim(s).is_empty())
                .and_then(|s| DateTime::parse_from_rfc3339(s).ok())
                .map(|dt| dt.with_timezone(&Utc))
        })
        .collect();
    let final_until = if active_status == "SUSPENDED"
        && !expiries.is_empty()
        && expiries.iter().all(|e| e.is_some())
    {
        // Kotlin Instant.toString: Z suffix, fractional seconds only when
        // non-zero - SecondsFormat::AutoSi + use_Z matches (probe-locked:
        // whole second -> no fraction; 0.5s -> ".500Z").
        expiries
            .into_iter()
            .flatten()
            .max()
            .map(|dt| dt.to_rfc3339_opts(chrono::SecondsFormat::AutoSi, true))
    } else {
        None
    };
    MergedRestriction {
        active_status: active_status.to_string(),
        final_reason,
        final_until,
    }
}

#[cfg(test)]
mod probe_locked_merge_tests {
    use super::*;

    #[test]
    fn mixed_fraction_expiries_max_by_instant_not_lexical() {
        // JVM probe: lexicographic max picks "2026-11-02T09:00:00Z" over
        // "2026-11-02T09:00:00.5Z"; Instant max picks .500Z. Port must match
        // Instant semantics and Kotlin Instant.toString formatting.
        let now = DateTime::parse_from_rfc3339("2026-11-01T00:00:00Z").unwrap().with_timezone(&Utc);
        let recs = vec![
            RestrictionRecord {
                status: "SUSPENDED".into(),
                suspension_reason: Some("a".into()),
                suspended_until: Some("2026-11-02T09:00:00Z".into()),
            },
            RestrictionRecord {
                status: "SUSPENDED".into(),
                suspension_reason: Some("b".into()),
                suspended_until: Some("2026-11-02T09:00:00.5Z".into()),
            },
        ];
        let refs: Vec<&RestrictionRecord> = recs.iter().collect();
        let merged = merge_restrictions(&refs, now);
        assert_eq!(merged.final_until.as_deref(), Some("2026-11-02T09:00:00.500Z"));
    }
}

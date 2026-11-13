//! Pure economy verification contracts; expectations from source rules.

use chrono::{DateTime, Utc};
use cyclecare_core::economy_status::*;

fn now() -> DateTime<Utc> {
    "2026-02-01T12:00:00Z".parse::<DateTime<Utc>>().unwrap()
}

fn rec(status: &str, until: Option<&str>, reason: Option<&str>) -> RestrictionRecord {
    RestrictionRecord {
        status: status.to_string(),
        suspended_until: until.map(|s| s.to_string()),
        suspension_reason: reason.map(|s| s.to_string()),
    }
}

#[test]
fn amounts_accept_numbers_reject_others_and_negatives() {
    assert_eq!(verified_coin_amount("coin_balance", WalletNumber::Integer(363)).unwrap(), "363");
    assert_eq!(verified_coin_amount("coin_balance", WalletNumber::Decimal(12.5)).unwrap(), "12.5");
    assert_eq!(
        verified_coin_amount("coin_balance", WalletNumber::NotANumber).unwrap_err(),
        "Cloud wallet coin_balance was not a number"
    );
    assert_eq!(
        verified_coin_amount("lifetime_spent", WalletNumber::Integer(-1)).unwrap_err(),
        "Cloud wallet lifetime_spent was negative"
    );
    assert_eq!(
        verified_coin_amount("coin_balance", WalletNumber::Decimal(-0.5)).unwrap_err(),
        "Cloud wallet coin_balance was negative"
    );
    assert_eq!(
        verified_coin_amount("coin_balance", WalletNumber::Decimal(f64::NAN)).unwrap_err(),
        "Cloud wallet coin_balance was not a number"
    );
}

#[test]
fn tier_and_rank_parse_case_insensitively_with_defaults() {
    assert_eq!(parse_tier("premium"), UserEconomyTier::Premium);
    assert_eq!(parse_tier("SUPPORTER"), UserEconomyTier::Supporter);
    assert_eq!(parse_tier("garbage"), UserEconomyTier::Free);
    assert_eq!(parse_rank("nocturne"), IdentityRank::Nocturne);
    assert_eq!(parse_rank("FOUNDER"), IdentityRank::Founder);
    assert_eq!(parse_rank("unknown"), IdentityRank::Lumen);
}

#[test]
fn suspended_with_ended_until_reads_active() {
    let ended = rec("SUSPENDED", Some("2026-01-01T00:00:00Z"), None);
    assert_eq!(effective_status(&ended, now()), "ACTIVE");
    let future = rec("SUSPENDED", Some("2026-03-01T00:00:00Z"), None);
    assert_eq!(effective_status(&future, now()), "SUSPENDED");
    // unparseable expiry: NOT ended
    let garbage = rec("SUSPENDED", Some("not-a-date"), None);
    assert_eq!(effective_status(&garbage, now()), "SUSPENDED");
    // no expiry: indefinite
    let indefinite = rec("SUSPENDED", None, None);
    assert_eq!(effective_status(&indefinite, now()), "SUSPENDED");
    // status casing/whitespace normalized
    let messy = rec("  banned  ", None, None);
    assert_eq!(effective_status(&messy, now()), "BANNED");
}

#[test]
fn merge_banned_wins_then_suspended_then_active() {
    let a = rec("ACTIVE", None, None);
    let s = rec("SUSPENDED", Some("2026-03-01T00:00:00Z"), Some("rule breach"));
    let b = rec("BANNED", None, Some("fraud"));
    assert_eq!(merge_restrictions(&[&a], now()).active_status, "ACTIVE");
    let m = merge_restrictions(&[&a, &s], now());
    assert_eq!(m.active_status, "SUSPENDED");
    assert_eq!(m.final_reason.as_deref(), Some("rule breach"));
    let m2 = merge_restrictions(&[&s, &b], now());
    assert_eq!(m2.active_status, "BANNED");
    assert_eq!(m2.final_reason.as_deref(), Some("fraud"));
}

#[test]
fn one_expired_suspension_never_lifts_the_other_sources_restriction() {
    // Source A: suspension ended yesterday -> effective ACTIVE, filtered out.
    // Source B: suspended indefinitely -> SUSPENDED stands, no expiry reported.
    let a = rec("SUSPENDED", Some("2026-01-01T00:00:00Z"), Some("old issue"));
    let b = rec("SUSPENDED", None, Some("ongoing review"));
    let m = merge_restrictions(&[&a, &b], now());
    assert_eq!(m.active_status, "SUSPENDED");
    assert_eq!(m.final_reason.as_deref(), Some("ongoing review"));
    assert_eq!(m.final_until, None, "no expiry when any strongest record lacks one");
}

#[test]
fn final_until_is_latest_only_when_all_strongest_have_one() {
    let a = rec("SUSPENDED", Some("2026-03-01T00:00:00Z"), None);
    let b = rec("SUSPENDED", Some("2026-04-01T00:00:00Z"), None);
    let m = merge_restrictions(&[&a, &b], now());
    assert_eq!(m.active_status, "SUSPENDED");
    assert_eq!(m.final_until.as_deref(), Some("2026-04-01T00:00:00Z"));
    assert_eq!(m.final_reason, None);
}

#[test]
fn valid_status_vocabulary_is_closed() {
    assert_eq!(VALID_ACCOUNT_STATUSES, ["ACTIVE", "SUSPENDED", "BANNED"]);
    assert_eq!(MAX_RESPONSE_BYTES, 65536);
}

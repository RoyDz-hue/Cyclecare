//! Port of the pure decision cores in data/economy/SupabaseEconomyService.kt
//! (main@31b88f5): rank thresholds, transfer validation preconditions,
//! daily-claim gate, offline-claim success arithmetic + message, and
//! optCoinAmount. Network, session, and AuthPreferences remain seams (the
//! full service port supplies them via traits); this module owns the rules.
//!
//! Verbatim blocks adapted only in return mechanics (documented in
//! /downloads/parity/economy/EconomyParity.kt).
//!
//! org.json scope: opt_coin_amount mirrors `opt(key) is Number ->
//! BigDecimal(value.toString())`. Android framework org.json types decimals
//! as Double; the Maven jar types them BigDecimal (documented divergence,
//! probe-locked earlier). For values in coin ranges both paths stringify
//! identically; the divergence is flagged, not silently resolved.

use bigdecimal::BigDecimal;
use std::str::FromStr;

use crate::economy_status::{IdentityRank, UserEconomyTier};
use crate::kotlin_text::kotlin_trim;

pub fn rank_kotlin_name(rank: IdentityRank) -> &'static str {
    match rank {
        IdentityRank::Lumen => "LUMEN",
        IdentityRank::Aurelia => "AURELIA",
        IdentityRank::Celestia => "CELESTIA",
        IdentityRank::Solara => "SOLARA",
        IdentityRank::Nocturne => "NOCTURNE",
        IdentityRank::Founder => "FOUNDER",
    }
}

pub fn tier_title(tier: UserEconomyTier) -> &'static str {
    match tier {
        UserEconomyTier::Free => "Free",
        UserEconomyTier::Supporter => "Supporter",
        UserEconomyTier::Premium => "Premium",
    }
}

/// Kotlin Float.toString of the tier multiplier (1.0/2.0/3.0).
pub fn tier_multiplier_string(tier: UserEconomyTier) -> &'static str {
    match tier {
        UserEconomyTier::Free => "1.0",
        UserEconomyTier::Supporter => "2.0",
        UserEconomyTier::Premium => "3.0",
    }
}

/// calculateRankForLifetimeCoins (verbatim threshold order).
pub fn calculate_rank_for_lifetime_coins(lifetime_coins: &BigDecimal) -> IdentityRank {
    if *lifetime_coins >= BigDecimal::from(7500) {
        IdentityRank::Founder
    } else if *lifetime_coins >= BigDecimal::from(3000) {
        IdentityRank::Nocturne
    } else if *lifetime_coins >= BigDecimal::from(1200) {
        IdentityRank::Solara
    } else if *lifetime_coins >= BigDecimal::from(500) {
        IdentityRank::Celestia
    } else if *lifetime_coins >= BigDecimal::from(150) {
        IdentityRank::Aurelia
    } else {
        IdentityRank::Lumen
    }
}

/// optCoinAmount(obj, key, fallback): only JSON numbers convert, via their
/// org.json toString; everything else (absent, null, string, object) falls
/// back.
pub fn opt_coin_amount(obj: &serde_json::Value, key: &str, fallback: &BigDecimal) -> BigDecimal {
    match obj.get(key) {
        Some(serde_json::Value::Number(n)) => {
            BigDecimal::from_str(&n.to_string()).unwrap_or_else(|_| fallback.clone())
        }
        _ => fallback.clone(),
    }
}

/// transferCoins validation preconditions, verbatim order + messages.
/// `own_email` is AuthPreferences.userEmail (nullable). Returns the error
/// message, or None when validation passes.
pub fn validate_transfer(
    balance: &BigDecimal,
    recipient_email: &str,
    own_email: Option<&str>,
    amount: &BigDecimal,
) -> Option<&'static str> {
    if amount.sign() == bigdecimal::num_bigint::Sign::Minus
        || amount.sign() == bigdecimal::num_bigint::Sign::NoSign
    {
        return Some("Enter a valid transfer amount.");
    }
    if strip_trailing_zeros_scale(amount) > 3 {
        return Some("Cycle Coins support up to 3 decimal places.");
    }
    if *balance < *amount {
        return Some("Insufficient Cycle Coins balance.");
    }
    if let Some(own) = own_email {
        // Kotlin equals(ignoreCase = true) is Unicode-aware
                // (Kelvin K == k, İ == i); ASCII-only folding diverges.
                if crate::kotlin_text::kotlin_equals_ignore_case(&kotlin_trim(recipient_email), own) {
            return Some("Cannot transfer coins to your own account.");
        }
    }
    None
}

/// java BigDecimal.stripTrailingZeros().scale().
fn strip_trailing_zeros_scale(amount: &BigDecimal) -> i64 {
    let (int, scale) = amount.as_bigint_and_exponent();
    if int.sign() == bigdecimal::num_bigint::Sign::NoSign {
        return 0; // unreachable via validate_transfer (signum<=0 rejected first)
    }
    let mut digits = int.magnitude().to_string();
    let mut scale = scale;
    while digits.ends_with('0') {
        digits.pop();
        scale -= 1;
    }
    // Java can yield negative scale (e.g. 1.5000 -> 1.5 scale 1; 100 -> 1E+2 scale -2)
    scale
}

/// verifyAndMineCoins claim gate. `today` is the caller's LocalDate.now()
/// toString seam. Returns the error message, or None to proceed to the RPC.
pub fn claim_gate(
    last_mined_date_string: Option<&str>,
    pending_offline_claim_date: Option<&str>,
    is_pending_reconciliation: bool,
    today: &str,
) -> Option<&'static str> {
    if last_mined_date_string == Some(today) || pending_offline_claim_date == Some(today) {
        return Some("You've already claimed today's reward. Next claim unlocks at midnight.");
    }
    if is_pending_reconciliation && pending_offline_claim_date != Some(today) {
        return Some("Connect to the internet to sync your previous offline reward first.");
    }
    None
}

/// verifyAndMineCoins offline success branch: arithmetic + rank + message,
/// verbatim.
pub struct OfflineClaimOutcome {
    pub awarded: BigDecimal,
    pub new_balance: BigDecimal,
    pub new_earned: BigDecimal,
    pub new_rank: IdentityRank,
    pub message: String,
}

pub fn offline_claim(
    coin_balance: &BigDecimal,
    lifetime_earned: &BigDecimal,
    tier: UserEconomyTier,
    base_reward: i64,
) -> OfflineClaimOutcome {
    let multiplier = BigDecimal::from_str(tier_multiplier_string(tier)).unwrap();
    let awarded = BigDecimal::from(base_reward) * &multiplier;
    let new_balance = coin_balance + &awarded;
    let new_earned = lifetime_earned + &awarded;
    let new_rank = calculate_rank_for_lifetime_coins(&new_earned);
    let message = format!(
        "+{} Cycle Coins ({}× {}). Will sync when online.",
        awarded,
        tier_multiplier_string(tier),
        tier_title(tier)
    );
    OfflineClaimOutcome {
        awarded,
        new_balance,
        new_earned,
        new_rank,
        message,
    }
}

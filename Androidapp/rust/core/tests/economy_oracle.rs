//! Cross-language parity: economy rules (rank, transfer, claim gate, offline
//! claim, optCoinAmount) vs verbatim production Kotlin on JVM
//! (/downloads/parity/economy/EconomyParity.kt, org.json 20240303).

use bigdecimal::BigDecimal;
use cyclecare_core::economy_rules::*;
use cyclecare_core::economy_status::{parse_tier, UserEconomyTier};
use std::path::PathBuf;
use std::str::FromStr;

fn dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("fixtures/oracle")
}

fn table(name: &str) -> Vec<(String, Vec<String>)> {
    let p = dir().join(name);
    std::fs::read_to_string(&p)
        .unwrap_or_else(|e| panic!("read {name}: {e}"))
        .lines()
        .filter(|l| !l.is_empty())
        .map(|l| {
            let mut it = l.split('\t');
            (it.next().unwrap().to_string(), it.map(String::from).collect())
        })
        .collect()
}

fn results(name: &str) -> Option<Vec<(String, Vec<String>)>> {
    let p = dir().join(name);
    if !p.exists() {
        eprintln!("oracle: {name} not generated yet; skipping");
        return None;
    }
    Some(table(name))
}

fn decode(token: &str) -> Option<String> {
    match token {
        "<NULL>" => None,
        "<EMPTY>" => Some(String::new()),
        other => Some(other.to_string()),
    }
}

#[test]
fn rank_matches_kotlin_outputs() {
    let Some(rs) = results("results_rank.tsv") else { return };
    for ((case, args), (_, expected)) in table("corpus_rank.tsv").iter().zip(rs.iter()) {
        let v = BigDecimal::from_str(&args[0]).unwrap();
        let actual = vec![rank_kotlin_name(calculate_rank_for_lifetime_coins(&v)).to_string()];
        assert_eq!(&actual, expected, "rank case {case} diverged from Kotlin");
    }
}

#[test]
fn transfer_matches_kotlin_outputs() {
    let Some(rs) = results("results_transfer.tsv") else { return };
    for ((case, args), (_, expected)) in table("corpus_transfer.tsv").iter().zip(rs.iter()) {
        let balance = BigDecimal::from_str(&args[0]).unwrap();
        let amount = BigDecimal::from_str(&args[3]).unwrap();
        let own = decode(&args[2]);
        let actual = vec![
            validate_transfer(&balance, &args[1], own.as_deref(), &amount)
                .unwrap_or("OK")
                .to_string(),
        ];
        assert_eq!(&actual, expected, "transfer case {case} diverged from Kotlin");
    }
}

#[test]
fn claim_gate_matches_kotlin_outputs() {
    let Some(rs) = results("results_claim_gate.tsv") else { return };
    for ((case, args), (_, expected)) in table("corpus_claim_gate.tsv").iter().zip(rs.iter()) {
        let actual = vec![claim_gate(
            decode(&args[0]).as_deref(),
            decode(&args[1]).as_deref(),
            args[2].parse().unwrap(),
            &args[3],
        )
        .unwrap_or("PROCEED")
        .to_string()];
        assert_eq!(&actual, expected, "claim_gate case {case} diverged from Kotlin");
    }
}

#[test]
fn offline_claim_matches_kotlin_outputs() {
    let Some(rs) = results("results_offline_claim.tsv") else { return };
    for ((case, args), (_, expected)) in table("corpus_offline_claim.tsv").iter().zip(rs.iter()) {
        let tier: UserEconomyTier = parse_tier(&args[2]);
        let o = offline_claim(
            &BigDecimal::from_str(&args[0]).unwrap(),
            &BigDecimal::from_str(&args[1]).unwrap(),
            tier,
            args[3].parse().unwrap(),
        );
        let actual = vec![
            o.awarded.to_string(),
            o.new_balance.to_string(),
            o.new_earned.to_string(),
            rank_kotlin_name(o.new_rank).to_string(),
            o.message,
        ];
        assert_eq!(&actual, expected, "offline_claim case {case} diverged from Kotlin");
    }
}

#[test]
fn opt_amount_matches_kotlin_outputs() {
    let Some(rs) = results("results_opt_amount.tsv") else { return };
    for ((case, args), (_, expected)) in table("corpus_opt_amount.tsv").iter().zip(rs.iter()) {
        let obj: serde_json::Value = serde_json::from_str(&args[0]).unwrap();
        let actual = vec![opt_coin_amount(&obj, "v", &BigDecimal::from_str("42.25").unwrap()).to_string()];
        assert_eq!(&actual, expected, "opt_amount case {case} diverged from Kotlin");
    }
}

#[test]
fn verified_coin_amount_matches_aosp_executed() {
    // AOSP-executed production truth (verbatim verifiedCoinAmount from
    // SupabaseEconomyService.kt run against compiled android-14.0.0_r1
    // org.json): integral doubles keep ".0" (363.0 -> "363.0"), -0.0 is
    // ACCEPTED and prints "0.0", scientific magnitudes expand to plain
    // decimal (1e22 -> "10000000000000000000000", 0.0001 -> "0.00010",
    // 10000000.0 -> "10000000").
    use cyclecare_core::economy_status::{verified_coin_amount, WalletNumber};
    use cyclecare_core::sync_codec::{JsonParser, JsonValue};
    let dir = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("fixtures/oracle");
    let results = std::fs::read_to_string(dir.join("results_verified_amount.tsv")).unwrap();
    let expected: std::collections::HashMap<String, String> = results
        .lines()
        .map(|l| {
            let mut it = l.splitn(2, '\t');
            (it.next().unwrap().to_string(), it.next().unwrap().to_string())
        })
        .collect();
    let mut checked = 0;
    for line in std::fs::read_to_string(dir.join("corpus_verified_amount.tsv")).unwrap().lines() {
        let mut it = line.splitn(3, '\t');
        let id = it.next().unwrap();
        let json = it.next().unwrap();
        let parsed = JsonParser::new(json).parse().unwrap();
        let obj = match parsed { JsonValue::Object(p) => p, _ => panic!("bad corpus") };
        let num = match obj.iter().find(|(k, _)| k == "v").map(|(_, v)| v) {
            Some(JsonValue::Int(i)) => WalletNumber::Integer(*i as i64),
            Some(JsonValue::Long(l)) => WalletNumber::Integer(*l),
            Some(JsonValue::Double(d)) => WalletNumber::Decimal(*d),
            _ => WalletNumber::NotANumber,
        };
        let actual = match verified_coin_amount("v", num) {
            Ok(t) => format!("OK:{t}"),
            Err(e) => format!("ERR:{e}"),
        };
        assert_eq!(&actual, expected.get(id).unwrap(), "verified amount mismatch for {id}");
        checked += 1;
    }
    assert_eq!(checked, 18);
}

#[test]
fn missing_coins_coerces_to_minimum() {
    use bigdecimal::BigDecimal;
    use std::str::FromStr;
    use cyclecare_core::economy_rules::missing_coins_for_unlock;
    // (price - balance) when balance < price
    assert_eq!(missing_coins_for_unlock(250, &BigDecimal::from(100)), BigDecimal::from(150));
    // balance above price still floors to 0.001 (dialog never shows 0)
    assert_eq!(missing_coins_for_unlock(250, &BigDecimal::from(300)), BigDecimal::from_str("0.001").unwrap());
    assert_eq!(missing_coins_for_unlock(250, &BigDecimal::from(250)), BigDecimal::from_str("0.001").unwrap());
    // fractional balance: 250 - 249.5 = 0.5
    assert_eq!(missing_coins_for_unlock(250, &BigDecimal::from_str("249.5").unwrap()), BigDecimal::from_str("0.5").unwrap());
}

#[test]
fn default_packages_localize_screen_path() {
    // The VaultEconomyScreen store path: localizePackages(DefaultCoinPackages, KE, no cache).
    use cyclecare_core::currency_locale::{default_coin_packages, localize_packages};
    let ke = localize_packages(&default_coin_packages(), "KE", None);
    assert_eq!(ke.len(), 3);
    assert_eq!(ke[0].price_display, "KES 389");
    assert_eq!(ke[2].price_display, "KES 1,949");
    assert!(!ke[0].is_hero_package); // hero = tag != null on the screen path
    assert!(ke[1].is_hero_package && ke[1].badge.as_deref() == Some("Most Cherished"));
    assert!(ke[2].is_hero_package && ke[2].badge.as_deref() == Some("Grand Value"));
    let us = localize_packages(&default_coin_packages(), "US", None);
    assert_eq!(us[0].price_display, "$2.99");
    assert_eq!(us[2].price_display, "$14.99");
}

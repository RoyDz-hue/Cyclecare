//! Port of data/economy/CurrencyLocaleManager.kt and the pure core of
//! data/economy/RemoteLocalPricing.kt (main@31b88f5).
//!
//! Host glue deliberately NOT here: TelephonyManager/Locale reads (the caller
//! supplies the three country candidates), SharedPreferences and the
//! RemoteLocalPricing.memoryCache layer, the get_public_pricing fetch and the
//! fx-refresh ping. cached_decision() models only the prefs-layer logic.
//!
//! JVM probe PricingParity.kt (parity/economy) executed the verbatim Kotlin
//! against AOSP org.json classes; every corpus row below is locked to it.
//! String.format(Locale.US, "%,.0f"/"%.2f") rounding is HALF_UP over the
//! EXACT binary double (f06 100.5 -> "KES 101", f07 102.5 -> "KES 103",
//! f08 99.995 -> "USD 100.00", f09 0.005 -> "USD 0.01"): implemented with
//! exact f64 -> BigInt rational rounding, never float arithmetic.

use crate::coin_format;
use crate::kotlin_text::{kotlin_is_blank, kotlin_trim};
use crate::sync_codec::{aosp_opt_string, JsonParser, JsonValue};
use bigdecimal::BigDecimal;
use num_bigint::BigInt;
use num_traits::{One, Signed, Zero};
use std::collections::HashMap;

// ---------- RemoteLocalPricing.LocalizedPrice ----------

#[derive(Debug, Clone, PartialEq)]
pub struct LocalizedPrice {
    pub currency: String,
    pub amount: f64,
    pub approx: bool,
    pub charge_currency: String,
    pub charge_amount: f64,
}

/// Exact decimal rounding of a binary double at `scale` digits with
/// RoundingMode.HALF_UP (Java Formatter semantics), returned as the scaled
/// integer. Negative inputs round symmetrically like Java's formatter.
fn round_half_up_scaled(amount: f64, scale: u32) -> BigInt {
    let bits = amount.to_bits();
    let negative = bits >> 63 != 0;
    let raw_exp = ((bits >> 52) & 0x7ff) as i64;
    let raw_mant = bits & 0xf_ffff_ffff_ffff;
    let (mant, exp2) = if raw_exp == 0 {
        (raw_mant, -1074i64) // subnormal
    } else {
        (raw_mant | 0x10_0000_0000_0000, raw_exp - 1075)
    };
    let mut num = BigInt::from(mant) * BigInt::from(10u64).pow(scale);
    let mut den = BigInt::one();
    if exp2 >= 0 {
        num <<= exp2 as usize;
    } else {
        den <<= (-exp2) as usize;
    }
    let q = &num / &den;
    let r = &num % &den;
    let rounded = if &r * 2u32 >= den { q + BigInt::one() } else { q };
    if negative {
        -rounded
    } else {
        rounded
    }
}

/// Java String.format(Locale.US, "%,d", v) for non-negative values.
fn group_thousands(digits: &str) -> String {
    let (sign, d) = digits.strip_prefix('-').map_or(("", digits), |r| ("-", r));
    let mut out = String::new();
    for (i, c) in d.chars().enumerate() {
        if i > 0 && (d.len() - i) % 3 == 0 {
            out.push(',');
        }
        out.push(c);
    }
    format!("{sign}{out}")
}

fn format_scaled(amount: f64, scale: u32) -> String {
    let q = round_half_up_scaled(amount, scale);
    let s = q.abs().to_string();
    let sign = if q.is_negative() { "-" } else { "" };
    if scale == 0 {
        return format!("{sign}{}", group_thousands(&s));
    }
    let scale = scale as usize;
    let padded = if s.len() <= scale {
        format!("{}{}", "0".repeat(scale + 1 - s.len()), s)
    } else {
        s
    };
    let (int_part, frac_part) = padded.split_at(padded.len() - scale);
    format!("{sign}{int_part}.{frac_part}")
}

/// LocalizedPrice.Companion.formatAmount (verbatim thresholds).
pub fn format_amount(currency: &str, amount: f64) -> String {
    let whole = amount == amount.trunc();
    let body = if amount >= 100.0 || whole {
        format_scaled(amount, 0)
    } else {
        format_scaled(amount, 2)
    };
    format!("{currency} {body}")
}

impl LocalizedPrice {
    /// Store display: "UGX 3,699" or "~UGX 3,699" when approximate.
    pub fn display_text(&self) -> String {
        format!(
            "{}{}",
            if self.approx { "~" } else { "" },
            format_amount(&self.currency, self.amount)
        )
    }

    /// Checkout truth line, e.g. "Charged $0.99 USD" or "Charged KES 50 via M-Pesa".
    pub fn charge_text(&self) -> String {
        if self.charge_currency == "KES" {
            let body = format_amount("KES", self.charge_amount);
            let stripped = body.strip_prefix("KES ").unwrap_or(&body);
            format!("Charged KES {} via M-Pesa", kotlin_trim(stripped))
        } else {
            format!(
                "Charged {}",
                format_amount(&self.charge_currency, self.charge_amount)
            )
        }
    }
}

// ---------- RemoteLocalPricing.parse ----------

fn obj_get<'a>(v: &'a JsonValue, key: &str) -> Option<&'a JsonValue> {
    match v {
        JsonValue::Object(pairs) => pairs.iter().find(|(k, _)| k == key).map(|(_, v)| v),
        _ => None,
    }
}

/// AOSP optJSONObject: the value only when it is an object.
fn aosp_opt_object<'a>(v: &'a JsonValue, key: &str) -> Option<&'a JsonValue> {
    match obj_get(v, key) {
        Some(o @ JsonValue::Object(_)) => Some(o),
        _ => None,
    }
}

fn aosp_opt_array<'a>(v: &'a JsonValue, key: &str) -> Option<&'a Vec<JsonValue>> {
    match obj_get(v, key) {
        Some(JsonValue::Array(items)) => Some(items),
        _ => None,
    }
}

/// AOSP JSON.toDouble: numbers coerce; strings parse via Double.valueOf
/// (which trims); everything else is null -> fallback.
fn aosp_opt_double(v: &JsonValue, key: &str, fallback: f64) -> f64 {
    match obj_get(v, key) {
        Some(JsonValue::Int(i)) => *i as f64,
        Some(JsonValue::Long(l)) => *l as f64,
        Some(JsonValue::Double(d)) => *d,
        Some(JsonValue::Str(s)) => s.trim().parse::<f64>().unwrap_or(fallback),
        _ => fallback,
    }
}

/// AOSP optBoolean(name, false): JSON.toBoolean (bool itself, string
/// equalsIgnoreCase "true", else null -> fallback).
fn aosp_opt_boolean(v: &JsonValue, key: &str, fallback: bool) -> bool {
    match obj_get(v, key) {
        Some(JsonValue::Bool(b)) => *b,
        Some(JsonValue::Str(s)) => s.eq_ignore_ascii_case("true"),
        _ => fallback,
    }
}

/// Kotlin String.length (UTF-16 code units).
fn kotlin_len(s: &str) -> usize {
    s.encode_utf16().count()
}

/// RemoteLocalPricing.parse: null on any malformed input (blank, non-object,
/// missing/non-array packages, bad element, bad display, validation failure).
pub fn parse_pricing(raw: Option<&str>) -> Option<HashMap<String, LocalizedPrice>> {
    let raw = raw?;
    if kotlin_is_blank(raw) {
        return None;
    }
    let root = match JsonParser::new(raw).parse() {
        Ok(v @ JsonValue::Object(_)) => v,
        _ => return None, // JSONObject(raw) throws -> catch -> null
    };
    let packages = aosp_opt_array(&root, "packages")?;
    let mut out: HashMap<String, LocalizedPrice> = HashMap::with_capacity(packages.len());
    for pkg in packages {
        let pkg = match pkg {
            JsonValue::Object(_) => pkg, // getJSONObject(i) throws otherwise
            _ => return None,
        };
        let id = aosp_opt_string(pkg, "id", "");
        let display = aosp_opt_object(pkg, "display")?;
        let currency = aosp_opt_string(display, "currency", "");
        let amount = aosp_opt_double(display, "amount", f64::NAN);
        let charge_currency = aosp_opt_string(display, "charge_currency", "");
        let charge_amount = aosp_opt_double(display, "charge_amount", f64::NAN);
        if kotlin_is_blank(&id) || kotlin_len(&currency) != 3 || amount.is_nan() || amount <= 0.0 {
            return None;
        }
        if kotlin_len(&charge_currency) != 3 || charge_amount.is_nan() || charge_amount <= 0.0 {
            return None;
        }
        let approx = aosp_opt_boolean(display, "approx", false);
        out.insert(
            id,
            LocalizedPrice {
                currency,
                amount,
                approx,
                charge_currency,
                charge_amount,
            },
        );
    }
    if out.is_empty() {
        None
    } else {
        Some(out)
    }
}

pub const MAX_CACHE_AGE_MS: i64 = 12 * 60 * 60 * 1000;

/// RemoteLocalPricing.cached prefs-layer decision (region match, freshness
/// window, parse). The memoryCache layer is host-side; callers check it first.
pub fn cached_decision(
    prefs_region: Option<&str>,
    fetched_at_ms: i64,
    now_ms: i64,
    json: Option<&str>,
    region: &str,
) -> Option<HashMap<String, LocalizedPrice>> {
    if prefs_region != Some(region) {
        return None;
    }
    if fetched_at_ms <= 0 || now_ms - fetched_at_ms > MAX_CACHE_AGE_MS {
        return None;
    }
    parse_pricing(json)
}

// ---------- CurrencyLocaleManager ----------

/// detectCountryCode pure core: SIM, then network, then device locale, then
/// "KE". The Context/TelephonyManager/Locale reads are host glue; callers
/// supply the three candidates (None for blank/absent, matching the Kotlin
/// isNullOrBlank checks). uppercase(Locale.US) is ASCII-faithful for ISO
/// country codes.
pub fn detect_country_code(
    sim_country: Option<&str>,
    network_country: Option<&str>,
    locale_country: Option<&str>,
) -> String {
    for candidate in [sim_country, network_country, locale_country] {
        if let Some(c) = candidate {
            if !kotlin_is_blank(c) {
                return c.to_uppercase();
            }
        }
    }
    "KE".to_string()
}

#[derive(Debug, Clone, PartialEq)]
pub struct LocalizedCoinPackage {
    pub id: String,
    pub title: String,
    pub coins: i64,
    pub bonus_coins: i64,
    pub price_display: String,
    pub charge_display: Option<String>,
    pub is_hero_package: bool,
    pub badge: Option<String>,
    pub description: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct CoinPurchasePackage {
    pub id: String,
    pub title: String,
    pub coins: i64,
    pub bonus_coins: i64,
    pub price_usd: String,
    pub price_kes: i64,
    pub tag: Option<String>,
    pub description: String,
}

/// The server accepts only these fixed package IDs and charges the matching
/// KES or USD amount; other countries use USD/PayPal until a local provider
/// is implemented.
pub fn get_localized_packages(kenya: bool) -> Vec<LocalizedCoinPackage> {
    let prices: [&str; 3] = if kenya {
        ["KES 389", "KES 909", "KES 1,949"]
    } else {
        ["$2.99", "$6.99", "$14.99"]
    };
    vec![
        LocalizedCoinPackage {
            id: "pkg_seed".into(),
            title: "Bloom Seed".into(),
            coins: 250,
            bonus_coins: 0,
            price_display: prices[0].into(),
            charge_display: None,
            is_hero_package: true,
            badge: Some("Starter".into()),
            description: "A starter bundle from the CycleCare server catalog.".into(),
        },
        LocalizedCoinPackage {
            id: "pkg_sanctuary".into(),
            title: "Sanctuary Vault".into(),
            coins: 750,
            bonus_coins: 100,
            price_display: prices[1].into(),
            charge_display: None,
            is_hero_package: true,
            badge: Some("Most Cherished".into()),
            description: "Includes 100 bonus coins at the fixed server price.".into(),
        },
        LocalizedCoinPackage {
            id: "pkg_celestial".into(),
            title: "Celestial Treasury".into(),
            coins: 2000,
            bonus_coins: 400,
            price_display: prices[2].into(),
            charge_display: None,
            is_hero_package: false,
            badge: Some("Grand Value".into()),
            description: "Includes 400 bonus coins at the fixed server price.".into(),
        },
    ]
}

/// Store display list from any package source. Kenyan devices see KES; every
/// other region sees USD. Server-cached local prices override display only.
pub fn localize_packages(
    packages: &[CoinPurchasePackage],
    region: &str,
    local_prices: Option<&HashMap<String, LocalizedPrice>>,
) -> Vec<LocalizedCoinPackage> {
    let kenya = region == "KE";
    packages
        .iter()
        .map(|pkg| {
            let local = local_prices.and_then(|m| m.get(&pkg.id));
            let price_display = match local {
                Some(l) => l.display_text(),
                None => {
                    if kenya && pkg.price_kes > 0 {
                        format!("KES {}", group_thousands(&pkg.price_kes.to_string()))
                    } else {
                        pkg.price_usd.clone()
                    }
                }
            };
            LocalizedCoinPackage {
                id: pkg.id.clone(),
                title: pkg.title.clone(),
                coins: pkg.coins,
                bonus_coins: pkg.bonus_coins,
                price_display,
                charge_display: local.map(|l| l.charge_text()),
                is_hero_package: pkg.tag.is_some(),
                badge: pkg.tag.clone(),
                description: pkg.description.clone(),
            }
        })
        .collect()
}

/// Builds a shareable request without changing the caller's wallet.
pub fn build_shareable_coin_request_message(
    user_email: Option<&str>,
    missing_coins: &BigDecimal,
) -> String {
    let email = user_email.unwrap_or("my registered email");
    format!(
        "Hi! I'm using CycleCare to track my cycle health. Could you send me {} Cycle Coins to help me unlock an artifact? My registered account is: {}. Let's track together on CycleCare!",
        coin_format::format(missing_coins),
        email
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::str::FromStr;

    #[test]
    fn detect_corpus_locked_to_jvm() {
        assert_eq!(detect_country_code(Some("ke"), None, Some("US")), "KE"); // d01
        assert_eq!(detect_country_code(Some(""), Some("ug"), Some("US")), "UG"); // d02
        assert_eq!(detect_country_code(None, None, Some("US")), "US"); // d03
        assert_eq!(detect_country_code(Some(" "), Some(" "), Some("")), "KE"); // d04
        assert_eq!(detect_country_code(None, None, None), "KE"); // d05 (empty locale country)
        assert_eq!(detect_country_code(Some("uk"), None, Some("US")), "UK"); // d06
    }

    #[test]
    fn package_tables_locked_to_jvm() {
        let ke = get_localized_packages(true);
        assert_eq!(
            ke.iter().map(|p| p.price_display.as_str()).collect::<Vec<_>>(),
            vec!["KES 389", "KES 909", "KES 1,949"]
        );
        assert_eq!(ke[0].id, "pkg_seed");
        assert!(ke[0].is_hero_package && ke[0].badge.as_deref() == Some("Starter"));
        assert_eq!(ke[1].bonus_coins, 100);
        assert!(!ke[2].is_hero_package);
        let us = get_localized_packages(false);
        assert_eq!(
            us.iter().map(|p| p.price_display.as_str()).collect::<Vec<_>>(),
            vec!["$2.99", "$6.99", "$14.99"]
        );
    }

    fn pkgs() -> Vec<CoinPurchasePackage> {
        vec![
            CoinPurchasePackage {
                id: "pkg_a".into(), title: "A".into(), coins: 250, bonus_coins: 0,
                price_usd: "$2.99".into(), price_kes: 1949, tag: Some("Hot".into()),
                description: "desc-a".into(),
            },
            CoinPurchasePackage {
                id: "pkg_b".into(), title: "B".into(), coins: 750, bonus_coins: 100,
                price_usd: "$6.99".into(), price_kes: 0, tag: None, description: "desc-b".into(),
            },
        ]
    }

    #[test]
    fn localize_corpus_locked_to_jvm() {
        let ke = localize_packages(&pkgs(), "KE", None);
        assert_eq!(ke[0].price_display, "KES 1,949"); // l01
        assert_eq!(ke[1].price_display, "$6.99"); // priceKes=0 -> USD
        assert!(ke[0].charge_display.is_none());
        assert!(ke[0].is_hero_package && ke[0].badge.as_deref() == Some("Hot"));
        assert!(!ke[1].is_hero_package && ke[1].badge.is_none());
        let us = localize_packages(&pkgs(), "US", None);
        assert_eq!(us[0].price_display, "$2.99"); // l03: KES ignored outside KE

        let mut prices = HashMap::new();
        prices.insert(
            "pkg_a".to_string(),
            LocalizedPrice {
                currency: "UGX".into(), amount: 3699.0, approx: true,
                charge_currency: "USD".into(), charge_amount: 0.99,
            },
        );
        let ug = localize_packages(&pkgs(), "UG", Some(&prices));
        assert_eq!(ug[0].price_display, "~UGX 3,699");
        assert_eq!(ug[0].charge_display.as_deref(), Some("Charged USD 0.99"));
        assert_eq!(ug[1].price_display, "$6.99"); // no local price -> USD
    }

    #[test]
    fn format_amount_corpus_locked_to_jvm() {
        assert_eq!(format_amount("UGX", 3699.0), "UGX 3,699"); // f01
        assert_eq!(format_amount("KES", 50.0), "KES 50"); // f02
        assert_eq!(format_amount("USD", 0.99), "USD 0.99"); // f03
        assert_eq!(format_amount("UGX", 99.5), "UGX 99.50"); // f04
        assert_eq!(format_amount("USD", 100.0), "USD 100"); // f05
        assert_eq!(format_amount("KES", 100.5), "KES 101"); // f06 HALF_UP
        assert_eq!(format_amount("KES", 102.5), "KES 103"); // f07 HALF_UP (not half-even)
        assert_eq!(format_amount("USD", 99.995), "USD 100.00"); // f08
        assert_eq!(format_amount("USD", 0.005), "USD 0.01"); // f09
    }

    #[test]
    fn display_and_charge_text_locked_to_jvm() {
        let lp = LocalizedPrice {
            currency: "UGX".into(), amount: 3699.0, approx: true,
            charge_currency: "USD".into(), charge_amount: 0.99,
        };
        assert_eq!(lp.display_text(), "~UGX 3,699"); // t01
        assert_eq!(lp.charge_text(), "Charged USD 0.99"); // t02
        let lp2 = LocalizedPrice {
            currency: "KES".into(), amount: 50.0, approx: false,
            charge_currency: "KES".into(), charge_amount: 50.0,
        };
        assert_eq!(lp2.display_text(), "KES 50"); // t03
        assert_eq!(lp2.charge_text(), "Charged KES 50 via M-Pesa"); // t04
        let lp3 = LocalizedPrice {
            charge_amount: 49.5, ..lp2.clone()
        };
        assert_eq!(lp3.charge_text(), "Charged KES 49.50 via M-Pesa"); // t05
    }

    #[test]
    fn parse_corpus_locked_to_jvm() {
        let ok = parse_pricing(Some(
            r#"{"packages":[{"id":"pkg_seed","display":{"currency":"UGX","amount":3699.0,"approx":true,"charge_currency":"USD","charge_amount":0.99}},{"id":"pkg_big","display":{"currency":"KES","amount":50,"charge_currency":"KES","charge_amount":50}}],"rates_stale":false}"#,
        ))
        .expect("p01");
        assert_eq!(ok.len(), 2);
        let seed = &ok["pkg_seed"];
        assert_eq!((seed.currency.as_str(), seed.amount, seed.approx), ("UGX", 3699.0, true));
        assert_eq!((ok["pkg_big"].charge_currency.as_str(), ok["pkg_big"].charge_amount), ("KES", 50.0));
        assert!(parse_pricing(Some(r#"{"packages":[{"id":"pkg_seed"}]}"#)).is_none()); // p02
        assert!(parse_pricing(Some(r#"{"packages":[{"id":" ","display":{"currency":"UGX","amount":1,"charge_currency":"USD","charge_amount":1}}]}"#)).is_none()); // p03
        assert!(parse_pricing(Some(r#"{"packages":[{"id":"x","display":{"currency":"UGXX","amount":1,"charge_currency":"USD","charge_amount":1}}]}"#)).is_none()); // p04
        assert!(parse_pricing(Some(r#"{"packages":[{"id":"x","display":{"currency":"UGX","charge_currency":"USD","charge_amount":1}}]}"#)).is_none()); // p05
        assert!(parse_pricing(Some(r#"{"packages":[{"id":"x","display":{"currency":"UGX","amount":0,"charge_currency":"USD","charge_amount":1}}]}"#)).is_none()); // p06
        assert!(parse_pricing(Some(r#"{"packages":[]}"#)).is_none()); // p07
        assert!(parse_pricing(Some("  ")).is_none()); // p08
        assert!(parse_pricing(Some("not json")).is_none()); // p09
        let coerced = parse_pricing(Some(r#"{"packages":[{"id":"x","display":{"currency":"UGX","amount":"3699","charge_currency":"USD","charge_amount":"0.99"}}]}"#)).expect("p10");
        assert_eq!(coerced["x"].amount, 3699.0);
        assert_eq!(coerced["x"].charge_amount, 0.99);
        assert!(!coerced["x"].approx);
        assert!(parse_pricing(Some(r#"{"packages":"notarray"}"#)).is_none()); // p11
    }

    #[test]
    fn cached_decision_corpus_locked_to_jvm() {
        let json = r#"{"packages":[{"id":"x","display":{"currency":"UGX","amount":1,"charge_currency":"USD","charge_amount":1}}]}"#;
        let now = 1_800_000_000_000i64;
        assert!(cached_decision(Some("UG"), now, now, Some(json), "KE").is_none()); // c01 region mismatch
        assert!(cached_decision(Some("KE"), now - 13 * 60 * 60 * 1000, now, Some(json), "KE").is_none()); // c02 stale
        assert!(cached_decision(Some("KE"), now, now, Some(json), "KE").is_some()); // c03 fresh
        assert!(cached_decision(Some("KE"), now, now, Some("garbage"), "KE").is_none()); // parse fails
        assert!(cached_decision(Some("KE"), 0, now, Some(json), "KE").is_none()); // never fetched
    }

    #[test]
    fn shareable_message_locked_to_jvm() {
        assert_eq!(
            build_shareable_coin_request_message(None, &BigDecimal::from_str("363.5").unwrap()),
            "Hi! I'm using CycleCare to track my cycle health. Could you send me 363.5 Cycle Coins to help me unlock an artifact? My registered account is: my registered email. Let's track together on CycleCare!"
        );
        assert_eq!(
            build_shareable_coin_request_message(Some("a@b.c"), &BigDecimal::from_str("30").unwrap()),
            "Hi! I'm using CycleCare to track my cycle health. Could you send me 30 Cycle Coins to help me unlock an artifact? My registered account is: a@b.c. Let's track together on CycleCare!"
        );
    }
}

//! Cross-language parity: payment validation, coin formatting, payment safety.
//! Kotlin results produced by the verbatim production sources on JVM
//! (/downloads/parity/payment/PaymentParity.kt, org.json 20240303).

use cyclecare_core::coin_format;
use cyclecare_core::payment_safety::{encoded_transaction_status_url, PaymentStatusVerifier};
use cyclecare_core::payment_validation::*;
use std::path::PathBuf;

fn dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("fixtures/oracle")
}

fn corpus(name: &str) -> Vec<(String, Vec<String>)> {
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
        eprintln!("oracle: {name} not generated yet (Kotlin runner has not run); skipping");
        return None;
    }
    Some(
        std::fs::read_to_string(&p)
            .unwrap()
            .lines()
            .filter(|l| !l.is_empty())
            .map(|l| {
                let mut it = l.split('\t');
                (it.next().unwrap().to_string(), it.map(String::from).collect())
            })
            .collect(),
    )
}

fn decode(token: &str) -> String {
    match token {
        "<EMPTY>" => String::new(),
        "<SPACES>" => "   ".to_string(),
        other => other.replace("<TAB>", "\t"),
    }
}

fn vr(r: &ValidationResult) -> String {
    match r {
        ValidationResult::Valid => "Valid".to_string(),
        ValidationResult::Invalid(m) => format!("Invalid:{m}"),
    }
}

#[test]
fn phone_matches_kotlin_outputs() {
    let Some(rs) = results("results_phone.tsv") else { return };
    for ((case, args), (_, expected)) in corpus("corpus_phone.tsv").iter().zip(rs.iter()) {
        let input = decode(&args[0]);
        let actual = vec![
            vr(&validate_kenyan_phone_number(&input)),
            sanitize_kenyan_phone_number(&input),
        ];
        assert_eq!(&actual, expected, "phone case {case} diverged from Kotlin");
    }
}

#[test]
fn card_matches_kotlin_outputs() {
    let Some(rs) = results("results_card.tsv") else { return };
    for ((case, args), (_, expected)) in corpus("corpus_card.tsv").iter().zip(rs.iter()) {
        let actual = vec![vr(&validate_card_number(&decode(&args[0])))];
        assert_eq!(&actual, expected, "card case {case} diverged from Kotlin");
    }
}

#[test]
fn expiry_matches_kotlin_outputs() {
    let Some(rs) = results("results_expiry.tsv") else { return };
    for ((case, args), (_, expected)) in corpus("corpus_expiry.tsv").iter().zip(rs.iter()) {
        let current = (args[1].parse().unwrap(), args[2].parse().unwrap());
        let actual = vec![vr(&validate_card_expiry(&decode(&args[0]), current))];
        assert_eq!(&actual, expected, "expiry case {case} diverged from Kotlin");
    }
}

#[test]
fn cvv_matches_kotlin_outputs() {
    let Some(rs) = results("results_cvv.tsv") else { return };
    for ((case, args), (_, expected)) in corpus("corpus_cvv.tsv").iter().zip(rs.iter()) {
        let actual = vec![vr(&validate_card_cvv(&decode(&args[0])))];
        assert_eq!(&actual, expected, "cvv case {case} diverged from Kotlin");
    }
}

#[test]
fn coin_format_matches_kotlin_outputs() {
    let Some(rs) = results("results_coin_format.tsv") else { return };
    for ((case, args), (_, expected)) in corpus("corpus_coin_format.tsv").iter().zip(rs.iter()) {
        let value: bigdecimal::BigDecimal = args[0].parse().unwrap();
        let actual = vec![coin_format::format(&value)];
        assert_eq!(&actual, expected, "coin_format case {case} diverged from Kotlin");
    }
}

#[test]
fn capture_matches_kotlin_outputs() {
    let Some(rs) = results("results_capture.tsv") else { return };
    for ((case, args), (_, expected)) in corpus("corpus_capture.tsv").iter().zip(rs.iter()) {
        let status = PaymentStatusVerifier::capture_status(args[0].parse().unwrap(), &decode(&args[1]));
        let actual = vec![status.kotlin_name().to_string()];
        assert_eq!(&actual, expected, "capture case {case} diverged from Kotlin");
    }
}

#[test]
fn history_matches_kotlin_outputs() {
    let Some(rs) = results("results_history.tsv") else { return };
    for ((case, args), (_, expected)) in corpus("corpus_history.tsv").iter().zip(rs.iter()) {
        let actual = vec![PaymentStatusVerifier::history_status(&decode(&args[0]))];
        assert_eq!(&actual, expected, "history case {case} diverged from Kotlin");
    }
}

#[test]
fn url_matches_kotlin_outputs() {
    let Some(rs) = results("results_url.tsv") else { return };
    for ((case, args), (_, expected)) in corpus("corpus_url.tsv").iter().zip(rs.iter()) {
        let actual = vec![encoded_transaction_status_url(&args[0], &decode(&args[1]))];
        assert_eq!(&actual, expected, "url case {case} diverged from Kotlin");
    }
}

fn opt(s: &str) -> Option<String> {
    if s == "-" {
        None
    } else {
        Some(decode(s))
    }
}

#[test]
fn checkout_matches_kotlin_outputs() {
    use cyclecare_core::checkout_dialog as cd;
    use cyclecare_core::payment_models::PaymentOptionType as O;
    use cyclecare_core::payment_safety::{PaymentOperation, PaymentProvider};
    let Some(rs) = results("results_checkout.tsv") else { return };
    let parse_opt = |s: &str| match s {
        "MPESA" => O::Mpesa,
        "PAYPAL" => O::Paypal,
        "DEBIT_CREDIT_CARD" => O::DebitCreditCard,
        "CRYPTO" => O::Crypto,
        other => panic!("option {other}"),
    };
    for ((case, args), (_, expected)) in corpus("corpus_checkout.tsv").iter().zip(rs.iter()) {
        let actual: Vec<String> = match args[0].as_str() {
            "gate" => {
                let allows: Box<dyn Fn(PaymentProvider, PaymentOperation) -> bool> = match args[3].as_str() {
                    "disabled" => Box::new(|_, _| false),
                    "all" => Box::new(|_, _| true),
                    "mpesa" => Box::new(|p, _| p == PaymentProvider::Payhero),
                    "paypal" => Box::new(|p, _| p == PaymentProvider::PaypalStandard),
                    other => panic!("policy {other}"),
                };
                let opts = cd::released_checkout_options(args[1].parse().unwrap(), args[2].parse().unwrap(), allows);
                vec![if opts.is_empty() {
                    "EMPTY".to_string()
                } else {
                    opts.iter().map(|o| o.kotlin_name()).collect::<Vec<_>>().join(",")
                }]
            }
            "nextstep" => vec![cd::next_step(
                args[1].parse().unwrap(),
                args[2].parse().unwrap(),
                parse_opt(&args[3]),
                opt(&args[4]).as_deref(),
                args[5].parse().unwrap(),
            )
            .to_string()],
            "amount" => vec![cd::summary_amount(parse_opt(&args[1]), args[2].parse().unwrap(), &decode(&args[3]))],
            "coins" => {
                let c: i64 = args[1].parse().unwrap();
                let b: i64 = args[2].parse().unwrap();
                vec![cd::coins_line(c, b)]
            }
            "alt" => {
                let show = cd::show_mpesa_alt_line(parse_opt(&args[1]), args[2].parse().unwrap(), args[3].parse().unwrap());
                vec![
                    show.to_string(),
                    if show { cd::mpesa_alt_line(args[3].parse().unwrap()) } else { "-".to_string() },
                ]
            }
            "desc" => vec![cd::payment_method_description(parse_opt(&args[1])).to_string()],
            "icon" => vec![cd::method_icon_content_description(args[1].parse().unwrap(), parse_opt(&args[2]))],
            "tag" => vec![cd::payment_method_test_tag(parse_opt(&args[1]))],
            "submit" => vec![cd::mpesa_submit_enabled(
                args[1].parse().unwrap(),
                args[2].parse().unwrap(),
                opt(&args[3]).as_deref(),
                &decode(&args[4]),
            )
            .to_string()],
            "mpesabtn" => vec![cd::mpesa_button_label(args[1].parse().unwrap())],
            "paypalbtn" => vec![cd::paypal_button_label(&decode(&args[1]))],
            "footer" => vec![cd::secure_footer(parse_opt(&args[1])).to_string()],
            "ptitle" => vec![cd::processing_title(parse_opt(&args[1])).to_string()],
            "pbody" => vec![cd::processing_body(parse_opt(&args[1]), opt(&args[2]).as_deref())],
            "sbody" => vec![cd::success_body(
                args[1].parse().unwrap(),
                args[2].parse().unwrap(),
                opt(&args[3]).as_deref(),
            )],
            "count" => vec![cd::option_count_label(args[1].parse().unwrap()).to_string()],
            "dismiss" => vec![cd::dialog_can_dismiss(args[1].parse().unwrap()).to_string()],
            "panel" => {
                let p = cd::active_panel(args[1].parse().unwrap(), args[2].parse().unwrap());
                vec![format!("{p:?}")]
            }
            "form" => {
                let f = cd::active_form(parse_opt(&args[1]));
                vec![format!("{f:?}")]
            }
            other => panic!("kind {other}"),
        };
        assert_eq!(&actual, expected, "checkout case {case} diverged from Kotlin");
    }
}

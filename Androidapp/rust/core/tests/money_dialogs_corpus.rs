//! JVM-executed oracle replay for money_dialogs (fixtures/money_dialogs).
use bigdecimal::BigDecimal;
use cyclecare_core::money_dialogs::*;
use std::str::FromStr;

fn bd(s: &str) -> BigDecimal {
    BigDecimal::from_str(s).unwrap()
}

fn render(d: TransferDecision) -> String {
    match d {
        TransferDecision::Error(e) => format!("ERR|{e}"),
        TransferDecision::Send { recipient_email, amount } => format!("SEND|{recipient_email}|{amount}"),
    }
}

#[test]
fn transfer_chain_matches_jvm() {
    let expected = include_str!("fixtures/money_dialogs/jvm_expected.txt");
    let mut lines = expected.lines();
    let bal = bd("10.500");
    let cases: Vec<(&str, &str, BigDecimal)> = vec![
        ("sam@x.io", "5", bal.clone()),
        ("samx.io", "5", bal.clone()),
        ("  ", "5", bal.clone()),
        ("", "5", bal.clone()),
        ("sam@x.io", "5.", bal.clone()),
        ("sam@x.io", "5.1234", bal.clone()),
        ("sam@x.io", "abc", bal.clone()),
        ("sam@x.io", "-5", bal.clone()),
        ("sam@x.io", "1,5", bal.clone()),
        ("sam@x.io", ".5", bal.clone()),
        ("sam@x.io", "007", bal.clone()),
        ("sam@x.io", "5.250", bal.clone()),
        ("sam@x.io", "0", bal.clone()),
        ("sam@x.io", "0.000", bal.clone()),
        ("sam@x.io", "0.001", bal.clone()),
        ("sam@x.io", "10.500", bal.clone()),
        ("sam@x.io", "10.501", bal.clone()),
        ("sam@x.io", "11", bd("1234567.891")),
        ("sam@x.io", "  5  ", bal.clone()),
        ("  sam@x.io  ", "5", bal.clone()),
    ];
    for (email, amount, balance) in cases {
        assert_eq!(
            render(transfer_confirm_decision(email, amount, &balance)),
            lines.next().unwrap(),
            "transfer {email:?} {amount:?}"
        );
    }
    assert_eq!(lines.next(), Some("---missing---"));
    for (price, coins) in [(50i64, "20"), (50, "49.9995"), (50, "50"), (50, "60")] {
        assert_eq!(
            missing_coins(price, &bd(coins)).to_string(),
            lines.next().unwrap(),
            "missing {price} {coins}"
        );
    }
    assert_eq!(lines.next(), Some("---mpesa---"));
    for (phone, price) in [("0712345678", 100), ("0712", 100), ("  0712345678  ", 100), ("0712345678", 0), ("0712345678", -5)] {
        assert_eq!(mpesa_send_enabled(phone, price).to_string(), lines.next().unwrap(), "mpesa {phone}");
    }
    assert_eq!(mpesa_price_label(100), lines.next().unwrap());
    assert_eq!(mpesa_price_label(0), lines.next().unwrap());
    assert!(lines.next().is_none());
}

#[test]
fn need_more_coins_label_matches_jvm() {
    // fixtures/money_dialogs/bd_jvm_expected.txt tail (after the "---" row)
    assert_eq!(need_more_coins_label(&bd("30")), "Need 30 More Coins");
    assert_eq!(need_more_coins_label(&bd("0.001")), "Need 0.001 More Coins");
    assert_eq!(need_more_coins_label(&bd("48.7655")), "Need 48.7655 More Coins");
}

#[test]
fn mpesa_button_label_verbatim() {
    assert_eq!(mpesa_send_button_label(250), "Send payment prompt \u{b7} KES 250");
}

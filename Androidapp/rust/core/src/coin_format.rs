//! Port of util/CoinFormatter.kt (main@31b88f5): DecimalFormat("#,##0.###",
//! Locale.US symbols) over BigDecimal. Display-only; up to 3 decimal places,
//! trailing zeros never shown (3.50 -> "3.5"), grouping every 3 integer
//! digits, at least one integer digit ("0.5" stays "0.5", never ".5").
//! DecimalFormat's default RoundingMode is HALF_EVEN (probe-locked on JVM).

use bigdecimal::BigDecimal;
use bigdecimal::num_bigint::BigInt;

/// Format exactly as java.text.DecimalFormat("#,##0.###", US) does.
pub fn format(value: &BigDecimal) -> String {
    let (int, scale) = value.as_bigint_and_exponent();
    let original_negative = int.sign() == bigdecimal::num_bigint::Sign::Minus;
    // Round to at most 3 fraction digits, HALF_EVEN (DecimalFormat default).
    let target_scale = scale.min(3);
    let rounded = if scale > target_scale {
        round_half_even(&int, scale, target_scale)
    } else {
        int
    };
    let digits = rounded.magnitude().to_string();
    // DecimalFormat keeps the ORIGINAL value's sign even when rounding to
    // zero: format(new BigDecimal("-0.0005")) -> "-0" (probe-locked cf19).
    let negative = original_negative;
    let frac_len = target_scale.max(0) as usize;
    let (int_part, frac_part) = split_at_scale(&digits, frac_len);
    let grouped = group_thousands(&int_part);
    let frac_trimmed = frac_part.trim_end_matches('0');
    let mut out = String::new();
    if negative {
        out.push('-');
    }
    out.push_str(&grouped);
    if !frac_trimmed.is_empty() {
        out.push('.');
        out.push_str(frac_trimmed);
    }
    out
}

fn split_at_scale(digits: &str, frac_len: usize) -> (String, String) {
    if frac_len == 0 {
        return (digits.to_string(), String::new());
    }
    if digits.len() > frac_len {
        let split = digits.len() - frac_len;
        (digits[..split].to_string(), digits[split..].to_string())
    } else {
        ("0".to_string(), format!("{:0>width$}", digits, width = frac_len))
    }
}

fn group_thousands(int_part: &str) -> String {
    let mut out = String::new();
    for (i, c) in int_part.chars().enumerate() {
        if i > 0 && (int_part.len() - i) % 3 == 0 {
            out.push(',');
        }
        out.push(c);
    }
    out
}

/// Round the integer coefficient `int` (representing int * 10^-scale) to
/// `target_scale`, HALF_EVEN on the dropped digits.
fn round_half_even(int: &BigInt, scale: i64, target_scale: i64) -> BigInt {
    let drop = (scale - target_scale) as u32;
    let divisor = BigInt::from(10u32).pow(drop);
    if int.sign() == bigdecimal::num_bigint::Sign::Minus {
        // Work on magnitude to keep half-even symmetric, reapply sign.
        let (q, r) = div_rem(BigInt::from_biguint(bigdecimal::num_bigint::Sign::Plus, int.magnitude().clone()), divisor.clone());
        -round_quotient(q, &r, &divisor)
    } else {
        let (q, r) = div_rem(int.clone(), divisor.clone());
        round_quotient(q, &r, &divisor)
    }
}

fn div_rem(a: BigInt, b: BigInt) -> (BigInt, BigInt) {
    let q = &a / &b;
    let r = &a % &b;
    (q, r)
}

fn round_quotient(q: BigInt, r: &BigInt, divisor: &BigInt) -> BigInt {
    let twice = r * 2u32;
    if twice < *divisor {
        q
    } else if twice > *divisor {
        q + 1
    } else if &q % 2u32 == BigInt::from(0u32) {
        q // exactly half, quotient even -> keep
    } else {
        q + 1 // exactly half, quotient odd -> round up to even
    }
}

pub fn format_long(value: i64) -> String {
    format(&BigDecimal::from(value))
}

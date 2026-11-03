//! Java BigDecimal.toString parity (JDK 17 probe-locked,
//! tests/fixtures/money_dialogs/bd_jvm_expected.txt): plain decimal while
//! scale >= 0 and adjusted exponent >= -6, otherwise scientific notation
//! ("1E-7", "1.23E+5"). Scale is preserved exactly - no rounding, ever.
//! Used for source sites that interpolate a raw BigDecimal (e.g.
//! InsufficientCoinsDialog :76), as distinct from CoinFormatter's
//! DecimalFormat display path.

use bigdecimal::BigDecimal;
use bigdecimal::num_bigint::{BigInt, Sign};

pub fn java_bigdecimal_to_string(value: &BigDecimal) -> String {
    let (int, scale) = value.as_bigint_and_exponent();
    let negative = int.sign() == Sign::Minus;
    let digits = int.magnitude().to_string(); // no leading zeros, "0" for zero
    let num_digits = digits.len() as i64;
    let adjusted_exponent = num_digits - 1 - scale;
    let mut out = String::new();
    if negative {
        out.push('-');
    }
    if scale == 0 {
        // Plain integer form.
        out.push_str(&digits);
    } else if scale > 0 && adjusted_exponent >= -6 {
        // Plain with decimal point (scale > 0 here).
        let point = num_digits - scale;
        if point > 0 {
            out.push_str(&digits[..point as usize]);
            out.push('.');
            out.push_str(&digits[point as usize..]);
        } else {
            out.push_str("0.");
            for _ in 0..(-point) {
                out.push('0');
            }
            out.push_str(&digits);
        }
    } else {
        // Scientific: first digit, optional ".rest", E<signed exponent>.
        out.push_str(&digits[..1]);
        if num_digits > 1 {
            out.push('.');
            out.push_str(&digits[1..]);
        }
        out.push('E');
        if adjusted_exponent >= 0 {
            out.push('+');
        }
        out.push_str(&adjusted_exponent.to_string());
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::str::FromStr;

    #[test]
    fn jvm_probe_family() {
        let expected = include_str!("../tests/fixtures/money_dialogs/bd_jvm_expected.txt");
        let cases = [
            "0.001",
            "30",
            "48.7655",
            "12.340",
            "0",
            "0.000000",
            "0.10",
            "100000000000000000000050",
            "1E+7",
            "0.0000001",
            "7E-9",
            "123E-8",
            "1.23E+5",
            "-4.500",
            "-0.0005",
            "999999999999999999999999.999",
        ];
        for (i, c) in cases.iter().enumerate() {
            let line = expected.lines().nth(i).unwrap();
            let jvm = line.split(" => ").nth(1).unwrap();
            let v = BigDecimal::from_str(c).unwrap();
            assert_eq!(&java_bigdecimal_to_string(&v), jvm, "case {c}");
        }
    }
}

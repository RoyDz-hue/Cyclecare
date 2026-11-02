//! Kotlin/JVM text primitives, semantics PROVEN by executed probe
//! (FixtureProbe + TrimProbe2, kotlinc 2.1.0 on JRE 21, host JVM - the same
//! java.lang.Character tables ART uses; ICU divergence is regex-only).
//!
//! Kotlin trim()/trimStart()/trimEnd()/isBlank() use Char.isWhitespace
//! (= java.lang.Character.isWhitespace || isSpaceChar). Proven trimmed set:
//! U+0009-000D, U+001C-001F, U+0020, U+00A0, U+1680, U+2000-200A, U+2028,
//! U+2029, U+202F, U+205F, U+3000. Proven NOT whitespace: U+0085 (NEL),
//! U+180E. Rust char::is_whitespace (Unicode White_Space) differs at exactly
//! U+001C..U+001F (Kotlin trims, Rust does not) and U+0085 (Rust trims,
//! Kotlin does not).

pub fn kotlin_is_whitespace(c: char) -> bool {
    (c.is_whitespace() && c != '\u{85}') || ('\u{1C}'..='\u{1F}').contains(&c)
}

pub fn kotlin_trim(s: &str) -> &str {
    s.trim_matches(kotlin_is_whitespace)
}

pub fn kotlin_trim_start(s: &str) -> &str {
    s.trim_start_matches(kotlin_is_whitespace)
}

pub fn kotlin_trim_end(s: &str) -> &str {
    s.trim_end_matches(kotlin_is_whitespace)
}

pub fn kotlin_is_blank(s: &str) -> bool {
    s.chars().all(kotlin_is_whitespace)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn probe_locked_trim_set() {
        // Each case executed on the JVM: trimmed==true/false.
        for cp in [0x09u32, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
                   0xA0, 0x1680, 0x2000, 0x2003, 0x2007, 0x200A, 0x2028, 0x2029,
                   0x202F, 0x205F, 0x3000] {
            let c = char::from_u32(cp).unwrap();
            let s = format!("{0}x{0}", c);
            assert_eq!(kotlin_trim(&s), "x", "U+{:04X} must trim", cp);
            assert!(kotlin_is_blank(&c.to_string()), "U+{:04X} must be blank", cp);
        }
        for cp in [0x85u32, 0x180E] {
            let c = char::from_u32(cp).unwrap();
            let s = format!("{0}x{0}", c);
            assert_eq!(kotlin_trim(&s), s.as_str(), "U+{:04X} must NOT trim", cp);
            assert!(!kotlin_is_blank(&c.to_string()), "U+{:04X} must not be blank", cp);
        }
        assert_eq!(kotlin_trim_end("\u{2003}x\u{2003}"), "\u{2003}x");
        assert_eq!(kotlin_trim_start("\u{A0}x\u{A0}"), "x\u{A0}");
    }
}

use unicode_general_category::{get_general_category, GeneralCategory};

/// Kotlin Char.isDigit on a UTF-16 code unit = Unicode category Nd AND
/// basic-multilingual-plane only. Supplementary-plane Nd digits (e.g. Osmanya
/// U+104A0) reach Kotlin as surrogate pairs whose halves are not digits.
pub fn kotlin_is_digit(c: char) -> bool {
    (c as u32) <= 0xFFFF && get_general_category(c) == GeneralCategory::DecimalNumber
}

/// Kotlin regex `\s` (ASCII-only unless UNICODE_CHARACTER_CLASS is set):
/// matches [ \t\n\x0B\f\r] exactly.
pub fn kotlin_regex_ascii_space(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r')
}

/// Kotlin String.toIntOrNull: optional ASCII sign, ASCII digits only,
/// no whitespace, i32 range.
pub fn kotlin_to_int_or_null(s: &str) -> Option<i32> {
    s.parse::<i32>().ok().filter(|_| {
        let t = s.strip_prefix(['+', '-']).unwrap_or(s);
        !t.is_empty() && t.bytes().all(|b| b.is_ascii_digit())
    })
}

/// Zero codepoints of every basic-multilingual-plane Nd block (Unicode 15),
/// matching java.lang.Character.digit(c, 10) for BMP digits.
const BMP_ND_ZEROS: &[u32] = &[
    0x0030, 0x0660, 0x06F0, 0x07C0, 0x0966, 0x09E6, 0x0A66, 0x0AE6, 0x0B66,
    0x0BE6, 0x0C66, 0x0CE6, 0x0D66, 0x0DE6, 0x0E50, 0x0ED0, 0x0F20, 0x1040,
    0x1090, 0x17E0, 0x1810, 0x1946, 0x19D0, 0x1A80, 0x1A90, 0x1B50, 0x1BB0,
    0x1C40, 0x1C50, 0xA620, 0xA8D0, 0xA900, 0xA9D0, 0xA9F0, 0xAA50, 0xABF0,
    0xFF10,
];

/// Kotlin Char.digitToInt() at radix 10 = Character.digit(c, 10): numeric
/// value for any BMP Nd digit (e.g. fullwidth U+FF10-U+FF19), None otherwise.
pub fn kotlin_digit_to_int_10(c: char) -> Option<u32> {
    let cp = c as u32;
    if cp <= 0xFFFF {
        for &z in BMP_ND_ZEROS {
            if cp >= z && cp < z + 10 {
                return Some(cp - z);
            }
        }
    }
    None
}

/// Kotlin String.equals(other, ignoreCase = true): per-character
/// Character.equalsIgnoreCase - c1 == c2 OR simpleUpper(c1) ==
/// simpleUpper(c2) OR simpleLower(c1) == simpleLower(c2). Java uses SIMPLE
/// (1:1) case mappings; Rust's char::to_uppercase/to_lowercase can be
/// multi-char, so multi-char results fall back to the original char, except
/// U+0130 whose Java simple lowercase is 'i' (executed: "İST" == "ist",
/// "İST" != "i̇st" (i+U+0307), Kelvin U+212A == 'k').
fn java_simple_upper(c: char) -> char {
    let mut it = c.to_uppercase();
    let first = it.next().unwrap();
    if it.next().is_none() { first } else { c }
}

fn java_simple_lower(c: char) -> char {
    if c == '\u{0130}' {
        return 'i';
    }
    let mut it = c.to_lowercase();
    let first = it.next().unwrap();
    if it.next().is_none() { first } else { c }
}

pub fn kotlin_equals_ignore_case(a: &str, b: &str) -> bool {
    let ca: Vec<char> = a.chars().collect();
    let cb: Vec<char> = b.chars().collect();
    ca.len() == cb.len()
        && ca.iter().zip(cb.iter()).all(|(&x, &y)| {
            x == y
                || java_simple_upper(x) == java_simple_upper(y)
                || java_simple_lower(x) == java_simple_lower(y)
        })
}

/// Kotlin String.take(n): at most n UTF-16 code units, surrogate-safe
/// (never splits a supplementary-plane pair; a leading lone high surrogate
/// at the boundary is dropped with its pair kept whole).
pub fn take_utf16(s: &str, n: usize) -> String {
    let mut units = 0usize;
    let mut end = s.len();
    for (i, c) in s.char_indices() {
        let w = c.len_utf16();
        if units + w > n {
            end = i;
            break;
        }
        units += w;
    }
    s[..end].to_string()
}

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

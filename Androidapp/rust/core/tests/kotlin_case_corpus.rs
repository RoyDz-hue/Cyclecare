//! JVM-probe lock: Kotlin trim().lowercase() vs Rust trim().to_lowercase()
//! (kotlinc 2.1.0/JRE 21 vs rustc, /tmp/caseprobe, codepoint-compared).
//! 17 inputs including context-sensitive final sigma, dotted I, titlecase
//! digraphs, ypogegrammeni, Cherokee, sharp S: outputs identical on both
//! runtimes. classify_choices relies on this parity.

fn cps(s: &str) -> String {
    s.chars().map(|c| format!("{:x}", c as u32)).collect::<Vec<_>>().join(",")
}

#[test]
fn lowercase_matches_jvm_codepoints() {
    let rows: &[(&str, &str)] = &[
        ("\u{03A3}", "3c3"),
        ("\u{03A3}\u{03A3}", "3c3,3c2"),
        ("A\u{03A3}", "61,3c2"),
        ("\u{03A3}A", "3c3,61"),
        ("\u{039F}\u{03A3}", "3bf,3c2"),
        ("\u{0130}", "69,307"),
        ("I", "69"),
        ("\u{01C5}", "1c6"),
        ("\u{01C4}", "1c6"),
        ("\u{1FBC}", "1fb3"),
        ("UNKNOWN", "75,6e,6b,6e,6f,77,6e"),
        ("It Varies", "69,74,20,76,61,72,69,65,73"),
        ("DON'T KNOW", "64,6f,6e,27,74,20,6b,6e,6f,77"),
        ("\u{AB70}\u{AB81}", "ab70,ab81"),
        ("\u{FB00}", "fb00"),
        ("\u{1E9E}", "df"),
        ("A\u{1FBC}\u{03A3}", "61,1fb3,3c2"),
    ];
    for (input, expected_cps) in rows {
        assert_eq!(cps(&input.trim().to_lowercase()), *expected_cps, "input {}", cps(input));
    }
}

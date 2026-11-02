
// Executed AOSP literal-typing lock (p10.kt, org.json android-14.0.0_r1
// compiled classes, executed 2026-11-02 13:45 EAT). Ops flagged 0xFFFFFFFF;
// the full row set proves the shared Long-parse + Int-range-check path,
// octal fallthrough to Double ("08"), and String fallthrough on overflow.
// Format: literal, expected "Type:toString" exactly as the JVM printed.
#[test]
fn aosp_literal_typing_matches_executed_org_json() {
    use cyclecare_core::sync_codec::{JsonParser, JsonValue};
    let cases: [(&str, &str); 15] = [
        ("0xFFFFFFFF", "Long:4294967295"),
        ("0x1F", "Int:31"),
        ("-010", "Int:-10"),
        ("08", "Double:8.0"),
        ("0x8", "Int:8"),
        ("010", "Int:8"),
        ("0777", "Int:511"),
        ("0x7FFFFFFF", "Int:2147483647"),
        ("0x80000000", "Long:2147483648"),
        ("0x100000000", "Long:4294967296"),
        ("0xFFFFFFFFFFFFFFFF", "Str:0xFFFFFFFFFFFFFFFF"),
        ("0x10000000000000000", "Str:0x10000000000000000"),
        ("-08", "Int:-8"),
        ("09.5", "Double:9.5"),
        ("-010.5", "Double:-10.5"),
    ];
    for (lit, expect) in cases {
        let body = format!("{{\"v\":{lit}}}");
        let parsed = JsonParser::new(&body).parse().unwrap_or_else(|e| panic!("{lit}: parse err {e}"));
        let v = match parsed {
            JsonValue::Object(pairs) => pairs.into_iter().find(|(k, _)| k == "v").map(|(_, v)| v).unwrap(),
            other => panic!("{lit}: not an object: {other:?}"),
        };
        // JVM probe printed via v.toString(); Double.toString(8.0)="8.0"
        // while Rust Display prints "8" - compare Double by value, rest verbatim.
        let rendered = match &v {
            JsonValue::Int(i) => format!("Int:{i}"),
            JsonValue::Long(l) => format!("Long:{l}"),
            JsonValue::Double(d) => {
                let e = expect.strip_prefix("Double:").unwrap_or("?");
                assert_eq!(format!("{d}"), e.strip_suffix(".0").unwrap_or(e), "literal {lit}");
                "Double".to_string()
            }
            JsonValue::Str(s) => format!("Str:{s}"),
            other => panic!("{lit}: unexpected {other:?}"),
        };
        if rendered != "Double" { assert_eq!(rendered, expect, "literal {lit}"); }
    }
}

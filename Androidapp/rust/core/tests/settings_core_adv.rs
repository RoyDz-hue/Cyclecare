//! Adversarial boundary corpus for bounded_conflict_preview, executed on
//! JDK 17.0.21+9 + AOSP org.json (fixtures/settings_core/Adv.kt). Rows are
//! "input => output" with newlines escaped.
use cyclecare_core::settings_core::bounded_conflict_preview;

#[test]
fn adversarial_boundaries_match_jvm() {
    let expected = include_str!("fixtures/settings_core/adv_jvm_expected.txt");
    let inputs: Vec<String> = vec![
        "{'mood':'HAPPY'}".into(),
        "{mood:\"HAPPY\"}".into(),
        "{\"a\":1,\"a\":2}".into(),
        "{\"x\":0x10}".into(),
        "{\"x\":NaN}".into(),
        "{\"x\":Infinity}".into(),
        "{\"x\":-Infinity}".into(),
        "{\"x\":017}".into(),
        "  {\"mood\":\"CALM\"}  ".into(),
        "{\"k\":\"v\",}".into(),
        "{mood:CALM}".into(),
        "{\"greek_word\":\"ΟΔΟΣ\"}".into(),
        "{\"ΟΔΟΣ\":\"x\"}".into(),
        "{\"dotted\":\"İSTANBUL\"}".into(),
        "{\"ßeta_value\":\"STRASSE\"}".into(),
        "{\"σ\":\"Σ\"}".into(),
        "{\"x\":1e10}".into(),
        "{\"x\":10000000000}".into(),
        "{\"x\":-0.0}".into(),
        "{\"x\":0.30000000000000004}".into(),
        "{\"x\":2147483648}".into(),
        "{\"x\":2147483647}".into(),
        "{\"x\":1.0}".into(),
        "{\"x\":1e-7}".into(),
        "{\"x\":123456789012345678901234567890}".into(),
    ];
    for (i, input) in inputs.iter().enumerate() {
        let line = expected.lines().nth(i).expect("corpus short");
        let expected_out = line.split(" => ").nth(1).expect("row shape");
        let actual = bounded_conflict_preview(input).replace('\n', "\\n");
        assert_eq!(&actual, expected_out, "input {input:?}");
    }
}

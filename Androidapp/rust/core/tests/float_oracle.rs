//! JVM-executed oracle for java_float_to_string: regenerates the exact
//! sweep of tests/fixtures/data_export_pdf/Floats.java (see README there)
//! and asserts equality with the JDK 17 output corpus, line by line.
use cyclecare_core::data_export_pdf::java_float_to_string;

#[test]
fn float_oracle_matches_jdk17_on_145606_values() {
    let corpus = include_str!("fixtures/data_export_pdf/float_oracle.txt");
    let mut lines = corpus.lines();
    let mut n = 0usize;
    let mut check = |v: f32| {
        let expected = lines.next().expect("corpus shorter than sweep");
        let actual = java_float_to_string(v);
        assert_eq!(actual, expected, "mismatch for bits {:#x}", v.to_bits());
        n += 1;
    };
    for i in -2000i32..=2000 {
        check(i as f32);
    }
    for i in 0i32..=10000 {
        check(i as f32 / 100.0);
    }
    for i in 3400i32..=4200 {
        check(i as f32 / 100.0);
    }
    for i in 0i32..=96 {
        check(i as f32 / 4.0);
    }
    for i in 1i32..=1000 {
        check(i as f32 / 1000.0);
    }
    let mut s: u64 = 0x12345678;
    for _ in 0..1000000 {
        s = s.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        let f = f32::from_bits((s >> 32) as u32);
        let a = f.abs();
        if f.is_nan() || f.is_infinite() || a == 0.0 || a < 1e-3 || a >= 1e7 {
            continue;
        }
        check(f);
    }
    assert_eq!(n, 145606, "corpus size drift");
    assert!(lines.next().is_none(), "corpus longer than sweep");
}

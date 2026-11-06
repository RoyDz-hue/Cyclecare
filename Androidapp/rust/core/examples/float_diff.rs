//! Differential harness: same deterministic f32 sweep as Floats.java,
//! printing java_float_to_string per value.
use cyclecare_core::data_export_pdf::java_float_to_string;
fn main() {
    let mut out = String::new();
    for i in -2000i32..=2000 {
        out.push_str(&java_float_to_string(i as f32));
        out.push('\n');
    }
    for i in 0i32..=10000 {
        out.push_str(&java_float_to_string(i as f32 / 100.0));
        out.push('\n');
    }
    for i in 3400i32..=4200 {
        out.push_str(&java_float_to_string(i as f32 / 100.0));
        out.push('\n');
    }
    for i in 0i32..=96 {
        out.push_str(&java_float_to_string(i as f32 / 4.0));
        out.push('\n');
    }
    for i in 1i32..=1000 {
        out.push_str(&java_float_to_string(i as f32 / 1000.0));
        out.push('\n');
    }
    let mut s: u64 = 0x12345678;
    for _ in 0..1000000 {
        s = s.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        let f = f32::from_bits((s >> 32) as u32);
        let a = f.abs();
        if f.is_nan() || f.is_infinite() || a == 0.0 || a < 1e-3 || a >= 1e7 {
            continue;
        }
        out.push_str(&java_float_to_string(f));
        out.push('\n');
    }
    print!("{out}");
}

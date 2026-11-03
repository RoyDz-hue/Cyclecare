fn main() {
    for v in [-2000.0f32, -1000.0, -999.0, 448.64062, 3656889.0, 0.5, 36.6] {
        println!("{v} -> {}", cyclecare_core::data_export_pdf::java_float_to_string(v));
    }
}

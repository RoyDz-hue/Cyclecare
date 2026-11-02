fn main() {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(|s| s.as_str()) {
        Some("introspect") => cyclecare_spike::cmd_introspect(&args[2], &args[3]),
        Some("verify") => cyclecare_spike::cmd_verify(&args[2], &args[3]),
        Some("create") => cyclecare_spike::cmd_create(&args[2], &args[3], &args[4]),
        Some("aes-verify") => cyclecare_spike::cmd_aes_verify(&args[2], &args[3]),
        Some("namespace") => cyclecare_spike::cmd_namespace(&args[2], &args[3]),
        other => { eprintln!("unknown command {:?}", other); std::process::exit(2); }
    }
}

//! Differential harness: reads candidate URLs on stdin, prints the ported
//! base_url(true, line) result per line ("NULL" for None). Compared
//! against the JDK17 harness output in fixtures.
fn main() {
    use std::io::BufRead;
    let stdin = std::io::stdin();
    for line in stdin.lock().lines() {
        let line = line.unwrap();
        match cyclecare_core::debug_bridge::base_url(true, &line) {
            Some(v) => println!("{v}"),
            None => println!("NULL"),
        }
    }
}

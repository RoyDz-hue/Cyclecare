//! Consolidated AuthPreferences surface: the 64-row JVM corpus (PinParity.kt,
//! jdk-17.0.21+9) now runs against the MAINTAINED modules (pin_verify,
//! lifecycle, md5) - no duplicated crypto. Plus the set_user_id session-bump
//! transitions, the PBEKeySpec empty-input gates, and the kotlin-trim
//! boundary on effective_user_id.

use cyclecare_core::auth_prefs::*;
use cyclecare_core::lifecycle::name_uuid_from_bytes;
use cyclecare_core::md5::md5;
use cyclecare_core::pin_verify;

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

#[test]
fn md5_fips_vectors() {
    assert_eq!(hex(&md5(b"")), "d41d8cd98f00b204e9800998ecf8427e");
    assert_eq!(hex(&md5(b"abc")), "900150983cd24fb0d6963f7d28e17f72");
    assert_eq!(hex(&md5(b"The quick brown fox jumps over the lazy dog")), "9e107d9d372bb6826bd81d3542a419d6");
}

#[test]
fn pin_crypto_corpus_jvm_executed_against_maintained_modules() {
    let data = std::fs::read_to_string("fixtures/oracle/results_authpin.tsv").unwrap();
    let (mut np, mut nc, mut nu, mut nd, mut ns) = (0, 0, 0, 0, 0);
    for line in data.lines() {
        let c: Vec<&str> = line.split('\t').collect();
        match c[0].chars().next().unwrap() {
            'p' => {
                let pin = String::from_utf8(unhex(c[1])).unwrap();
                let salt = unhex(c[2]);
                let iterations: u32 = c[3].parse().unwrap();
                assert_eq!(
                    pin_verify::pbkdf2(&pin, &salt, iterations).to_vec(),
                    unhex(c[4]),
                    "{}: maintained PBKDF2 diverged from JVM SecretKeyFactory",
                    c[0]
                );
                np += 1;
            }
            'c' => {
                let pin = String::from_utf8(unhex(c[1])).unwrap();
                assert_eq!(pin_verify::password_bytes(&pin), unhex(c[1]), "{}: UTF-8 parity", c[0]);
                nc += 1;
            }
            'u' => {
                let device_id = String::from_utf8(unhex(c[1])).unwrap();
                assert_eq!(name_uuid_from_bytes(device_id.as_bytes()), c[2], "{}: nameUUIDFromBytes", c[0]);
                nu += 1;
            }
            'd' => {
                let device_id = String::from_utf8(unhex(c[1])).unwrap();
                let pin = String::from_utf8(unhex(c[2])).unwrap();
                // Maintained argument order is (device_id, raw).
                assert_eq!(pin_verify::hash_pin_legacy_device(&device_id, &pin), c[3], "{}: legacy device hash", c[0]);
                nd += 1;
            }
            's' => {
                let pin = String::from_utf8(unhex(c[1])).unwrap();
                assert_eq!(pin_verify::hash_pin_legacy_salt(&pin), c[2], "{}: legacy salt hash", c[0]);
                ns += 1;
            }
            other => panic!("bad row kind {other}"),
        }
    }
    assert_eq!((np, nc, nu, nd, ns), (48, 4, 4, 4, 4));
}

#[test]
fn consolidated_surface_roundtrip() {
    let salt = [7u8; 16];
    let stored = encode_pin_pbkdf2("1234", &salt);
    assert!(stored.starts_with("pbkdf2:150000:07070707070707070707070707070707:"));
    assert_eq!(verify_pin(Some(&stored), "1234", "dev"), VerifyPin::Current);
    assert_eq!(verify_pin(Some(&stored), "1235", "dev"), VerifyPin::Rejected);
    // legacy: every legacy match collapses to LegacyMigrate (re-encode signal)
    assert_eq!(verify_pin(Some("4321"), "4321", "dev-1"), VerifyPin::LegacyMigrate);
    let legacy = hash_pin_legacy_device("dev-1", "4321");
    assert_eq!(verify_pin(Some(&legacy), "4321", "dev-1"), VerifyPin::LegacyMigrate);
    assert_eq!(verify_pin(Some(&legacy), "4321", "dev-2"), VerifyPin::Rejected);
    assert_eq!(verify_pin(None, "4321", "dev-1"), VerifyPin::Rejected);
}

#[test]
fn pbkdf2_empty_input_gates_match_java_throws() {
    // Reviewer differential: Java PBEKeySpec throws on empty password and
    // empty salt (95/400 random cases); the source's catch -> false.
    let salt = [7u8; 16];
    let stored = encode_pin_pbkdf2("1234", &salt);
    assert!(!verify_pin_pbkdf2(&stored, ""), "empty candidate must fail closed");
    let empty_salt = format!("pbkdf2:150000::{}", "aa".repeat(32));
    assert!(!verify_pin_pbkdf2(&empty_salt, "1234"), "empty salt must fail closed");
}

#[test]
fn effective_user_id_kotlin_trim_boundaries() {
    assert_eq!(effective_user_id(Some("uid-1"), "dev-1"), "uid-1");
    let derived = effective_user_id(None, "dev-1");
    assert_eq!(derived, name_uuid_from_bytes(b"dev-1"));
    assert_eq!(derived.as_bytes()[14], b'3', "version 3 nibble");
    // Kotlin whitespace boundaries (central helper): U+001C IS Kotlin-blank
    // (falls to device id) while U+0085 NEL is NOT (kept as the id).
    assert_eq!(effective_user_id(Some("\u{1C}"), "dev-1"), derived, "U+001C is Kotlin-blank");
    assert_eq!(effective_user_id(Some("\u{85}"), "dev-1"), "\u{85}", "U+0085 is NOT Kotlin-blank");
    assert_eq!(effective_user_id(Some("   "), "dev-1"), derived);
}

#[derive(Default)]
struct PrefsFx {
    user_id: Option<String>,
    generation: i64,
    commits: Vec<i64>,
    writes: Vec<Option<String>>,
    commit_ok: bool,
}

impl AuthPrefsFx for PrefsFx {
    fn read_user_id(&mut self) -> Option<String> { self.user_id.clone() }
    fn read_session_generation(&mut self) -> i64 { self.generation }
    fn commit_session_generation(&mut self, g: i64) -> bool {
        self.commits.push(g);
        if self.commit_ok { self.generation = g; }
        self.commit_ok
    }
    fn write_user_id(&mut self, value: Option<&str>) {
        self.writes.push(value.map(str::to_string));
        self.user_id = value.map(str::to_string);
    }
}

#[test]
fn user_id_change_bumps_generation_before_write() {
    let mut fx = PrefsFx { user_id: Some("a".into()), generation: 41, commit_ok: true, ..Default::default() };
    set_user_id(&mut fx, Some("b")).unwrap();
    assert_eq!(fx.commits, vec![42], "generation bumped exactly once");
    assert_eq!(fx.writes, vec![Some("b".to_string())]);
    let mut fx = PrefsFx { user_id: Some("a".into()), generation: 41, commit_ok: true, ..Default::default() };
    set_user_id(&mut fx, Some("a")).unwrap();
    assert!(fx.commits.is_empty(), "unchanged id never bumps");
    assert_eq!(fx.writes, vec![Some("a".to_string())]);
    let mut fx = PrefsFx { user_id: Some("a".into()), generation: 41, commit_ok: true, ..Default::default() };
    set_user_id(&mut fx, None).unwrap();
    assert_eq!(fx.commits, vec![42]);
}

#[test]
fn generation_overflow_is_a_typed_fault_before_write() {
    let mut fx = PrefsFx { user_id: Some("a".into()), generation: i64::MAX, commit_ok: true, ..Default::default() };
    assert_eq!(set_user_id(&mut fx, Some("b")), Err(AuthPrefsFault::GenerationOverflow));
    assert!(fx.writes.is_empty(), "no write after the overflow throw");
    let mut fx = PrefsFx { user_id: Some("a".into()), generation: 1, commit_ok: false, ..Default::default() };
    set_user_id(&mut fx, Some("b")).unwrap();
    assert_eq!(fx.writes, vec![Some("b".to_string())], "source writes even when commit fails");
}

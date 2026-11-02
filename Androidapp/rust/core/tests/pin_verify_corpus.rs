//! JVM-probed corpus for the PIN verify/encode chain (PinProbe.kt,
//! kotlinc 2.1.0 / JRE 21, 2026-11-02). Salt fixed to bytes 00..0f.
use cyclecare_core::pin_verify::*;

const SALT: [u8; 16] = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15];

#[test]
fn pbkdf2_vectors_jvm_locked() {
    assert_eq!(
        to_hex_pub(&pbkdf2("1234", &SALT, 150_000)),
        "c2d2538e0b8c6b7b651f8e426c254556b512e9f0744e0a8e9422946fa153b845"
    );
    assert_eq!(
        to_hex_pub(&pbkdf2("1234", &SALT, 50_000)),
        "8338397ff7a63667dbf1507b64bf5991b53c20e0e2c3e3da4b041f835d687438"
    );
}

#[test]
fn pbekeyspec_utf8_encoding_locked() {
    // OpenJDK 21 PBEKeySpec converts the password char[] to UTF-8 BYTES
    // (NOT the PKCS#5 low-8-bit encoding older docs describe); these four
    // JVM vectors lock that (low-8-bit would differ on every non-ASCII row).
    assert_eq!(
        to_hex_pub(&pbkdf2("1", &SALT, 50_000)),
        "795964950087d7c4332ac761ff986d3273c748665a32d723d66b373d0394560a"
    );
    assert_eq!(
        to_hex_pub(&pbkdf2("\u{FF11}", &SALT, 50_000)),
        "8b8288642b8105b499cb0685eb641dd496a78187cc1ad7264e6381c0965bbe23"
    );
    assert_eq!(
        to_hex_pub(&pbkdf2("\u{00E9}", &SALT, 50_000)),
        "d68c7e69739a2d65aae6c2bff0fbb0bbf95deb0126863bfde62830dce907b4e2"
    );
    assert_eq!(
        to_hex_pub(&pbkdf2("\u{1F338}", &SALT, 50_000)),
        "cfe9c341f630a1756c8675461f8fc4924c41cc265a4c3d9fe75d774bf93671ce"
    );
}

#[test]
fn legacy_hashes_jvm_locked() {
    assert_eq!(
        hash_pin_legacy_salt("1234"),
        "30768ee0714152082cf533609fdd9ec34a5f31b44481216d501a999f074a7168"
    );
    assert_eq!(
        hash_pin_legacy_device("d3v1c3", "1234"),
        "cc844c88c4e032b03d53c97573148d0dbdec1a7352f41389fbdfc3b57415c0f8"
    );
}

#[test]
fn encode_format_and_roundtrip() {
    let stored = encode_pin_pbkdf2_with_salt("1234", &SALT);
    assert!(stored.starts_with("pbkdf2:150000:000102030405060708090a0b0c0d0e0f:"));
    assert_eq!(verify_pin(Some(&stored), "1234", "dev"), PinVerify::Current);
    assert_eq!(verify_pin(Some(&stored), "0000", "dev"), PinVerify::Rejected);
    assert_eq!(verify_pin(Some(&stored), "1234", "other-dev"), PinVerify::Current);
}

#[test]
fn verify_order_and_migration() {
    // Legacy plaintext.
    assert_eq!(verify_pin(Some("1234"), "1234", "dev"), PinVerify::LegacyMigrate);
    assert_eq!(verify_pin(Some("1234"), "1235", "dev"), PinVerify::Rejected);
    // Legacy device hash; wrong device id cannot verify.
    let dev = hash_pin_legacy_device("d3v1c3", "1234");
    assert_eq!(verify_pin(Some(&dev), "1234", "d3v1c3"), PinVerify::LegacyMigrate);
    assert_eq!(verify_pin(Some(&dev), "1234", "other"), PinVerify::Rejected);
    // Legacy salt hash.
    let salt_h = hash_pin_legacy_salt("1234");
    assert_eq!(verify_pin(Some(&salt_h), "1234", "any"), PinVerify::LegacyMigrate);
    // No stored PIN.
    assert_eq!(verify_pin(None, "1234", "dev"), PinVerify::Rejected);
}

#[test]
fn malformed_and_bounds_fail_closed() {
    assert_eq!(verify_pin(Some("pbkdf2:150000:00:zz"), "1234", "d"), PinVerify::Rejected);
    assert_eq!(verify_pin(Some("pbkdf2:150000:00"), "1234", "d"), PinVerify::Rejected);
    assert_eq!(verify_pin(Some("pbkdf2:notanint:00:00"), "1234", "d"), PinVerify::Rejected);
    // Iterations below/above accepted bounds fail closed even for a real hash.
    let low = format!("pbkdf2:49999:{}:{}", hex_pub(&SALT), to_hex_pub(&pbkdf2("1234", &SALT, 49_999)));
    assert_eq!(verify_pin(Some(&low), "1234", "d"), PinVerify::Rejected);
    let high = format!("pbkdf2:2000001:{}:{}", hex_pub(&SALT), to_hex_pub(&pbkdf2("1234", &SALT, 50_000)));
    assert_eq!(verify_pin(Some(&high), "1234", "d"), PinVerify::Rejected);
    // Boundary values are accepted.
    let min_ok = format!("pbkdf2:50000:{}:{}", hex_pub(&SALT), to_hex_pub(&pbkdf2("1234", &SALT, 50_000)));
    assert_eq!(verify_pin(Some(&min_ok), "1234", "d"), PinVerify::Current);
}

fn to_hex_pub(b: &[u8; 32]) -> String {
    b.iter().map(|x| format!("{:02x}", x)).collect()
}
fn hex_pub(b: &[u8; 16]) -> String {
    b.iter().map(|x| format!("{:02x}", x)).collect()
}

//! Quick-unlock PIN verify/encode chain.
//! Port of AuthPreferences.verifyPin / encodePinPbkdf2 / verifyPinPbkdf2 /
//! hashPinLegacyDevice / hashPinLegacySalt (security/AuthPreferences.kt,
//! main@31b88f5, JVM-probed 2026-11-02).
//!
//! CRITICAL char semantics (JVM-probed on the portable oracle, OpenJDK
//! 21.0.13): production passes raw.toCharArray() to PBEKeySpec, and this
//! JDK's PBKDF2WithHmacSHA256 converts the char[] to UTF-8 BYTES (NOT the
//! PKCS#5 low-8-bit encoding older docs describe). Probed: "1"->[0x31],
//! "é" (U+00E9)->[0xC3,0xA9], "１" (U+FF11)->[0xEF,0xBC,0x91],
//! "🌸"->[0xF0,0x9F,0x8C,0xB8]. A low-8-bit port produces DIFFERENT hashes
//! for every non-ASCII PIN. RESIDUAL GAP: the production runtime is
//! Android Conscrypt, not OpenJDK; its char->byte conversion for non-ASCII
//! PINs is not executable here and stays a device-verification gap
//! (ASCII PINs, the only kind isCompletePin's digit check admits for
//! BMP-ASCII, are unaffected by any of these encodings).
//!
//! Storage formats: current "pbkdf2:<iterations>:<salt32hex>:<hash64hex>"
//! (150_000 iterations, 16-byte salt, 256-bit key). Legacy plaintext and two
//! legacy SHA-256 shapes still VERIFY and migrate on success (the caller
//! re-encodes with PBKDF2, mirroring `pin = candidate`). Iteration bounds
//! 50_000..=2_000_000 enforced before verifying; malformed stored values
//! fail closed (false), matching the Kotlin catch-all.

use sha2::{Digest, Sha256};

// Vetted RustCrypto primitives (hmac/pbkdf2 0.12, matching sha2 0.10);
// replaces the earlier hand-rolled HMAC/PBKDF2 per review. The JVM-probed
// corpus vectors below are unchanged and re-executed against these crates.

pub const PBKDF2_PREFIX: &str = "pbkdf2:";
pub const PBKDF2_ITERATIONS: u32 = 150_000;
pub const PBKDF2_MIN_ACCEPTED_ITERATIONS: u32 = 50_000;
pub const PBKDF2_MAX_ACCEPTED_ITERATIONS: u32 = 2_000_000;
pub const PBKDF2_SALT_BYTES: usize = 16;
pub const PBKDF2_KEY_BITS: usize = 256;

/// Password bytes: UTF-8 encoding of the PIN string, matching the probed
/// OpenJDK 21 PBEKeySpec conversion (see module docs for the Conscrypt
/// residual gap). A valid &str needs no replacement-char handling.
pub fn password_bytes(raw: &str) -> Vec<u8> {
    raw.as_bytes().to_vec()
}

/// PBKDF2-HMAC-SHA256, single 32-byte block (dkLen = 256 bits = one block),
/// via the maintained RustCrypto pbkdf2 crate.
pub fn pbkdf2(raw: &str, salt: &[u8], iterations: u32) -> [u8; 32] {
    let pw = password_bytes(raw);
    let mut out = [0u8; 32];
    pbkdf2::pbkdf2_hmac::<Sha256>(&pw, salt, iterations, &mut out);
    out
}

fn to_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{:02x}", b)).collect()
}

fn hex_to_bytes(s: &str) -> Option<Vec<u8>> {
    if s.len() % 2 != 0 {
        return None;
    }
    (0..s.len() / 2)
        .map(|i| u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).ok())
        .collect()
}

/// encodePinPbkdf2 with caller-supplied salt (production: SecureRandom).
pub fn encode_pin_pbkdf2_with_salt(raw: &str, salt: &[u8; PBKDF2_SALT_BYTES]) -> String {
    let hash = pbkdf2(raw, salt, PBKDF2_ITERATIONS);
    format!(
        "{}{}:{}:{}",
        PBKDF2_PREFIX,
        PBKDF2_ITERATIONS,
        to_hex(salt),
        to_hex(&hash)
    )
}

/// verifyPinPbkdf2: parse, bounds-check iterations, constant-time compare.
/// Any malformed part fails closed (Kotlin catch -> false).
///
/// PBEKeySpec input gates (reviewer differential 2026-11-03: 95/400 random
/// inputs where Java throws but Rust computed): an EMPTY candidate makes
/// PBEKeySpec throw IllegalArgumentException, which the source's catch
/// turns into `false`; an empty SALT from a malformed stored value throws
/// the same way. Rust computes happily on both - gate them to `false` to
/// match the executed Java behavior.
pub fn verify_pin_pbkdf2(stored: &str, candidate: &str) -> bool {
    if candidate.is_empty() {
        return false; // PBEKeySpec empty-password throw -> catch -> false
    }
    let body = match stored.strip_prefix(PBKDF2_PREFIX) {
        Some(b) => b,
        None => return false,
    };
    let parts: Vec<&str> = body.split(':').collect();
    if parts.len() != 3 {
        return false;
    }
    let iterations: u32 = match parts[0].parse() {
        Ok(v) => v,
        Err(_) => return false,
    };
    if iterations < PBKDF2_MIN_ACCEPTED_ITERATIONS || iterations > PBKDF2_MAX_ACCEPTED_ITERATIONS {
        return false;
    }
    let salt = match hex_to_bytes(parts[1]) {
        Some(v) if !v.is_empty() => v,
        _ => return false, // PBEKeySpec empty-salt throw -> catch -> false
    };
    let expected = match hex_to_bytes(parts[2]) {
        Some(v) => v,
        None => return false,
    };
    let actual = pbkdf2(candidate, &salt, iterations);
    // MessageDigest.isEqual via the established timing-safe primitive
    // (subtle::ConstantTimeEq; a hand-rolled XOR fold is only a best-effort
    // claim the compiler is free to weaken - reviewer note 2026-11-03).
    // Mismatched lengths cannot be equal; ct_eq returns Choice(0) there.
    use subtle::ConstantTimeEq;
    expected.ct_eq(&actual).into()
}

/// hashPinLegacyDevice: SHA-256 hex of "cyclecare_<deviceId>_<raw>".
pub fn hash_pin_legacy_device(device_id: &str, raw: &str) -> String {
    to_hex(&Sha256::digest(format!("cyclecare_{}_{}", device_id, raw).as_bytes()))
}

/// hashPinLegacySalt: SHA-256 hex of "cyclecare_salt_<raw>".
pub fn hash_pin_legacy_salt(raw: &str) -> String {
    to_hex(&Sha256::digest(format!("cyclecare_salt_{}", raw).as_bytes()))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PinVerify {
    /// Stored PBKDF2 matched.
    Current,
    /// A legacy format matched; production immediately re-encodes the PIN
    /// with PBKDF2 (the `pin = candidate` migration on first unlock).
    LegacyMigrate,
    /// No match, malformed storage, or no stored PIN.
    Rejected,
}

/// verifyPin verbatim order: PBKDF2 prefix -> legacy plaintext -> legacy
/// device hash -> legacy salt hash -> reject. `stored` is the raw stored
/// value; None mirrors readSecureString returning null (false).
pub fn verify_pin(stored: Option<&str>, candidate: &str, device_id: &str) -> PinVerify {
    let stored = match stored {
        Some(s) => s,
        None => return PinVerify::Rejected,
    };
    if stored.starts_with(PBKDF2_PREFIX) {
        return if verify_pin_pbkdf2(stored, candidate) {
            PinVerify::Current
        } else {
            PinVerify::Rejected
        };
    }
    if stored == candidate {
        return PinVerify::LegacyMigrate;
    }
    if stored == hash_pin_legacy_device(device_id, candidate) {
        return PinVerify::LegacyMigrate;
    }
    if stored == hash_pin_legacy_salt(candidate) {
        return PinVerify::LegacyMigrate;
    }
    PinVerify::Rejected
}

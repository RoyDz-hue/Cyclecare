//! AuthPreferences.kt (security, main 31b88f5): the quick-unlock PIN codec
//! and the identity chain behind the owner handoff. All crypto corpus-
//! locked against the source's own JCE calls (PinParity.kt on
//! jdk-17.0.21+9, 64 rows): PBKDF2WithHmacSHA256 via SecretKeyFactory,
//! UUID.nameUUIDFromBytes, and the two legacy SHA-256 PIN hashes.
//!
//! PIN codec (:388-434, :455-476):
//! - encodePinPbkdf2: "pbkdf2:150000:<salt hex>:<hash hex>", 16-byte salt,
//!   256-bit key. PBKDF2 implemented inline over the vendored hmac+sha2
//!   (no new deps); RFC 2898 single-block case plus the general loop.
//! - Password bytes: SunJCE PBKDF2WithHmacSHA256 encodes the PBEKeySpec
//!   char[] password as UTF-8 (JDK-8187543, since JDK 9) - PROVEN by
//!   corpus row p25 ("pün💰" hashes its UTF-8 bytes; a low-byte-truncation
//!   hypothesis was tested against the JVM and REJECTED, along with
//!   code-point and UTF-16-unit byte encodings). LABELED GAP: Android's
//!   Conscrypt PBKDF2WithHmacSHA256 is NOT covered by the desktop JVM
//!   oracle; its password encoding on device is unverified (same class of
//!   gap as the existing Android-vs-JDK items).
//! - verifyPinPbkdf2 (:417-428): parts != 3 -> false; iteration bounds
//!   [50_000, 2_000_000] -> false outside; MessageDigest.isEqual is
//!   CONSTANT-TIME - ported as an explicit constant-time loop (Rust == on
//!   slices early-exits; labeled difference that matters here).
//! - Legacy formats (:397-407): plaintext, device hash
//!   (SHA-256 "cyclecare_<deviceId>_<pin>"), salt hash (SHA-256
//!   "cyclecare_salt_<pin>"); a successful legacy verify triggers
//!   IMMEDIATE RE-ENCODE to PBKDF2 (modeled as an outcome signal; the
//!   re-encode write is a host effect). Legacy hash failure returns ""
//!   (hashPinLegacy* catch Exception) which never matches a non-empty
//!   candidate but DOES match an empty one - source edge, labeled.
//!
//! Identity chain (:438-449, :360-364):
//! - effectiveUserId: non-blank userId wins; else UUID.nameUUIDFromBytes
//!   (deviceId UTF-8 bytes) - MD5 (RFC 1321, implemented inline, FIPS
//!   vectors locked in tests) with version-3/variant bits (:447), with
//!   fallback to the raw deviceId on exception (unreachable for valid
//!   input; MD5 does not fail - labeled).
//! - userId setter (:360-364): synchronized; ONLY a changed id bumps
//!   session_generation_v3 via Math.addExact (overflow throws
//!   ArithmeticException in the source - typed fault, never wraps) and
//!   commits BEFORE the id write. writeSecureString (:607-610): blank
//!   value REMOVES the key rather than storing "".
//!
//! NOT PORTED (host seams): EncryptedSharedPreferences/MasterKey, the
//! legacy-prefs migration and fallback wipe list (:75-116), WorkManager
//! cancellation, and the actual storage. SecureRandom salt generation is
//! injected. The fail-closed PBKDF2-unavailable throw (:446-449) is
//! unreachable in Rust (hmac/sha2 are infallible) - labeled.

use hmac::{Hmac, Mac};
use sha2::Sha256;

use crate::kotlin_text::kotlin_trim;

pub const PBKDF2_PREFIX: &str = "pbkdf2:";
pub const PBKDF2_ITERATIONS: u32 = 150_000;
pub const PBKDF2_MIN_ACCEPTED_ITERATIONS: u32 = 50_000;
pub const PBKDF2_MAX_ACCEPTED_ITERATIONS: u32 = 2_000_000;
pub const PBKDF2_SALT_BYTES: usize = 16;
pub const PBKDF2_KEY_BYTES: usize = 32;

fn kotlin_is_blank(s: &str) -> bool {
    kotlin_trim(s).is_empty()
}

// ---------------------------------------------------------------------------
// MD5 (RFC 1321) - inline, no new deps. FIPS vectors locked in tests.
// ---------------------------------------------------------------------------

const MD5_S: [u32; 64] = [
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
];

const MD5_K: [u32; 64] = [
    0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
    0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
    0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
    0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
    0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
    0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
    0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
    0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
];

pub fn md5(input: &[u8]) -> [u8; 16] {
    let mut a0: u32 = 0x67452301;
    let mut b0: u32 = 0xefcdab89;
    let mut c0: u32 = 0x98badcfe;
    let mut d0: u32 = 0x10325476;
    let bitlen = (input.len() as u64).wrapping_mul(8);
    let mut msg = input.to_vec();
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&bitlen.to_le_bytes());
    for chunk in msg.chunks_exact(64) {
        let mut m = [0u32; 16];
        for (i, w) in m.iter_mut().enumerate() {
            *w = u32::from_le_bytes([chunk[4 * i], chunk[4 * i + 1], chunk[4 * i + 2], chunk[4 * i + 3]]);
        }
        let (mut a, mut b, mut c, mut d) = (a0, b0, c0, d0);
        for i in 0..64 {
            let (f, g) = match i / 16 {
                0 => ((b & c) | (!b & d), i),
                1 => ((d & b) | (!d & c), (5 * i + 1) % 16),
                2 => (b ^ c ^ d, (3 * i + 5) % 16),
                _ => (c ^ (b | !d), (7 * i) % 16),
            };
            let tmp = f
                .wrapping_add(a)
                .wrapping_add(MD5_K[i])
                .wrapping_add(m[g]);
            a = d;
            d = c;
            c = b;
            b = b.wrapping_add(tmp.rotate_left(MD5_S[i]));
        }
        a0 = a0.wrapping_add(a);
        b0 = b0.wrapping_add(b);
        c0 = c0.wrapping_add(c);
        d0 = d0.wrapping_add(d);
    }
    let mut out = [0u8; 16];
    out[0..4].copy_from_slice(&a0.to_le_bytes());
    out[4..8].copy_from_slice(&b0.to_le_bytes());
    out[8..12].copy_from_slice(&c0.to_le_bytes());
    out[12..16].copy_from_slice(&d0.to_le_bytes());
    out
}

/// java.util.UUID.nameUUIDFromBytes (:447): MD5, then version 3 and
/// variant bits, canonical 8-4-4-4-12 lowercase.
pub fn name_uuid_from_bytes(bytes: &[u8]) -> String {
    let mut d = md5(bytes);
    d[6] = (d[6] & 0x0f) | 0x30; // version 3
    d[8] = (d[8] & 0x3f) | 0x80; // IETF variant
    let h = |b: &[u8]| b.iter().map(|x| format!("{x:02x}")).collect::<String>();
    format!(
        "{}-{}-{}-{}-{}",
        h(&d[0..4]),
        h(&d[4..6]),
        h(&d[6..8]),
        h(&d[8..10]),
        h(&d[10..16])
    )
}

/// effectiveUserId (:438-449): non-blank userId wins; else the v3 UUID of
/// the deviceId's UTF-8 bytes. The source's catch -> raw deviceId fallback
/// is unreachable for valid input (MD5 cannot fail) - labeled.
pub fn effective_user_id(user_id: Option<&str>, device_id: &str) -> String {
    match user_id {
        Some(uid) if !kotlin_is_blank(uid) => uid.to_string(),
        _ => name_uuid_from_bytes(device_id.as_bytes()),
    }
}

// ---------------------------------------------------------------------------
// PBKDF2-HMAC-SHA256 (RFC 2898) over vendored hmac+sha2.
// ---------------------------------------------------------------------------

/// SunJCE PBEKeySpec password bytes: UTF-8 (JDK-8187543), corpus-locked
/// via row p25. Conscrypt-on-device unverified (labeled gap).
pub fn pbe_password_bytes(raw: &str) -> Vec<u8> {
    raw.as_bytes().to_vec()
}

pub fn pbkdf2_hmac_sha256(password: &[u8], salt: &[u8], iterations: u32) -> [u8; PBKDF2_KEY_BYTES] {
    // 32-byte output = exactly one block: U1..Uc of block index 1.
    let mut block = salt.to_vec();
    block.extend_from_slice(&1u32.to_be_bytes());
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(password).expect("HMAC takes any key length");
    mac.update(&block);
    let mut u: [u8; 32] = mac.finalize().into_bytes().into();
    let mut out = u;
    for _ in 1..iterations {
        let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(password).expect("HMAC takes any key length");
        mac.update(&u);
        u = mac.finalize().into_bytes().into();
        for i in 0..32 {
            out[i] ^= u[i];
        }
    }
    out
}

pub fn hex_encode(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn hex_decode(s: &str) -> Option<Vec<u8>> {
    if s.len() % 2 != 0 || !s.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    Some(
        (0..s.len() / 2)
            .map(|i| u8::from_str_radix(&s[2 * i..2 * i + 2], 16).unwrap())
            .collect(),
    )
}

/// MessageDigest.isEqual parity: CONSTANT-TIME comparison (the JVM method
/// does not early-exit on the first difference for equal-length inputs).
fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for i in 0..a.len() {
        diff |= a[i] ^ b[i];
    }
    diff == 0
}

/// encodePinPbkdf2 (:409-415); salt injected (SecureRandom is host).
pub fn encode_pin_pbkdf2(raw: &str, salt: &[u8; PBKDF2_SALT_BYTES]) -> String {
    let hash = pbkdf2_hmac_sha256(&pbe_password_bytes(raw), salt, PBKDF2_ITERATIONS);
    format!("{PBKDF2_PREFIX}{PBKDF2_ITERATIONS}:{}:{}", hex_encode(salt), hex_encode(&hash))
}

fn sha256_hex(data: &[u8]) -> String {
    use sha2::Digest;
    hex_encode(&Sha256::digest(data))
}

/// hashPinLegacyDevice (:479-487); the source's catch returns "".
pub fn hash_pin_legacy_device(raw: &str, device_id: &str) -> String {
    sha256_hex(format!("cyclecare_{device_id}_{raw}").as_bytes())
}

/// hashPinLegacySalt (:489-497).
pub fn hash_pin_legacy_salt(raw: &str) -> String {
    sha256_hex(format!("cyclecare_salt_{raw}").as_bytes())
}

/// verifyPin outcome (:388-407). Legacy matches carry the re-encode
/// obligation (source re-encodes via the pin setter immediately).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VerifyPin {
    /// Current PBKDF2 format matched.
    Pbkdf2,
    /// Legacy plaintext match - host must re-encode to PBKDF2.
    LegacyPlaintext,
    /// Legacy device-hash match - host must re-encode.
    LegacyDevice,
    /// Legacy salt-hash match - host must re-encode.
    LegacySalt,
    /// No match (or stored is null).
    NoMatch,
}

impl VerifyPin {
    pub fn matched(self) -> bool {
        !matches!(self, VerifyPin::NoMatch)
    }
    /// Source: any legacy success re-encodes immediately (:398-406).
    pub fn requires_reencode(self) -> bool {
        matches!(self, VerifyPin::LegacyPlaintext | VerifyPin::LegacyDevice | VerifyPin::LegacySalt)
    }
}

/// verifyPinPbkdf2 (:417-428): format/iteration gates then constant-time
/// compare. Malformed parts, out-of-range iterations, or bad hex -> no
/// match (source catches Exception -> false).
pub fn verify_pin_pbkdf2(stored: &str, candidate: &str) -> bool {
    let body = match stored.strip_prefix(PBKDF2_PREFIX) {
        Some(b) => b,
        None => return false,
    };
    let parts: Vec<&str> = body.split(':').collect();
    if parts.len() != 3 {
        return false;
    }
    let iterations: u32 = match parts[0].parse() {
        Ok(n) => n,
        Err(_) => return false,
    };
    if !(PBKDF2_MIN_ACCEPTED_ITERATIONS..=PBKDF2_MAX_ACCEPTED_ITERATIONS).contains(&iterations) {
        return false;
    }
    let (salt, expected) = match (hex_decode(parts[1]), hex_decode(parts[2])) {
        (Some(s), Some(e)) => (s, e),
        _ => return false,
    };
    let actual = pbkdf2_hmac_sha256(&pbe_password_bytes(candidate), &salt, iterations);
    constant_time_eq(&expected, &actual)
}

/// verifyPin (:388-407): the full 4-format chain. Legacy comparisons use
/// String equality in the source (NOT constant-time - labeled; only the
/// PBKDF2 path uses MessageDigest.isEqual).
pub fn verify_pin(stored: Option<&str>, candidate: &str, device_id: &str) -> VerifyPin {
    let stored = match stored {
        Some(s) => s,
        None => return VerifyPin::NoMatch,
    };
    if stored.starts_with(PBKDF2_PREFIX) {
        return if verify_pin_pbkdf2(stored, candidate) { VerifyPin::Pbkdf2 } else { VerifyPin::NoMatch };
    }
    if stored == candidate {
        return VerifyPin::LegacyPlaintext;
    }
    if stored == hash_pin_legacy_device(candidate, device_id) {
        return VerifyPin::LegacyDevice;
    }
    if stored == hash_pin_legacy_salt(candidate) {
        return VerifyPin::LegacySalt;
    }
    VerifyPin::NoMatch
}

// ---------------------------------------------------------------------------
// userId setter + writeSecureString (:360-364, :607-610), injected effects.
// ---------------------------------------------------------------------------

/// Minimal prefs surface for the identity-chain drivers.
pub trait AuthPrefsFx {
    fn read_user_id(&mut self) -> Option<String>;
    fn read_session_generation(&mut self) -> i64;
    /// Committed generation write; bool = commit() result.
    fn commit_session_generation(&mut self, generation: i64) -> bool;
    /// writeSecureString: blank/None removes the key.
    fn write_user_id(&mut self, value: Option<&str>);
}

/// Math.addExact parity: overflow is a typed fault, never a wrap.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AuthPrefsFault {
    /// Math.addExact overflowed (ArithmeticException parity).
    GenerationOverflow,
    /// commit() returned false.
    CommitFailed,
}

/// userId setter (:360-364): read current; ONLY a change bumps
/// session_generation_v3 (addExact) and commits BEFORE writing the id.
/// The generation bump result is ignored by the source after commit -
/// commit failure still writes the id (source behavior, labeled: the
/// commit bool is unchecked there). The bump ITSELF failing (overflow)
/// aborts before the write (the exception propagates).
pub fn set_user_id<F: AuthPrefsFx>(fx: &mut F, value: Option<&str>) -> Result<(), AuthPrefsFault> {
    let current = fx.read_user_id();
    if current.as_deref() != value {
        let generation = fx
            .read_session_generation()
            .checked_add(1)
            .ok_or(AuthPrefsFault::GenerationOverflow)?;
        let _committed = fx.commit_session_generation(generation); // source ignores the bool
    }
    fx.write_user_id(value);
    Ok(())
}

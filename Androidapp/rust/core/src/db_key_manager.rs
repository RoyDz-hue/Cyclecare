//! DatabaseKeyManager.kt (data/local, main 31b88f5): the AES-GCM wrapped
//! SQLCipher passphrase. Two layers:
//!
//! 1. BLOB CODEC (ported, cross-implementation-verified): the exact wire
//!    format - AES/GCM/NoPadding with a 12-byte IV and 128-bit tag, stored
//!    as Base64(NO_WRAP) of `iv || ciphertext || tag`. Differential-locked
//!    against the source's own JCE calls (Cipher.getInstance("AES/GCM/
//!    NoPadding"), GCMParameterSpec(128, iv)) executed on jdk-17.0.21+9:
//!    same key + same IV -> byte-identical blob, JVM-produced blobs decrypt
//!    here, tampered tags fail, truncated blobs hit the require() message,
//!    invalid-UTF-8 plaintext under a valid tag decodes with the same
//!    U+FFFD substitution the JVM String(bytes, UTF_8) performs (u* rows),
//!    and the Base64 layer is an EXACT PORT of the AOSP android14-release
//!    Base64.Decoder state machine, corpus-locked against the REAL
//!    android.util.Base64 class compiled from AOSP source and run on
//!    jdk-17.0.21+9 (l*/a* rows: garbage leniency, unpadded acceptance,
//!    '=' misuse, leftover-char failures).
//!
//! 2. getOrCreatePassphrase SEQUENCE (composed driver): wrapped present ->
//!    decrypt and return (errors propagate like the source's require() and
//!    AEADBadTagException); absent -> SecureRandom 32 bytes -> Base64 ->
//!    secret.fill(0) in source order -> encrypt -> commit() with check() ->
//!    the exact persist-failure message on false.
//!
//! DEVICE CONTRACT (preserved verbatim from main 2026-11-03 05:51 EAT):
//! "Device path MUST retain nonextractable Keystore JCE host operation;
//! raw32byte staging key doesn't implement it." The injected
//! [DbKeyEffects::wrapping_key] raw 32 bytes are STAGING-ONLY: they prove
//! the crypto boundary, they do not implement the device behavior. On
//! device the wrapping key never leaves the hardware Keystore and both GCM
//! operations run through JCE on the Keystore handle.
//! Actual device compatibility remains open (unproven on a device).
//!
//! CONCURRENCY: the source annotates getOrCreatePassphrase @Synchronized
//! (instance-level monitor). Rust has no intrinsic monitor: the host MUST
//! serialize calls to this driver behind its own mutex holding the
//! manager's lifetime. Two racing create paths would otherwise both
//! generate and the loser's commit could be overwritten (source prevents
//! this via the monitor; this port models the sequential result only).

use aes_gcm::aead::{Aead, Payload};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use base64::Engine;

/// Source constants (DatabaseKeyManager.kt companion object).
pub const KEY_ALIAS: &str = "cyclecare_room_db_wrapping_key_v1";
pub const PREFERENCES_NAME: &str = "cyclecare_local_db_key";
pub const WRAPPED_PASSPHRASE: &str = "wrapped_sqlcipher_passphrase";
pub const TRANSFORMATION: &str = "AES/GCM/NoPadding";
pub const GCM_IV_LENGTH: usize = 12;
pub const GCM_TAG_BITS: usize = 128;

/// check(commit) message (:33-35).
pub const ERR_PERSIST: &str = "Could not securely persist the local database key.";
/// require(size > 12) message (:60).
pub const ERR_INVALID_STORED: &str = "Stored local database key is invalid.";

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DbKeyError {
    /// Base64 decode failed even after lenient filtering (android's decoder
    /// ignores non-alphabet chars but still rejects structurally bad
    /// input: bad length, broken padding).
    MalformedBase64,
    /// combined.size <= GCM_IV_LENGTH: the source's require() message.
    InvalidStoredKey,
    /// GCM tag verification failed (AEADBadTagException parity).
    TagMismatch,
    /// getOrCreateWrappingKey() failed: Keystore.load, getKey, or the
    /// KeyGenerator init/generate threw. Source has NO catching here - the
    /// throw propagates out of getOrCreatePassphrase; typed propagation.
    KeystoreFailed,
    /// commit() returned false: the source's check() message.
    PersistFailed,
}

fn b64() -> impl Engine {
    base64::engine::general_purpose::STANDARD // NO_WRAP equivalent
}

/// Exact port of the AOSP android14-release Base64.Decoder state machine
/// (frameworks/base/core/java/android/util/Base64.java :178-456, fetched
/// from android.googlesource.com; the same file COMPILED AND RUN as the
/// corpus oracle, so this is verified against the real decoder, not a
/// stand-in). Semantics carried 1:1:
/// - SKIP bytes (any non-alphabet, non-'=' byte) are IGNORED in every
///   state (:333-385 - whitespace/garbage leniency).
/// - UNPADDED input is accepted at finish: state 2 emits 1 byte, state 3
///   emits 2 bytes (:421-436). The reviewer's recalled "unpadded Android
///   acceptance" is CONFIRMED here with source lines.
/// - '=' is legal only in states 2/3/4; in states 0/1 or after completed
///   padding (state 5) it errors (:347-:351, :367-:377, :379-:385).
/// - One leftover input char at finish (state 1) errors; state 4 at
///   finish (one '=' read) errors (:414-:419, :437-:441).
/// - Failure = IllegalArgumentException("bad base-64") at :163, mapped to
///   [DbKeyError::MalformedBase64].
/// The AOSP fast-path block is omitted per its own comment (:289-291:
/// "You can remove this whole block and the output should be the same,
/// just slower"). Decode flags: NO_WRAP affects only the ENCODER; the
/// decoder selects alphabet on URL_SAFE only (:257-259), so decode(str,
/// NO_WRAP) uses the standard table.
const B64_SKIP: i32 = -1;
const B64_EQUALS: i32 = -2;

/// AOSP DECODE table (:184-202): -1 skip, -2 '=', else sextet value.
const B64_DECODE: [i32; 256] = {
    let mut t = [B64_SKIP; 256];
    t[b'+' as usize] = 62;
    t[b'/' as usize] = 63;
    let mut i = 0;
    while i < 10 { t[b'0' as usize + i] = (52 + i) as i32; i += 1; }
    t[b'=' as usize] = B64_EQUALS;
    let mut i = 0;
    while i < 26 { t[b'A' as usize + i] = i as i32; i += 1; }
    let mut i = 0;
    while i < 26 { t[b'a' as usize + i] = (26 + i) as i32; i += 1; }
    t
};

pub fn decode_base64_android(input: &str) -> Result<Vec<u8>, DbKeyError> {
    let bytes = input.as_bytes();
    let mut output = Vec::with_capacity(bytes.len() * 3 / 4);
    let mut state: u8 = 0;
    let mut value: i32 = 0;
    for &b in bytes {
        let d = B64_DECODE[b as usize];
        match state {
            0 => {
                if d >= 0 { value = d; state = 1; }
                else if d != B64_SKIP { return Err(DbKeyError::MalformedBase64); }
            }
            1 => {
                if d >= 0 { value = (value << 6) | d; state = 2; }
                else if d != B64_SKIP { return Err(DbKeyError::MalformedBase64); }
            }
            2 => {
                if d >= 0 { value = (value << 6) | d; state = 3; }
                else if d == B64_EQUALS { output.push((value >> 4) as u8); state = 4; }
                else if d != B64_SKIP { return Err(DbKeyError::MalformedBase64); }
            }
            3 => {
                if d >= 0 {
                    value = (value << 6) | d;
                    output.push((value >> 16) as u8);
                    output.push((value >> 8) as u8);
                    output.push(value as u8);
                    state = 0;
                } else if d == B64_EQUALS {
                    output.push((value >> 10) as u8);
                    output.push((value >> 2) as u8);
                    state = 5;
                } else if d != B64_SKIP { return Err(DbKeyError::MalformedBase64); }
            }
            4 => {
                if d == B64_EQUALS { state = 5; }
                else if d != B64_SKIP { return Err(DbKeyError::MalformedBase64); }
            }
            _ => {
                if d != B64_SKIP { return Err(DbKeyError::MalformedBase64); }
            }
        }
    }
    match state {
        0 | 5 => Ok(output),
        2 => { output.push((value >> 4) as u8); Ok(output) }
        3 => { output.push((value >> 10) as u8); output.push((value >> 2) as u8); Ok(output) }
        _ => Err(DbKeyError::MalformedBase64), // states 1 and 4
    }
}

/// encrypt(): Base64 NO_WRAP of `cipher.iv || doFinal(utf8 plaintext)`.
/// The IV is injected; the source draws it from the initialized cipher
/// (randomized encryption is required by the key spec).
pub fn gcm_encrypt_blob(key: &[u8; 32], iv: &[u8; GCM_IV_LENGTH], plaintext: &str) -> String {
    let cipher = Aes256Gcm::new_from_slice(key).expect("32-byte key");
    let ct = cipher
        .encrypt(Nonce::from_slice(iv), Payload { msg: plaintext.as_bytes(), aad: b"" })
        .expect("GCM encryption of in-memory data cannot fail");
    let mut combined = Vec::with_capacity(GCM_IV_LENGTH + ct.len());
    combined.extend_from_slice(iv);
    combined.extend_from_slice(&ct);
    b64().encode(combined)
}

/// decrypt(): Base64 decode (EXACT AOSP Decoder port) -> require(size > 12)
/// -> split iv/ciphertext -> GCM 128-bit tag verify -> String(UTF_8) with
/// the JVM's malformed-input substitution (U+FFFD), from_utf8_lossy parity:
/// u28 corpus row proves identical substitution bytes on the oracle input.
pub fn gcm_decrypt_blob(key: &[u8; 32], encoded: &str) -> Result<String, DbKeyError> {
    let combined = decode_base64_android(encoded)?;
    if combined.len() <= GCM_IV_LENGTH {
        return Err(DbKeyError::InvalidStoredKey);
    }
    let (iv, ct) = combined.split_at(GCM_IV_LENGTH);
    let cipher = Aes256Gcm::new_from_slice(key).expect("32-byte key");
    let plain = cipher
        .decrypt(Nonce::from_slice(iv), Payload { msg: ct, aad: b"" })
        .map_err(|_| DbKeyError::TagMismatch)?;
    Ok(String::from_utf8_lossy(&plain).into_owned())
}

/// Passphrase derivation: Base64 NO_WRAP of 32 random bytes (:27-29).
pub fn passphrase_from_secret(secret: &[u8; 32]) -> String {
    b64().encode(secret)
}

/// Host effects for getOrCreatePassphrase.
pub trait DbKeyEffects {
    /// getOrCreateWrappingKey(): the Keystore-resident AES key. STAGING:
    /// injected as raw bytes. DEVICE: a nonextractable Keystore handle and
    /// the crypto runs through JCE - the raw-32 path does NOT implement
    /// that. Err = the source's unguarded Keystore throw (load/getKey/
    /// generate), typed-propagated as [DbKeyError::KeystoreFailed].
    fn wrapping_key(&self) -> Result<[u8; 32], DbKeyError>;
    /// preferences.getString(WRAPPED_PASSPHRASE, null).
    fn get_wrapped(&self) -> Option<String>;
    /// SecureRandom().nextBytes(32). Mutable: the caller owns the buffer so
    /// the driver can zeroize it (source: secret.fill(0)).
    fn secure_random_32(&mut self) -> [u8; 32];
    /// Fresh 12-byte IV (source: from the encrypt-mode cipher).
    fn gcm_iv(&mut self) -> [u8; GCM_IV_LENGTH];
    /// preferences.edit().putString(...).commit() - returns the commit bool.
    fn commit_wrapped(&mut self, value: &str) -> bool;
}

/// :21-37 composed. Wrapped present -> decrypt (errors PROPAGATE like the
/// source's require()/AEADBadTagException - no swallow). Absent ->
/// generate, derive, ZEROIZE in source order, encrypt, commit-with-check.
/// Host MUST serialize calls (source @Synchronized - see module docs).
pub fn get_or_create_passphrase<E: DbKeyEffects>(fx: &mut E) -> Result<String, DbKeyError> {
    let key = fx.wrapping_key()?;
    if let Some(wrapped) = fx.get_wrapped() {
        return gcm_decrypt_blob(&key, &wrapped);
    }
    let mut secret = fx.secure_random_32();
    let passphrase = passphrase_from_secret(&secret);
    // Source order: secret.fill(0) immediately after encoding, BEFORE the
    // encrypt call. Now actually implemented, not just documented.
    secret.fill(0);
    let iv = fx.gcm_iv();
    let encrypted = gcm_encrypt_blob(&key, &iv, &passphrase);
    if !fx.commit_wrapped(&encrypted) {
        return Err(DbKeyError::PersistFailed);
    }
    Ok(passphrase)
}

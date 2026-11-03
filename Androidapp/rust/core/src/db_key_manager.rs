//! DatabaseKeyManager.kt (data/local, main 31b88f5): the AES-GCM wrapped
//! SQLCipher passphrase. Two layers:
//!
//! 1. BLOB CODEC (ported, cross-implementation-verified): the exact wire
//!    format - AES/GCM/NoPadding with a 12-byte IV and 128-bit tag, stored
//!    as Base64(NO_WRAP) of `iv || ciphertext || tag`. Differential-locked
//!    against the source's own JCE calls (Cipher.getInstance("AES/GCM/
//!    NoPadding"), GCMParameterSpec(128, iv)) executed on jdk-17.0.21+9:
//!    same key + same IV -> byte-identical blob, JVM-produced blobs decrypt
//!    here, tampered tags fail, truncated blobs hit the require() message.
//!    The Android Keystore wrapping key is injected as raw bytes: on device
//!    it never leaves the hardware keystore; the crypto BOUNDARY (JCE
//!    AES/GCM) is what this corpus proves. android.util.Base64 decode
//!    ignores non-alphabet characters while Rust's STANDARD decoder rejects
//!    them - divergence on MALFORMED input only, labeled, never hit on
//!    values this manager itself writes.
//!
//! 2. getOrCreatePassphrase SEQUENCE (composed driver): wrapped present ->
//!    decrypt and return (errors propagate like the source's require() and
//!    AEADBadTagException); absent -> SecureRandom 32 bytes -> Base64 ->
//!    source zeroes the byte array (modeled: the local copy is consumed and
//!    dropped; a Rust &[u8;32] cannot be fill(0)-ed through the trait, the
//!    host-owned buffer obligation is documented) -> encrypt -> commit()
//!    with check() -> the exact persist-failure message on false.

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
    /// Base64 decode failed (Rust strict; android lenient - labeled limit).
    MalformedBase64,
    /// combined.size <= GCM_IV_LENGTH: the source's require() message.
    InvalidStoredKey,
    /// GCM tag verification failed (AEADBadTagException parity).
    TagMismatch,
    /// Decrypted bytes were not UTF-8 (source would mangle via String();
    /// Kotlin's String(bytes, UTF_8) replaces malformed input, so this is
    /// unreachable on values the manager itself wrote - fail visibly).
    NotUtf8,
    /// commit() returned false: the source's check() message.
    PersistFailed,
}

fn b64() -> impl Engine {
    base64::engine::general_purpose::STANDARD // NO_WRAP equivalent
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

/// decrypt(): Base64 decode -> require(size > 12) -> split iv/ciphertext ->
/// GCM 128-bit tag verify -> UTF-8 string.
pub fn gcm_decrypt_blob(key: &[u8; 32], encoded: &str) -> Result<String, DbKeyError> {
    let combined = b64().decode(encoded).map_err(|_| DbKeyError::MalformedBase64)?;
    if combined.len() <= GCM_IV_LENGTH {
        return Err(DbKeyError::InvalidStoredKey);
    }
    let (iv, ct) = combined.split_at(GCM_IV_LENGTH);
    let cipher = Aes256Gcm::new_from_slice(key).expect("32-byte key");
    let plain = cipher
        .decrypt(Nonce::from_slice(iv), Payload { msg: ct, aad: b"" })
        .map_err(|_| DbKeyError::TagMismatch)?;
    String::from_utf8(plain).map_err(|_| DbKeyError::NotUtf8)
}

/// Passphrase derivation: Base64 NO_WRAP of 32 random bytes (:27-29).
pub fn passphrase_from_secret(secret: &[u8; 32]) -> String {
    b64().encode(secret)
}

/// Host effects for getOrCreatePassphrase.
pub trait DbKeyEffects {
    /// getOrCreateWrappingKey(): the Keystore-resident AES key, injected as
    /// raw bytes for staging; on device this is a Keystore handle and the
    /// crypto ops run through JCE without exposing the bytes.
    fn wrapping_key(&self) -> [u8; 32];
    /// preferences.getString(WRAPPED_PASSPHRASE, null).
    fn get_wrapped(&self) -> Option<String>;
    /// SecureRandom().nextBytes(32).
    fn secure_random_32(&mut self) -> [u8; 32];
    /// Fresh 12-byte IV (source: from the encrypt-mode cipher).
    fn gcm_iv(&mut self) -> [u8; GCM_IV_LENGTH];
    /// preferences.edit().putString(...).commit() - returns the commit bool.
    fn commit_wrapped(&mut self, value: &str) -> bool;
}

/// :21-37 composed. Wrapped present -> decrypt (errors PROPAGATE like the
/// source's require()/AEADBadTagException - no swallow). Absent -> generate,
/// derive, encrypt, commit-with-check.
pub fn get_or_create_passphrase<E: DbKeyEffects>(fx: &mut E) -> Result<String, DbKeyError> {
    let key = fx.wrapping_key();
    if let Some(wrapped) = fx.get_wrapped() {
        return gcm_decrypt_blob(&key, &wrapped);
    }
    let secret = fx.secure_random_32();
    let passphrase = passphrase_from_secret(&secret);
    // Source: secret.fill(0) immediately after encoding. The port never
    // retains the secret beyond this call; host zeroization obligation noted.
    drop(secret);
    let iv = fx.gcm_iv();
    let encrypted = gcm_encrypt_blob(&key, &iv, &passphrase);
    if !fx.commit_wrapped(&encrypted) {
        return Err(DbKeyError::PersistFailed);
    }
    Ok(passphrase)
}

//! Host adapter boundary for the non-extractable AndroidKeyStore/JCE
//! wrapping key and the EncryptedSharedPreferences store selection, ported
//! from data/local/DatabaseKeyManager.kt and security/AuthPreferences.kt
//! companion (main@31b88f5). This module grounds the ADAPTER/COMPOSITION
//! seam: no new codec - staging seal/open REUSE db_key_manager's verified
//! AOSP-Base64 + AES-GCM functions, and store selection REUSES
//! secure_store's corpus-locked sanitize/migrate. Device implementations of
//! the ports are host obligations; staging stays isolated (config false).

use crate::db_key_manager::{
    gcm_decrypt_blob, gcm_encrypt_blob, passphrase_from_secret, DbKeyError, GCM_IV_LENGTH,
};
use crate::secure_store::{fallback_sanitize, migrate_legacy, Prefs};

// ---------- A. Non-extractable Keystore/JCE port (DatabaseKeyManager.kt) ----------

/// KeyGenParameterSpec mirror (:38-46): the wrapping key is generated INSIDE
/// the AndroidKeyStore provider and is non-extractable by that provider's
/// contract (Keystore-resident SecretKey handles return null from
/// getEncoded(); the raw path below is the STAGING adapter only).
pub struct WrappingKeySpec {
    pub alias: &'static str,
    pub key_size_bits: u32,
    pub purposes_encrypt: bool,
    pub purposes_decrypt: bool,
    pub block_mode_gcm: bool,
    pub padding_none: bool,
    pub randomized_encryption_required: bool,
    /// AndroidKeyStore contract: key material never leaves the provider.
    pub extractable: bool,
}

pub const WRAPPING_KEY_SPEC: WrappingKeySpec = WrappingKeySpec {
    alias: "cyclecare_room_db_wrapping_key_v1",
    key_size_bits: 256,
    purposes_encrypt: true,
    purposes_decrypt: true,
    block_mode_gcm: true,
    padding_none: true,
    randomized_encryption_required: true,
    extractable: false,
};

/// The JCE Cipher-shaped boundary: seal/open with an OPAQUE key. No method
/// yields key material; a device impl talks to Cipher + Keystore, so the
/// core NEVER holds the wrapping key. Error mapping matches the source's
/// unguarded throws (:30-37, :52-66): Keystore/KeyGenerator failures ->
/// KeystoreFailed; AEADBadTagException -> TagMismatch; bad stored blob ->
/// MalformedBase64/InvalidStoredKey via the AOSP decoder port.
pub trait WrappingKeyPort {
    /// getOrCreateWrappingKey(): load Keystore, return existing alias key,
    /// else generate per [WRAPPING_KEY_SPEC].
    fn ensure_key(&mut self) -> Result<(), DbKeyError>;
    /// encrypt(): Cipher.init(ENCRYPT) draws the IV (randomized required),
    /// doFinal(UTF-8), Base64 NO_WRAP of iv||ciphertext.
    fn seal(&mut self, plaintext: &str) -> Result<String, DbKeyError>;
    /// decrypt(): Base64 decode -> require(size>12) -> GCM open -> UTF-8.
    fn open(&mut self, encoded: &str) -> Result<String, DbKeyError>;
}

/// STAGING adapter: holds raw bytes (the thing the device contract forbids)
/// so host-free tests can drive the port. Isolated to test harnesses; not
/// wired to any production path. seal/open delegate to the corpus-locked
/// db_key_manager codec - no duplicate crypto.
pub struct StagingWrappingKey<R: FnMut() -> [u8; 32], I: FnMut() -> [u8; GCM_IV_LENGTH]> {
    key: Option<[u8; 32]>,
    keygen_ok: bool,
    random_key: R,
    random_iv: I,
}

impl<R: FnMut() -> [u8; 32], I: FnMut() -> [u8; GCM_IV_LENGTH]> StagingWrappingKey<R, I> {
    pub fn new(random_key: R, random_iv: I) -> Self {
        Self { key: None, keygen_ok: true, random_key, random_iv }
    }
    /// Simulate Keystore.load / KeyGenerator failure (source: unguarded).
    pub fn fail_keygen(&mut self) { self.keygen_ok = false; }
    /// Simulate device keystore wipe: alias key gone, wrapped blob orphaned.
    pub fn simulate_keystore_wipe(&mut self) { self.key = None; }
}

impl<R: FnMut() -> [u8; 32], I: FnMut() -> [u8; GCM_IV_LENGTH]> WrappingKeyPort
    for StagingWrappingKey<R, I>
{
    fn ensure_key(&mut self) -> Result<(), DbKeyError> {
        if !self.keygen_ok { return Err(DbKeyError::KeystoreFailed); }
        if self.key.is_none() { self.key = Some((self.random_key)()); }
        Ok(())
    }
    fn seal(&mut self, plaintext: &str) -> Result<String, DbKeyError> {
        self.ensure_key()?;
        let key = self.key.ok_or(DbKeyError::KeystoreFailed)?;
        let iv = (self.random_iv)();
        Ok(gcm_encrypt_blob(&key, &iv, plaintext))
    }
    fn open(&mut self, encoded: &str) -> Result<String, DbKeyError> {
        self.ensure_key()?;
        let key = self.key.ok_or(DbKeyError::KeystoreFailed)?;
        gcm_decrypt_blob(&key, encoded)
    }
}

/// Host effects for the passphrase store row (SharedPreferences
/// "cyclecare_local_db_key" / "wrapped_sqlcipher_passphrase", :56-58).
pub trait WrappedPassphraseStore {
    fn get_wrapped(&self) -> Option<String>;
    /// edit().putString(...).commit() - returns the commit bool (:33).
    fn commit_wrapped(&mut self, value: &str) -> bool;
    /// SecureRandom().nextBytes(32) (:27).
    fn secure_random_32(&mut self) -> [u8; 32];
}

/// getOrCreatePassphrase (:21-37) composed on the port: identical sequence
/// to db_key_manager::get_or_create_passphrase but the wrapping key never
/// crosses the boundary. @Synchronized remains a host obligation.
pub fn get_or_create_passphrase_via_port<P: WrappingKeyPort, S: WrappedPassphraseStore>(
    port: &mut P,
    store: &mut S,
) -> Result<String, DbKeyError> {
    port.ensure_key()?;
    if let Some(wrapped) = store.get_wrapped() {
        return port.open(&wrapped);
    }
    let mut secret = store.secure_random_32();
    let passphrase = passphrase_from_secret(&secret);
    secret.fill(0); // source order: zeroize before encrypt (:29)
    let encrypted = port.seal(&passphrase)?;
    if !store.commit_wrapped(&encrypted) {
        return Err(DbKeyError::PersistFailed);
    }
    Ok(passphrase)
}

// ---------- B. Encrypted-preferences factory (AuthPreferences.kt companion :68-127) ----------

pub const PREFS_SECURE_NAME: &str = "cyclecare_auth_secure_prefs";
pub const PREFS_LEGACY_NAME: &str = "cyclecare_auth_prefs";

/// createEncryptedOrFallbackPrefs' MasterKey/EncryptedSharedPreferences
/// construction (:70-79). Any Exception -> plaintext fallback. The crypto
/// schemes (AES256_GCM master key, AES256_SIV keys, AES256_GCM values) are
/// androidx.security:security-crypto behavior - host obligation, not
/// re-implemented here.
pub trait SecureStoreFactory {
    /// Ok = encrypted store constructed (contents readable). Err = the
    /// source's catch(_: Exception).
    fn create_encrypted(&mut self) -> Result<Prefs, ()>;
    /// getSharedPreferences(PREFS_LEGACY_NAME, MODE_PRIVATE), live singleton.
    fn open_legacy(&mut self) -> Prefs;
    /// Persist legacy mutations (migration clear / fallback sanitize).
    fn commit_legacy(&mut self, legacy: &Prefs);
}

pub struct StoreSelection {
    pub prefs: Prefs,
    /// preferenceStore.secure (:21) -> isSecureStorageAvailable (:20).
    pub secure: bool,
}

/// :68-104 composed. Success: migrate legacy (swallow, best-effort :105-127
/// - the pure map model cannot throw; a host IO failure inside migration is
/// the host's swallow to implement), secure=true. Failure: sanitize the
/// legacy store (exactly the 14 credential keys, corpus-locked), secure=false.
pub fn select_preference_store<F: SecureStoreFactory>(fx: &mut F) -> StoreSelection {
    match fx.create_encrypted() {
        Ok(mut secure) => {
            let mut legacy = fx.open_legacy();
            migrate_legacy(&mut legacy, &mut secure);
            fx.commit_legacy(&legacy);
            StoreSelection { prefs: secure, secure: true }
        }
        Err(()) => {
            let mut legacy = fx.open_legacy();
            fallback_sanitize(&mut legacy);
            fx.commit_legacy(&legacy);
            StoreSelection { prefs: legacy, secure: false }
        }
    }
}

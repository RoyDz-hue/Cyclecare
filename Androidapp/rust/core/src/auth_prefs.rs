//! AuthPreferences.kt (security, main 31b88f5): the identity/session chain
//! behind the owner handoff. CONSOLIDATED 2026-11-03 (reviewer direction):
//! the PIN codec lives in [crate::pin_verify] (maintained, JVM-probed on
//! OpenJDK 21), MD5/UUID v3 in [crate::md5]/[crate::lifecycle], and this
//! module only adds what had NO maintained home: the userId setter's
//! session-generation bump (:360-364). Re-exports keep a single import
//! surface; no duplicated crypto remains here.

pub use crate::lifecycle::effective_user_id;
pub use crate::pin_verify::{
    encode_pin_pbkdf2_with_salt as encode_pin_pbkdf2, hash_pin_legacy_device, hash_pin_legacy_salt,
    password_bytes as pbe_password_bytes, pbkdf2 as pbkdf2_hmac_sha256, verify_pin, verify_pin_pbkdf2,
    PinVerify as VerifyPin, PBKDF2_ITERATIONS, PBKDF2_MAX_ACCEPTED_ITERATIONS,
    PBKDF2_MIN_ACCEPTED_ITERATIONS, PBKDF2_PREFIX, PBKDF2_SALT_BYTES,
};

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

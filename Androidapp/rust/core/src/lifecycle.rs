//! lifecycle.rs — account-start/session/onboarding/wallet lifecycle state
//! ownership. Ports of (main@31b88f5):
//! - AuthPreferences.hasCompletedInitialAuth gating (secure-storage
//!   availability decides whether a reopen recovers the account at all)
//! - AuthPreferences.effectiveUserId (userId, else UUID v3 over device id)
//! - AuthPreferences session transitions (userId setter bump, clearSession,
//!   insecure-store never-stores-but-still-bumps behavior)
//! - CycleCareViewModel.init startup gate (cloud check / lock / onboarding /
//!   wallet-fetch triggers)
//! Wallet display state itself lives in wallet.rs; fetch classification in
//! economy_status.rs. Probe-locked: /downloads/parity/lifecycle/ (kotlinc
//! 2.1.0/JRE 21, verbatim extracted expressions).

use crate::md5::md5;

/// AuthPreferences.hasCompletedInitialAuth:
/// `isLocalOnlyMode || (isSecureStorageAvailable && stored)`.
/// Critical: a Keystore/EncryptedSharedPreferences failure on reopen makes
/// this false even when the stored flag is true - the app returns to the
/// welcome/auth route with credentials purged from the plaintext fallback.
pub fn has_completed_initial_auth(
    is_local_only_mode: bool,
    is_secure_storage_available: bool,
    stored_initial_auth_completed: bool,
) -> bool {
    is_local_only_mode || (is_secure_storage_available && stored_initial_auth_completed)
}

/// java.util.UUID.nameUUIDFromBytes(deviceId UTF-8 bytes): MD5, version 3,
/// IETF variant, lowercase 8-4-4-4-12.
pub fn name_uuid_from_bytes(data: &[u8]) -> String {
    let mut d = md5(data);
    d[6] = (d[6] & 0x0f) | 0x30;
    d[8] = (d[8] & 0x3f) | 0x80;
    let hex: String = d.iter().map(|b| format!("{:02x}", b)).collect();
    format!(
        "{}-{}-{}-{}-{}",
        &hex[0..8], &hex[8..12], &hex[12..16], &hex[16..20], &hex[20..32]
    )
}

/// AuthPreferences.effectiveUserId: the authenticated userId when non-blank,
/// else the device-bound anonymous id (UUID v3 of the device id).
pub fn effective_user_id(user_id: Option<&str>, device_id: &str) -> String {
    match user_id {
        Some(uid) if !uid.trim().is_empty() => uid.to_string(),
        // Kotlin checks isNullOrBlank (no trim); a blank-but-nonempty string
        // falls through to the UUID branch there too.
        _ => name_uuid_from_bytes(device_id.as_bytes()),
    }
}

/// Startup gate from CycleCareViewModel.init. All inputs are the persisted
/// snapshot values at reopen; outputs decide the visible route and whether a
/// verified wallet fetch starts.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StartupGate {
    pub has_completed_initial_auth: bool,
    pub needs_cloud_profile_check: bool,
    pub is_locked: bool,
    pub show_onboarding: bool,
    pub fetch_wallet: bool,
}

pub fn startup_gate(
    is_local_only_mode: bool,
    is_secure_storage_available: bool,
    stored_initial_auth_completed: bool,
    profile_present: bool,
    settings_onboarding_completed: bool,
    is_pin_enabled: bool,
    pin_present: bool,
    is_fingerprint_enabled: bool,
    user_id_present: bool,
) -> StartupGate {
    let has_completed = has_completed_initial_auth(
        is_local_only_mode,
        is_secure_storage_available,
        stored_initial_auth_completed,
    );
    let needs_cloud_profile_check =
        has_completed && !is_local_only_mode && (!profile_present || !settings_onboarding_completed);
    let is_pin_set = is_pin_enabled && pin_present;
    let is_locked = has_completed && (is_pin_set || is_fingerprint_enabled);
    let show_onboarding = has_completed
        && !needs_cloud_profile_check
        && (!profile_present || !settings_onboarding_completed);
    let fetch_wallet = has_completed && !is_local_only_mode && user_id_present;
    StartupGate {
        has_completed_initial_auth: has_completed,
        needs_cloud_profile_check,
        is_locked,
        show_onboarding,
        fetch_wallet,
    }
}

/// Pure model of the AuthPreferences session transitions (map-backed).
/// Insecure stores never retain secure strings but STILL bump the session
/// generation on every userId set (probe-locked: session_insecure g1->g2).
#[derive(Debug, Clone)]
pub struct SessionStore {
    secure: bool,
    entries: std::collections::HashMap<String, String>,
}

impl SessionStore {
    pub fn new(secure: bool) -> Self {
        SessionStore { secure, entries: std::collections::HashMap::new() }
    }

    fn read_secure_string(&self, key: &str) -> Option<&str> {
        if self.secure {
            self.entries.get(key).map(String::as_str)
        } else {
            None
        }
    }

    fn write_secure_string(&mut self, key: &str, value: Option<&str>) {
        match value {
            Some(v) if self.secure && !v.trim().is_empty() => {
                self.entries.insert(key.to_string(), v.to_string());
            }
            // Kotlin: isNullOrBlank -> remove. Blank strings are removed.
            _ => {
                self.entries.remove(key);
            }
        }
    }

    pub fn session_generation(&self) -> i64 {
        self.entries
            .get("session_generation_v3")
            .and_then(|v| v.parse().ok())
            .unwrap_or(0)
    }

    fn bump_generation(&mut self) {
        // Math.addExact semantics: panic on overflow is unreachable in
        // practice; use wrapping to mirror long wraparound defensively.
        let next = self.session_generation().wrapping_add(1);
        self.entries.insert("session_generation_v3".to_string(), next.to_string());
    }

    pub fn user_id(&self) -> Option<&str> {
        self.read_secure_string("user_id")
    }

    /// userId setter: any change (including set on an insecure store, where
    /// the read is always null) bumps the generation before writing.
    pub fn set_user_id(&mut self, value: Option<&str>) {
        let current = self.read_secure_string("user_id").map(|s| s.to_string());
        if current.as_deref() != value {
            self.bump_generation();
        }
        self.write_secure_string("user_id", value);
    }

    /// clearSession: bump + purge the session keys; mode back to ANONYMOUS.
    pub fn clear_session(&mut self) {
        self.bump_generation();
        for k in [
            "access_token",
            "refresh_token",
            "session_expires_at",
            "google_pkce_verifier",
            "user_id",
            "user_email",
            "pending_email",
            "pending_auth_action",
        ] {
            self.entries.remove(k);
        }
        self.entries.insert("pending_guest_email_link".to_string(), "false".to_string());
        self.entries.insert("needs_password_setup".to_string(), "false".to_string());
        self.entries.insert("account_mode".to_string(), "ANONYMOUS".to_string());
    }

    pub fn get(&self, key: &str) -> Option<&str> {
        self.entries.get(key).map(String::as_str)
    }

    pub fn is_secure_storage_available(&self) -> bool {
        self.secure
    }

    pub fn access_token(&self) -> Option<&str> {
        self.read_secure_string("access_token")
    }

    pub fn set_access_token(&mut self, value: Option<&str>) {
        self.write_secure_string("access_token", value);
    }

    pub fn refresh_token(&self) -> Option<&str> {
        self.read_secure_string("refresh_token")
    }

    pub fn set_refresh_token(&mut self, value: Option<&str>) {
        self.write_secure_string("refresh_token", value);
    }

    pub fn user_email(&self) -> Option<&str> {
        self.read_secure_string("user_email")
    }

    pub fn set_user_email(&mut self, value: Option<&str>) {
        self.write_secure_string("user_email", value);
    }

    /// sessionExpiresAtSeconds: gated on secure storage, 0 otherwise.
    pub fn session_expires_at_seconds(&self) -> i64 {
        if self.secure {
            self.entries
                .get("session_expires_at")
                .and_then(|v| v.parse().ok())
                .unwrap_or(0)
        } else {
            0
        }
    }

    pub fn set_session_expires_at_seconds(&mut self, value: i64) {
        if self.secure {
            self.entries
                .insert("session_expires_at".to_string(), value.to_string());
        }
    }

    /// accountMode: plain (non-secure) pref, default "ANONYMOUS".
    pub fn account_mode(&self) -> &str {
        self.entries
            .get("account_mode")
            .map(String::as_str)
            .unwrap_or("ANONYMOUS")
    }

    pub fn set_account_mode(&mut self, value: &str) {
        self.entries.insert("account_mode".to_string(), value.to_string());
    }

    /// AuthPreferences.withCurrentSession: null unless secure AND the
    /// owner/generation still match at commit time.
    pub fn with_current_session<T>(
        &mut self,
        owner: &str,
        session: i64,
        block: impl FnOnce(&mut Self) -> T,
    ) -> Option<T> {
        if !self.secure || self.user_id() != Some(owner) || self.session_generation() != session {
            return None;
        }
        Some(block(self))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn md5_rfc1321_vectors() {
        let hex = |d: &[u8]| md5(d).iter().map(|b| format!("{:02x}", b)).collect::<String>();
        assert_eq!(hex(b""), "d41d8cd98f00b204e9800998ecf8427e");
        assert_eq!(hex(b"abc"), "900150983cd24fb0d6963f7d28e17f72");
        assert_eq!(
            hex(b"message digest"),
            "f96b697d7cb7938d525a2f31aaf161d0"
        );
        assert_eq!(
            hex(b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"),
            "d174ab98d277d9f5a5611c2c9f419d9f"
        );
    }

    #[test]
    fn uuid_v3_matches_jvm() {
        // Executed on the JVM (LifecycleParity probe).
        assert_eq!(
            name_uuid_from_bytes(b"dev-1"),
            "1a6fd60a-34f6-39ee-a11b-f9e6b9689441"
        );
        assert_eq!(
            name_uuid_from_bytes(b"device-xyz-789"),
            "82a1c504-fa77-3f9e-90fb-89a1a3fd45ee"
        );
    }

    // Executed JVM locks (LifecycleParity.kt + SessionParity.kt, kotlinc
    // 2.1.0/JRE 21, executed 2026-11-02 13:47 EAT; verbatim ViewModel.init
    // gate expressions and AuthPreferences session transitions).
    fn gate_str(
        local: bool, secure: bool, stored: bool, profile: bool, ob: bool,
        pin_en: bool, pin_present: bool, fp: bool, uid: bool,
    ) -> String {
        let g = startup_gate(local, secure, stored, profile, ob, pin_en, pin_present, fp, uid);
        format!(
            "auth={} cloudCheck={} locked={} onboarding={} fetch={}",
            g.has_completed_initial_auth, g.needs_cloud_profile_check, g.is_locked,
            g.show_onboarding, g.fetch_wallet
        )
    }

    #[test]
    fn startup_gate_matches_executed_jvm_probes() {
        let cases: [(&str, (bool, bool, bool, bool, bool, bool, bool, bool, bool), &str); 10] = [
            ("gate_fresh_install", (false, false, false, false, false, false, false, false, false),
                "auth=false cloudCheck=false locked=false onboarding=false fetch=false"),
            ("gate_returning_complete", (false, true, true, true, true, false, false, false, true),
                "auth=true cloudCheck=false locked=false onboarding=false fetch=true"),
            ("gate_keystore_failure_reopen", (false, false, true, true, true, false, false, false, false),
                "auth=false cloudCheck=false locked=false onboarding=false fetch=false"),
            ("gate_local_only", (true, false, false, true, true, false, false, false, false),
                "auth=true cloudCheck=false locked=false onboarding=false fetch=false"),
            ("gate_local_only_no_profile", (true, true, true, false, false, false, false, false, false),
                "auth=true cloudCheck=false locked=false onboarding=true fetch=false"),
            ("gate_cloud_check", (false, true, true, false, false, false, false, false, true),
                "auth=true cloudCheck=true locked=false onboarding=false fetch=true"),
            ("gate_onboarding_needed", (true, true, true, true, false, false, false, false, false),
                "auth=true cloudCheck=false locked=false onboarding=true fetch=false"),
            ("gate_locked_pin", (false, true, true, true, true, true, true, false, true),
                "auth=true cloudCheck=false locked=true onboarding=false fetch=true"),
            ("gate_locked_fp", (false, true, true, true, true, false, false, true, true),
                "auth=true cloudCheck=false locked=true onboarding=false fetch=true"),
            ("gate_pin_enabled_but_blank", (false, true, true, true, true, true, false, false, true),
                "auth=true cloudCheck=false locked=false onboarding=false fetch=true"),
        ];
        for (name, args, expect) in cases {
            let got = gate_str(args.0, args.1, args.2, args.3, args.4, args.5, args.6, args.7, args.8);
            assert_eq!(got, expect, "{name}");
        }
    }

    #[test]
    fn effective_user_id_matches_executed_jvm_probes() {
        assert_eq!(effective_user_id(Some("user-abc"), "dev-1"), "user-abc");
        assert_eq!(
            effective_user_id(Some(""), "dev-1"),
            "1a6fd60a-34f6-39ee-a11b-f9e6b9689441"
        );
        assert_eq!(
            effective_user_id(None, "dev-1"),
            "1a6fd60a-34f6-39ee-a11b-f9e6b9689441"
        );
        assert_eq!(
            effective_user_id(None, "device-xyz-789"),
            "82a1c504-fa77-3f9e-90fb-89a1a3fd45ee"
        );
    }

    #[test]
    fn session_transitions_match_executed_jvm_probes() {
        // Secure store: bump only on change; clearSession bumps once more.
        let mut a = SessionStore::new(true);
        let mut seq = format!("g{};", a.session_generation());
        a.set_user_id(Some("u1"));
        seq += &format!("set_u1:g{},id={};", a.session_generation(), a.user_id().unwrap_or("null"));
        a.set_user_id(Some("u1"));
        seq += &format!("set_u1_again:g{};", a.session_generation());
        a.set_user_id(Some("u2"));
        seq += &format!("set_u2:g{};", a.session_generation());
        a.set_user_id(None);
        seq += &format!("set_null:g{},id={};", a.session_generation(), a.user_id().unwrap_or("null"));
        a.set_user_id(Some("u3"));
        a.clear_session();
        seq += &format!(
            "clear:g{},id={},mode={};",
            a.session_generation(),
            a.user_id().unwrap_or("null"),
            a.get("account_mode").unwrap_or("null")
        );
        assert_eq!(
            seq,
            "g0;set_u1:g1,id=u1;set_u1_again:g1;set_u2:g2;set_null:g3,id=null;clear:g5,id=null,mode=ANONYMOUS;"
        );

        // Insecure store: never retains the id but still bumps on every set.
        let mut b = SessionStore::new(false);
        let mut seq2 = String::new();
        b.set_user_id(Some("u1"));
        seq2 += &format!("set_u1:g{},id={};", b.session_generation(), b.user_id().unwrap_or("null"));
        b.set_user_id(Some("u1"));
        seq2 += &format!("set_u1_again:g{},id={};", b.session_generation(), b.user_id().unwrap_or("null"));
        b.clear_session();
        seq2 += &format!("clear:g{};", b.session_generation());
        assert_eq!(seq2, "set_u1:g1,id=null;set_u1_again:g2,id=null;clear:g3;");
    }
}

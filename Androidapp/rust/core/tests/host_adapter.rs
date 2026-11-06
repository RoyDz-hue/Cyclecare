//! Adapter-boundary composition tests for the Keystore/JCE port and the
//! encrypted-preferences store selection (host_adapter.rs). All staging:
//! scripted randomness, in-memory prefs. Not a device proof.

use cyclecare_core::db_key_manager::{self, DbKeyError};
use cyclecare_core::host_adapter::*;
use cyclecare_core::lifecycle::has_completed_initial_auth;
use cyclecare_core::secure_store::{Prefs, PrefsValue, FALLBACK_SANITIZE_KEYS};

// Scripted RNG streams so both drivers see identical bytes.
struct Seq { items: std::collections::VecDeque<[u8; 32]> }
impl Seq {
    fn next(&mut self) -> [u8; 32] { self.items.pop_front().expect("scripted") }
}

struct MemStore {
    wrapped: Option<String>,
    commits: Vec<String>,
    commit_ok: bool,
    randoms: std::collections::VecDeque<[u8; 32]>,
}
impl WrappedPassphraseStore for MemStore {
    fn get_wrapped(&self) -> Option<String> { self.wrapped.clone() }
    fn commit_wrapped(&mut self, v: &str) -> bool {
        self.commits.push(v.to_string());
        if self.commit_ok { self.wrapped = Some(v.to_string()); }
        self.commit_ok
    }
    fn secure_random_32(&mut self) -> [u8; 32] { self.randoms.pop_front().expect("scripted") }
}

// Old raw-bytes effects path (v111 surface) for the equivalence proof.
struct OldFx {
    key: [u8; 32],
    store: MemStore,
    ivs: std::collections::VecDeque<[u8; 12]>,
}
impl db_key_manager::DbKeyEffects for OldFx {
    fn wrapping_key(&self) -> Result<[u8; 32], DbKeyError> { Ok(self.key) }
    fn get_wrapped(&self) -> Option<String> { self.store.get_wrapped() }
    fn secure_random_32(&mut self) -> [u8; 32] { self.store.secure_random_32() }
    fn gcm_iv(&mut self) -> [u8; 12] { self.ivs.pop_front().expect("scripted") }
    fn commit_wrapped(&mut self, v: &str) -> bool { self.store.commit_wrapped(v) }
}

fn fixed(n: u8) -> [u8; 32] { [n; 32] }

#[test]
fn spec_mirrors_source_keygen_parameters() {
    assert_eq!(WRAPPING_KEY_SPEC.alias, "cyclecare_room_db_wrapping_key_v1");
    assert_eq!(WRAPPING_KEY_SPEC.key_size_bits, 256);
    assert!(WRAPPING_KEY_SPEC.purposes_encrypt && WRAPPING_KEY_SPEC.purposes_decrypt);
    assert!(WRAPPING_KEY_SPEC.block_mode_gcm && WRAPPING_KEY_SPEC.padding_none);
    assert!(WRAPPING_KEY_SPEC.randomized_encryption_required);
    assert!(!WRAPPING_KEY_SPEC.extractable, "AndroidKeyStore keys are non-extractable");
}

#[test]
fn port_driver_matches_raw_driver_byte_for_byte() {
    let (key, secret, iv) = (fixed(9), fixed(3), [5u8; 12]);
    let mut old = OldFx {
        key,
        store: MemStore { wrapped: None, commits: vec![], commit_ok: true,
                          randoms: vec![secret].into_iter().collect() },
        ivs: vec![iv].into_iter().collect(),
    };
    let old_pass = db_key_manager::get_or_create_passphrase(&mut old).unwrap();
    let old_wrapped = old.store.wrapped.clone().unwrap();

    let key_stream = std::collections::VecDeque::from(vec![key]);
    let iv_stream = std::collections::VecDeque::from(vec![iv]);
    let mut port = StagingWrappingKey::new(move || key_stream.clone().pop_front().unwrap(),
                                           move || iv_stream.clone().pop_front().unwrap());
    let mut store = MemStore { wrapped: None, commits: vec![], commit_ok: true,
                               randoms: vec![secret].into_iter().collect() };
    let new_pass = get_or_create_passphrase_via_port(&mut port, &mut store).unwrap();

    assert_eq!(old_pass, new_pass, "same passphrase through the port");
    assert_eq!(store.commits, vec![old_wrapped], "identical persisted wrapped blob");

    // Second launch: stored blob opens through the port, no regeneration.
    let pass2 = get_or_create_passphrase_via_port(&mut port, &mut store).unwrap();
    assert_eq!(pass2, old_pass);
    assert_eq!(store.commits.len(), 1, "no rewrite when a wrapped blob exists");
}

#[test]
fn keystore_failure_and_wipe_propagate_as_source_throws() {
    let keys = std::sync::Mutex::new(vec![fixed(1), fixed(7)].into_iter().collect::<std::collections::VecDeque<_>>());
    let mut port = StagingWrappingKey::new(|| keys.lock().unwrap().pop_front().unwrap(), || [0u8; 12]);
    let mut store = MemStore { wrapped: None, commits: vec![], commit_ok: true,
                               randoms: vec![fixed(2)].into_iter().collect() };
    let pass = get_or_create_passphrase_via_port(&mut port, &mut store).unwrap();

    // Keystore wipe: alias key lost; regeneration yields a DIFFERENT key
    // (scripted distinct) -> stored blob fails GCM open under the new key. Source parity: AEADBadTagException propagates, no swallow -
    // key loss means the wrapped passphrase is unrecoverable (device risk,
    // labeled; not a new failure mode introduced by the port).
    port.simulate_keystore_wipe();
    assert_eq!(get_or_create_passphrase_via_port(&mut port, &mut store),
               Err(DbKeyError::TagMismatch));

    // Keygen/load failure: KeystoreFailed before any store read or write.
    let mut bad = StagingWrappingKey::new(|| fixed(1), || [0u8; 12]);
    bad.fail_keygen();
    let mut s2 = MemStore { wrapped: None, commits: vec![], commit_ok: true, randoms: Default::default() };
    assert_eq!(get_or_create_passphrase_via_port(&mut bad, &mut s2), Err(DbKeyError::KeystoreFailed));
    assert!(s2.commits.is_empty());

    // Commit false -> check() -> PersistFailed.
    let mut port2 = StagingWrappingKey::new(|| fixed(1), || [0u8; 12]);
    let mut s3 = MemStore { wrapped: None, commits: vec![], commit_ok: false,
                            randoms: vec![fixed(2)].into_iter().collect() };
    assert_eq!(get_or_create_passphrase_via_port(&mut port2, &mut s3), Err(DbKeyError::PersistFailed));
    let _ = pass;
}

// ---------- Encrypted-preferences store selection ----------

struct Factory {
    encrypted: Result<Prefs, ()>,
    legacy: Prefs,
    committed: Vec<Prefs>,
    /// Failure injection at the source's two batched-editor points:
    /// SecureBatch = throw before the secure apply (NOTHING persists to
    /// secure, legacy intact); LegacyClear = secure batch applied, throw
    /// on the legacy clear (secure fully migrated, legacy intact).
    migration_failure: Option<MigrateFailure>,
}
#[derive(Clone, Copy)]
enum MigrateFailure { SecureBatch, LegacyClear }
impl Factory {
    fn ok(legacy: Prefs) -> Self {
        Factory { encrypted: Ok(Prefs::new()), legacy, committed: vec![], migration_failure: None }
    }
    fn fallback(legacy: Prefs) -> Self {
        Factory { encrypted: Err(()), legacy, committed: vec![], migration_failure: None }
    }
}
impl SecureStoreFactory for Factory {
    fn create_encrypted(&mut self) -> Result<Prefs, ()> { self.encrypted.clone() }
    fn open_legacy(&mut self) -> Prefs { self.legacy.clone() }
    fn migrate_legacy_into(&mut self, secure: &mut Prefs) -> Result<(), ()> {
        match self.migration_failure {
            Some(MigrateFailure::SecureBatch) => {
                // Throw before the batched apply: the editor is discarded,
                // NOTHING persists to secure, legacy stays intact.
                Err(())
            }
            Some(MigrateFailure::LegacyClear) => {
                // Secure batch applied atomically, then the clear failed:
                // secure fully migrated, legacy NOT cleared.
                for (k, v) in self.legacy.clone() {
                    if !secure.contains_key(&k) { secure.insert(k, v); }
                }
                Err(())
            }
            None => cyclecare_core::host_adapter::staging_migrate_legacy(&mut self.legacy, secure),
        }
    }
    fn commit_legacy(&mut self, l: &Prefs) { self.legacy = l.clone(); self.committed.push(l.clone()); }
}

fn legacy_with_state() -> Prefs {
    let mut p = Prefs::new();
    p.insert("user_id".into(), PrefsValue::Str("u-1".into()));
    p.insert("access_token".into(), PrefsValue::Str("tok".into()));
    p.insert("refresh_token".into(), PrefsValue::Str("ref".into()));
    p.insert("initial_auth_completed".into(), PrefsValue::Bool(true));
    p.insert("local_only_mode_v1".into(), PrefsValue::Bool(false));
    p.insert("last_sync_timestamp".into(), PrefsValue::Long(1234));
    p
}

#[test]
fn secure_path_migrates_and_preserves_identity_session_onboarding() {
    let mut fx = Factory::ok(legacy_with_state());
    let sel = select_preference_store(&mut fx);
    assert!(sel.secure);
    // identity/session/onboarding rows survived the migration
    assert_eq!(sel.prefs.get("user_id"), Some(&PrefsValue::Str("u-1".into())));
    assert_eq!(sel.prefs.get("access_token"), Some(&PrefsValue::Str("tok".into())));
    assert_eq!(sel.prefs.get("refresh_token"), Some(&PrefsValue::Str("ref".into())));
    assert_eq!(sel.prefs.get("initial_auth_completed"), Some(&PrefsValue::Bool(true)));
    // legacy cleared and persisted
    assert!(fx.legacy.is_empty());
    // onboarding gate reads through with secure storage
    let stored = matches!(sel.prefs.get("initial_auth_completed"), Some(PrefsValue::Bool(true)));
    assert!(has_completed_initial_auth(false, sel.secure, stored));
}

#[test]
fn secure_path_never_clobbers_existing_secure_values() {
    let mut secure = Prefs::new();
    secure.insert("user_id".into(), PrefsValue::Str("u-secure".into()));
    let mut fx = Factory { encrypted: Ok(secure), ..Factory::ok(legacy_with_state()) };
    let sel = select_preference_store(&mut fx);
    assert_eq!(sel.prefs.get("user_id"), Some(&PrefsValue::Str("u-secure".into())), "secure wins");
    assert_eq!(sel.prefs.get("access_token"), Some(&PrefsValue::Str("tok".into())), "absent keys still migrate");
    assert!(fx.legacy.is_empty());
}

#[test]
fn fallback_scrubs_exactly_14_credentials_but_keeps_non_credentials() {
    let mut fx = Factory::fallback(legacy_with_state());
    let sel = select_preference_store(&mut fx);
    assert!(!sel.secure, "secure=false: isSecureStorageAvailable false");
    for k in FALLBACK_SANITIZE_KEYS {
        assert!(!sel.prefs.contains_key(k), "scrubbed {k}");
    }
    assert_eq!(FALLBACK_SANITIZE_KEYS.len(), 14);
    // non-credential state survives the fallback
    assert_eq!(sel.prefs.get("local_only_mode_v1"), Some(&PrefsValue::Bool(false)));
    assert_eq!(sel.prefs.get("last_sync_timestamp"), Some(&PrefsValue::Long(1234)));
    // onboarding gate: secure=false drops initial_auth (source parity -
    // the user re-sees auth after secure-storage loss; NOT a new behavior)
    assert!(!has_completed_initial_auth(false, sel.secure, true));
    // local-only mode still satisfies the gate without secure storage
    assert!(has_completed_initial_auth(true, sel.secure, true));
}

#[test]
fn fallback_keeps_user_id_pairing_consistent_with_db_passphrase_seam() {
    // After the scrub, user_id is gone: effective_user_id falls back to the
    // device-derived UUID. Document the composition: identity loss in the
    // fallback also re-scopes every effectiveUserId-keyed row. Parity with
    // source (AuthPreferences.kt :83-100 + :438-449), not a port choice.
    let mut fx = Factory::fallback(legacy_with_state());
    let sel = select_preference_store(&mut fx);
    let uid = sel.prefs.get("user_id").and_then(|v| match v {
        PrefsValue::Str(s) => Some(s.as_str()), _ => None,
    });
    let effective = cyclecare_core::lifecycle::effective_user_id(uid, "dev-1");
    assert_eq!(effective, cyclecare_core::lifecycle::name_uuid_from_bytes(b"dev-1"));
}

#[test]
fn migration_throw_before_secure_apply_persists_nothing_and_legacy_intact() {
    // Batched editor semantics: a Throwable BEFORE the secure apply leaves
    // the secure store UNCHANGED (no partial writes), legacy intact, and
    // the selection is STILL secure=true (source catch(_: Throwable)).
    let mut fx = Factory { migration_failure: Some(MigrateFailure::SecureBatch), ..Factory::ok(legacy_with_state()) };
    let sel = select_preference_store(&mut fx);
    assert!(sel.secure, "selection is still the secure store");
    assert!(sel.prefs.is_empty(), "no partial writes: the batch was discarded");
    assert_eq!(fx.legacy.get("access_token"), Some(&PrefsValue::Str("tok".into())),
               "legacy NOT cleared when clear() was never reached");
}

#[test]
fn migration_throw_on_legacy_clear_leaves_secure_fully_migrated() {
    let mut fx = Factory { migration_failure: Some(MigrateFailure::LegacyClear), ..Factory::ok(legacy_with_state()) };
    let sel = select_preference_store(&mut fx);
    assert!(sel.secure);
    assert_eq!(sel.prefs.get("user_id"), Some(&PrefsValue::Str("u-1".into())), "secure batch applied atomically");
    assert_eq!(sel.prefs.get("access_token"), Some(&PrefsValue::Str("tok".into())));
    assert!(!fx.legacy.is_empty(), "legacy NOT cleared: its clear editor failed");
}

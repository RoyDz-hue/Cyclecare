//! DatabaseKeyManager crypto corpus: cross-implementation proof against the
//! source's own JCE AES/GCM calls (jdk-17.0.21+9, SunJCE). k* rows: same
//! key+IV must produce a BYTE-IDENTICAL blob and decrypt back; t* rows:
//! tampered tag must fail; s* rows: truncated blob must hit the require()
//! message; p* rows: Base64 NO_WRAP passphrase derivation.

use cyclecare_core::db_key_manager::{
    gcm_decrypt_blob, gcm_encrypt_blob, get_or_create_passphrase, passphrase_from_secret,
    DbKeyEffects, DbKeyError, ERR_INVALID_STORED, ERR_PERSIST, WRAPPED_PASSPHRASE,
};

fn from_hex(s: &str) -> String {
    String::from_utf8(unhex(s)).unwrap()
}

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

#[test]
fn gcm_blob_cross_implementation_corpus() {
    let data = std::fs::read_to_string("fixtures/oracle/results_dbkey.tsv").unwrap();
    let (mut nk, mut nt, mut ns, mut np, mut nu, mut nl) = (0, 0, 0, 0, 0, 0);
    for line in data.lines() {
        let c: Vec<&str> = line.split('\t').collect();
        match c[0].chars().next().unwrap() {
            'k' => {
                let key: [u8; 32] = unhex(c[1]).try_into().unwrap();
                let iv: [u8; 12] = unhex(c[2]).try_into().unwrap();
                let plain = String::from_utf8(unhex(c[3])).unwrap();
                // byte-identical encrypt
                assert_eq!(gcm_encrypt_blob(&key, &iv, &plain), c[4], "{}: encrypt diverged", c[0]);
                // JVM-produced blob decrypts here
                assert_eq!(gcm_decrypt_blob(&key, c[4]).unwrap(), plain, "{}: decrypt diverged", c[0]);
                nk += 1;
            }
            't' => {
                let key: [u8; 32] = unhex(c[1]).try_into().unwrap();
                assert_eq!(c[3], "AEADBadTagException", "{}: JVM expectation changed", c[0]);
                assert_eq!(gcm_decrypt_blob(&key, c[2]), Err(DbKeyError::TagMismatch), "{}: tamper accepted", c[0]);
                nt += 1;
            }
            's' => {
                assert_eq!(c[2], format!("IAE:{}", ERR_INVALID_STORED), "{}: require message changed", c[0]);
                let key = [7u8; 32];
                assert_eq!(gcm_decrypt_blob(&key, c[1]), Err(DbKeyError::InvalidStoredKey), "{}: short blob accepted", c[0]);
                ns += 1;
            }
            'p' => {
                let secret: [u8; 32] = unhex(c[1]).try_into().unwrap();
                assert_eq!(passphrase_from_secret(&secret), c[2], "{}: passphrase format diverged", c[0]);
                np += 1;
            }
            // Invalid-UTF-8 plaintext under a VALID tag: JVM String(bytes,
            // UTF_8) substitution; c[3] is the hex of the JVM result string.
            'u' => {
                let key: [u8; 32] = unhex(c[1]).try_into().unwrap();
                assert_eq!(gcm_decrypt_blob(&key, c[2]).unwrap(), from_hex(c[3]), "{}: U+FFFD substitution diverged", c[0]);
                nu += 1;
            }
            // Lenient Base64: blob with non-alphabet garbage (\n escaped in
            // TSV) must still decrypt (android.util.Base64 parity).
            'l' => {
                let key: [u8; 32] = unhex(c[1]).try_into().unwrap();
                let dirty = c[2].replace("\\n", "\n");
                assert_eq!(gcm_decrypt_blob(&key, &dirty).unwrap(), c[3], "{}: lenient decode diverged", c[0]);
                nl += 1;
            }
            other => panic!("bad row kind {other}"),
        }
    }
    assert_eq!((nk, nt, ns, np, nu, nl), (9, 9, 9, 3, 1, 1));
}

struct KeyFx {
    key: Result<[u8; 32], DbKeyError>,
    wrapped: Option<String>,
    random: [u8; 32],
    iv: [u8; 12],
    commit_ok: bool,
    calls: Vec<String>,
}

impl DbKeyEffects for KeyFx {
    fn wrapping_key(&self) -> Result<[u8; 32], DbKeyError> {
        self.key.clone()
    }
    fn get_wrapped(&self) -> Option<String> {
        self.wrapped.clone()
    }
    fn secure_random_32(&mut self) -> [u8; 32] {
        self.calls.push("random".to_string());
        self.random
    }
    fn gcm_iv(&mut self) -> [u8; 12] {
        self.calls.push("iv".to_string());
        self.iv
    }
    fn commit_wrapped(&mut self, _value: &str) -> bool {
        self.calls.push("commit".to_string());
        self.commit_ok
    }
}

fn fx(wrapped: Option<String>, commit_ok: bool) -> KeyFx {
    KeyFx {
        key: Ok([3u8; 32]),
        wrapped,
        random: [9u8; 32],
        iv: [5u8; 12],
        commit_ok,
        calls: vec![],
    }
}

#[test]
fn existing_wrapped_passphrase_is_decrypted_without_generation() {
    let key = [3u8; 32];
    let blob = gcm_encrypt_blob(&key, &[1u8; 12], "cGFzcw==");
    let mut f = fx(Some(blob), true);
    assert_eq!(get_or_create_passphrase(&mut f).unwrap(), "cGFzcw==");
    assert!(f.calls.is_empty(), "no generation effects on the existing path");
}

#[test]
fn tampered_stored_blob_propagates_failure() {
    // Source parity: AEADBadTagException propagates out of getOrCreatePassphrase.
    let key = [3u8; 32];
    let mut blob = gcm_encrypt_blob(&key, &[1u8; 12], "cGFzcw==");
    blob.replace_range(blob.len() - 2..blob.len() - 1, "A");
    let mut f = fx(Some(blob), true);
    assert_eq!(get_or_create_passphrase(&mut f), Err(DbKeyError::TagMismatch));
}

#[test]
fn create_path_generates_encrypts_commits() {
    let mut f = fx(None, true);
    let pass = get_or_create_passphrase(&mut f).unwrap();
    assert_eq!(pass, passphrase_from_secret(&[9u8; 32]));
    assert_eq!(f.calls, vec!["random", "iv", "commit"]);
}

#[test]
fn keystore_failure_propagates_before_any_other_effect() {
    // Source: getOrCreateWrappingKey() throws are unguarded and propagate.
    let mut f = fx(None, true);
    f.key = Err(DbKeyError::KeystoreFailed);
    assert_eq!(get_or_create_passphrase(&mut f), Err(DbKeyError::KeystoreFailed));
    assert!(f.calls.is_empty(), "no random/iv/commit after Keystore failure");
}

#[test]
fn create_path_commit_failure_is_the_check_message() {
    let mut f = fx(None, false);
    assert_eq!(get_or_create_passphrase(&mut f), Err(DbKeyError::PersistFailed));
    assert_eq!(ERR_PERSIST, "Could not securely persist the local database key.");
    assert_eq!(WRAPPED_PASSPHRASE, "wrapped_sqlcipher_passphrase");
}

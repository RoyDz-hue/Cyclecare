//! AuthPreferences corpus: JVM-executed JCE vectors (PinParity.kt, 64
//! rows) + MD5 FIPS vectors + the identity-chain transitions.

use cyclecare_core::auth_prefs::*;

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
}

fn utf8_hex(s: &str) -> String {
    s.as_bytes().iter().map(|b| format!("{b:02x}")).collect()
}

#[test]
fn md5_fips_vectors() {
    assert_eq!(hex_encode(&md5(b"")), "d41d8cd98f00b204e9800998ecf8427e");
    assert_eq!(hex_encode(&md5(b"abc")), "900150983cd24fb0d6963f7d28e17f72");
    assert_eq!(
        hex_encode(&md5(b"The quick brown fox jumps over the lazy dog")),
        "9e107d9d372bb6826bd81d3542a419d6"
    );
}

#[test]
fn pin_crypto_corpus_jvm_executed() {
    let data = std::fs::read_to_string("fixtures/oracle/results_authpin.tsv").unwrap();
    let (mut np, mut nc, mut nu, mut nd, mut ns) = (0, 0, 0, 0, 0);
    for line in data.lines() {
        let c: Vec<&str> = line.split('\t').collect();
        match c[0].chars().next().unwrap() {
            'p' => {
                let password_utf8 = unhex(c[1]);
                let pin = String::from_utf8(password_utf8).unwrap();
                let salt = unhex(c[2]);
                let iterations: u32 = c[3].parse().unwrap();
                let expect = unhex(c[4]);
                assert_eq!(
                    pbkdf2_hmac_sha256(&pbe_password_bytes(&pin), &salt, iterations).to_vec(),
                    expect,
                    "{}: PBKDF2 diverged from JVM SecretKeyFactory",
                    c[0]
                );
                np += 1;
            }
            'c' => {
                // SunJCE PBKDF2 password bytes are UTF-8 (JDK-8187543):
                // the c-rows record the UTF-16 units for reference while
                // the asserted mapping is UTF-8 (column 1).
                let pin = String::from_utf8(unhex(c[1])).unwrap();
                assert_eq!(pbe_password_bytes(&pin), unhex(c[1]), "{}: PBE UTF-8 parity", c[0]);
                nc += 1;
            }
            'u' => {
                let device_id = String::from_utf8(unhex(c[1])).unwrap();
                assert_eq!(name_uuid_from_bytes(device_id.as_bytes()), c[2], "{}: nameUUIDFromBytes diverged", c[0]);
                nu += 1;
            }
            'd' => {
                let device_id = String::from_utf8(unhex(c[1])).unwrap();
                let pin = String::from_utf8(unhex(c[2])).unwrap();
                assert_eq!(hash_pin_legacy_device(&pin, &device_id), c[3], "{}: legacy device hash", c[0]);
                nd += 1;
            }
            's' => {
                let pin = String::from_utf8(unhex(c[1])).unwrap();
                assert_eq!(hash_pin_legacy_salt(&pin), c[2], "{}: legacy salt hash", c[0]);
                ns += 1;
            }
            other => panic!("bad row kind {other}"),
        }
    }
    assert_eq!((np, nc, nu, nd, ns), (48, 4, 4, 4, 4));
}

#[test]
fn encode_format_and_roundtrip() {
    let salt = [7u8; 16];
    let stored = encode_pin_pbkdf2("1234", &salt);
    assert_eq!(
        stored,
        format!("pbkdf2:150000:07070707070707070707070707070707:{}", hex_encode(&pbkdf2_hmac_sha256(b"1234", &salt, 150_000)))
    );
    assert_eq!(verify_pin(Some(&stored), "1234", "dev"), VerifyPin::Pbkdf2);
    assert_eq!(verify_pin(Some(&stored), "1235", "dev"), VerifyPin::NoMatch);
    assert_eq!(verify_pin(Some(&stored), "1234 ", "dev"), VerifyPin::NoMatch);
}

#[test]
fn verify_format_gates() {
    let salt = [7u8; 16];
    let good = encode_pin_pbkdf2("1234", &salt);
    // wrong part count
    assert!(!verify_pin_pbkdf2("pbkdf2:150000:aa", "1234"));
    assert!(!verify_pin_pbkdf2("pbkdf2:150000:aa:bb:cc", "1234"));
    // iteration bounds: 50k-2M accepted; 1 and 49_999 and 2_000_001 rejected
    let h = |it: u32| format!("pbkdf2:{it}:{}:{}", hex_encode(&salt), hex_encode(&pbkdf2_hmac_sha256(b"1234", &salt, it)));
    assert!(!verify_pin_pbkdf2(&h(1), "1234"), "below min rejected");
    assert!(!verify_pin_pbkdf2(&h(49_999), "1234"), "below min rejected");
    assert!(!verify_pin_pbkdf2(&h(2_000_001), "1234"), "above max rejected");
    assert!(verify_pin_pbkdf2(&h(50_000), "1234"), "min accepted");
    // bad hex
    assert!(!verify_pin_pbkdf2("pbkdf2:150000:zz:bb", "1234"));
    assert!(!verify_pin_pbkdf2("pbkdf2:150000:070:bbbb", "1234")); // odd length
    // non-prefixed input never takes the PBKDF2 path
    assert_eq!(verify_pin(Some("1234"), "1234", "dev"), VerifyPin::LegacyPlaintext);
    let _ = good;
}

#[test]
fn legacy_paths_signal_reencode() {
    // plaintext
    assert_eq!(verify_pin(Some("4321"), "4321", "dev-1"), VerifyPin::LegacyPlaintext);
    assert!(VerifyPin::LegacyPlaintext.requires_reencode());
    // device hash
    let stored = hash_pin_legacy_device("4321", "dev-1");
    assert_eq!(verify_pin(Some(&stored), "4321", "dev-1"), VerifyPin::LegacyDevice);
    // wrong device id => device hash does NOT match, but salt hash still can
    assert_eq!(verify_pin(Some(&stored), "4321", "dev-2"), VerifyPin::NoMatch);
    let stored = hash_pin_legacy_salt("4321");
    assert_eq!(verify_pin(Some(&stored), "4321", "anything"), VerifyPin::LegacySalt);
    // null stored
    assert_eq!(verify_pin(None, "4321", "dev-1"), VerifyPin::NoMatch);
    // Source edge: legacy hash failure returns "" and an EMPTY candidate
    // matches it via the plaintext path (stored == candidate == "").
    // (pin setter removes blanks so a real store never holds "" - labeled.)
    assert_eq!(verify_pin(Some(""), "", "dev-1"), VerifyPin::LegacyPlaintext);
}

#[test]
fn effective_user_id_chain() {
    // non-blank userId wins verbatim
    assert_eq!(effective_user_id(Some("uid-1"), "dev-1"), "uid-1");
    // blank/null fall to the device-derived v3 UUID (JVM corpus u-rows)
    let derived = effective_user_id(None, "dev-1");
    assert_eq!(derived, name_uuid_from_bytes(b"dev-1"));
    assert_eq!(derived.len(), 36);
    assert_eq!(derived.as_bytes()[14], b'3', "version 3 nibble");
    assert_eq!(effective_user_id(Some("   "), "dev-1"), derived);
    // unicode device id digests UTF-8 bytes (JVM corpus row)
    assert_eq!(name_uuid_from_bytes("dévice-💡".as_bytes()), effective_user_id(None, "dévice-💡"));
}

#[derive(Default)]
struct PrefsFx {
    user_id: Option<String>,
    generation: i64,
    commits: Vec<i64>,
    writes: Vec<Option<String>>,
    commit_ok: bool,
}

impl AuthPrefsFx for PrefsFx {
    fn read_user_id(&mut self) -> Option<String> { self.user_id.clone() }
    fn read_session_generation(&mut self) -> i64 { self.generation }
    fn commit_session_generation(&mut self, g: i64) -> bool {
        self.commits.push(g);
        if self.commit_ok { self.generation = g; }
        self.commit_ok
    }
    fn write_user_id(&mut self, value: Option<&str>) {
        self.writes.push(value.map(str::to_string));
        self.user_id = value.map(str::to_string);
    }
}

#[test]
fn user_id_change_bumps_generation_before_write() {
    let mut fx = PrefsFx { user_id: Some("a".into()), generation: 41, commit_ok: true, ..Default::default() };
    set_user_id(&mut fx, Some("b")).unwrap();
    assert_eq!(fx.commits, vec![42], "generation bumped exactly once");
    assert_eq!(fx.writes, vec![Some("b".to_string())]);
    // Same id: no bump, no write is still issued (source writes regardless)
    let mut fx = PrefsFx { user_id: Some("a".into()), generation: 41, commit_ok: true, ..Default::default() };
    set_user_id(&mut fx, Some("a")).unwrap();
    assert!(fx.commits.is_empty(), "unchanged id never bumps");
    assert_eq!(fx.writes, vec![Some("a".to_string())]);
    // Clearing (None) counts as a change
    let mut fx = PrefsFx { user_id: Some("a".into()), generation: 41, commit_ok: true, ..Default::default() };
    set_user_id(&mut fx, None).unwrap();
    assert_eq!(fx.commits, vec![42]);
}

#[test]
fn generation_overflow_is_a_typed_fault_before_write() {
    // Math.addExact parity: ArithmeticException propagates, id NOT written.
    let mut fx = PrefsFx { user_id: Some("a".into()), generation: i64::MAX, commit_ok: true, ..Default::default() };
    assert_eq!(set_user_id(&mut fx, Some("b")), Err(AuthPrefsFault::GenerationOverflow));
    assert!(fx.writes.is_empty(), "no write after the overflow throw");
    // commit() false: source IGNORES the bool and still writes (labeled)
    let mut fx = PrefsFx { user_id: Some("a".into()), generation: 1, commit_ok: false, ..Default::default() };
    set_user_id(&mut fx, Some("b")).unwrap();
    assert_eq!(fx.writes, vec![Some("b".to_string())], "source writes even when commit fails");
}

//! JVM-executed storage semantics (StoreProbe.kt, 2026-11-02).
use cyclecare_core::secure_store::*;

fn prefs(entries: &[(&str, PrefsValue)]) -> Prefs {
    entries.iter().map(|(k, v)| (k.to_string(), v.clone())).collect()
}

#[test]
fn read_write_secure_string() {
    let m = prefs(&[("k", PrefsValue::Str("v".into()))]);
    assert_eq!(read_secure_string(true, &m, "k").as_deref(), Some("v"));
    assert_eq!(read_secure_string(true, &m, "x"), None);
    // Insecure reads EVERYTHING as absent (the conflation).
    assert_eq!(read_secure_string(false, &m, "k"), None);

    let mut w = prefs(&[("k", PrefsValue::Str("old".into()))]);
    write_secure_string(true, &mut w, "k", Some("new"));
    assert_eq!(w.get("k"), Some(&PrefsValue::Str("new".into())));
    write_secure_string(true, &mut w, "k", Some(""));
    assert_eq!(w.get("k"), None, "blank write removes");
    w.insert("k".into(), PrefsValue::Str("keep".into()));
    write_secure_string(true, &mut w, "k", Some("  "));
    assert_eq!(w.get("k"), None, "whitespace write removes");
    w.insert("k".into(), PrefsValue::Str("keep".into()));
    write_secure_string(false, &mut w, "k", Some("new"));
    assert_eq!(w.get("k"), None, "insecure write REMOVES");
    write_secure_string(true, &mut w, "k", None);
    assert_eq!(w.get("k"), None);
}

#[test]
fn boolean_gates() {
    let m = prefs(&[("b", PrefsValue::Bool(true))]);
    assert!(read_gate(true, &m, "b"));
    assert!(!read_gate(true, &prefs(&[]), "b"));
    assert!(!read_gate(false, &m, "b"), "insecure gate reads false");
    let mut g = prefs(&[]);
    write_gate(false, &mut g, "b", true);
    assert!(!g.contains_key("b"), "insecure gate write dropped");
    write_gate(true, &mut g, "b", true);
    assert!(g.contains_key("b"));
}

#[test]
fn fallback_sanitize_exact_14() {
    let mut plain = prefs(&[
        ("user_id", PrefsValue::Str("u".into())),
        ("theme", PrefsValue::Str("dark".into())),
        ("quick_unlock_pin", PrefsValue::Str("hash".into())),
    ]);
    fallback_sanitize(&mut plain);
    assert_eq!(plain.keys().cloned().collect::<Vec<_>>(), vec!["theme"]);
    assert_eq!(FALLBACK_SANITIZE_KEYS.len(), 14);
}

#[test]
fn migrate_legacy_typed_copy_if_absent() {
    let mut legacy = prefs(&[
        ("s", PrefsValue::Str("str".into())),
        ("b", PrefsValue::Bool(true)),
        ("i", PrefsValue::Int(7)),
        ("l", PrefsValue::Long(9)),
        ("f", PrefsValue::Float(1.5)),
        ("existing", PrefsValue::Str("legacy-val".into())),
    ]);
    let mut secure = prefs(&[("existing", PrefsValue::Str("secure-val".into()))]);
    migrate_legacy(&mut legacy, &mut secure);
    assert_eq!(secure.get("existing"), Some(&PrefsValue::Str("secure-val".into())));
    assert_eq!(secure.get("i"), Some(&PrefsValue::Int(7)));
    assert_eq!(secure.get("l"), Some(&PrefsValue::Long(9)));
    assert_eq!(secure.get("f"), Some(&PrefsValue::Float(1.5)));
    assert!(legacy.is_empty());
    // Empty legacy is a no-op.
    let mut empty = prefs(&[]);
    let before = secure.clone();
    migrate_legacy(&mut empty, &mut secure);
    assert_eq!(secure, before);
}

#[test]
fn proposed_hardening_tri_state_not_parity() {
    let m = prefs(&[("quick_unlock_pin", PrefsValue::Str("hash".into()))]);
    assert_eq!(credential_presence(false, &m, "quick_unlock_pin"), CredentialPresence::StorageUnavailable);
    assert_eq!(credential_presence(true, &m, "quick_unlock_pin"), CredentialPresence::Present);
    assert_eq!(credential_presence(true, &prefs(&[]), "quick_unlock_pin"), CredentialPresence::AbsentConfigured);
    let blank = prefs(&[("quick_unlock_pin", PrefsValue::Str("  ".into()))]);
    assert_eq!(credential_presence(true, &blank, "quick_unlock_pin"), CredentialPresence::AbsentConfigured);
    assert!(!unlock_via_biometric_bound(false));
    assert!(unlock_via_biometric_bound(true));
}

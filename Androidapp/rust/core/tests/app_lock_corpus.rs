//! JVM-executed truth tables (LockProbe.kt, 2026-11-02), verbatim expressions.
use cyclecare_core::app_lock::*;

#[test]
fn is_pin_set_table() {
    for en in [false, true] {
        assert!(!is_pin_set(en, None));
        assert!(!is_pin_set(en, Some("")));
        assert!(!is_pin_set(en, Some("  ")));
        assert_eq!(is_pin_set(en, Some("pbkdf2:...")), en);
    }
}

#[test]
fn startup_lock_table() {
    for init in [false, true] {
        for pin_set_case in [None, Some(""), Some("x")] {
            for fp in [false, true] {
                let got = is_locked_at_startup(init, true, pin_set_case, fp);
                let pin_set = pin_set_case == Some("x");
                assert_eq!(got, init && (pin_set || fp));
            }
        }
    }
    // JVM-locked rows verbatim:
    assert!(!is_locked_at_startup(true, false, None, false));
    assert!(is_locked_at_startup(true, false, None, true));
    assert!(is_locked_at_startup(true, true, Some("stored"), false));
    assert!(!is_locked_at_startup(true, true, Some("  "), false)); // blank pin does not lock
}

#[test]
fn post_save_variant_divergence() {
    // No blank check: pin_enabled=true with blank stored pin LOCKS here but
    // NOT at startup. JVM-locked divergence row.
    assert!(is_locked_after_quick_unlock_save(true, false));
    assert!(!is_locked_after_quick_unlock_save(false, false));
    assert!(is_locked_after_quick_unlock_save(false, true));
    assert!(is_locked_after_quick_unlock_save(true, true));
    assert_ne!(
        is_locked_at_startup(true, true, None, false),
        is_locked_after_quick_unlock_save(true, false)
    );
}

#[test]
fn unlock_app_table_fail_open_locked() {
    // verify=false: unlocks when stored is null/blank/whitespace (fail-open).
    assert!(unlock_app(false, None));
    assert!(unlock_app(false, Some("")));
    assert!(unlock_app(false, Some("  ")));
    assert!(!unlock_app(false, Some("stored")));
    // verify=true always unlocks.
    for p in [None, Some(""), Some("  "), Some("stored")] {
        assert!(unlock_app(true, p));
    }
    assert!(unlock_via_biometric());
}

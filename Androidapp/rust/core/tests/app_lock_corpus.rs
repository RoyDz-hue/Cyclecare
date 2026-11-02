//! JVM-executed truth tables (LockProbe.kt + LockProbe2.kt, 2026-11-02/03),
//! verbatim expressions from vm.kt with corrected line attribution.
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
fn startup_lock_table_pref_only() {
    for init in [false, true] {
        for pin_set_case in [None, Some(""), Some("x")] {
            for fp in [false, true] {
                let got = is_locked_at_startup(init, true, pin_set_case, fp);
                let pin_set = pin_set_case == Some("x");
                assert_eq!(got, init && (pin_set || fp));
            }
        }
    }
    assert!(!is_locked_at_startup(true, false, None, false));
    assert!(is_locked_at_startup(true, false, None, true));
    assert!(is_locked_at_startup(true, true, Some("stored"), false));
    assert!(!is_locked_at_startup(true, true, Some("  "), false));
}

#[test]
fn ui_pin_enabled_merge() {
    assert!(!ui_pin_enabled(false, false));
    assert!(ui_pin_enabled(false, true));
    assert!(ui_pin_enabled(true, false));
    assert!(ui_pin_enabled(true, true));
}

#[test]
fn lock_app_three_way_predicate() {
    // JVM-locked: any single true locks (including settings-only).
    assert!(!lock_app(false, false, false));
    assert!(lock_app(false, false, true));
    assert!(lock_app(false, true, false));
    assert!(lock_app(true, false, false));
    assert!(lock_app(true, true, true));
    // Divergence: settings-only locks via lockApp but NOT at startup.
    assert!(lock_app(ui_pin_enabled(false, true), false, true));
    assert!(!is_locked_at_startup(true, false, None, false));
}

#[test]
fn start_local_only_no_blank_check() {
    assert!(is_locked_after_start_local_only(true, false));
    assert!(!is_locked_after_start_local_only(false, false));
    assert!(is_locked_after_start_local_only(false, true));
    assert!(is_locked_after_start_local_only(true, true));
    // Divergence vs startup when pin_enabled=true, stored PIN absent:
    assert_ne!(
        is_locked_at_startup(true, true, None, false),
        is_locked_after_start_local_only(true, false)
    );
}

#[test]
fn unlock_app_table_fail_open_locked() {
    assert!(unlock_app(false, None));
    assert!(unlock_app(false, Some("")));
    assert!(unlock_app(false, Some("  ")));
    assert!(!unlock_app(false, Some("stored")));
    for p in [None, Some(""), Some("  "), Some("stored")] {
        assert!(unlock_app(true, p));
    }
    assert!(unlock_via_biometric());
}

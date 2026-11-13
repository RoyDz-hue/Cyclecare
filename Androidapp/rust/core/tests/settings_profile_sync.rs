//! Corpus lock for sync_profile_on_settings: both Kotlin call sites
//! (CycleRepository.updateSettingsBound :571-577, RustBoundRoomHost
//! .updateSettings :305) evaluate the SAME expression, so the corpus is
//! the decision truth table, not captured output.
use cyclecare_core::settings_core::sync_profile_on_settings;

#[test]
fn no_stored_profile_means_no_profile_write() {
    // read == null -> changed == null -> only the settings row is written.
    assert_eq!(sync_profile_on_settings(None, 30, 28), None);
    assert_eq!(sync_profile_on_settings(None, 28, 28), None);
}

#[test]
fn changed_average_recomputes_option_from_new_value() {
    assert_eq!(
        sync_profile_on_settings(Some("28 days"), 30, 28).as_deref(),
        Some("30 days")
    );
    // A non-standard stored option is REPLACED when the average changed.
    assert_eq!(
        sync_profile_on_settings(Some("I don't know"), 30, 28).as_deref(),
        Some("30 days")
    );
    // Raw Int interpolation, no clamping at this layer.
    assert_eq!(
        sync_profile_on_settings(Some("28 days"), 99, 28).as_deref(),
        Some("99 days")
    );
}

#[test]
fn unchanged_average_preserves_stored_option_verbatim() {
    assert_eq!(
        sync_profile_on_settings(Some("28 days"), 28, 28).as_deref(),
        Some("28 days")
    );
    // Even a value that LOOKS stale is preserved when the average is
    // unchanged - the Kotlin guard keys on inequality, not consistency.
    assert_eq!(
        sync_profile_on_settings(Some("21 days"), 28, 28).as_deref(),
        Some("21 days")
    );
    assert_eq!(
        sync_profile_on_settings(Some("I don't know"), 28, 28).as_deref(),
        Some("I don't know")
    );
    assert_eq!(
        sync_profile_on_settings(Some(""), 28, 28).as_deref(),
        Some("")
    );
}

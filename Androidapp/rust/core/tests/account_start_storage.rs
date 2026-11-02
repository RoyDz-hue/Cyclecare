//! Executable account-start slice over REAL Rust-owned storage (rusqlite,
//! Room-mirrored DDL): reopen app -> same owner namespace -> onboarding and
//! settings preserved -> cold-start route resolves Home. This is the storage
//! leg of the account-start/session/onboarding/wallet vertical slice; the
//! session/keystore legs remain host-side (documented in lifecycle.rs), and
//! SQLCipher cross-read of a production DB is still an OPEN proof.

use cyclecare_core::account_start::LocalDataState;
use cyclecare_core::owner_data_gate::namespace_for;
use cyclecare_core::startup_route::{route, ShellRoute, StartupInput};
use cyclecare_core::storage_db::StorageDb;
use cyclecare_core::sync_codec::{CycleSettings, OnboardingProfile};
use cyclecare_core::track_log::CycleDayLog;

fn temp_db_path(tag: &str) -> std::path::PathBuf {
    let dir = std::env::temp_dir().join(format!("cc-storage-{}-{}", tag, std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn sample_log(date_string: &str) -> CycleDayLog {
    CycleDayLog {
        date_string: date_string.to_string(),
        flow_level: "MEDIUM".to_string(),
        energy_level: "LOW".to_string(),
        mood_level: String::new(),
        symptoms_csv: "fatigue".to_string(),
        vaginal_health_csv: String::new(),
        urinary_health_csv: String::new(),
        cervical_mucus: String::new(),
        ovulation_test: "NONE".to_string(),
        bbt_celsius: 0.0,
        sleep_hours: 6.5,
        sleep_quality: "POOR".to_string(),
        water_glasses: 4,
        notes: "tired".to_string(),
        pain_intensity: 2,
    }
}

#[test]
fn reopen_same_owner_preserves_onboarding_settings_and_logs() {
    let owner = "roy-owner-1";
    let dir = temp_db_path("reopen");
    let path = dir.join(namespace_for(owner));

    // First run: complete onboarding, save settings, log two days.
    {
        let db = StorageDb::open_file(&path).unwrap();
        let settings = CycleSettings {
            is_onboarding_completed: true,
            average_cycle_length: 31,
            period_duration: 6,
            ..Default::default()
        };
        db.save_settings(&settings).unwrap();
        let profile = OnboardingProfile {
            is_completed: true,
            completed_at_timestamp: 1_762_000_000,
            account_mode: "ACCOUNT".to_string(),
            ..Default::default()
        };
        db.insert_or_update_profile(&profile).unwrap();
        db.insert_or_update_log(&sample_log("2026-11-01")).unwrap();
        db.insert_or_update_log(&sample_log("2026-11-02")).unwrap();
    } // connection dropped: app "closed"

    // Reopen: same owner -> same namespace -> everything preserved.
    {
        let db = StorageDb::open_file(&path).unwrap();
        let settings = db.get_settings().unwrap().expect("settings preserved");
        assert!(settings.is_onboarding_completed);
        assert_eq!(settings.average_cycle_length, 31);
        assert_eq!(settings.period_duration, 6);
        let profile = db.get_profile().unwrap().expect("profile preserved");
        assert!(profile.is_completed);
        assert_eq!(profile.account_mode, "ACCOUNT");
        let logs = db.get_all_logs().unwrap();
        assert_eq!(logs.len(), 2);
        assert_eq!(logs[0].date_string, "2026-11-02"); // DESC order

        // Cold-start route with the storage-backed verdicts: Home.
        let decision = route(&StartupInput {
            has_completed_initial_auth: true,
            is_checking_cloud_profile: false,
            cloud_profile_check_failed: false,
            is_app_locked: false,
            local_valid: settings.is_onboarding_completed && profile.is_completed,
            local_state: LocalDataState::Clean,
            remote_profile: None,
        });
        assert_eq!(decision, ShellRoute::Home);
    }

    // A different owner namespaces to a DIFFERENT file: no cross-owner reads.
    let other = dir.join(namespace_for("roy-owner-2"));
    assert_ne!(path, other);
    {
        let db = StorageDb::open_file(&other).unwrap();
        assert!(db.get_settings().unwrap().is_none());
        assert!(db.get_profile().unwrap().is_none());
        assert!(db.get_all_logs().unwrap().is_empty());
    }

    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn empty_owner_db_is_not_treated_as_completed() {
    let dir = temp_db_path("empty");
    let path = dir.join(namespace_for("fresh-owner"));
    let db = StorageDb::open_file(&path).unwrap();
    // Fresh namespace: onboarding decision must not claim completion.
    let decision = route(&StartupInput {
        has_completed_initial_auth: true,
        is_checking_cloud_profile: false,
        cloud_profile_check_failed: false,
        is_app_locked: false,
        local_valid: db.get_settings().unwrap().map(|s| s.is_onboarding_completed).unwrap_or(false),
        local_state: LocalDataState::Clean,
        remote_profile: Some(false),
    });
    assert_eq!(decision, ShellRoute::Onboarding);
    let _ = std::fs::remove_dir_all(&dir);
}

/// Track surface round-trip: TrackDayState -> candidate_log -> scratch-SQLite
/// -> reopen -> reset_from_log restores every holder; a second save for the
/// same date REPLACES (Room OnConflictStrategy.REPLACE), never duplicates.
#[test]
fn track_candidate_round_trips_through_scratch_storage() {
    use cyclecare_core::storage_db::StorageDb;
    use cyclecare_core::track_vm::TrackDayState;

    let dir = std::env::temp_dir().join(format!("cc_track_rt_{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let mut st = TrackDayState::default();
    st.selected_energy = "NORMAL".into();
    st.selected_mood = "GOOD".into();
    st.selected_symptoms.toggle("cramps");
    st.selected_symptoms.toggle("acne");
    st.increment_water();
    st.set_sleep_hours(6.5);
    st.notes_text = "first day".into();
    let candidate = st.candidate_log("2026-11-02");
    assert!(candidate.has_recorded_observations());

    {
        let db = StorageDb::open_file(&dir.join("owner.db")).unwrap();
        db.insert_or_update_log(&candidate).unwrap();
    }
    // Reopen: the stored row drives reset_from_log back to the same state.
    {
        let db = StorageDb::open_file(&dir.join("owner.db")).unwrap();
        let stored = db.get_log_by_date("2026-11-02").unwrap().expect("log persisted");
        let restored = TrackDayState::reset_from_log(Some(&stored));
        assert_eq!(restored.selected_energy, "NORMAL");
        assert_eq!(restored.selected_mood, "GOOD");
        assert_eq!(restored.selected_symptoms.join_csv(), "cramps,acne");
        assert!(restored.water_recorded && restored.water_count == 1);
        assert!(restored.sleep_hours_recorded && restored.sleep_hours == 6.5);
        assert_eq!(restored.notes_text, "first day");

        // Second save same date_string: REPLACE, still exactly one row.
        let mut st2 = restored;
        st2.notes_text = "edited".into();
        db.insert_or_update_log(&st2.candidate_log("2026-11-02")).unwrap();
        assert_eq!(db.get_all_logs().unwrap().len(), 1);
        assert_eq!(db.get_log_by_date("2026-11-02").unwrap().unwrap().notes, "edited");
    }
    let _ = std::fs::remove_dir_all(&dir);
}

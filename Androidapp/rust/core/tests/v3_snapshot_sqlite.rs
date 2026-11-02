//! REAL-SQLite proofs for the journalExplicitSnapshot store
//! (SupabaseSyncService.kt:45-57): one EXCLUSIVE transaction, journal order
//! (all logs DAO-ordered, then settings, then profile), removeReviewedPending
//! semantics, revision sequence/hash/server carry-over, and whole-journal
//! rollback on ConsentChanged or a DAO fault. Synthetic scratch data only;
//! host-only feature `sqlite`.
#![cfg(feature = "sqlite")]

use cyclecare_core::sync_codec::{self, CycleDayLog, CycleSettings, OnboardingProfile};
use cyclecare_core::v3_consent::{journal_explicit_snapshot, V3ConsentError, V3SnapshotStore};
use cyclecare_core::v3_dao_sqlite::SqliteV3Store;

const OWNER: &str = "owner-uid";
const SESSION: i64 = 7;
const LOCAL: i64 = 3;
const SERVER: i64 = 11;

fn log(date: &str) -> CycleDayLog {
    CycleDayLog {
        date: date.into(), flow: "LIGHT".into(), energy: "HIGH".into(), mood: "CALM".into(),
        symptoms: "cramps".into(), vaginal_health: "".into(), urinary_health: "".into(),
        cervical_mucus: "CREAMY".into(), ovulation_test: "NEGATIVE".into(),
        bbt_celsius: 36.5, sleep_hours: 7.5, sleep_quality: "GOOD".into(),
        water_glasses: 8, notes: "n".into(), pain_intensity: 2,
    }
}

fn settings() -> CycleSettings {
    CycleSettings {
        id: 1, is_onboarding_completed: true, selected_goals_csv: "track".into(),
        last_period_start_date: "2026-10-01".into(), average_cycle_length: 28,
        period_duration: 5, luteal_phase_days: 14, is_app_lock_enabled: false,
        lock_timeout_minutes: 5, is_discreet_notification_mode: false,
        is_pregnancy_mode: false, pregnancy_due_date: "".into(), remind_period: true,
        remind_fertile: false, remind_daily_log: true,
    }
}

fn profile() -> OnboardingProfile {
    OnboardingProfile {
        id: 1, is_completed: true, completed_at_timestamp: 1_700_000_000_000,
        is_age_verified: true, is_terms_agreed: true, account_mode: "LOCAL".into(),
        selected_goals_csv: "track".into(), last_period_date_string: "2026-10-01".into(),
        last_period_end_date_string: "2026-10-05".into(), approximate_period_choice: "EXACT".into(),
        cycle_length_option: "DAYS".into(), cycle_length_days: 28, period_duration_days: 5,
        remind_fertile_window: false, typical_symptoms_csv: "cramps".into(),
        typical_energy: "HIGH".into(), typical_sleep_quality: "GOOD".into(),
        has_completed_deep_baseline: false,
    }
}

fn seed_domain(store: &SqliteV3Store) {
    // Seed through the same verbatim DAO SQL the pull path uses.
    store.conn.execute_batch("DELETE FROM cycle_day_logs; DELETE FROM cycle_settings; DELETE FROM onboarding_profiles;").unwrap();
    for d in ["2026-10-20", "2026-10-21", "2026-10-22"] {
        let l = log(d);
        store.conn.execute(
            "INSERT OR REPLACE INTO cycle_day_logs (dateString, flowLevel, energyLevel, moodLevel, symptomsCsv,
             vaginalHealthCsv, urinaryHealthCsv, cervicalMucus, ovulationTest, bbtCelsius, sleepHours,
             sleepQuality, waterGlasses, notes, painIntensity)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15)",
            rusqlite::params![l.date, l.flow, l.energy, l.mood, l.symptoms, l.vaginal_health,
                l.urinary_health, l.cervical_mucus, l.ovulation_test, l.bbt_celsius, l.sleep_hours,
                l.sleep_quality, l.water_glasses, l.notes, l.pain_intensity],
        ).unwrap();
    }
    let s = settings();
    store.conn.execute(
        "INSERT OR REPLACE INTO cycle_settings (id, isOnboardingCompleted, selectedGoalsCsv, lastPeriodStartDate,
         averageCycleLength, periodDuration, lutealPhaseDays, isAppLockEnabled, lockTimeoutMinutes,
         isDiscreetNotificationMode, isPregnancyMode, pregnancyDueDate, remindPeriod, remindFertile, remindDailyLog)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15)",
        rusqlite::params![s.id, s.is_onboarding_completed as i64, s.selected_goals_csv, s.last_period_start_date,
            s.average_cycle_length, s.period_duration, s.luteal_phase_days, s.is_app_lock_enabled as i64,
            s.lock_timeout_minutes, s.is_discreet_notification_mode as i64, s.is_pregnancy_mode as i64,
            s.pregnancy_due_date, s.remind_period as i64, s.remind_fertile as i64, s.remind_daily_log as i64],
    ).unwrap();
    let p = profile();
    store.conn.execute(
        "INSERT OR REPLACE INTO onboarding_profiles (id, isCompleted, completedAtTimestamp, isAgeVerified,
         isTermsAgreed, accountMode, selectedGoalsCsv, lastPeriodDateString, lastPeriodEndDateString,
         approximatePeriodChoice, cycleLengthOption, cycleLengthDays, periodDurationDays,
         remindFertileWindow, typicalSymptomsCsv, typicalEnergy, typicalSleepQuality, hasCompletedDeepBaseline)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17,?18)",
        rusqlite::params![p.id, p.is_completed as i64, p.completed_at_timestamp, p.is_age_verified as i64,
            p.is_terms_agreed as i64, p.account_mode, p.selected_goals_csv, p.last_period_date_string,
            p.last_period_end_date_string, p.approximate_period_choice, p.cycle_length_option,
            p.cycle_length_days, p.period_duration_days, p.remind_fertile_window as i64,
            p.typical_symptoms_csv, p.typical_energy, p.typical_sleep_quality, p.has_completed_deep_baseline as i64],
    ).unwrap();
}

fn count(store: &SqliteV3Store, table: &str) -> i64 {
    store.conn.query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |r| r.get(0)).unwrap()
}

#[test]
fn snapshot_journals_logs_settings_profile_in_source_order() {
    let mut store = SqliteV3Store::open_in_memory().unwrap();
    seed_domain(&store);
    let mut uuids = (1..=5).map(|i| format!("uuid-{i}")).collect::<Vec<_>>().into_iter();
    journal_explicit_snapshot(
        OWNER, SESSION, LOCAL, SERVER, &mut store,
        &mut || true, &mut || uuids.next().unwrap(), &mut || 1_000,
    ).unwrap();
    // 3 logs + settings + profile = 5 revisions and 5 outbox UPSERTs.
    assert_eq!(count(&store, "health_sync_revisions"), 5);
    assert_eq!(count(&store, "health_sync_outbox"), 5);
    // Journal order = DAO order (dateString DESC), then settings, then profile:
    // outbox insertion order is rowid order in SQLite.
    let ids: Vec<String> = {
        let mut st = store.conn.prepare("SELECT recordId, revision, baseRevision, kind, consentGeneration, attempts, nextAttemptAt FROM health_sync_outbox ORDER BY rowid").unwrap();
        st.query_map([], |r| Ok((
            r.get::<_, String>(0)?, r.get::<_, i64>(1)?, r.get::<_, i64>(2)?,
            r.get::<_, String>(3)?, r.get::<_, i64>(4)?, r.get::<_, i64>(5)?, r.get::<_, i64>(6)?,
        ))).unwrap().map(|r| {
            let (id, rev, base, kind, gen, att, naa) = r.unwrap();
            assert_eq!(rev, 1, "first journal sequence for {id}");
            assert_eq!(base, 0, "no prior server revision for {id}");
            assert_eq!(kind, "UPSERT");
            assert_eq!(gen, LOCAL);
            assert_eq!((att, naa), (0, 0), "entity defaults");
            id
        }).collect()
    };
    assert_eq!(ids, vec!["2026-10-22", "2026-10-21", "2026-10-20", "settings", "profile"]);
    // Revision payloadHash matches the codec payload for each record.
    let l = log("2026-10-22");
    let expect = sync_codec::hash(Some(&sync_codec::log_payload(&l)));
    let got: String = store.conn.query_row(
        "SELECT payloadHash FROM health_sync_revisions WHERE ownerId = ?1 AND recordId = '2026-10-22'",
        rusqlite::params![OWNER], |r| r.get(0)).unwrap();
    assert_eq!(got, expect);
    // Outbox payloadJson is the codec payload verbatim.
    let pj: String = store.conn.query_row(
        "SELECT payloadJson FROM health_sync_outbox WHERE ownerId = ?1 AND recordId = 'settings'",
        rusqlite::params![OWNER], |r| r.get(0)).unwrap();
    assert_eq!(pj, sync_codec::settings_payload(&settings()));
}

#[test]
fn snapshot_continues_prior_sequence_and_carries_server_revision() {
    let mut store = SqliteV3Store::open_in_memory().unwrap();
    seed_domain(&store);
    // Prior revision for one log: local 4, server 9.
    store.conn.execute(
        "INSERT OR REPLACE INTO health_sync_revisions
         (ownerId, recordId, revision, payloadHash, tombstone, updatedAt, serverRevision)
         VALUES (?1, '2026-10-21', 4, 'old-hash', 0, 500, 9)",
        rusqlite::params![OWNER]).unwrap();
    let mut n = 0i64;
    journal_explicit_snapshot(
        OWNER, SESSION, LOCAL, SERVER, &mut store,
        &mut || true, &mut || { n += 1; format!("uuid-{n}") }, &mut || 1_000,
    ).unwrap();
    let (rev, base): (i64, i64) = store.conn.query_row(
        "SELECT r.revision, o.baseRevision FROM health_sync_revisions r
         JOIN health_sync_outbox o ON o.recordId = r.recordId AND o.ownerId = r.ownerId
         WHERE r.recordId = '2026-10-21' AND r.ownerId = ?1",
        rusqlite::params![OWNER], |r| Ok((r.get(0)?, r.get(1)?)) ).unwrap();
    assert_eq!(rev, 5, "Math.addExact(prior 4, 1)");
    assert_eq!(base, 9, "prior serverRevision carried into outbox base");
}

#[test]
fn snapshot_remove_reviewed_pending_keeps_unreviewed() {
    let mut store = SqliteV3Store::open_in_memory().unwrap();
    seed_domain(&store);
    // Prior local revision 4 for one record; pending rows at rev 3 (reviewed)
    // and rev 5 (unreviewed, HealthSyncLocal.kt:91-92 keeps revision > 4).
    store.conn.execute(
        "INSERT OR REPLACE INTO health_sync_revisions
         (ownerId, recordId, revision, payloadHash, tombstone, updatedAt, serverRevision)
         VALUES (?1, '2026-10-21', 4, 'h', 0, 500, 0)",
        rusqlite::params![OWNER]).unwrap();
    for (op, rev) in [("old-op", 3), ("new-op", 5)] {
        store.conn.execute(
            "INSERT OR REPLACE INTO health_sync_outbox
             (ownerId, operationId, recordId, kind, baseRevision, revision, payloadJson,
              payloadHash, consentGeneration, attempts, nextAttemptAt)
             VALUES (?1, ?2, '2026-10-21', 'UPSERT', 0, ?3, NULL, 'h', 1, 0, 0)",
            rusqlite::params![OWNER, op, rev]).unwrap();
    }
    let mut n = 0i64;
    journal_explicit_snapshot(
        OWNER, SESSION, LOCAL, SERVER, &mut store,
        &mut || true, &mut || { n += 1; format!("uuid-{n}") }, &mut || 1_000,
    ).unwrap();
    let remaining: i64 = store.conn.query_row(
        "SELECT COUNT(*) FROM health_sync_outbox WHERE recordId = '2026-10-21' AND operationId = 'old-op'",
        [], |r| r.get(0)).unwrap();
    assert_eq!(remaining, 0, "reviewed pending removed (revision <= prior local)");
    let kept: i64 = store.conn.query_row(
        "SELECT COUNT(*) FROM health_sync_outbox WHERE recordId = '2026-10-21' AND operationId = 'new-op'",
        [], |r| r.get(0)).unwrap();
    assert_eq!(kept, 1, "unreviewed pending kept (revision > prior local)");
}

#[test]
fn consent_changed_rolls_back_whole_journal() {
    let mut store = SqliteV3Store::open_in_memory().unwrap();
    seed_domain(&store);
    let err = journal_explicit_snapshot(
        OWNER, SESSION, LOCAL, SERVER, &mut store,
        &mut || false, &mut || "u".to_string(), &mut || 1_000,
    ).unwrap_err();
    assert!(matches!(err, V3ConsentError::ConsentChanged));
    assert_eq!(count(&store, "health_sync_revisions"), 0);
    assert_eq!(count(&store, "health_sync_outbox"), 0);
    assert_eq!(count(&store, "cycle_day_logs"), 3, "domain rows untouched");
}

#[test]
fn dao_fault_mid_journal_rolls_back_everything() {
    let mut store = SqliteV3Store::open_in_memory().unwrap();
    seed_domain(&store);
    // Abort the putRevision for 'settings': by then all 3 log revisions are
    // already journaled inside the same transaction.
    store.conn.execute_batch(
        "CREATE TRIGGER fault BEFORE INSERT ON health_sync_revisions
         WHEN NEW.recordId = 'settings'
         BEGIN SELECT RAISE(ABORT,'injected_fault'); END;",
    ).unwrap();
    let err = journal_explicit_snapshot(
        OWNER, SESSION, LOCAL, SERVER, &mut store,
        &mut || true, &mut || "u".to_string(), &mut || 1_000,
    ).unwrap_err();
    assert!(matches!(err, V3ConsentError::Db), "typed Db, not a panic or silent drop");
    assert_eq!(count(&store, "health_sync_revisions"), 0, "log revisions rolled back too");
    assert_eq!(count(&store, "health_sync_outbox"), 0, "outbox rows rolled back too");
    assert_eq!(count(&store, "cycle_day_logs"), 3, "domain rows untouched");
    store.conn.execute_batch("DROP TRIGGER fault").unwrap();
}

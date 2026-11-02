//! Executable Rust storage layer over the Room DDL mirror
//! (fixtures/schema/room-ddl-18dd603.sql, KSP-generated evidence of exactly
//! what Room executes at open). Implements the DAO contracts of
//! data/local/CycleDayLogDao.kt + OnboardingProfileDao.kt with the verbatim
//! SQL: REPLACE conflict strategy, dateString DESC ordering, LIMIT 1 lookups,
//! id = 1 singleton rows.
//!
//! Honest boundary — SCRATCH vs PRODUCTION RECORDS: every database this
//! layer opens is a PLAIN bundled-SQLite scratch file (rusqlite, no codec).
//! The production Android databases are SQLCipher-encrypted with
//! Keystore-derived keys. Nothing here reads, converts or migrates a live
//! encrypted record, and there is NO plaintext fallback: until encrypted
//! compatibility plus owner/data preservation are proven end-to-end, this
//! layer is evidence for the schema/DAO contract only, never a migration
//! path. Failed opens preserve the file byte-for-byte and never delete
//! (keystore-invalidated DBs are unrecoverable by design). PRAGMA
//! user_version is deliberately left at 0: Room's version=9 and
//! room_master_table identity hash are device-side bookkeeping that scratch
//! files must not fake (see tests/storage_hardening.rs). Flow-based
//! observation (Kotlin Flow) has no port; the suspend CRUD contracts are
//! what this layer owns.

use rusqlite::{params, Connection, OptionalExtension};

use crate::sync_codec::{CycleDayLog, CycleSettings, OnboardingProfile};

const DDL: &str = include_str!("../fixtures/schema/room-ddl-18dd603.sql");

pub struct StorageDb {
    pub conn: Connection,
}

fn bool_to_int(b: bool) -> i32 {
    if b { 1 } else { 0 }
}
fn int_to_bool(v: i32) -> bool {
    v != 0
}

impl StorageDb {
    /// Open an in-memory database with the mirrored schema applied.
    pub fn open_memory() -> rusqlite::Result<Self> {
        let conn = Connection::open_in_memory()?;
        conn.execute_batch(DDL)?;
        Ok(StorageDb { conn })
    }

    /// Open (or create) a file database with the mirrored schema applied.
    /// `namespace_for(owner)` (owner_data_gate) supplies the per-owner name.
    pub fn open_file(path: &std::path::Path) -> rusqlite::Result<Self> {
        let conn = Connection::open(path)?;
        conn.execute_batch(DDL)?;
        Ok(StorageDb { conn })
    }

    // ---------- CycleDayLogDao ----------

    /// SELECT * FROM cycle_day_logs ORDER BY dateString DESC
    pub fn get_all_logs(&self) -> rusqlite::Result<Vec<CycleDayLog>> {
        let mut stmt = self
            .conn
            .prepare("SELECT * FROM cycle_day_logs ORDER BY dateString DESC")?;
        let rows = stmt.query_map([], |row| {
            Ok(CycleDayLog {
                date: row.get("dateString")?,
                flow: row.get("flowLevel")?,
                energy: row.get("energyLevel")?,
                mood: row.get("moodLevel")?,
                symptoms: row.get("symptomsCsv")?,
                vaginal_health: row.get("vaginalHealthCsv")?,
                urinary_health: row.get("urinaryHealthCsv")?,
                cervical_mucus: row.get("cervicalMucus")?,
                ovulation_test: row.get("ovulationTest")?,
                bbt_celsius: row.get("bbtCelsius")?,
                sleep_hours: row.get("sleepHours")?,
                sleep_quality: row.get("sleepQuality")?,
                water_glasses: row.get("waterGlasses")?,
                notes: row.get("notes")?,
                pain_intensity: row.get("painIntensity")?,
            })
        })?;
        rows.collect()
    }

    /// SELECT * FROM cycle_day_logs WHERE dateString = :date LIMIT 1
    pub fn get_log_by_date(&self, date: &str) -> rusqlite::Result<Option<CycleDayLog>> {
        self.conn
            .query_row(
                "SELECT * FROM cycle_day_logs WHERE dateString = ?1 LIMIT 1",
                params![date],
                |row| {
                    Ok(CycleDayLog {
                        date: row.get("dateString")?,
                        flow: row.get("flowLevel")?,
                        energy: row.get("energyLevel")?,
                        mood: row.get("moodLevel")?,
                        symptoms: row.get("symptomsCsv")?,
                        vaginal_health: row.get("vaginalHealthCsv")?,
                        urinary_health: row.get("urinaryHealthCsv")?,
                        cervical_mucus: row.get("cervicalMucus")?,
                        ovulation_test: row.get("ovulationTest")?,
                        bbt_celsius: row.get("bbtCelsius")?,
                        sleep_hours: row.get("sleepHours")?,
                        sleep_quality: row.get("sleepQuality")?,
                        water_glasses: row.get("waterGlasses")?,
                        notes: row.get("notes")?,
                        pain_intensity: row.get("painIntensity")?,
                    })
                },
            )
            .optional()
    }

    /// @Insert(onConflict = REPLACE)
    pub fn insert_or_update_log(&self, log: &CycleDayLog) -> rusqlite::Result<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO cycle_day_logs (dateString, flowLevel, energyLevel, moodLevel, symptomsCsv, vaginalHealthCsv, urinaryHealthCsv, cervicalMucus, ovulationTest, bbtCelsius, sleepHours, sleepQuality, waterGlasses, notes, painIntensity) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15)",
            params![
                log.date, log.flow, log.energy, log.mood, log.symptoms, log.vaginal_health,
                log.urinary_health, log.cervical_mucus, log.ovulation_test, log.bbt_celsius,
                log.sleep_hours, log.sleep_quality, log.water_glasses, log.notes, log.pain_intensity
            ],
        )?;
        Ok(())
    }

    /// DELETE FROM cycle_day_logs WHERE dateString = :date
    pub fn delete_log(&self, date: &str) -> rusqlite::Result<()> {
        self.conn
            .execute("DELETE FROM cycle_day_logs WHERE dateString = ?1", params![date])?;
        Ok(())
    }

    /// DELETE FROM cycle_day_logs
    pub fn delete_all_logs(&self) -> rusqlite::Result<()> {
        self.conn.execute("DELETE FROM cycle_day_logs", [])?;
        Ok(())
    }

    // ---------- settings (same DAO file) ----------

    /// SELECT * FROM cycle_settings WHERE id = 1 LIMIT 1
    pub fn get_settings(&self) -> rusqlite::Result<Option<CycleSettings>> {
        self.conn
            .query_row("SELECT * FROM cycle_settings WHERE id = 1 LIMIT 1", [], |row| {
                Ok(CycleSettings {
                    id: row.get("id")?,
                    is_onboarding_completed: int_to_bool(row.get("isOnboardingCompleted")?),
                    selected_goals_csv: row.get("selectedGoalsCsv")?,
                    last_period_start_date: row.get("lastPeriodStartDate")?,
                    average_cycle_length: row.get("averageCycleLength")?,
                    period_duration: row.get("periodDuration")?,
                    luteal_phase_days: row.get("lutealPhaseDays")?,
                    is_app_lock_enabled: int_to_bool(row.get("isAppLockEnabled")?),
                    lock_timeout_minutes: row.get("lockTimeoutMinutes")?,
                    is_discreet_notification_mode: int_to_bool(row.get("isDiscreetNotificationMode")?),
                    is_pregnancy_mode: int_to_bool(row.get("isPregnancyMode")?),
                    pregnancy_due_date: row.get("pregnancyDueDate")?,
                    remind_period: int_to_bool(row.get("remindPeriod")?),
                    remind_fertile: int_to_bool(row.get("remindFertile")?),
                    remind_daily_log: int_to_bool(row.get("remindDailyLog")?),
                })
            })
            .optional()
    }

    /// @Insert(onConflict = REPLACE)
    pub fn save_settings(&self, s: &CycleSettings) -> rusqlite::Result<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO cycle_settings (id, isOnboardingCompleted, selectedGoalsCsv, lastPeriodStartDate, averageCycleLength, periodDuration, lutealPhaseDays, isAppLockEnabled, lockTimeoutMinutes, isDiscreetNotificationMode, isPregnancyMode, pregnancyDueDate, remindPeriod, remindFertile, remindDailyLog) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15)",
            params![
                s.id, bool_to_int(s.is_onboarding_completed), s.selected_goals_csv,
                s.last_period_start_date, s.average_cycle_length, s.period_duration,
                s.luteal_phase_days, bool_to_int(s.is_app_lock_enabled), s.lock_timeout_minutes,
                bool_to_int(s.is_discreet_notification_mode), bool_to_int(s.is_pregnancy_mode),
                s.pregnancy_due_date, bool_to_int(s.remind_period), bool_to_int(s.remind_fertile),
                bool_to_int(s.remind_daily_log)
            ],
        )?;
        Ok(())
    }

    /// DELETE FROM cycle_settings WHERE id = 1
    pub fn delete_settings(&self) -> rusqlite::Result<()> {
        self.conn.execute("DELETE FROM cycle_settings WHERE id = 1", [])?;
        Ok(())
    }

    // ---------- OnboardingProfileDao ----------

    /// SELECT * FROM onboarding_profiles WHERE id = 1 LIMIT 1
    pub fn get_profile(&self) -> rusqlite::Result<Option<OnboardingProfile>> {
        self.conn
            .query_row("SELECT * FROM onboarding_profiles WHERE id = 1 LIMIT 1", [], |row| {
                Ok(OnboardingProfile {
                    id: row.get("id")?,
                    is_completed: int_to_bool(row.get("isCompleted")?),
                    completed_at_timestamp: row.get("completedAtTimestamp")?,
                    is_age_verified: int_to_bool(row.get("isAgeVerified")?),
                    is_terms_agreed: int_to_bool(row.get("isTermsAgreed")?),
                    account_mode: row.get("accountMode")?,
                    selected_goals_csv: row.get("selectedGoalsCsv")?,
                    last_period_date_string: row.get("lastPeriodDateString")?,
                    last_period_end_date_string: row.get("lastPeriodEndDateString")?,
                    approximate_period_choice: row.get("approximatePeriodChoice")?,
                    cycle_length_option: row.get("cycleLengthOption")?,
                    cycle_length_days: row.get("cycleLengthDays")?,
                    period_duration_days: row.get("periodDurationDays")?,
                    remind_fertile_window: int_to_bool(row.get("remindFertileWindow")?),
                    typical_symptoms_csv: row.get("typicalSymptomsCsv")?,
                    typical_energy: row.get("typicalEnergy")?,
                    typical_sleep_quality: row.get("typicalSleepQuality")?,
                    has_completed_deep_baseline: int_to_bool(row.get("hasCompletedDeepBaseline")?),
                })
            })
            .optional()
    }

    /// @Insert(onConflict = REPLACE)
    pub fn insert_or_update_profile(&self, p: &OnboardingProfile) -> rusqlite::Result<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO onboarding_profiles (id, isCompleted, completedAtTimestamp, isAgeVerified, isTermsAgreed, accountMode, selectedGoalsCsv, lastPeriodDateString, lastPeriodEndDateString, approximatePeriodChoice, cycleLengthOption, cycleLengthDays, periodDurationDays, remindFertileWindow, typicalSymptomsCsv, typicalEnergy, typicalSleepQuality, hasCompletedDeepBaseline) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17,?18)",
            params![
                p.id, bool_to_int(p.is_completed), p.completed_at_timestamp,
                bool_to_int(p.is_age_verified), bool_to_int(p.is_terms_agreed), p.account_mode,
                p.selected_goals_csv, p.last_period_date_string, p.last_period_end_date_string,
                p.approximate_period_choice, p.cycle_length_option, p.cycle_length_days,
                p.period_duration_days, bool_to_int(p.remind_fertile_window), p.typical_symptoms_csv,
                p.typical_energy, p.typical_sleep_quality, bool_to_int(p.has_completed_deep_baseline)
            ],
        )?;
        Ok(())
    }

    /// DELETE FROM onboarding_profiles
    pub fn delete_profile(&self) -> rusqlite::Result<()> {
        self.conn.execute("DELETE FROM onboarding_profiles", [])?;
        Ok(())
    }

    // ---------- health_sync journal rows (HealthSyncLocal.kt entities) ----------

    /// SELECT ... FROM health_sync_revisions WHERE ownerId = ? AND recordId = ?
    pub fn get_revision(
        &self,
        owner: &str,
        record_id: &str,
    ) -> rusqlite::Result<Option<crate::sync_codec::HealthSyncRevision>> {
        use crate::sync_codec::HealthSyncRevision;
        self.conn
            .query_row(
                "SELECT ownerId, recordId, revision, payloadHash, tombstone, updatedAt, serverRevision FROM health_sync_revisions WHERE ownerId = ?1 AND recordId = ?2",
                params![owner, record_id],
                |row| {
                    Ok(HealthSyncRevision {
                        owner_id: row.get(0)?,
                        record_id: row.get(1)?,
                        revision: row.get(2)?,
                        payload_hash: row.get(3)?,
                        tombstone: int_to_bool(row.get(4)?),
                        updated_at: row.get(5)?,
                        server_revision: row.get(6)?,
                    })
                },
            )
            .optional()
    }

    /// INSERT OR REPLACE INTO health_sync_revisions (Room @Upsert semantics).
    pub fn put_revision(&self, r: &crate::sync_codec::HealthSyncRevision) -> rusqlite::Result<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO health_sync_revisions (ownerId, recordId, revision, payloadHash, tombstone, updatedAt, serverRevision) VALUES (?1,?2,?3,?4,?5,?6,?7)",
            params![
                r.owner_id, r.record_id, r.revision, r.payload_hash,
                bool_to_int(r.tombstone), r.updated_at, r.server_revision
            ],
        )?;
        Ok(())
    }

    /// INSERT OR REPLACE INTO health_sync_outbox.
    pub fn enqueue_outbox(&self, e: &crate::sync_codec::HealthSyncOutboxEntry) -> rusqlite::Result<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO health_sync_outbox (ownerId, operationId, recordId, kind, baseRevision, revision, payloadJson, payloadHash, consentGeneration, attempts, nextAttemptAt, lastError, serverReceipt, serverRevision) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14)",
            params![
                e.owner_id, e.operation_id, e.record_id, e.kind, e.base_revision,
                e.revision, e.payload_json, e.payload_hash, e.consent_generation,
                e.attempts, e.next_attempt_at, e.last_error, e.server_receipt, e.server_revision
            ],
        )?;
        Ok(())
    }

    /// Outbox rows for an owner, oldest first (test/diagnostic aid).
    pub fn list_outbox(
        &self,
        owner: &str,
    ) -> rusqlite::Result<Vec<crate::sync_codec::HealthSyncOutboxEntry>> {
        use crate::sync_codec::HealthSyncOutboxEntry;
        let mut stmt = self.conn.prepare(
            "SELECT ownerId, operationId, recordId, kind, baseRevision, revision, payloadJson, payloadHash, consentGeneration, attempts, nextAttemptAt, lastError, serverReceipt, serverRevision FROM health_sync_outbox WHERE ownerId = ?1 ORDER BY rowid",
        )?;
        let rows = stmt.query_map(params![owner], |row| {
            Ok(HealthSyncOutboxEntry {
                owner_id: row.get(0)?,
                operation_id: row.get(1)?,
                record_id: row.get(2)?,
                kind: row.get(3)?,
                base_revision: row.get(4)?,
                revision: row.get(5)?,
                payload_json: row.get(6)?,
                payload_hash: row.get(7)?,
                consent_generation: row.get(8)?,
                attempts: row.get(9)?,
                next_attempt_at: row.get(10)?,
                last_error: row.get(11)?,
                server_receipt: row.get(12)?,
                server_revision: row.get(13)?,
            })
        })?;
        rows.collect()
    }

    /// CycleRepository.completeOnboardingWithProfile, executed as ONE SQLite
    /// transaction: insert profile, save patched settings, putRevision +
    /// enqueue for "profile" and "settings". operationIds/now are supplied by
    /// the caller (UUID/wall-clock in production). Returns the write-set.
    pub fn complete_onboarding_transaction(
        &self,
        outcome: &crate::onboarding_complete::CompletionOutcome,
        owner_identity: Option<&str>,
        consent_generation: i64,
        now_millis: i64,
        profile_operation_id: &str,
        settings_operation_id: &str,
    ) -> rusqlite::Result<crate::onboarding_complete::JournalWriteSet> {
        let owner = crate::onboarding_complete::owner_or_legacy(owner_identity);
        let tx = self.conn.unchecked_transaction()?;
        let current_settings = self
            .get_settings()?
            .unwrap_or_else(crate::sync_codec::CycleSettings::default);
        let prior_profile = self.get_revision(&owner, "profile")?;
        let prior_settings = self.get_revision(&owner, "settings")?;
        let writes = crate::onboarding_complete::plan_completion_journal(
            outcome,
            &current_settings,
            Some(&owner),
            consent_generation,
            now_millis,
            profile_operation_id,
            settings_operation_id,
            prior_profile.as_ref(),
            prior_settings.as_ref(),
        );
        self.insert_or_update_profile(&outcome.profile)?;
        self.save_settings(&writes.updated_settings)?;
        self.put_revision(&writes.profile_revision)?;
        self.enqueue_outbox(&writes.profile_outbox)?;
        self.put_revision(&writes.settings_revision)?;
        self.enqueue_outbox(&writes.settings_outbox)?;
        tx.commit()?;
        Ok(writes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn log(date: &str, pain: i32) -> CycleDayLog {
        CycleDayLog {
            date: date.to_string(),
            flow: "MEDIUM".to_string(),
            energy: String::new(),
            mood: String::new(),
            symptoms: "cramps".to_string(),
            vaginal_health: String::new(),
            urinary_health: String::new(),
            cervical_mucus: String::new(),
            ovulation_test: "NONE".to_string(),
            bbt_celsius: 36.5,
            sleep_hours: 7.5,
            sleep_quality: "GOOD".to_string(),
            water_glasses: 6,
            notes: String::new(),
            pain_intensity: pain,
        }
    }

    #[test]
    fn schema_applies_and_crud_matches_dao() {
        let db = StorageDb::open_memory().unwrap();
        assert!(db.get_all_logs().unwrap().is_empty());
        db.insert_or_update_log(&log("2026-11-01", 3)).unwrap();
        db.insert_or_update_log(&log("2026-10-30", 0)).unwrap();
        db.insert_or_update_log(&log("2026-11-02", 7)).unwrap();
        // ORDER BY dateString DESC
        let all = db.get_all_logs().unwrap();
        assert_eq!(all.iter().map(|l| l.date.as_str()).collect::<Vec<_>>(),
            vec!["2026-11-02", "2026-11-01", "2026-10-30"]);
        // LIMIT 1 lookup + field round-trip (incl f32 REAL columns)
        let one = db.get_log_by_date("2026-11-01").unwrap().unwrap();
        assert_eq!(one.pain_intensity, 3);
        assert_eq!(one.symptoms, "cramps");
        assert!((one.bbt_celsius - 36.5).abs() < 1e-6);
        assert!(db.get_log_by_date("2020-01-01").unwrap().is_none());
        // REPLACE conflict strategy on the PRIMARY KEY
        db.insert_or_update_log(&log("2026-11-01", 9)).unwrap();
        assert_eq!(db.get_log_by_date("2026-11-01").unwrap().unwrap().pain_intensity, 9);
        assert_eq!(db.get_all_logs().unwrap().len(), 3);
        db.delete_log("2026-11-01").unwrap();
        assert!(db.get_log_by_date("2026-11-01").unwrap().is_none());
        db.delete_all_logs().unwrap();
        assert!(db.get_all_logs().unwrap().is_empty());
    }

    #[test]
    fn settings_and_profile_singletons() {
        let db = StorageDb::open_memory().unwrap();
        assert!(db.get_settings().unwrap().is_none());
        let mut s = crate::sync_codec::CycleSettings::default();
        s.is_onboarding_completed = true;
        s.average_cycle_length = 31;
        db.save_settings(&s).unwrap();
        let got = db.get_settings().unwrap().unwrap();
        assert!(got.is_onboarding_completed);
        assert_eq!(got.average_cycle_length, 31);
        // REPLACE keeps the singleton
        s.average_cycle_length = 29;
        db.save_settings(&s).unwrap();
        assert_eq!(db.get_settings().unwrap().unwrap().average_cycle_length, 29);
        db.delete_settings().unwrap();
        assert!(db.get_settings().unwrap().is_none());

        assert!(db.get_profile().unwrap().is_none());
        let p = crate::sync_codec::OnboardingProfile {
            is_completed: true,
            completed_at_timestamp: 1_762_000_000,
            ..Default::default()
        };
        db.insert_or_update_profile(&p).unwrap();
        let got = db.get_profile().unwrap().unwrap();
        assert!(got.is_completed);
        assert_eq!(got.completed_at_timestamp, 1_762_000_000);
        assert!(got.is_age_verified); // entity default TRUE preserved
        db.delete_profile().unwrap();
        assert!(db.get_profile().unwrap().is_none());
    }

    #[test]
    fn ddl_mirror_covers_all_seven_tables() {
        let db = StorageDb::open_memory().unwrap();
        let mut stmt = db
            .conn
            .prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
            .unwrap();
        let names: Vec<String> = stmt.query_map([], |r| r.get(0)).unwrap().flatten().collect();
        assert_eq!(
            names,
            vec![
                "cycle_day_logs", "cycle_settings", "health_sync_conflicts", "health_sync_cursors",
                "health_sync_outbox", "health_sync_revisions", "onboarding_profiles"
            ]
        );
    }
}

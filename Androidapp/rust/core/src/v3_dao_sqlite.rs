//! REAL rusqlite-backed HealthSyncLocalDao (HealthSyncLocal.kt:14-127) —
//! the actual DAO behind the V3 seams, replacing in-memory fakes with the
//! verbatim @Query SQL against the production table/column/PK/index shapes.
//! Feature `sqlite` (bundled), host-only: this proves the seams against a
//! real database engine; Android Room on the encrypted app database remains
//! the separate, still-open integration (no plaintext fallback, no live
//! migration). Synthetic scratch data only.
//!
//! DDL is derived from the entity definitions; Room's exported schema JSON
//! is not in the repo, so exact generated-DDL parity (constraint spellings)
//! is asserted here via PRAGMA against the entity shapes rather than diffed
//! against Room's output. Columns are always written with full lists.
//!
//! Current-owner parity (OwnerScopedDatabaseProvider.kt): the provider
//! resolves the database AT CALL TIME by authPreferences.userId so account
//! switches can never reuse a prior owner's handle. The same obligation
//! applies here: route/construct SqliteV3Store per current owner at every
//! call site; never cache a store across an account switch.

#![cfg(feature = "sqlite")]

use rusqlite::{params, Connection, OptionalExtension, TransactionBehavior};
use crate::sync_codec::{CycleDayLog, CycleSettings, OnboardingProfile};
use crate::v3_outbox::{CommitOutcome, ConflictRow, OutboxEntry, V3DaoError, V3SyncDao};
use crate::v3_pull::{DomainStore, RevisionRow, V3PullError, V3PullStore, V3PullTx};

/// Fresh-create DDL. Authoritative shape: the KSP-generated DDL that Room
/// executes at open (CycleCareDatabase_Impl.kt, ops spike 18dd603, shipped
/// as tests/fixtures/room_schema_ksp.sql), verified applicable to main
/// 31b88f5 by field-level comparison against the entity sources
/// (HealthSyncLocal.kt:14-63, CycleDayLog.kt, CycleSettings.kt,
/// OnboardingProfile.kt; CycleCareDatabase.kt:15-16 lists exactly these 7
/// entities, version 9). A regression test diffs every statement against
/// the fixture. Inserts always name every column, so defaults are never
/// load-bearing.
pub const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS health_sync_revisions (
    ownerId TEXT NOT NULL,
    recordId TEXT NOT NULL,
    revision INTEGER NOT NULL,
    payloadHash TEXT,
    tombstone INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    serverRevision INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (ownerId, recordId)
);
CREATE TABLE IF NOT EXISTS health_sync_outbox (
    ownerId TEXT NOT NULL,
    operationId TEXT NOT NULL,
    recordId TEXT NOT NULL,
    kind TEXT NOT NULL,
    baseRevision INTEGER NOT NULL,
    revision INTEGER NOT NULL,
    payloadJson TEXT,
    payloadHash TEXT NOT NULL,
    consentGeneration INTEGER NOT NULL,
    attempts INTEGER NOT NULL,
    nextAttemptAt INTEGER NOT NULL,
    lastError TEXT,
    serverReceipt TEXT,
    serverRevision INTEGER,
    PRIMARY KEY (ownerId, operationId)
);
CREATE INDEX IF NOT EXISTS index_health_sync_outbox_ownerId_nextAttemptAt
    ON health_sync_outbox (ownerId, nextAttemptAt);
CREATE TABLE IF NOT EXISTS health_sync_conflicts (
    ownerId TEXT NOT NULL,
    recordId TEXT NOT NULL,
    operationId TEXT NOT NULL,
    localRevision INTEGER NOT NULL,
    localPayloadJson TEXT,
    localKind TEXT NOT NULL,
    remoteRevision INTEGER NOT NULL,
    remotePayloadJson TEXT,
    remoteTombstone INTEGER NOT NULL,
    createdAt INTEGER NOT NULL,
    resolvedAt INTEGER,
    resolution TEXT,
    PRIMARY KEY (ownerId, recordId)
);
CREATE INDEX IF NOT EXISTS index_health_sync_conflicts_ownerId_resolvedAt
    ON health_sync_conflicts (ownerId, resolvedAt);
CREATE TABLE IF NOT EXISTS health_sync_cursors (
    ownerId TEXT NOT NULL,
    changeSeq INTEGER NOT NULL,
    PRIMARY KEY (ownerId)
);
";

fn row_to_entry(r: &rusqlite::Row<'_>) -> rusqlite::Result<OutboxEntry> {
    Ok(OutboxEntry {
        owner: r.get("ownerId")?,
        operation_id: r.get("operationId")?,
        record_id: r.get("recordId")?,
        kind: r.get("kind")?,
        base_revision: r.get("baseRevision")?,
        revision: r.get("revision")?,
        payload_json: r.get("payloadJson")?,
        payload_hash: r.get("payloadHash")?,
        consent_generation: r.get("consentGeneration")?,
        attempts: r.get("attempts")?,
        next_attempt_at: r.get("nextAttemptAt")?,
    })
}

/// :66-67 — SELECT * ... WHERE ownerId ORDER BY nextAttemptAt, revision.
const SQL_PENDING: &str =
    "SELECT * FROM health_sync_outbox WHERE ownerId = ?1 ORDER BY nextAttemptAt, revision";

fn query_pending(conn: &Connection, owner: &str) -> rusqlite::Result<Vec<OutboxEntry>> {
    let mut st = conn.prepare(SQL_PENDING)?;
    let rows = st.query_map(params![owner], row_to_entry)?;
    rows.collect()
}

/// Domain tables (KSP-verbatim): cycle_day_logs, cycle_settings,
/// onboarding_profiles. Same database file as the sync tables, exactly as
/// CycleCareDatabase holds all seven entities.
pub const DOMAIN_SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS cycle_day_logs (
    dateString TEXT NOT NULL,
    flowLevel TEXT NOT NULL, energyLevel TEXT NOT NULL, moodLevel TEXT NOT NULL,
    symptomsCsv TEXT NOT NULL, vaginalHealthCsv TEXT NOT NULL, urinaryHealthCsv TEXT NOT NULL,
    cervicalMucus TEXT NOT NULL, ovulationTest TEXT NOT NULL,
    bbtCelsius REAL NOT NULL, sleepHours REAL NOT NULL, sleepQuality TEXT NOT NULL,
    waterGlasses INTEGER NOT NULL, notes TEXT NOT NULL, painIntensity INTEGER NOT NULL,
    PRIMARY KEY (dateString)
);
CREATE TABLE IF NOT EXISTS cycle_settings (
    id INTEGER NOT NULL,
    isOnboardingCompleted INTEGER NOT NULL, selectedGoalsCsv TEXT NOT NULL,
    lastPeriodStartDate TEXT NOT NULL, averageCycleLength INTEGER NOT NULL,
    periodDuration INTEGER NOT NULL, lutealPhaseDays INTEGER NOT NULL,
    isAppLockEnabled INTEGER NOT NULL, lockTimeoutMinutes INTEGER NOT NULL,
    isDiscreetNotificationMode INTEGER NOT NULL, isPregnancyMode INTEGER NOT NULL,
    pregnancyDueDate TEXT NOT NULL, remindPeriod INTEGER NOT NULL,
    remindFertile INTEGER NOT NULL, remindDailyLog INTEGER NOT NULL,
    PRIMARY KEY (id)
);
CREATE TABLE IF NOT EXISTS onboarding_profiles (
    id INTEGER NOT NULL,
    isCompleted INTEGER NOT NULL, completedAtTimestamp INTEGER NOT NULL,
    isAgeVerified INTEGER NOT NULL, isTermsAgreed INTEGER NOT NULL,
    accountMode TEXT NOT NULL, selectedGoalsCsv TEXT NOT NULL,
    lastPeriodDateString TEXT NOT NULL, lastPeriodEndDateString TEXT NOT NULL,
    approximatePeriodChoice TEXT NOT NULL, cycleLengthOption TEXT NOT NULL,
    cycleLengthDays INTEGER NOT NULL, periodDurationDays INTEGER NOT NULL,
    remindFertileWindow INTEGER NOT NULL, typicalSymptomsCsv TEXT NOT NULL,
    typicalEnergy TEXT NOT NULL, typicalSleepQuality TEXT NOT NULL,
    hasCompletedDeepBaseline INTEGER NOT NULL,
    PRIMARY KEY (id)
);
";

/// The real store: one Connection holds BOTH the sync tables and the domain
/// tables, so a page transaction commits or rolls back domain and sync rows
/// together - closing the same-connection atomicity gap.
pub struct SqliteV3Store {
    pub conn: Connection,
}

impl SqliteV3Store {
    pub fn open_in_memory() -> rusqlite::Result<Self> {
        let conn = Connection::open_in_memory()?;
        conn.execute_batch(SCHEMA)?;
        conn.execute_batch(DOMAIN_SCHEMA)?;
        Ok(SqliteV3Store { conn })
    }
    /// Scratch-file variant for restart-recovery tests (synthetic data only).
    pub fn open_file(path: &std::path::Path) -> rusqlite::Result<Self> {
        let conn = Connection::open(path)?;
        conn.execute_batch(SCHEMA)?;
        conn.execute_batch(DOMAIN_SCHEMA)?;
        Ok(SqliteV3Store { conn })
    }
    /// :117-118 — INSERT OR REPLACE (OnConflictStrategy.REPLACE).
    pub fn enqueue(&self, e: &OutboxEntry) -> rusqlite::Result<usize> {
        self.conn.execute(
            "INSERT OR REPLACE INTO health_sync_outbox
             (ownerId, operationId, recordId, kind, baseRevision, revision, payloadJson,
              payloadHash, consentGeneration, attempts, nextAttemptAt)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)",
            params![e.owner, e.operation_id, e.record_id, e.kind, e.base_revision, e.revision,
                    e.payload_json, e.payload_hash, e.consent_generation, e.attempts, e.next_attempt_at],
        )
    }
}

fn put_conflict_on(conn: &Connection, c: &ConflictRow) -> rusqlite::Result<usize> {
    // :104-105 — INSERT OR REPLACE; resolvedAt/resolution stay NULL.
    conn.execute(
        "INSERT OR REPLACE INTO health_sync_conflicts
         (ownerId, recordId, operationId, localRevision, localPayloadJson, localKind,
          remoteRevision, remotePayloadJson, remoteTombstone, createdAt)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)",
        params![c.owner, c.record_id, c.operation_id, c.local_revision, c.payload_json, c.kind,
                c.remote_revision, c.remote_payload, c.remote_tombstone as i64, c.created_ms],
    )
}

impl V3SyncDao for SqliteV3Store {
    fn pending(&mut self, owner: &str) -> Result<Vec<OutboxEntry>, V3DaoError> {
        // A read failure is a typed Err, never an empty list (Kotlin: the
        // suspend DAO throws; an empty list here would silently skip
        // operations and corrupt rebase decisions).
        query_pending(&self.conn, owner).map_err(|_| V3DaoError::Db)
    }

    fn commit_applied(
        &mut self,
        owner: &str,
        operation_id: &str,
        record_id: &str,
        entry_revision: i64,
        base_revision: i64,
        new_revision: i64,
        gate: &mut dyn FnMut() -> bool,
    ) -> CommitOutcome {
        let r = (|| -> rusqlite::Result<CommitOutcome> {
            // Same verified EXCLUSIVE mode as the pull transaction (both are
            // db.withTransaction in the source, :89 and :156).
            let tx = self.conn.transaction_with_behavior(TransactionBehavior::Exclusive)?;
            // (1) The in-transaction current() gate — false rolls back.
            if !gate() {
                return Ok(CommitOutcome::GateDrift);
            }
            // (2) Read the existing revision row (:60-61 shape).
            // QueryReturnedNoRows = the row is absent (skip the update);
            // ANY OTHER ERROR is a database failure: roll back and report
            // DbError - treating an error as "missing" would commit the
            // ACK + rebase against an unread existing row.
            let existing: Option<i64> = tx
                .query_row(
                    "SELECT serverRevision FROM health_sync_revisions WHERE ownerId = ?1 AND recordId = ?2 LIMIT 1",
                    params![owner, record_id],
                    |r| r.get(0),
                )
                .optional()?;
            // (3) Max-only update, ONLY when the row exists: never insert,
            // never lower (v51 contract).
            if existing.is_some() {
                tx.execute(
                    "UPDATE health_sync_revisions SET serverRevision = ?3
                     WHERE ownerId = ?1 AND recordId = ?2 AND ?3 > serverRevision",
                    params![owner, record_id, new_revision],
                )?;
            }
            // (4) acknowledgeMatched (:79-80).
            tx.execute(
                "DELETE FROM health_sync_outbox WHERE ownerId = ?1 AND operationId = ?2 AND recordId = ?3 AND revision = ?4",
                params![owner, operation_id, record_id, entry_revision],
            )?;
            // (5) rebasePending (:88-89).
            tx.execute(
                "UPDATE health_sync_outbox SET baseRevision = ?4 WHERE ownerId = ?1 AND recordId = ?2 AND baseRevision = ?3",
                params![owner, record_id, base_revision, new_revision],
            )?;
            tx.commit()?;
            Ok(CommitOutcome::Committed)
        })();
        match r {
            Ok(outcome) => outcome, // GateDrift: tx dropped without commit -> rolled back
            Err(_) => CommitOutcome::DbError, // tx dropped -> engine rolls back
        }
    }

    fn put_conflict(&mut self, conflict: ConflictRow) -> Result<(), V3DaoError> {
        put_conflict_on(&self.conn, &conflict).map(|_| ()).map_err(|_| V3DaoError::Db)
    }

    fn retry(&mut self, owner: &str, operation_id: &str, next_attempt_at: i64, message: &str) -> Result<(), V3DaoError> {
        // :94-95
        self.conn
            .execute(
                "UPDATE health_sync_outbox SET attempts = attempts + 1, nextAttemptAt = ?3, lastError = ?4 WHERE ownerId = ?1 AND operationId = ?2",
                params![owner, operation_id, next_attempt_at, message],
            )
            .map(|_| ())
            .map_err(|_| V3DaoError::Db)
    }
}

/// Domain reads/writes run on the SAME Transaction as the sync statements
/// (Kotlin: db.withTransaction covers both healthSyncDao and cycleDao/
/// onboardingDao calls, SupabaseSyncService.kt:156-181).
struct SqlitePullTx<'t> {
    tx: &'t rusqlite::Transaction<'t>,
}

fn dbe<T>(r: rusqlite::Result<T>) -> Result<T, V3PullError> { r.map_err(|_| V3PullError::Db) }

impl SqlitePullTx<'_> {
    fn conn(&self) -> &Connection { self.tx }
}

fn log_from_row(r: &rusqlite::Row<'_>) -> rusqlite::Result<CycleDayLog> {
    Ok(CycleDayLog {
        date: r.get("dateString")?, flow: r.get("flowLevel")?, energy: r.get("energyLevel")?,
        mood: r.get("moodLevel")?, symptoms: r.get("symptomsCsv")?,
        vaginal_health: r.get("vaginalHealthCsv")?, urinary_health: r.get("urinaryHealthCsv")?,
        cervical_mucus: r.get("cervicalMucus")?, ovulation_test: r.get("ovulationTest")?,
        bbt_celsius: r.get("bbtCelsius")?, sleep_hours: r.get("sleepHours")?,
        sleep_quality: r.get("sleepQuality")?, water_glasses: r.get("waterGlasses")?,
        notes: r.get("notes")?, pain_intensity: r.get("painIntensity")?,
    })
}
fn settings_from_row(r: &rusqlite::Row<'_>) -> rusqlite::Result<CycleSettings> {
    Ok(CycleSettings {
        id: r.get("id")?,
        is_onboarding_completed: r.get::<_, i64>("isOnboardingCompleted")? != 0,
        selected_goals_csv: r.get("selectedGoalsCsv")?,
        last_period_start_date: r.get("lastPeriodStartDate")?,
        average_cycle_length: r.get("averageCycleLength")?,
        period_duration: r.get("periodDuration")?,
        luteal_phase_days: r.get("lutealPhaseDays")?,
        is_app_lock_enabled: r.get::<_, i64>("isAppLockEnabled")? != 0,
        lock_timeout_minutes: r.get("lockTimeoutMinutes")?,
        is_discreet_notification_mode: r.get::<_, i64>("isDiscreetNotificationMode")? != 0,
        is_pregnancy_mode: r.get::<_, i64>("isPregnancyMode")? != 0,
        pregnancy_due_date: r.get("pregnancyDueDate")?,
        remind_period: r.get::<_, i64>("remindPeriod")? != 0,
        remind_fertile: r.get::<_, i64>("remindFertile")? != 0,
        remind_daily_log: r.get::<_, i64>("remindDailyLog")? != 0,
    })
}
fn profile_from_row(r: &rusqlite::Row<'_>) -> rusqlite::Result<OnboardingProfile> {
    Ok(OnboardingProfile {
        id: r.get("id")?,
        is_completed: r.get::<_, i64>("isCompleted")? != 0,
        completed_at_timestamp: r.get("completedAtTimestamp")?,
        is_age_verified: r.get::<_, i64>("isAgeVerified")? != 0,
        is_terms_agreed: r.get::<_, i64>("isTermsAgreed")? != 0,
        account_mode: r.get("accountMode")?,
        selected_goals_csv: r.get("selectedGoalsCsv")?,
        last_period_date_string: r.get("lastPeriodDateString")?,
        last_period_end_date_string: r.get("lastPeriodEndDateString")?,
        approximate_period_choice: r.get("approximatePeriodChoice")?,
        cycle_length_option: r.get("cycleLengthOption")?,
        cycle_length_days: r.get("cycleLengthDays")?,
        period_duration_days: r.get("periodDurationDays")?,
        remind_fertile_window: r.get::<_, i64>("remindFertileWindow")? != 0,
        typical_symptoms_csv: r.get("typicalSymptomsCsv")?,
        typical_energy: r.get("typicalEnergy")?,
        typical_sleep_quality: r.get("typicalSleepQuality")?,
        has_completed_deep_baseline: r.get::<_, i64>("hasCompletedDeepBaseline")? != 0,
    })
}

/// Verbatim CycleDayLogDao / OnboardingProfileDao queries, on the
/// transaction's connection.
fn domain_get_settings(conn: &Connection) -> rusqlite::Result<Option<CycleSettings>> {
    // CycleDayLogDao.kt:35-36
    conn.query_row("SELECT * FROM cycle_settings WHERE id = 1 LIMIT 1", [], settings_from_row).optional()
}
fn domain_save_settings(conn: &Connection, s: &CycleSettings) -> rusqlite::Result<usize> {
    // CycleDayLogDao.kt:38-39 - INSERT OR REPLACE
    conn.execute(
        "INSERT OR REPLACE INTO cycle_settings (id, isOnboardingCompleted, selectedGoalsCsv, lastPeriodStartDate,
         averageCycleLength, periodDuration, lutealPhaseDays, isAppLockEnabled, lockTimeoutMinutes,
         isDiscreetNotificationMode, isPregnancyMode, pregnancyDueDate, remindPeriod, remindFertile, remindDailyLog)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15)",
        params![s.id, s.is_onboarding_completed as i64, s.selected_goals_csv, s.last_period_start_date,
                s.average_cycle_length, s.period_duration, s.luteal_phase_days, s.is_app_lock_enabled as i64,
                s.lock_timeout_minutes, s.is_discreet_notification_mode as i64, s.is_pregnancy_mode as i64,
                s.pregnancy_due_date, s.remind_period as i64, s.remind_fertile as i64, s.remind_daily_log as i64],
    )
}
fn domain_get_profile(conn: &Connection) -> rusqlite::Result<Option<OnboardingProfile>> {
    // OnboardingProfileDao.kt:14-15
    conn.query_row("SELECT * FROM onboarding_profiles WHERE id = 1 LIMIT 1", [], profile_from_row).optional()
}
fn domain_save_profile(conn: &Connection, p: &OnboardingProfile) -> rusqlite::Result<usize> {
    // OnboardingProfileDao.kt:17-18 - INSERT OR REPLACE
    conn.execute(
        "INSERT OR REPLACE INTO onboarding_profiles (id, isCompleted, completedAtTimestamp, isAgeVerified,
         isTermsAgreed, accountMode, selectedGoalsCsv, lastPeriodDateString, lastPeriodEndDateString,
         approximatePeriodChoice, cycleLengthOption, cycleLengthDays, periodDurationDays,
         remindFertileWindow, typicalSymptomsCsv, typicalEnergy, typicalSleepQuality, hasCompletedDeepBaseline)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17,?18)",
        params![p.id, p.is_completed as i64, p.completed_at_timestamp, p.is_age_verified as i64,
                p.is_terms_agreed as i64, p.account_mode, p.selected_goals_csv, p.last_period_date_string,
                p.last_period_end_date_string, p.approximate_period_choice, p.cycle_length_option,
                p.cycle_length_days, p.period_duration_days, p.remind_fertile_window as i64,
                p.typical_symptoms_csv, p.typical_energy, p.typical_sleep_quality,
                p.has_completed_deep_baseline as i64],
    )
}
fn domain_upsert_log(conn: &Connection, l: &CycleDayLog) -> rusqlite::Result<usize> {
    // CycleDayLogDao.kt:23-24 - INSERT OR REPLACE
    conn.execute(
        "INSERT OR REPLACE INTO cycle_day_logs (dateString, flowLevel, energyLevel, moodLevel, symptomsCsv,
         vaginalHealthCsv, urinaryHealthCsv, cervicalMucus, ovulationTest, bbtCelsius, sleepHours,
         sleepQuality, waterGlasses, notes, painIntensity)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15)",
        params![l.date, l.flow, l.energy, l.mood, l.symptoms, l.vaginal_health, l.urinary_health,
                l.cervical_mucus, l.ovulation_test, l.bbt_celsius, l.sleep_hours, l.sleep_quality,
                l.water_glasses, l.notes, l.pain_intensity],
    )
}
fn domain_delete_log(conn: &Connection, date: &str) -> rusqlite::Result<usize> {
    // CycleDayLogDao.kt:26-27
    conn.execute("DELETE FROM cycle_day_logs WHERE dateString = ?1", params![date])
}

impl DomainStore for SqlitePullTx<'_> {
    fn get_settings(&mut self) -> Option<CycleSettings> {
        domain_get_settings(self.conn()).ok().flatten()
    }
    fn save_settings(&mut self, s: CycleSettings) -> Result<(), String> {
        domain_save_settings(self.conn(), &s).map(|_| ()).map_err(|e| e.to_string())
    }
    fn get_profile(&mut self) -> Option<OnboardingProfile> {
        domain_get_profile(self.conn()).ok().flatten()
    }
    fn save_profile(&mut self, p: OnboardingProfile) -> Result<(), String> {
        domain_save_profile(self.conn(), &p).map(|_| ()).map_err(|e| e.to_string())
    }
    fn upsert_log(&mut self, l: CycleDayLog) -> Result<(), String> {
        domain_upsert_log(self.conn(), &l).map(|_| ()).map_err(|e| e.to_string())
    }
    fn delete_log(&mut self, date: &str) -> Result<(), String> {
        domain_delete_log(self.conn(), date).map(|_| ()).map_err(|e| e.to_string())
    }
}

impl V3PullTx for SqlitePullTx<'_> {
    fn revision(&mut self, owner: &str, id: &str) -> Result<Option<RevisionRow>, V3PullError> {
        // :60-61
        dbe(self.conn().query_row(
            "SELECT revision, serverRevision FROM health_sync_revisions WHERE ownerId = ?1 AND recordId = ?2 LIMIT 1",
            params![owner, id],
            |r| Ok(RevisionRow { revision: r.get(0)?, server_revision: r.get(1)? }),
        ).optional())
    }
    fn put_revision(&mut self, owner: &str, id: &str, rev: i64, hash: Option<String>, tombstone: bool, now: i64, server_rev: i64) -> Result<(), V3PullError> {
        // :63-64 — INSERT OR REPLACE.
        dbe(self.conn().execute(
            "INSERT OR REPLACE INTO health_sync_revisions
             (ownerId, recordId, revision, payloadHash, tombstone, updatedAt, serverRevision)
             VALUES (?1,?2,?3,?4,?5,?6,?7)",
            params![owner, id, rev, hash, tombstone as i64, now, server_rev],
        ).map(|_| ()))
    }
    fn pending_for_record(&mut self, owner: &str, id: &str) -> Result<Vec<OutboxEntry>, V3PullError> {
        // dao.pending(owner).filter { it.recordId == id } — ORDER BY preserved.
        dbe(query_pending(self.conn(), owner).map(|v| v.into_iter().filter(|e| e.record_id == id).collect()))
    }
    fn put_conflict(&mut self, c: ConflictRow) -> Result<(), V3PullError> {
        dbe(put_conflict_on(self.conn(), &c).map(|_| ()))
    }
    fn put_cursor(&mut self, owner: &str, seq: i64) -> Result<(), V3PullError> {
        // :116-117 — INSERT OR REPLACE.
        dbe(self.conn().execute(
            "INSERT OR REPLACE INTO health_sync_cursors (ownerId, changeSeq) VALUES (?1, ?2)",
            params![owner, seq],
        ).map(|_| ()))
    }
}

impl V3PullStore for SqliteV3Store {
    fn cursor(&mut self, owner: &str) -> Result<Option<i64>, V3PullError> {
        // :113-114. NoRows -> None (no cursor yet); any other error -> Db.
        dbe(self.conn
            .query_row("SELECT changeSeq FROM health_sync_cursors WHERE ownerId = ?1 LIMIT 1", params![owner], |r| r.get(0))
            .optional())
    }
    fn transaction(&mut self, body: &mut dyn FnMut(&mut dyn V3PullTx) -> Result<i32, V3PullError>) -> Result<i32, V3PullError> {
        // Real withTransaction parity. VERIFIED MODE: Room withTransaction ->
        // SupportSQLiteDatabase.beginTransaction() -> AOSP 14.0.0_r1
        // SQLiteDatabase.beginTransaction() -> beginTransaction(null,
        // exclusive=TRUE) -> SQLiteSession.TRANSACTION_MODE_EXCLUSIVE, i.e.
        // BEGIN EXCLUSIVE (verified in the AOSP source; the androidx
        // Room-version hop is pinned by app/build.gradle, not re-verified
        // here). rusqlite's Connection::transaction() defaults to DEFERRED -
        // deliberately selecting Exclusive to match.
        let tx = dbe(self.conn.transaction_with_behavior(TransactionBehavior::Exclusive))?;
        let r = {
            let mut t = SqlitePullTx { tx: &tx };
            body(&mut t)?
        };
        dbe(tx.commit())?;
        Ok(r)
    }
}

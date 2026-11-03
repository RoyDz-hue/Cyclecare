//! CycleCareDatabase.kt migrations (main 31b88f5): createMigration4To5
//! (:33-96) as an injected-effects driver + the exact SQL bodies of every
//! migration step, JVM-EXECUTED locked (MigSqlParity.kt runs the source's
//! trimIndent on jdk-17.0.21+9; fixtures/oracle/migration_sql.tsv is its
//! byte-exact output, including the "SELECT \n" TRAILING-SPACE quirk that
//! trimIndent preserves - locked, not cleaned).
//!
//! createMigration4To5 decision structure (:34-53): query the legacy
//! plaintext appLockPin, move it into AuthPreferences (encrypted prefs)
//! ONLY when a row exists, the column exists, the stored PIN is non-blank,
//! AND AuthPreferences has no PIN yet (preserved-account handoff: never
//! overwrites an existing credential). The whole PIN block sits in one
//! try/catch(Throwable) that SWALLOWS every fault - including a failed
//! cursor.close(), which is SKIPPED on any earlier fault (source leak
//! quirk, ported 1:1 and labeled). The table recreation SQL ALWAYS runs
//! after the swallow. Migrations 5-9 are pure SQL constants from
//! :126-158. Migration EXECUTION (Room running these against a real db)
//! remains host-only.

use crate::kotlin_text::kotlin_trim;

fn kotlin_is_blank(s: &str) -> bool {
    kotlin_trim(s).is_empty()
}

/// Query text (:35).
pub const SQL_4_TO_5_PIN_QUERY: &str = "SELECT appLockPin FROM cycle_settings WHERE id = 1";
pub const SQL_4_TO_5_CREATE: &str = r#####"CREATE TABLE IF NOT EXISTS `cycle_settings_new` (
    `id` INTEGER NOT NULL PRIMARY KEY,
    `isOnboardingCompleted` INTEGER NOT NULL,
    `selectedGoalsCsv` TEXT NOT NULL,
    `lastPeriodStartDate` TEXT NOT NULL,
    `averageCycleLength` INTEGER NOT NULL,
    `periodDuration` INTEGER NOT NULL,
    `lutealPhaseDays` INTEGER NOT NULL,
    `isAppLockEnabled` INTEGER NOT NULL,
    `lockTimeoutMinutes` INTEGER NOT NULL,
    `isDiscreetNotificationMode` INTEGER NOT NULL,
    `isPregnancyMode` INTEGER NOT NULL,
    `pregnancyDueDate` TEXT NOT NULL,
    `remindPeriod` INTEGER NOT NULL,
    `remindFertile` INTEGER NOT NULL,
    `remindDailyLog` INTEGER NOT NULL
)"#####;

pub const SQL_4_TO_5_INSERT: &str = r#####"INSERT INTO `cycle_settings_new` (
    `id`, `isOnboardingCompleted`, `selectedGoalsCsv`, `lastPeriodStartDate`,
    `averageCycleLength`, `periodDuration`, `lutealPhaseDays`, `isAppLockEnabled`,
    `lockTimeoutMinutes`, `isDiscreetNotificationMode`, `isPregnancyMode`,
    `pregnancyDueDate`, `remindPeriod`, `remindFertile`, `remindDailyLog`
)
SELECT 
    `id`, `isOnboardingCompleted`, `selectedGoalsCsv`, `lastPeriodStartDate`,
    `averageCycleLength`, `periodDuration`, `lutealPhaseDays`, `isAppLockEnabled`,
    `lockTimeoutMinutes`, `isDiscreetNotificationMode`, `isPregnancyMode`,
    `pregnancyDueDate`, `remindPeriod`, `remindFertile`, `remindDailyLog`
FROM `cycle_settings`"#####;

pub const SQL_4_TO_5_DROP: &str = r#####"DROP TABLE `cycle_settings`"#####;

pub const SQL_4_TO_5_RENAME: &str = r#####"ALTER TABLE `cycle_settings_new` RENAME TO `cycle_settings`"#####;

/// 5->6 (:127-133): health-sync tables, 4 statements in source order.
pub const SQL_5_TO_6: [&str; 4] = [
    "CREATE TABLE IF NOT EXISTS `health_sync_revisions` (`ownerId` TEXT NOT NULL, `recordId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `payloadHash` TEXT, `tombstone` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`ownerId`, `recordId`))",
    "CREATE TABLE IF NOT EXISTS `health_sync_outbox` (`ownerId` TEXT NOT NULL, `operationId` TEXT NOT NULL, `recordId` TEXT NOT NULL, `kind` TEXT NOT NULL, `baseRevision` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `payloadJson` TEXT, `payloadHash` TEXT NOT NULL, `consentGeneration` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `nextAttemptAt` INTEGER NOT NULL, `lastError` TEXT, PRIMARY KEY(`ownerId`, `operationId`))",
    "CREATE TABLE IF NOT EXISTS `health_sync_cursors` (`ownerId` TEXT NOT NULL, `changeSeq` INTEGER NOT NULL, PRIMARY KEY(`ownerId`))",
    "CREATE INDEX IF NOT EXISTS `index_health_sync_outbox_ownerId_nextAttemptAt` ON `health_sync_outbox` (`ownerId`, `nextAttemptAt`)",
];

/// 6->7 (:135-140): outbox receipt columns + conflicts table, 4 statements.
pub const SQL_6_TO_7: [&str; 4] = [
    "ALTER TABLE `health_sync_outbox` ADD COLUMN `serverReceipt` TEXT",
    "ALTER TABLE `health_sync_outbox` ADD COLUMN `serverRevision` INTEGER",
    "CREATE TABLE IF NOT EXISTS `health_sync_conflicts` (`ownerId` TEXT NOT NULL, `recordId` TEXT NOT NULL, `operationId` TEXT NOT NULL, `localRevision` INTEGER NOT NULL, `localPayloadJson` TEXT, `localKind` TEXT NOT NULL, `remoteRevision` INTEGER NOT NULL, `remotePayloadJson` TEXT, `remoteTombstone` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `resolvedAt` INTEGER, `resolution` TEXT, PRIMARY KEY(`ownerId`, `recordId`))",
    "CREATE INDEX IF NOT EXISTS `index_health_sync_conflicts_ownerId_resolvedAt` ON `health_sync_conflicts` (`ownerId`, `resolvedAt`)",
];

/// 7->8 (:143-147).
pub const SQL_7_TO_8: [&str; 1] =
    ["ALTER TABLE `health_sync_revisions` ADD COLUMN `serverRevision` INTEGER NOT NULL DEFAULT 0"];

/// 8->9 (:149-156): raw period end date, preserved exactly as entered.
pub const SQL_8_TO_9: [&str; 1] =
    ["ALTER TABLE `onboarding_profiles` ADD COLUMN `lastPeriodEndDateString` TEXT NOT NULL DEFAULT ''"];

/// The cursor state the source's PIN sweep observes (:36-40).
#[derive(Debug, Clone, PartialEq)]
pub struct PinCursor {
    /// cursor.moveToFirst()
    pub has_row: bool,
    /// cursor.getColumnIndex("appLockPin") - -1 when the column is absent.
    pub pin_index: i32,
    /// cursor.getString(pinIdx) - nullable.
    pub pin: Option<String>,
}

/// Injected effects for createMigration4To5. Every PIN-block effect is
/// fallible; ANY fault aborts the rest of the PIN block (one try/catch
/// (Throwable) in the source) and is swallowed. The four recreation SQL
/// statements run regardless.
pub trait Migration45Fx {
    /// db.query(SQL_4_TO_5_PIN_QUERY).
    fn query_pin_cursor(&mut self) -> Result<PinCursor, ()>;
    /// AuthPreferences(context).pin read (:42-43) - construction included.
    fn auth_prefs_pin(&mut self) -> Result<Option<String>, ()>;
    /// authPrefs.pin = rawPin (:44).
    fn auth_prefs_set_pin(&mut self, pin: &str) -> Result<(), ()>;
    /// authPrefs.isPinEnabled = true (:45).
    fn auth_prefs_set_pin_enabled(&mut self, enabled: bool) -> Result<(), ()>;
    /// cursor.close() (:48) - INSIDE the try: skipped on earlier faults,
    /// its own failure swallowed (source leak quirk, labeled).
    fn cursor_close(&mut self) -> Result<(), ()>;
    /// db.execSQL for the recreation statements (:55-95).
    fn exec_sql(&mut self, sql: &str);
}

/// createMigration4To5.migrate (:34-95), composed. Never fails visibly:
/// the source's only failure surface is the swallowed PIN block; execSQL
/// faults propagate through Room's migration runner (host seam).
pub fn run_migration_4_to_5<F: Migration45Fx>(fx: &mut F) {
    let pin_block: Result<(), ()> = (|| {
        let cursor = fx.query_pin_cursor()?;
        if cursor.has_row && cursor.pin_index != -1 {
            if let Some(raw_pin) = &cursor.pin {
                if !kotlin_is_blank(raw_pin) {
                    if fx.auth_prefs_pin()?.map_or(true, |p| kotlin_is_blank(&p)) {
                        fx.auth_prefs_set_pin(raw_pin)?;
                        fx.auth_prefs_set_pin_enabled(true)?;
                    }
                }
            }
        }
        fx.cursor_close()?;
        Ok(())
    })();
    let _ = pin_block; // catch (_: Throwable) - swallowed by design
    fx.exec_sql(SQL_4_TO_5_CREATE);
    fx.exec_sql(SQL_4_TO_5_INSERT);
    fx.exec_sql(SQL_4_TO_5_DROP);
    fx.exec_sql(SQL_4_TO_5_RENAME);
}

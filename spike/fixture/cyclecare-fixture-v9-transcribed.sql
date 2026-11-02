-- CycleCare local DB v9 fixture schema, transcribed from main@31b88f5
-- Sources: data/local/CycleCareDatabase.kt (migrations 4->5,5->6,6->7,7->8,8->9),
-- data/local/CycleDayLog.kt, data/local/OnboardingProfile.kt
-- Note: exportSchema=false, so entities + migrations are the only schema reference.
-- Ops: diff against a live-opened DB (PRAGMA table_info per table) before trusting.

CREATE TABLE IF NOT EXISTS `cycle_day_logs` (
  `dateString` TEXT NOT NULL PRIMARY KEY,
  `flowLevel` TEXT NOT NULL DEFAULT '',
  `energyLevel` TEXT NOT NULL DEFAULT '',
  `moodLevel` TEXT NOT NULL DEFAULT '',
  `symptomsCsv` TEXT NOT NULL DEFAULT '',
  `vaginalHealthCsv` TEXT NOT NULL DEFAULT '',
  `urinaryHealthCsv` TEXT NOT NULL DEFAULT '',
  `cervicalMucus` TEXT NOT NULL DEFAULT '',
  `ovulationTest` TEXT NOT NULL DEFAULT 'NONE',
  `bbtCelsius` REAL NOT NULL DEFAULT 0.0,
  `sleepHours` REAL NOT NULL DEFAULT 0.0,
  `sleepQuality` TEXT NOT NULL DEFAULT '',
  `waterGlasses` INTEGER NOT NULL DEFAULT 0,
  `notes` TEXT NOT NULL DEFAULT '',
  `painIntensity` INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS `cycle_settings` (
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
);

CREATE TABLE IF NOT EXISTS `onboarding_profiles` (
  `id` INTEGER NOT NULL PRIMARY KEY,
  `isCompleted` INTEGER NOT NULL,
  `completedAtTimestamp` INTEGER NOT NULL,
  `isAgeVerified` INTEGER NOT NULL,
  `isTermsAgreed` INTEGER NOT NULL,
  `accountMode` TEXT NOT NULL,
  `selectedGoalsCsv` TEXT NOT NULL,
  `lastPeriodDateString` TEXT NOT NULL,
  `lastPeriodEndDateString` TEXT NOT NULL DEFAULT '',
  `approximatePeriodChoice` TEXT NOT NULL,
  `cycleLengthOption` TEXT NOT NULL,
  `cycleLengthDays` INTEGER NOT NULL,
  `periodDurationDays` INTEGER NOT NULL,
  `remindFertileWindow` INTEGER NOT NULL,
  `typicalSymptomsCsv` TEXT NOT NULL,
  `typicalEnergy` TEXT NOT NULL,
  `typicalSleepQuality` TEXT NOT NULL,
  `hasCompletedDeepBaseline` INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS `health_sync_revisions` (
  `ownerId` TEXT NOT NULL,
  `recordId` TEXT NOT NULL,
  `revision` INTEGER NOT NULL,
  `payloadHash` TEXT,
  `tombstone` INTEGER NOT NULL,
  `updatedAt` INTEGER NOT NULL,
  `serverRevision` INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(`ownerId`, `recordId`)
);

CREATE TABLE IF NOT EXISTS `health_sync_outbox` (
  `ownerId` TEXT NOT NULL,
  `operationId` TEXT NOT NULL,
  `recordId` TEXT NOT NULL,
  `kind` TEXT NOT NULL,
  `baseRevision` INTEGER NOT NULL,
  `revision` INTEGER NOT NULL,
  `payloadJson` TEXT,
  `payloadHash` TEXT NOT NULL,
  `consentGeneration` INTEGER NOT NULL,
  `attempts` INTEGER NOT NULL,
  `nextAttemptAt` INTEGER NOT NULL,
  `lastError` TEXT,
  `serverReceipt` TEXT,
  `serverRevision` INTEGER,
  PRIMARY KEY(`ownerId`, `operationId`)
);
CREATE INDEX IF NOT EXISTS `index_health_sync_outbox_ownerId_nextAttemptAt` ON `health_sync_outbox` (`ownerId`, `nextAttemptAt`);

CREATE TABLE IF NOT EXISTS `health_sync_cursors` (
  `ownerId` TEXT NOT NULL,
  `changeSeq` INTEGER NOT NULL,
  PRIMARY KEY(`ownerId`)
);

CREATE TABLE IF NOT EXISTS `health_sync_conflicts` (
  `ownerId` TEXT NOT NULL,
  `recordId` TEXT NOT NULL,
  `operationId` TEXT NOT NULL,
  `localRevision` INTEGER NOT NULL,
  `localPayloadJson` TEXT,
  `localKind` TEXT NOT NULL,
  `remoteRevision` INTEGER NOT NULL,
  `remotePayloadJson` TEXT,
  `remoteTombstone` INTEGER NOT NULL,
  `createdAt` INTEGER NOT NULL,
  `resolvedAt` INTEGER,
  `resolution` TEXT,
  PRIMARY KEY(`ownerId`, `recordId`)
);
CREATE INDEX IF NOT EXISTS `index_health_sync_conflicts_ownerId_resolvedAt` ON `health_sync_conflicts` (`ownerId`, `resolvedAt`);

-- Room bookkeeping (present in every Room DB)
CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT);

-- Synthetic, non-secret fixture rows
INSERT INTO `cycle_settings` VALUES (1,1,'', '2026-10-05',29,5,14,0,0,0,0,'',1,1,0);
INSERT INTO `onboarding_profiles` VALUES (1,1,1762000000000,1,1,'PRIVATE','','2026-10-05','2026-10-09','EXACT','',29,5,0,'','','',0);
INSERT INTO `cycle_day_logs` (`dateString`,`flowLevel`,`moodLevel`,`symptomsCsv`,`waterGlasses`,`notes`,`painIntensity`)
  VALUES ('2026-10-05','MEDIUM','GOOD','cramps,fatigue',6,'fixture row',3),
         ('2026-10-06','LIGHT','NEUTRAL','',7,'',0),
         ('2026-10-20','NONE','GOOD','headache',8,'synthetic note',1);
INSERT INTO `health_sync_revisions` VALUES ('synthetic-owner','rec-1',3,'hash3',0,1762000000000,2);
INSERT INTO `health_sync_outbox` VALUES ('synthetic-owner','op-1','rec-1','UPSERT',2,3,'{"k":"v"}','hash3',1,0,0,NULL,NULL,NULL);
INSERT INTO `health_sync_cursors` VALUES ('synthetic-owner',42);
INSERT INTO `health_sync_conflicts` VALUES ('synthetic-owner','rec-2','op-2',1,'{"a":1}','UPSERT',2,'{"a":2}',0,1762000000000,NULL,NULL);
INSERT INTO room_master_table VALUES (42,'fixture-identity-hash');

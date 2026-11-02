-- CycleCare v9 fixture ROWS (full-column; valid against Room-real DDL with no
-- column defaults). Synthetic data only. DDL comes from the Room schema dump
-- at CI time; room_master_table gets the REAL identity hash from the dump.
INSERT INTO `cycle_settings` (`id`,`isOnboardingCompleted`,`selectedGoalsCsv`,`lastPeriodStartDate`,`averageCycleLength`,`periodDuration`,`lutealPhaseDays`,`isAppLockEnabled`,`lockTimeoutMinutes`,`isDiscreetNotificationMode`,`isPregnancyMode`,`pregnancyDueDate`,`remindPeriod`,`remindFertile`,`remindDailyLog`)
  VALUES (1,1,'','2026-10-05',29,5,14,0,0,0,0,'',1,1,0);
INSERT INTO `onboarding_profiles` (`id`,`isCompleted`,`completedAtTimestamp`,`isAgeVerified`,`isTermsAgreed`,`accountMode`,`selectedGoalsCsv`,`lastPeriodDateString`,`lastPeriodEndDateString`,`approximatePeriodChoice`,`cycleLengthOption`,`cycleLengthDays`,`periodDurationDays`,`remindFertileWindow`,`typicalSymptomsCsv`,`typicalEnergy`,`typicalSleepQuality`,`hasCompletedDeepBaseline`)
  VALUES (1,1,1762000000000,1,1,'PRIVATE','','2026-10-05','2026-10-09','EXACT','',29,5,0,'','','',0);
INSERT INTO `cycle_day_logs` (`dateString`,`flowLevel`,`energyLevel`,`moodLevel`,`symptomsCsv`,`vaginalHealthCsv`,`urinaryHealthCsv`,`cervicalMucus`,`ovulationTest`,`bbtCelsius`,`sleepHours`,`sleepQuality`,`waterGlasses`,`notes`,`painIntensity`)
  VALUES ('2026-10-05','MEDIUM','','GOOD','cramps,fatigue','','','','NONE',0.0,0.0,'',6,'fixture row',3),
         ('2026-10-06','LIGHT','','NEUTRAL','','','','','NONE',0.0,0.0,'',7,'',0),
         ('2026-10-20','NONE','','GOOD','headache','','','','','NONE',0.0,0.0,'',8,'synthetic note',1);
INSERT INTO `health_sync_revisions` (`ownerId`,`recordId`,`revision`,`payloadHash`,`tombstone`,`updatedAt`,`serverRevision`)
  VALUES ('synthetic-owner','rec-1',3,'hash3',0,1762000000000,2);
INSERT INTO `health_sync_outbox` (`ownerId`,`operationId`,`recordId`,`kind`,`baseRevision`,`revision`,`payloadJson`,`payloadHash`,`consentGeneration`,`attempts`,`nextAttemptAt`,`lastError`,`serverReceipt`,`serverRevision`)
  VALUES ('synthetic-owner','op-1','rec-1','UPSERT',2,3,'{"k":"v"}','hash3',1,0,0,NULL,NULL,NULL);
INSERT INTO `health_sync_cursors` (`ownerId`,`changeSeq`) VALUES ('synthetic-owner',42);
INSERT INTO `health_sync_conflicts` (`ownerId`,`recordId`,`operationId`,`localRevision`,`localPayloadJson`,`localKind`,`remoteRevision`,`remotePayloadJson`,`remoteTombstone`,`createdAt`,`resolvedAt`,`resolution`)
  VALUES ('synthetic-owner','rec-2','op-2',1,'{"a":1}','UPSERT',2,'{"a":2}',0,1762000000000,NULL,NULL);

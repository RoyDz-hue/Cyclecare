\set ON_ERROR_STOP on
-- Standalone disposable contract test. Run from supabase/tests with a fresh database.
-- This file owns only test fixtures; it does not modify proposals or production migrations.
\ir fixtures/approved-uuid-era.sql
-- approved-uuid-era.sql and its UUID repair provide these legacy tables.
GRANT USAGE ON SCHEMA public TO authenticated,service_role;
INSERT INTO auth.users(id,email) VALUES
 ('00000000-0000-0000-0000-000000000001','owner-a@example.test'),('00000000-0000-0000-0000-000000000002','owner-b@example.test'),('00000000-0000-0000-0000-000000000003','owner-c@example.test');
INSERT INTO public.cyclecare_privacy_consents(user_id,cloud_sync_enabled) VALUES
 ('00000000-0000-0000-0000-000000000001',true),('00000000-0000-0000-0000-000000000002',true),('00000000-0000-0000-0000-000000000003',true) ON CONFLICT(user_id) DO UPDATE SET cloud_sync_enabled=EXCLUDED.cloud_sync_enabled;
INSERT INTO public.transactions(user_id,external_reference,provider,amount,status) VALUES ('00000000-0000-0000-0000-000000000001','sync-fixture-pending','payhero',12.00,'pending');
\ir ../proposals/20261019000100_payment_settlement_protocol.sql
\ir ../proposals/20261019090000_health_sync_v3.sql
\ir ../proposals/20261019143000_cyclecare_ai_processor_consent_runtime.sql
\ir health-sync-v3-wire.sql
-- Capture deterministic UUIDs as database owner before helper privileges are revoked.
SELECT public.cyclecare_v3_record_uuid('00000000-0000-0000-0000-000000000001'::uuid,'2026-10-19')::text AS date_record, public.cyclecare_v3_record_uuid('00000000-0000-0000-0000-000000000001'::uuid,'settings')::text AS settings_record, public.cyclecare_v3_record_uuid('00000000-0000-0000-0000-000000000001'::uuid,'profile')::text AS profile_record \gset
CREATE OR REPLACE FUNCTION public.test_assert(p boolean,p_label text) RETURNS void LANGUAGE plpgsql AS $$BEGIN IF p IS DISTINCT FROM true THEN RAISE EXCEPTION 'FAIL: %',p_label; END IF; RAISE NOTICE 'PASS: %',p_label; END$$;
SET ROLE authenticated;
SET request.jwt.claim.role='authenticated';
SET request.jwt.claim.sub='00000000-0000-0000-0000-000000000001';
SET request.jwt.claims='{"sub":"00000000-0000-0000-0000-000000000001","role":"authenticated"}';
SELECT (set_health_sync_consent(true)->>'generation')::bigint AS g \gset
SELECT test_assert(:g=1,'consent starts at generation one');
-- Current wire envelope: operation id, derived record UUID, base/revision, kind, payload, consent generation, logical id.
SELECT apply_health_sync_operation_v3('10000000-0000-0000-0000-000000000001',:'date_record'::uuid,0,1,'UPSERT',jsonb_build_object('kind','UPSERT','logicalRecordId','2026-10-19','value',jsonb_build_object('temperatureC',36.7,'symptoms',jsonb_build_array('fatigue'))),:g,'2026-10-19') AS receipt \gset
SELECT test_assert(:'receipt'::jsonb->>'status'='applied','valid health envelope applies');
SELECT test_assert((:'receipt'::jsonb->>'record_id')::uuid=:'date_record'::uuid,'applied receipt has derived record id');
SELECT test_assert((:'receipt'::jsonb->>'operation_id')::uuid='10000000-0000-0000-0000-000000000001','applied receipt has operation id');
SELECT test_assert(:'receipt'::jsonb->>'logical_record_id'='2026-10-19','applied receipt has logical id');
SELECT test_assert(:'receipt'::jsonb->>'payload_hash' ~ '^[0-9a-f]{64}$','applied receipt has 64-hex payload hash');
SELECT test_assert((:'receipt'::jsonb->>'revision')='1','applied receipt reports original revision');
SELECT apply_health_sync_operation_v3('10000000-0000-0000-0000-000000000001',:'date_record'::uuid,0,1,'UPSERT',jsonb_build_object('kind','UPSERT','logicalRecordId','2026-10-19','value',jsonb_build_object('temperatureC',36.7,'symptoms',jsonb_build_array('fatigue'))),:g,'2026-10-19') AS dup \gset
SELECT test_assert(:'dup'::jsonb->>'status'='duplicate','same operation is duplicate');
SELECT test_assert((:'dup'::jsonb->>'revision')='1','duplicate retains original revision not current revision');
SELECT test_assert((:'dup'::jsonb->>'record_id') IS NOT NULL AND (:'dup'::jsonb->>'operation_id') IS NOT NULL,'duplicate contains identity fields');
SELECT test_assert(:'dup'::jsonb->>'payload_hash' = :'receipt'::jsonb->>'payload_hash','duplicate retains original hash');
SELECT test_assert((apply_health_sync_operation_v3('10000000-0000-0000-0000-000000000002',:'date_record'::uuid,1,2,'UPSERT',jsonb_build_object('kind','UPSERT','logicalRecordId','2026-10-19','value',jsonb_build_object('temperatureC',37.1)),:g,'2026-10-19')->>'status')='applied','second revision applies');
SELECT test_assert((apply_health_sync_operation_v3('10000000-0000-0000-0000-000000000003',:'date_record'::uuid,0,1,'UPSERT',jsonb_build_object('kind','UPSERT','logicalRecordId','2026-10-19','value',jsonb_build_object('temperatureC',38)),:g,'2026-10-19')->>'status')='conflict','stale base returns conflict');
SELECT test_assert((apply_health_sync_operation_v3('10000000-0000-0000-0000-000000000004',:'settings_record'::uuid,0,2,'UPSERT',jsonb_build_object('kind','UPSERT','logicalRecordId','settings','value',jsonb_build_object('x',1)),:g,'settings')->>'reason')='revision_must_be_base_plus_one','revision increment enforced');
SELECT test_assert((apply_health_sync_operation_v3('10000000-0000-0000-0000-000000000005',:'settings_record'::uuid,0,1,'UPSERT',jsonb_build_object('kind','UPSERT','logicalRecordId','settings','value',jsonb_build_object('x',1)),:g,'profile')->>'reason')='invalid_operation','wrong record/logical UUID rejected');
SELECT test_assert((apply_health_sync_operation_v3('10000000-0000-0000-0000-000000000006','54d8191b-8a52-38d7-a25d-83ffa143b813'::uuid,0,1,'UPSERT',jsonb_build_object('kind','UPSERT','logicalRecordId','2026-02-31','value',jsonb_build_object('x',1)),:g,'2026-02-31')->>'reason')='invalid_logical_date','invalid calendar date rejected');
SELECT test_assert((apply_health_sync_operation_v3('10000000-0000-0000-0000-000000000007',:'settings_record'::uuid,0,1,'UPSERT',jsonb_build_object('kind','UPSERT','logicalRecordId','profile','value',jsonb_build_object('x',1)),:g,'settings')->>'reason')='invalid_wire_envelope','envelope logical id mismatch rejected');
SELECT test_assert((apply_health_sync_operation_v3('10000000-0000-0000-0000-000000000008',:'profile_record'::uuid,0,1,'UPSERT',jsonb_build_object('kind','UPSERT','logicalRecordId','profile','value',jsonb_build_object('x',1)),:g,'profile')->>'status')='applied','profile envelope applies');
SELECT test_assert((apply_health_sync_operation_v3('10000000-0000-0000-0000-000000000009',:'settings_record'::uuid,0,1,'DELETE',NULL,:g,'settings')->>'status')='applied','explicit logical DELETE applies');
SELECT test_assert((apply_health_sync_operation_v3('10000000-0000-0000-0000-000000000010',:'settings_record'::uuid,1,2,'UPSERT',jsonb_build_object('kind','UPSERT','logicalRecordId','settings','value',jsonb_build_object('x',2)),:g,'settings')->>'reason')='tombstone_resurrection','tombstone prevents resurrection');
SELECT test_assert((SELECT tombstone AND payload IS NULL FROM public.read_health_sync_changes(0,500,:g) WHERE logical_record_id='settings'),'tombstone has no payload');
SELECT test_assert((SELECT count(*) FROM read_health_sync_changes(0,1,:g))=1,'pagination limit is honored');
SELECT test_assert((SELECT count(*) FROM read_health_sync_changes(0,500,:g) WHERE logical_record_id IN ('2026-10-19','profile','settings'))=3,'cursor feed contains all owner records');
SELECT test_assert(NOT has_table_privilege('authenticated','public.cyclecare_health_sync_records','SELECT'),'ordinary role cannot select health table');
SELECT test_assert(NOT has_table_privilege('authenticated','public.cyclecare_health_sync_operations','SELECT'),'ordinary role cannot select operation journal');
SELECT test_assert(NOT has_sequence_privilege('authenticated','public.cyclecare_health_sync_change_seq','USAGE'),'ordinary role cannot use sequence');
SELECT test_assert(NOT has_function_privilege('authenticated','public.cyclecare_v3_record_uuid(uuid,text)','EXECUTE'),'helper is revoked');
SELECT test_assert((set_health_sync_consent(false)->>'enabled')='false','revocation disables sync');
SELECT test_assert((apply_health_sync_operation_v3('10000000-0000-0000-0000-000000000011',:'date_record'::uuid,2,3,'UPSERT',jsonb_build_object('kind','UPSERT','logicalRecordId','2026-10-19','value',jsonb_build_object('x',3)),:g,'2026-10-19')->>'reason')='stale_or_revoked_consent','stale write rejected after revoke');
SELECT (set_health_sync_consent(true)->>'generation')::bigint AS resumed \gset
SELECT test_assert(:resumed>:g,'resume advances consent clock');
SELECT test_assert((SELECT count(*) FROM read_health_sync_changes(0,500,:g))=0,'old read generation remains invalid');
SELECT test_assert((SELECT count(*) FROM read_health_sync_changes(0,500,:resumed))=3,'explicit resume restores access to existing same-owner cloud records');
-- AI clocks survive erasure while receipts disappear; provider remains closed until reviewed.
SELECT test_assert((set_my_ai_processor_consent_v3('COHERE',false,0,'', 'cyclecare-ai-manifest-v3')->>'revision')='1','AI consent clock can be initialized');
RESET ROLE;
SELECT test_assert((SELECT count(*) FROM cyclecare_ai_processor_consent_clocks WHERE user_id=auth.uid())=1,'AI clock exists before erasure');
INSERT INTO public.daily_logs(user_id,date,notes) VALUES (auth.uid(),'2026-10-19','private');
INSERT INTO public.profiles(user_id,goals,last_period_date) VALUES(auth.uid(),'erase me','2026-10-01');
SET ROLE authenticated;
SELECT delete_my_cyclecare_health_data() AS erased \gset
RESET ROLE;
SELECT test_assert(:'erased'::jsonb->>'financial_records_retained'='true','erasure reports financial preservation');
SELECT test_assert(NOT EXISTS(SELECT 1 FROM cyclecare_health_sync_records WHERE user_id=auth.uid()) AND NOT EXISTS(SELECT 1 FROM cyclecare_health_sync_operations WHERE user_id=auth.uid()),'erasure removes health rows and receipts');
SELECT test_assert((SELECT enabled FROM cyclecare_health_sync_consent WHERE user_id=auth.uid())=false,'erasure retains disabled consent row');
SELECT test_assert((SELECT count(*) FROM cyclecare_health_deletions WHERE user_id=auth.uid()::text)=1,'erasure marker persists');
SELECT test_assert((SELECT count(*) FROM daily_logs WHERE user_id=auth.uid())=0,'erasure removes daily logs');
SELECT test_assert((SELECT last_period_date IS NULL AND goals='' FROM profiles WHERE user_id=auth.uid()),'erasure clears profile health fields');
SELECT test_assert((SELECT count(*) FROM transactions WHERE external_reference='sync-fixture-pending')=1,'financial pending transaction preserved');
SELECT test_assert((SELECT revision>1 FROM cyclecare_ai_processor_consent_clocks WHERE user_id=auth.uid() AND processor='COHERE'),'AI clock survives erasure epoch');
SET ROLE authenticated;
SELECT test_assert((SELECT (set_health_sync_consent(true)->>'enabled')='false'),'privacy marker blocks resume');
RESET ROLE;
UPDATE public.cyclecare_health_deletions SET deleted_at=now() WHERE user_id=auth.uid()::text;
UPDATE public.cyclecare_privacy_consents SET cloud_sync_enabled=true WHERE user_id=auth.uid()::text;
SET ROLE authenticated;
SELECT test_assert((set_health_sync_consent(true)->>'enabled')='false','legacy privacy deletion still blocks resume');
RESET ROLE;
SET ROLE service_role;
SET request.jwt.claim.role='service_role'; SET request.jwt.claim.sub='00000000-0000-0000-0000-000000000001'; SET request.jwt.claims='{"sub":"00000000-0000-0000-0000-000000000001","role":"service_role"}';
SELECT test_assert((SELECT count(*) FROM transactions WHERE external_reference='sync-fixture-pending')=1,'service can verify retained financial evidence');
RESET ROLE;
-- Simulate a separately authorized future resumption in this disposable fixture only.
DELETE FROM public.cyclecare_health_deletions WHERE user_id=auth.uid()::text;
INSERT INTO public.cyclecare_privacy_consents(user_id,cloud_sync_enabled) VALUES(auth.uid()::text,true) ON CONFLICT(user_id) DO UPDATE SET cloud_sync_enabled=true;
SET ROLE authenticated;
SET request.jwt.claim.role='authenticated';
SET request.jwt.claims='{"sub":"00000000-0000-0000-0000-000000000001","role":"authenticated"}';
SELECT (set_health_sync_consent(true)->>'generation')::bigint AS after_erasure_generation \gset
SELECT test_assert(:after_erasure_generation>:resumed,'server epoch cannot ABA-reset across health erasure');
SELECT test_assert((apply_health_sync_operation_v3('10000000-0000-0000-0000-000000000001',:'date_record'::uuid,0,1,'UPSERT',jsonb_build_object('kind','UPSERT','logicalRecordId','2026-10-19','value',jsonb_build_object('old','private')),:g,'2026-10-19')->>'reason')='stale_or_revoked_consent','pre-erasure operation cannot resurrect health data after resume');
SELECT test_assert((SELECT count(*) FROM read_health_sync_changes(0,500,:after_erasure_generation))=0,'new epoch has no erased health payloads');
RESET ROLE;
SELECT 'health_sync_v3_assertions_complete' AS result;

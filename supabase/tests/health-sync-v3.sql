\set ON_ERROR_STOP on
-- Disposable PostgreSQL ONLY. Run from the repository root against a fresh test database.
CREATE SCHEMA auth;
CREATE TABLE auth.users(id uuid PRIMARY KEY);
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$ SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
DO $$ BEGIN CREATE ROLE authenticated; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE ROLE anon; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
GRANT USAGE ON SCHEMA auth TO authenticated;
CREATE TABLE public.unrelated_privilege_probe(id integer);
GRANT SELECT ON public.unrelated_privilege_probe TO authenticated;
CREATE TABLE public.cyclecare_privacy_consents(user_id text PRIMARY KEY, cloud_sync_enabled boolean NOT NULL);
CREATE TABLE public.cyclecare_health_deletions(user_id text PRIMARY KEY);
INSERT INTO auth.users VALUES ('00000000-0000-0000-0000-000000000001'),('00000000-0000-0000-0000-000000000002');
INSERT INTO public.cyclecare_privacy_consents VALUES ('00000000-0000-0000-0000-000000000001',true),('00000000-0000-0000-0000-000000000002',true);
CREATE FUNCTION public.test_assert(condition boolean,label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN IF condition IS DISTINCT FROM true THEN RAISE EXCEPTION 'FAIL: %',label; END IF; RAISE NOTICE 'PASS: %',label; END $$;
\i supabase/proposals/20261019090000_health_sync_v3.sql
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',false);
SELECT (set_health_sync_consent(true)->>'generation')::bigint AS generation \gset
SELECT test_assert(:generation=1,'first explicit consent has generation one');
SELECT test_assert((apply_health_sync_operation('10000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001',0,1,'UPSERT','{"v":1}',encode(sha256(convert_to('{"v": 1}','UTF8')),'hex'),:generation)->>'status')='applied','first operation applied');
SELECT test_assert((apply_health_sync_operation('10000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001',0,1,'UPSERT','{"v":1}',encode(sha256(convert_to('{"v": 1}','UTF8')),'hex'),:generation)->>'status')='duplicate','identical retry idempotent');
SELECT test_assert((apply_health_sync_operation('10000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001',0,1,'UPSERT','{"v":2}',encode(sha256(convert_to('{"v": 2}','UTF8')),'hex'),:generation)->>'reason')='operation_id_payload_reuse','reused operation rejects changed payload');
SELECT test_assert((apply_health_sync_operation('10000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001',9,1,'UPSERT','{"v":1}',encode(sha256(convert_to('{"v": 1}','UTF8')),'hex'),:generation)->>'reason')='operation_id_payload_reuse','reused operation rejects changed base');
SELECT test_assert((apply_health_sync_operation('10000000-0000-0000-0000-000000000002','20000000-0000-0000-0000-000000000002',4,5,'UPSERT','{"v":3}',encode(sha256(convert_to('{"v": 3}','UTF8')),'hex'),:generation)->>'status')='conflict','missing record requires zero base');
SELECT test_assert((apply_health_sync_operation('10000000-0000-0000-0000-000000000003','20000000-0000-0000-0000-000000000002',0,2,'UPSERT','{"v":3}',encode(sha256(convert_to('{"v": 3}','UTF8')),'hex'),:generation)->>'reason')='revision_must_be_base_plus_one','revision must increment exactly once');
SELECT test_assert((apply_health_sync_operation('10000000-0000-0000-0000-000000000004','20000000-0000-0000-0000-000000000003',0,1,'UPSERT','{"v":4}','bad-hash',:generation)->>'reason')='payload_hash_mismatch','server verifies payload fingerprint');
SELECT test_assert((apply_health_sync_operation('10000000-0000-0000-0000-000000000008','20000000-0000-0000-0000-000000000002',0,1,'UPSERT','{"v":2}',encode(sha256(convert_to('{"v": 2}','UTF8')),'hex'),:generation)->>'status')='applied','second record at same revision applied');
SELECT test_assert(count(*)=2,'two records with equal revisions both visible') FROM read_health_sync_changes(0,500,:generation);
SELECT change_seq AS first_cursor FROM read_health_sync_changes(0,1,:generation) \gset
SELECT test_assert(count(*)=1,'cursor pagination does not skip equal record revisions') FROM read_health_sync_changes(:first_cursor,1,:generation);
SELECT test_assert((apply_health_sync_operation('10000000-0000-0000-0000-000000000005','20000000-0000-0000-0000-000000000001',1,2,'DELETE',NULL,encode(sha256(convert_to('null','UTF8')),'hex'),:generation)->>'status')='applied','delete applied');
SELECT test_assert(payload IS NULL AND tombstone,'delete erases current health payload') FROM read_health_sync_changes(0,500,:generation) WHERE record_id='20000000-0000-0000-0000-000000000001';
SELECT test_assert((apply_health_sync_operation('10000000-0000-0000-0000-000000000006','20000000-0000-0000-0000-000000000001',2,3,'UPSERT','{"v":4}',encode(sha256(convert_to('{"v": 4}','UTF8')),'hex'),:generation)->>'reason')='tombstone_resurrection','deleted record cannot resurrect');
SELECT test_assert((apply_health_sync_operation('10000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001',0,1,'UPSERT','{"v":1}',encode(sha256(convert_to('{"v": 1}','UTF8')),'hex'),:generation)->>'revision')='1','old retry acknowledges original operation not latest record');
SELECT test_assert(NOT has_table_privilege('authenticated','public.cyclecare_health_sync_records','SELECT'),'direct health SELECT cannot bypass consent');
SELECT test_assert(NOT has_table_privilege('authenticated','public.cyclecare_health_sync_operations','SELECT'),'operation receipts not exposed directly');
SELECT test_assert(has_table_privilege('authenticated','public.unrelated_privilege_probe','SELECT'),'unrelated privileges preserved');
SELECT (set_health_sync_consent(false)->>'generation')::bigint AS revoked_generation \gset
SELECT test_assert(count(*)=0,'revocation blocks health reads') FROM read_health_sync_changes(0,500,:generation);
SELECT test_assert((apply_health_sync_operation('10000000-0000-0000-0000-000000000007','20000000-0000-0000-0000-000000000002',1,2,'UPSERT','{"v":5}',encode(sha256(convert_to('{"v": 5}','UTF8')),'hex'),:generation)->>'reason')='stale_or_revoked_consent','revocation blocks stale writes');
SELECT (set_health_sync_consent(true)->>'generation')::bigint AS resumed_generation \gset
SELECT test_assert(count(*)=0,'resume does not accept old consent generation') FROM read_health_sync_changes(0,500,:generation);
SELECT test_assert(count(*)=2,'new explicit generation can read') FROM read_health_sync_changes(0,500,:resumed_generation);
SELECT set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',false);
SELECT (set_health_sync_consent(true)->>'generation')::bigint AS other_generation \gset
SELECT test_assert(count(*)=0,'other owner cannot read first owner records') FROM read_health_sync_changes(0,500,:other_generation);
RESET ROLE;
SELECT test_assert(NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='cyclecare_health_sync_operations' AND column_name='payload'),'operation journal retains no historical health payloads');
INSERT INTO public.cyclecare_health_deletions VALUES ('00000000-0000-0000-0000-000000000001');
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',false);
SELECT test_assert((set_health_sync_consent(true)->>'enabled')='false','existing cloud deletion blocks new protocol enable');
SELECT test_assert(count(*)=0,'existing cloud deletion blocks reads despite v3 enabled flag') FROM read_health_sync_changes(0,500,:resumed_generation);
RESET ROLE;
SELECT 'health_sync_v3_assertions_complete' AS result;

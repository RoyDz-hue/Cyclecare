\set ON_ERROR_STOP on
-- Included by health-sync-v3.sql after proposals; also runnable after the proposal on a disposable DB.
DO $$DECLARE ok boolean; BEGIN
 SELECT public.cyclecare_v3_record_uuid('00000000-0000-0000-0000-000000000001'::uuid,'settings') IS NOT NULL INTO ok;
 IF NOT ok THEN RAISE EXCEPTION 'helper did not return UUID'; END IF;
 SELECT has_function_privilege('authenticated','public.cyclecare_v3_record_uuid(uuid,text)','EXECUTE') INTO ok;
 IF ok THEN RAISE EXCEPTION 'helper must be revoked from ordinary role'; END IF;
 IF to_regprocedure('public.apply_health_sync_operation_v3(uuid,uuid,bigint,bigint,text,jsonb,bigint,text)') IS NULL THEN RAISE EXCEPTION 'current public wrapper is missing'; END IF;
 IF to_regprocedure('public.apply_health_sync_operation(uuid,uuid,bigint,bigint,text,jsonb,text,bigint,text)') IS NULL THEN RAISE EXCEPTION 'private core is missing'; END IF;
END$$;

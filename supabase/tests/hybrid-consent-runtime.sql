\set ON_ERROR_STOP on
-- Run on a fresh disposable approved-uuid-era fixture plus the AI candidate.
-- All review grants below are test fixtures, never production or human approval.
BEGIN;
CREATE OR REPLACE FUNCTION public.hybrid_test_assert(p boolean,label text) RETURNS void LANGUAGE plpgsql AS $$ BEGIN IF p IS DISTINCT FROM true THEN RAISE EXCEPTION 'FAIL: %',label; END IF; RAISE NOTICE 'PASS: %',label; END $$;
INSERT INTO auth.users(id,email) VALUES('00000000-0000-0000-0000-000000000001','ordinary@example.test'),('00000000-0000-0000-0000-000000000002','test-admin@example.test') ON CONFLICT DO NOTHING;
SELECT hybrid_test_assert((SELECT NOT hybrid_enabled FROM cyclecare_runtime_config WHERE id=true),'Hybrid database gate defaults closed');
SELECT hybrid_test_assert((SELECT count(*)=3 AND NOT bool_or(eligible OR account_reviewed OR use_case_reviewed OR audience_reviewed) FROM cyclecare_ai_provider_manifests),'all processor policies start unapproved');
SELECT hybrid_test_assert((SELECT manifest_fingerprint='9ded3b301d7ca164dbb6cf95b20e26d6e026d3b12d93aafa6bc7af4b4af0536b' AND provider_only='openai' AND allow_fallbacks=false AND data_collection='deny' AND zdr FROM cyclecare_ai_provider_manifests WHERE processor='OPENROUTER'),'SQL pins OpenRouter upstream, no fallback, no training and ZDR');
SELECT hybrid_test_assert(to_regprocedure('public.set_my_ai_processor_consent(text,boolean,text,text)') IS NULL,'obsolete non-CAS consent entrypoint is absent');
SELECT hybrid_test_assert(NOT has_table_privilege('authenticated','cyclecare_ai_processor_consents','INSERT') AND NOT has_table_privilege('authenticated','cyclecare_ai_processor_consent_clocks','SELECT'),'ordinary clients cannot bypass receipt mutations or inspect clocks');
SELECT hybrid_test_assert(NOT has_table_privilege('service_role','cyclecare_ai_processor_consents','UPDATE'),'service credential cannot directly alter receipts');
SET ROLE service_role;
SELECT hybrid_test_assert((SELECT count(*) FROM cyclecare_ai_provider_manifests)=3,'actual service role can read manifests');
SELECT hybrid_test_assert((SELECT count(*) FROM cyclecare_ai_provider_policy_readiness)=3,'actual service role can read policy readiness');
SELECT hybrid_test_assert((SELECT count(*) FROM cyclecare_ai_processor_consent_clocks)>=0,'actual service role can read private consent clocks');
RESET ROLE;
SET ROLE authenticated;
SET LOCAL request.jwt.claim.role='authenticated'; SET LOCAL request.jwt.claim.sub='00000000-0000-0000-0000-000000000001';
SET LOCAL request.jwt.claims='{"sub":"00000000-0000-0000-0000-000000000001","role":"authenticated","user_metadata":{"role":"admin"}}';
DO $$ BEGIN
 BEGIN PERFORM admin_update_runtime_config('COHERE','PAYHERO'); RAISE EXCEPTION 'ordinary user changed runtime'; EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'PASS: ordinary user cannot change runtime'; END;
 BEGIN PERFORM admin_review_ai_provider_manifest('COHERE',true,'not allowed'); RAISE EXCEPTION 'ordinary user reviewed policy'; EXCEPTION WHEN OTHERS THEN IF SQLERRM NOT LIKE '%Administrator access required%' THEN RAISE; END IF; RAISE NOTICE 'PASS: editable user metadata cannot certify provider eligibility'; END;
 BEGIN PERFORM set_my_ai_processor_consent_v3('COHERE',true,0,'c1e57debf0fd9df74a32d28d7f0262cadee4a5d4247594bb731db3ba917b3797','cyclecare-ai-manifest-v3'); RAISE EXCEPTION 'unreviewed processor accepted consent'; EXCEPTION WHEN OTHERS THEN IF SQLERRM='unreviewed processor accepted consent' THEN RAISE; END IF; RAISE NOTICE 'PASS: unreviewed processor rejects grant'; END;
END $$;
RESET ROLE;
-- A protected membership row, not editable metadata or an always-true helper.
INSERT INTO cyclecare_admin_roles(user_id) VALUES('00000000-0000-0000-0000-000000000002');
SET ROLE authenticated;
SET LOCAL request.jwt.claim.sub='00000000-0000-0000-0000-000000000002'; SET LOCAL request.jwt.claims='{"sub":"00000000-0000-0000-0000-000000000002","role":"authenticated"}';
SELECT hybrid_test_assert(admin_update_runtime_config('COHERE','PAYHERO')->>'active_ai_provider'='COHERE','protected administrator can select supported single provider');
DO $$ BEGIN
 BEGIN PERFORM admin_update_runtime_config('HYBRID','PAYHERO'); RAISE EXCEPTION 'closed Hybrid selected'; EXCEPTION WHEN OTHERS THEN IF SQLERRM NOT LIKE '%not enabled for release%' THEN RAISE; END IF; RAISE NOTICE 'PASS: admin cannot bypass closed Hybrid release gate'; END;
 BEGIN PERFORM admin_update_runtime_config('POLLINATIONS','PAYHERO'); RAISE EXCEPTION 'image provider selected for chat'; EXCEPTION WHEN OTHERS THEN IF SQLERRM NOT LIKE '%Unsupported provider%' THEN RAISE; END IF; RAISE NOTICE 'PASS: image generation is never a chat provider'; END;
END $$;
RESET ROLE;
UPDATE cyclecare_runtime_config SET hybrid_enabled=true WHERE id=true;
SET ROLE authenticated;
DO $$ BEGIN
 BEGIN PERFORM admin_update_runtime_config('HYBRID','PAYHERO'); RAISE EXCEPTION 'unreviewed Hybrid selected'; EXCEPTION WHEN OTHERS THEN IF SQLERRM NOT LIKE '%review is incomplete%' THEN RAISE; END IF; RAISE NOTICE 'PASS: database release flag alone cannot bypass processor policy review'; END;
END $$;
SELECT admin_review_ai_provider_manifest('COHERE',true,'DISPOSABLE TEST FIXTURE ONLY, not human or production approval');
SELECT admin_review_ai_provider_manifest('OPENROUTER',true,'DISPOSABLE TEST FIXTURE ONLY, not human or production approval');
SELECT hybrid_test_assert(admin_update_runtime_config('HYBRID','PAYHERO')->>'active_ai_provider'='HYBRID','reviewed candidate runtime RPC supports explicit Hybrid');
SET LOCAL request.jwt.claim.sub='00000000-0000-0000-0000-000000000001'; SET LOCAL request.jwt.claims='{"sub":"00000000-0000-0000-0000-000000000001","role":"authenticated"}';
SELECT hybrid_test_assert(set_my_ai_processor_consent_v3('COHERE',true,0,'c1e57debf0fd9df74a32d28d7f0262cadee4a5d4247594bb731db3ba917b3797','cyclecare-ai-manifest-v3')->>'revision'='1','reviewed processor creates owner-bound revision-one grant');
SELECT hybrid_test_assert(set_my_ai_processor_consent_v3('COHERE',false,1,'c1e57debf0fd9df74a32d28d7f0262cadee4a5d4247594bb731db3ba917b3797','cyclecare-ai-manifest-v3')->>'revision'='2','revocation advances consent CAS clock');
DO $$ BEGIN
 BEGIN PERFORM set_my_ai_processor_consent_v3('COHERE',true,0,'c1e57debf0fd9df74a32d28d7f0262cadee4a5d4247594bb731db3ba917b3797','cyclecare-ai-manifest-v3'); RAISE EXCEPTION 'old grant overwrote revoke'; EXCEPTION WHEN raise_exception THEN IF SQLERRM<>'revision conflict' THEN RAISE; END IF; RAISE NOTICE 'PASS: stale grant cannot override revocation'; END;
END $$;
RESET ROLE;
SELECT hybrid_test_assert((SELECT NOT granted FROM cyclecare_ai_processor_consents WHERE user_id='00000000-0000-0000-0000-000000000001' AND processor='COHERE'),'authoritative receipt remains revoked');
ROLLBACK;
SELECT 'HYBRID_RUNTIME_ASSERTIONS_COMPLETE' AS result;

-- Add LAJIWA as a selectable AI provider across the consent/runtime contract.
-- Fail-closed: the seeded rows are unreviewed and ineligible, so no member data can
-- flow to Lajiwa until an explicit admin review AND a current member consent receipt.
BEGIN;

ALTER TABLE public.cyclecare_runtime_config DROP CONSTRAINT IF EXISTS cyclecare_runtime_config_active_ai_provider_check;
ALTER TABLE public.cyclecare_runtime_config ADD CONSTRAINT cyclecare_runtime_config_active_ai_provider_check CHECK (active_ai_provider IN ('COHERE','GEMINI','OPENROUTER','LAJIWA','HYBRID'));

ALTER TABLE public.cyclecare_ai_processor_consent_clocks DROP CONSTRAINT IF EXISTS cyclecare_ai_processor_consent_clocks_processor_check;
ALTER TABLE public.cyclecare_ai_processor_consent_clocks ADD CONSTRAINT cyclecare_ai_processor_consent_clocks_processor_check CHECK (processor IN ('COHERE', 'GEMINI', 'OPENROUTER', 'LAJIWA'));

ALTER TABLE public.cyclecare_ai_processor_consents DROP CONSTRAINT IF EXISTS cyclecare_ai_processor_consents_processor_check;
ALTER TABLE public.cyclecare_ai_processor_consents ADD CONSTRAINT cyclecare_ai_processor_consents_processor_check CHECK (processor IN ('COHERE', 'GEMINI', 'OPENROUTER', 'LAJIWA'));

ALTER TABLE public.cyclecare_ai_provider_policy_readiness DROP CONSTRAINT IF EXISTS cyclecare_ai_provider_policy_readiness_processor_check;
ALTER TABLE public.cyclecare_ai_provider_policy_readiness ADD CONSTRAINT cyclecare_ai_provider_policy_readiness_processor_check CHECK (processor IN ('COHERE', 'GEMINI', 'OPENROUTER', 'LAJIWA'));

ALTER TABLE public.cyclecare_ai_provider_manifests DROP CONSTRAINT IF EXISTS cyclecare_ai_provider_manifests_processor_check;
ALTER TABLE public.cyclecare_ai_provider_manifests ADD CONSTRAINT cyclecare_ai_provider_manifests_processor_check CHECK (processor IN ('COHERE','GEMINI','OPENROUTER','LAJIWA'));

INSERT INTO public.cyclecare_ai_provider_policy_readiness(processor,policy_revision,reviewed,eligible,manifest_fingerprint,notes) VALUES
 ('LAJIWA','lajiwa-selfhosted-2026-10',FALSE,FALSE,'fb4031e60ab7de2bb44fd1a2493758696dc989d1a1eed459b55e1473708645c6','Lajiwa self-hosted provider eligibility has not been reviewed.')
ON CONFLICT (processor) DO NOTHING;

INSERT INTO public.cyclecare_ai_provider_manifests(processor,display_name,model,policy_url,policy_revision,manifest_revision,manifest_fingerprint,processors,unavailable_reason,upstream,provider_only,allow_fallbacks,data_collection,zdr)
VALUES
 ('LAJIWA','Lajiwa','llama-3.1-8b-instruct-fp8','https://lajiwa.pages.dev/','lajiwa-selfhosted-2026-10','cyclecare-ai-manifest-v3','fb4031e60ab7de2bb44fd1a2493758696dc989d1a1eed459b55e1473708645c6','[{"id":"lajiwa","name":"Lajiwa","policy_url":"https://lajiwa.pages.dev/"}]','Lajiwa self-hosted provider eligibility has not been reviewed.',NULL,NULL,NULL,NULL,NULL)
ON CONFLICT (processor) DO UPDATE SET display_name=EXCLUDED.display_name,model=EXCLUDED.model,policy_url=EXCLUDED.policy_url,policy_revision=EXCLUDED.policy_revision,manifest_revision=EXCLUDED.manifest_revision,manifest_fingerprint=EXCLUDED.manifest_fingerprint,processors=EXCLUDED.processors,unavailable_reason=EXCLUDED.unavailable_reason,upstream=EXCLUDED.upstream,provider_only=EXCLUDED.provider_only,allow_fallbacks=EXCLUDED.allow_fallbacks,data_collection=EXCLUDED.data_collection,zdr=EXCLUDED.zdr;

CREATE OR REPLACE FUNCTION public.set_my_ai_processor_consent_v3(p_processor TEXT, p_enabled BOOLEAN, p_expected_revision INTEGER, p_manifest_fingerprint TEXT, p_manifest_revision TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE v_user UUID:=auth.uid(); v_processor TEXT:=upper(trim(coalesce(p_processor,''))); v_old INTEGER; v_next INTEGER; v_manifest RECORD; v_policy RECORD; v_row RECORD;
BEGIN
 IF v_user IS NULL OR v_processor NOT IN ('COHERE','GEMINI','OPENROUTER','LAJIWA') OR p_enabled IS NULL OR p_expected_revision IS NULL OR p_expected_revision<0 OR p_manifest_revision IS DISTINCT FROM 'cyclecare-ai-manifest-v3' OR (p_enabled AND (p_manifest_fingerprint IS NULL OR p_manifest_fingerprint !~ '^[a-f0-9]{64}$')) THEN RAISE EXCEPTION 'Invalid consent request'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cyclecare-health:'||v_user::text,0));
 INSERT INTO public.cyclecare_ai_processor_consent_clocks(user_id,processor,revision) VALUES(v_user,v_processor,0) ON CONFLICT DO NOTHING;
 SELECT revision INTO v_old FROM public.cyclecare_ai_processor_consent_clocks WHERE user_id=v_user AND processor=v_processor FOR UPDATE;
 IF v_old <> p_expected_revision THEN RAISE EXCEPTION 'revision conflict'; END IF;
 SELECT * INTO v_manifest FROM public.cyclecare_ai_provider_manifests WHERE processor=v_processor;
 SELECT * INTO v_policy FROM public.cyclecare_ai_provider_policy_readiness WHERE processor=v_processor;
 IF p_enabled AND (v_manifest IS NULL OR v_policy IS NULL OR v_manifest.manifest_fingerprint <> p_manifest_fingerprint OR v_policy.manifest_fingerprint <> p_manifest_fingerprint OR NOT v_manifest.eligible OR NOT v_manifest.account_reviewed OR NOT v_manifest.use_case_reviewed OR NOT v_manifest.audience_reviewed OR NOT v_policy.reviewed OR NOT v_policy.eligible) THEN RAISE EXCEPTION 'provider unavailable'; END IF;
 v_next:=v_old+1;
 UPDATE public.cyclecare_ai_processor_consent_clocks SET revision=v_next WHERE user_id=v_user AND processor=v_processor;
 INSERT INTO public.cyclecare_ai_processor_consents(user_id,processor,granted,consent_revision,account_snapshot,manifest_fingerprint,revoked_at,updated_at)
 VALUES(v_user,v_processor,p_enabled,'cyclecare-ai-manifest-v3-'||v_next,v_user,NULLIF(trim(p_manifest_fingerprint),''),CASE WHEN p_enabled THEN NULL ELSE now() END,now())
 ON CONFLICT(user_id,processor) DO UPDATE SET granted=EXCLUDED.granted,consent_revision=EXCLUDED.consent_revision,account_snapshot=EXCLUDED.account_snapshot,manifest_fingerprint=EXCLUDED.manifest_fingerprint,granted_at=CASE WHEN EXCLUDED.granted THEN now() ELSE public.cyclecare_ai_processor_consents.granted_at END,revoked_at=EXCLUDED.revoked_at,updated_at=now() RETURNING * INTO v_row;
 RETURN jsonb_build_object('provider_id',v_processor,'enabled',v_row.granted,'revision',v_next,'manifest_fingerprint',coalesce(v_row.manifest_fingerprint,''),'account_snapshot',v_user,'manifest_revision',p_manifest_revision);
END; $$;
REVOKE ALL ON FUNCTION public.set_my_ai_processor_consent_v3(TEXT,BOOLEAN,INTEGER,TEXT,TEXT) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.set_my_ai_processor_consent_v3(TEXT,BOOLEAN,INTEGER,TEXT,TEXT) TO authenticated;

CREATE OR REPLACE FUNCTION public.erase_ai_processor_consents_v3(p_user UUID) RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
BEGIN
 IF p_user IS NULL THEN RAISE EXCEPTION 'Owner required'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cyclecare-health:'||p_user::text,0));
 INSERT INTO public.cyclecare_ai_processor_consent_clocks(user_id,processor,revision) SELECT p_user,x,1 FROM unnest(ARRAY['COHERE','GEMINI','OPENROUTER','LAJIWA']::text[]) x ON CONFLICT(user_id,processor) DO UPDATE SET revision=public.cyclecare_ai_processor_consent_clocks.revision+1;
 DELETE FROM public.cyclecare_ai_processor_consents WHERE user_id=p_user;
END; $$;
REVOKE ALL ON FUNCTION public.erase_ai_processor_consents_v3(UUID) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.erase_ai_processor_consents_v3(UUID) TO service_role;

CREATE OR REPLACE FUNCTION public.admin_review_ai_provider_manifest(p_processor TEXT, p_eligible BOOLEAN, p_notes TEXT DEFAULT NULL)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE v_processor TEXT:=upper(trim(coalesce(p_processor,''))); v_row RECORD;
BEGIN
 IF NOT COALESCE((current_user IN ('service_role','supabase_admin') OR auth.role()='service_role' OR COALESCE(auth.jwt()->>'role','')='service_role' OR EXISTS (SELECT 1 FROM public.cyclecare_admin_roles WHERE user_id=auth.uid())),FALSE) THEN RAISE EXCEPTION 'Administrator access required'; END IF;
 IF v_processor NOT IN ('COHERE','GEMINI','OPENROUTER','LAJIWA') THEN RAISE EXCEPTION 'Unknown provider'; END IF;
 UPDATE public.cyclecare_ai_provider_manifests SET eligible=p_eligible, account_reviewed=p_eligible, use_case_reviewed=p_eligible, audience_reviewed=p_eligible, reviewed_at=CASE WHEN p_eligible THEN now() ELSE NULL END, reviewed_by=CASE WHEN p_eligible THEN auth.uid() ELSE NULL END, unavailable_reason=CASE WHEN p_eligible THEN '' ELSE coalesce(p_notes,'Provider policy eligibility is not approved.') END WHERE processor=v_processor RETURNING processor,manifest_revision,manifest_fingerprint,eligible,reviewed_at INTO v_row;
 IF v_row IS NULL THEN RAISE EXCEPTION 'Manifest not found'; END IF;
 UPDATE public.cyclecare_ai_provider_policy_readiness SET reviewed=p_eligible, eligible=p_eligible, reviewed_at=CASE WHEN p_eligible THEN now() ELSE NULL END, reviewed_by=CASE WHEN p_eligible THEN auth.uid() ELSE NULL END, notes=CASE WHEN p_eligible THEN NULL ELSE coalesce(p_notes,'Provider policy eligibility is not approved.') END WHERE processor=v_processor;
 RETURN jsonb_build_object('processor',v_row.processor,'manifest_revision',v_row.manifest_revision,'manifest_fingerprint',v_row.manifest_fingerprint,'eligible',v_row.eligible,'reviewed_at',v_row.reviewed_at);
END; $$;
REVOKE ALL ON FUNCTION public.admin_review_ai_provider_manifest(TEXT,BOOLEAN,TEXT) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.admin_review_ai_provider_manifest(TEXT,BOOLEAN,TEXT) TO authenticated,service_role;

CREATE OR REPLACE FUNCTION public.admin_update_runtime_config(p_active_ai_provider TEXT,p_active_mpesa_provider TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE ai TEXT:=upper(trim(COALESCE(p_active_ai_provider,''))); mpesa TEXT:=upper(trim(COALESCE(p_active_mpesa_provider,''))); result JSONB; ready BOOLEAN;
BEGIN
 IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required' USING ERRCODE='42501'; END IF;
 IF ai NOT IN ('COHERE','GEMINI','OPENROUTER','LAJIWA','HYBRID') OR mpesa <> 'PAYHERO' THEN RAISE EXCEPTION 'Unsupported provider selection'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cyclecare-ai-runtime',0));
 IF ai='HYBRID' THEN
   SELECT hybrid_enabled AND hybrid_contract_revision='hybrid-v3' AND hybrid_consent_revision='hybrid-v3' INTO ready FROM public.cyclecare_runtime_config WHERE id=true FOR UPDATE;
   IF ready IS DISTINCT FROM true THEN RAISE EXCEPTION 'Hybrid is not enabled for release'; END IF;
   SELECT count(*)=2 AND bool_and(p.reviewed AND p.eligible AND m.eligible AND m.account_reviewed AND m.use_case_reviewed AND m.audience_reviewed AND p.manifest_fingerprint=m.manifest_fingerprint AND p.policy_revision=m.policy_revision AND m.manifest_revision='cyclecare-ai-manifest-v3')
   INTO ready FROM public.cyclecare_ai_provider_policy_readiness p JOIN public.cyclecare_ai_provider_manifests m USING(processor) WHERE p.processor IN ('COHERE','OPENROUTER');
   IF ready IS DISTINCT FROM true THEN RAISE EXCEPTION 'Hybrid processor review is incomplete'; END IF;
 END IF;
 INSERT INTO public.cyclecare_runtime_config(id,active_ai_provider,active_mpesa_provider,updated_at,updated_by) VALUES(true,ai,mpesa,now(),auth.uid())
 ON CONFLICT(id) DO UPDATE SET active_ai_provider=EXCLUDED.active_ai_provider,active_mpesa_provider=EXCLUDED.active_mpesa_provider,updated_at=now(),updated_by=auth.uid();
 SELECT to_jsonb(c) INTO result FROM public.cyclecare_runtime_config c WHERE id=true;
 RETURN result;
END $$;
REVOKE ALL ON FUNCTION public.admin_update_runtime_config(TEXT,TEXT) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.admin_update_runtime_config(TEXT,TEXT) TO authenticated;
COMMIT;

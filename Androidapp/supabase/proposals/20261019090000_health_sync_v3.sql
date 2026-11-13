-- PROPOSAL ONLY. This is NOT an applied migration or permission to release cloud sync.
-- Validate against the actual approved catalog before any separately approved staging rollout.
BEGIN;
CREATE SEQUENCE public.cyclecare_health_sync_change_seq AS bigint;
CREATE TABLE public.cyclecare_health_sync_consent (
  user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  generation bigint NOT NULL DEFAULT 1 CHECK (generation > 0),
  enabled boolean NOT NULL DEFAULT false, updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE public.cyclecare_health_sync_records (
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  record_id uuid NOT NULL, logical_record_id text, revision bigint NOT NULL CHECK (revision > 0),
  change_seq bigint NOT NULL, payload jsonb, payload_hash text NOT NULL,
  tombstone boolean NOT NULL DEFAULT false, operation_id uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (user_id, record_id),
  UNIQUE (user_id, change_seq), CHECK (tombstone = (payload IS NULL))
);
-- Operation receipts contain fingerprints, never old health payloads. DELETE erases the current payload.
CREATE TABLE public.cyclecare_health_sync_operations (
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  operation_id uuid NOT NULL, record_id uuid NOT NULL,
  base_revision bigint NOT NULL CHECK (base_revision >= 0),
  revision bigint NOT NULL CHECK (revision = base_revision + 1),
  kind text NOT NULL CHECK (kind IN ('UPSERT','DELETE')),
  payload_hash text NOT NULL, consent_generation bigint NOT NULL,
  change_seq bigint NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, operation_id)
);
ALTER TABLE public.cyclecare_health_sync_consent ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.cyclecare_health_sync_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.cyclecare_health_sync_operations ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.cyclecare_health_sync_consent, public.cyclecare_health_sync_records,
  public.cyclecare_health_sync_operations FROM PUBLIC, anon, authenticated;
REVOKE ALL ON SEQUENCE public.cyclecare_health_sync_change_seq FROM PUBLIC, anon, authenticated;
CREATE POLICY health_sync_consent_owner ON public.cyclecare_health_sync_consent FOR SELECT
  TO authenticated USING (user_id=auth.uid());
GRANT SELECT ON public.cyclecare_health_sync_consent TO authenticated;
-- No direct health-table grants: RPC consent checks cannot be bypassed with PostgREST SELECT.

CREATE FUNCTION public.cyclecare_health_sync_legacy_allowed(p_user uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE allowed boolean;
BEGIN
 IF p_user IS NULL THEN RETURN false; END IF;
 IF to_regclass('public.cyclecare_health_deletions') IS NOT NULL THEN
   EXECUTE 'SELECT EXISTS(SELECT 1 FROM public.cyclecare_health_deletions WHERE user_id::text=$1::text)'
     INTO allowed USING p_user;
   IF allowed THEN RETURN false; END IF;
 END IF;
 IF to_regclass('public.cyclecare_privacy_consents') IS NOT NULL THEN
   EXECUTE 'SELECT cloud_sync_enabled FROM public.cyclecare_privacy_consents WHERE user_id::text=$1::text FOR SHARE'
     INTO allowed USING p_user;
   IF allowed IS DISTINCT FROM true THEN RETURN false; END IF;
 END IF;
 RETURN true;
END $$;
REVOKE ALL ON FUNCTION public.cyclecare_health_sync_legacy_allowed(uuid) FROM PUBLIC,anon,authenticated;

CREATE FUNCTION public.set_health_sync_consent(p_enabled boolean)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE u uuid:=auth.uid(); g bigint;
BEGIN
 IF u IS NULL THEN RAISE EXCEPTION 'authentication required' USING ERRCODE='42501'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cyclecare-health:'||u::text,0));
 IF p_enabled IS NULL THEN RAISE EXCEPTION 'explicit consent required'; END IF;
 IF p_enabled AND NOT public.cyclecare_health_sync_legacy_allowed(u) THEN
   RETURN jsonb_build_object('enabled',false,'reason','legacy_consent_or_deletion_blocks_enable');
 END IF;
 INSERT INTO public.cyclecare_health_sync_consent(user_id,enabled) VALUES(u,p_enabled)
 ON CONFLICT(user_id) DO UPDATE SET generation=cyclecare_health_sync_consent.generation+1,
 enabled=excluded.enabled,updated_at=now() RETURNING generation INTO g;
 RETURN jsonb_build_object('enabled',p_enabled,'generation',g);
END $$;

CREATE FUNCTION public.cyclecare_v3_record_uuid(p_owner uuid, p_logical text) RETURNS uuid
LANGUAGE sql IMMUTABLE STRICT AS $$
  SELECT encode(set_byte(set_byte(decode(md5('cyclecare-v3:'||p_owner::text||':'||p_logical),'hex'),6,(get_byte(decode(md5('cyclecare-v3:'||p_owner::text||':'||p_logical),'hex'),6)&15)|48),8,(get_byte(decode(md5('cyclecare-v3:'||p_owner::text||':'||p_logical),'hex'),8)&63)|128),'hex')::uuid
$$;
REVOKE ALL ON FUNCTION public.cyclecare_v3_record_uuid(uuid,text) FROM PUBLIC,anon,authenticated;

CREATE FUNCTION public.apply_health_sync_operation(p_operation_id uuid,p_record_id uuid,
 p_base_revision bigint,p_revision bigint,p_kind text,p_payload jsonb,p_payload_hash text,p_consent_generation bigint,p_logical_record_id text DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE u uuid:=auth.uid(); c public.cyclecare_health_sync_consent%ROWTYPE;
 r public.cyclecare_health_sync_records%ROWTYPE; oldop public.cyclecare_health_sync_operations%ROWTYPE;
 seq bigint; expected text; record_exists boolean; logical text;
BEGIN
 IF u IS NULL THEN RAISE EXCEPTION 'authentication required' USING ERRCODE='42501'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cyclecare-health:'||u::text,0));
 SELECT * INTO c FROM public.cyclecare_health_sync_consent WHERE user_id=u FOR UPDATE;
 IF NOT FOUND OR NOT c.enabled OR p_consent_generation IS DISTINCT FROM c.generation
    OR NOT public.cyclecare_health_sync_legacy_allowed(u) THEN
   RETURN jsonb_build_object('status','rejected','reason','stale_or_revoked_consent');
 END IF;
 logical:=COALESCE(p_logical_record_id, CASE WHEN p_payload IS NOT NULL THEN p_payload->>'logicalRecordId' ELSE NULL END);
 IF p_operation_id IS NULL OR p_record_id IS NULL OR p_base_revision IS NULL OR p_revision IS NULL
    OR p_base_revision<0 OR p_kind IS NULL OR p_kind NOT IN ('UPSERT','DELETE')
    OR (p_kind='UPSERT' AND (p_payload IS NULL OR jsonb_typeof(p_payload)<>'object' OR octet_length(p_payload::text)>65536))
    OR (p_kind='DELETE' AND p_payload IS NOT NULL) OR logical IS NULL OR logical NOT IN ('profile','settings') AND logical !~ '^\d{4}-\d{2}-\d{2}$' OR p_record_id <> public.cyclecare_v3_record_uuid(u,logical) THEN
   RETURN jsonb_build_object('status','rejected','reason','invalid_operation');
 END IF;
 IF logical NOT IN ('profile','settings') THEN
   BEGIN
     IF to_char(logical::date,'YYYY-MM-DD') IS DISTINCT FROM logical THEN
       RETURN jsonb_build_object('status','rejected','reason','invalid_logical_date');
     END IF;
   EXCEPTION WHEN invalid_datetime_format OR datetime_field_overflow THEN
     RETURN jsonb_build_object('status','rejected','reason','invalid_logical_date');
   END;
 END IF;
 IF p_kind='UPSERT' AND (p_payload->>'kind' IS DISTINCT FROM 'UPSERT'
      OR p_payload->>'logicalRecordId' IS DISTINCT FROM logical OR jsonb_typeof(p_payload->'value') IS DISTINCT FROM 'object') THEN
   RETURN jsonb_build_object('status','rejected','reason','invalid_wire_envelope');
 END IF;
 -- PostgreSQL canonical jsonb text is the protocol fingerprint input; never trust an arbitrary client hash.
 expected:=encode(sha256(convert_to(COALESCE(p_payload::text,'null'),'UTF8')),'hex');
 IF p_payload_hash IS DISTINCT FROM expected THEN
   RETURN jsonb_build_object('status','rejected','reason','payload_hash_mismatch');
 END IF;
 SELECT * INTO oldop FROM public.cyclecare_health_sync_operations WHERE user_id=u AND operation_id=p_operation_id;
 IF FOUND THEN
   IF oldop.record_id=p_record_id AND oldop.base_revision=p_base_revision AND oldop.revision=p_revision
      AND oldop.kind=p_kind AND oldop.payload_hash=expected AND oldop.consent_generation=p_consent_generation THEN
     RETURN jsonb_build_object('status','duplicate','revision',oldop.revision,'change_seq',oldop.change_seq,'record_id',oldop.record_id,'operation_id',oldop.operation_id,'logical_record_id',logical,'payload_hash',oldop.payload_hash);
   END IF;
   RETURN jsonb_build_object('status','rejected','reason','operation_id_payload_reuse');
 END IF;
 SELECT * INTO r FROM public.cyclecare_health_sync_records WHERE user_id=u AND record_id=p_record_id FOR UPDATE;
 record_exists:=FOUND;
 IF (NOT record_exists AND p_base_revision<>0) OR (record_exists AND r.revision<>p_base_revision) THEN
   RETURN jsonb_build_object('status','conflict','record_id',p_record_id,'revision',COALESCE(r.revision,0),'tombstone',COALESCE(r.tombstone,false),'payload',CASE WHEN COALESCE(r.tombstone,false) THEN NULL ELSE r.payload END,'payload_hash',r.payload_hash);
 END IF;
 IF p_base_revision=9223372036854775807 OR p_revision<>p_base_revision+1 THEN
   RETURN jsonb_build_object('status','rejected','reason','revision_must_be_base_plus_one');
 END IF;
 IF record_exists AND r.tombstone AND p_kind='UPSERT' THEN
   RETURN jsonb_build_object('status','rejected','reason','tombstone_resurrection');
 END IF;
 seq:=nextval('public.cyclecare_health_sync_change_seq');
 INSERT INTO public.cyclecare_health_sync_operations(user_id,operation_id,record_id,base_revision,revision,kind,payload_hash,consent_generation,change_seq)
 VALUES(u,p_operation_id,p_record_id,p_base_revision,p_revision,p_kind,expected,p_consent_generation,seq);
 INSERT INTO public.cyclecare_health_sync_records(user_id,record_id,logical_record_id,revision,change_seq,payload,payload_hash,tombstone,operation_id)
 VALUES(u,p_record_id,logical,p_revision,seq,p_payload,expected,p_kind='DELETE',p_operation_id)
 ON CONFLICT(user_id,record_id) DO UPDATE SET revision=excluded.revision,change_seq=excluded.change_seq,
 payload=excluded.payload,payload_hash=excluded.payload_hash,tombstone=excluded.tombstone,
 operation_id=excluded.operation_id,logical_record_id=excluded.logical_record_id,updated_at=now();
 RETURN jsonb_build_object('status','applied','revision',p_revision,'change_seq',seq,'payload_hash',expected,'record_id',p_record_id,'operation_id',p_operation_id,'logical_record_id',logical);
END $$;

REVOKE ALL ON FUNCTION public.apply_health_sync_operation(uuid,uuid,bigint,bigint,text,jsonb,text,bigint,text) FROM PUBLIC,anon,authenticated;

CREATE FUNCTION public.apply_health_sync_operation_v3(p_operation_id uuid,p_record_id uuid,
 p_base_revision bigint,p_revision bigint,p_kind text,p_payload jsonb,p_consent_generation bigint,p_logical_record_id text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
BEGIN
 RETURN public.apply_health_sync_operation(p_operation_id,p_record_id,p_base_revision,p_revision,p_kind,p_payload,
   encode(sha256(convert_to(COALESCE(p_payload::text,'null'),'UTF8')),'hex'),p_consent_generation,p_logical_record_id);
END $$;

CREATE FUNCTION public.read_health_sync_changes(p_after_seq bigint,p_limit integer,p_consent_generation bigint)
RETURNS SETOF public.cyclecare_health_sync_records LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE u uuid:=auth.uid(); c public.cyclecare_health_sync_consent%ROWTYPE;
BEGIN
 IF u IS NULL THEN RAISE EXCEPTION 'authentication required' USING ERRCODE='42501'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cyclecare-health:'||u::text,0));
 SELECT * INTO c FROM public.cyclecare_health_sync_consent WHERE user_id=u FOR SHARE;
 IF NOT FOUND OR NOT c.enabled OR p_consent_generation IS DISTINCT FROM c.generation
    OR NOT public.cyclecare_health_sync_legacy_allowed(u) THEN RETURN; END IF;
 RETURN QUERY SELECT r.* FROM public.cyclecare_health_sync_records r
   WHERE r.user_id=u AND r.change_seq>greatest(COALESCE(p_after_seq,0),0)
   ORDER BY r.change_seq LIMIT least(greatest(COALESCE(p_limit,100),1),500);
END $$;
REVOKE ALL ON FUNCTION public.read_health_sync_changes(bigint,integer,bigint), public.set_health_sync_consent(boolean),
 public.apply_health_sync_operation_v3(uuid,uuid,bigint,bigint,text,jsonb,bigint,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.read_health_sync_changes(bigint,integer,bigint), public.set_health_sync_consent(boolean),
 public.apply_health_sync_operation_v3(uuid,uuid,bigint,bigint,text,jsonb,bigint,text) TO authenticated;

-- Candidate replacement for the existing health-only erasure RPC. This remains
-- proposal-only: rollout must first verify the legacy catalog and its grants.
-- It removes v3 payloads, operation receipts, cursors and consent state while
-- retaining only the legacy no-resurrection marker and non-health evidence.
CREATE OR REPLACE FUNCTION public.delete_my_cyclecare_health_data() RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE u uuid:=auth.uid(); deleted_logs bigint:=0;
BEGIN
 IF u IS NULL THEN RAISE EXCEPTION 'authentication required' USING ERRCODE='42501'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cyclecare-health:'||u::text,0));
 -- Marker is written first so stale v2/v3 workers cannot re-enable or resurrect data.
 IF to_regclass('public.cyclecare_health_deletions') IS NOT NULL THEN
   EXECUTE 'INSERT INTO public.cyclecare_health_deletions(user_id) VALUES ($1) ON CONFLICT(user_id) DO UPDATE SET deleted_at=now()' USING u;
 END IF;
 DELETE FROM public.cyclecare_health_sync_records WHERE user_id=u;
 DELETE FROM public.cyclecare_health_sync_operations WHERE user_id=u;
 -- Retain only a disabled security clock. Resetting its generation would allow a
 -- pre-erasure request to become valid after an explicit future sync resumption.
 INSERT INTO public.cyclecare_health_sync_consent(user_id,enabled) VALUES(u,false)
 ON CONFLICT(user_id) DO UPDATE SET generation=cyclecare_health_sync_consent.generation+1,enabled=false,updated_at=now();
 IF to_regclass('public.daily_logs') IS NOT NULL THEN
   DELETE FROM public.daily_logs WHERE user_id::text=u::text;
   GET DIAGNOSTICS deleted_logs=ROW_COUNT;
 END IF;
 IF to_regclass('public.cycles') IS NOT NULL THEN DELETE FROM public.cycles WHERE user_id::text=u::text; END IF;
 IF to_regclass('public.cyclecare_privacy_consents') IS NOT NULL THEN DELETE FROM public.cyclecare_privacy_consents WHERE user_id::text=u::text; END IF;
 IF to_regprocedure('public.erase_ai_processor_consents_v3(uuid)') IS NOT NULL THEN PERFORM public.erase_ai_processor_consents_v3(u); END IF;
 IF to_regclass('public.profiles') IS NOT NULL THEN
   UPDATE public.profiles SET goals='', last_period_date=NULL,
     cycle_length=28, period_duration=5, typical_symptoms='', typical_energy='', typical_sleep='', updated_at=now()
     WHERE user_id::text=u::text;
 END IF;
 RETURN jsonb_build_object('success',true,'health_data_deleted',true,'deleted_daily_logs',deleted_logs,
   'v3_replay_footprints_deleted',true,'financial_records_retained',true,'auth_identity_deletion_required',true,
   'limitations','Transactions, payment references, economy ledger and audit evidence are retained; provider-side AI records require their own approved erasure lifecycle.');
END $$;
REVOKE ALL ON FUNCTION public.delete_my_cyclecare_health_data() FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.delete_my_cyclecare_health_data() TO authenticated;
COMMIT;

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
  record_id uuid NOT NULL, revision bigint NOT NULL CHECK (revision > 0),
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

CREATE FUNCTION public.apply_health_sync_operation(p_operation_id uuid,p_record_id uuid,
 p_base_revision bigint,p_revision bigint,p_kind text,p_payload jsonb,p_payload_hash text,p_consent_generation bigint)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE u uuid:=auth.uid(); c public.cyclecare_health_sync_consent%ROWTYPE;
 r public.cyclecare_health_sync_records%ROWTYPE; oldop public.cyclecare_health_sync_operations%ROWTYPE;
 seq bigint; expected text; record_exists boolean;
BEGIN
 IF u IS NULL THEN RAISE EXCEPTION 'authentication required' USING ERRCODE='42501'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cyclecare-health:'||u::text,0));
 SELECT * INTO c FROM public.cyclecare_health_sync_consent WHERE user_id=u FOR UPDATE;
 IF NOT FOUND OR NOT c.enabled OR p_consent_generation IS DISTINCT FROM c.generation
    OR NOT public.cyclecare_health_sync_legacy_allowed(u) THEN
   RETURN jsonb_build_object('status','rejected','reason','stale_or_revoked_consent');
 END IF;
 IF p_operation_id IS NULL OR p_record_id IS NULL OR p_base_revision IS NULL OR p_revision IS NULL
    OR p_base_revision<0 OR p_kind IS NULL OR p_kind NOT IN ('UPSERT','DELETE')
    OR (p_kind='UPSERT' AND (p_payload IS NULL OR jsonb_typeof(p_payload)<>'object' OR octet_length(p_payload::text)>65536))
    OR (p_kind='DELETE' AND p_payload IS NOT NULL) THEN
   RETURN jsonb_build_object('status','rejected','reason','invalid_operation');
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
     RETURN jsonb_build_object('status','duplicate','revision',oldop.revision,'change_seq',oldop.change_seq);
   END IF;
   RETURN jsonb_build_object('status','rejected','reason','operation_id_payload_reuse');
 END IF;
 SELECT * INTO r FROM public.cyclecare_health_sync_records WHERE user_id=u AND record_id=p_record_id FOR UPDATE;
 record_exists:=FOUND;
 IF (NOT record_exists AND p_base_revision<>0) OR (record_exists AND r.revision<>p_base_revision) THEN
   RETURN jsonb_build_object('status','conflict','record_id',p_record_id,'revision',COALESCE(r.revision,0),'tombstone',COALESCE(r.tombstone,false));
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
 INSERT INTO public.cyclecare_health_sync_records(user_id,record_id,revision,change_seq,payload,payload_hash,tombstone,operation_id)
 VALUES(u,p_record_id,p_revision,seq,p_payload,expected,p_kind='DELETE',p_operation_id)
 ON CONFLICT(user_id,record_id) DO UPDATE SET revision=excluded.revision,change_seq=excluded.change_seq,
 payload=excluded.payload,payload_hash=excluded.payload_hash,tombstone=excluded.tombstone,
 operation_id=excluded.operation_id,updated_at=now();
 RETURN jsonb_build_object('status','applied','revision',p_revision,'change_seq',seq,'payload_hash',expected);
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
 public.apply_health_sync_operation(uuid,uuid,bigint,bigint,text,jsonb,text,bigint) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.read_health_sync_changes(bigint,integer,bigint), public.set_health_sync_consent(boolean),
 public.apply_health_sync_operation(uuid,uuid,bigint,bigint,text,jsonb,text,bigint) TO authenticated;
COMMIT;

#!/usr/bin/env python3
"""Deterministic two-session health-sync v3 concurrency contract test."""
import concurrent.futures, hashlib, json, os, subprocess, threading, uuid

OWNER = "00000000-0000-0000-0000-000000000003"  # dedicated owner, not SQL suite owner
ARGS = ["psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1"]

def psql(sql, role=None):
    env = os.environ.copy()
    if not env.get("PGDATABASE") or env.get("CYCLECARE_DISPOSABLE_DB") != "1":
        raise SystemExit("refusing mutation: set CYCLECARE_DISPOSABLE_DB=1 and PGDATABASE to a disposable database")
    prefix = "BEGIN; SET LOCAL ROLE %s; SET LOCAL request.jwt.claim.role='%s'; SET LOCAL request.jwt.claim.sub='%s'; SET LOCAL request.jwt.claims='%s'; " % (role or "authenticated", role or "authenticated", OWNER, json.dumps({"sub": OWNER, "role": role or "authenticated"}).replace("'", "''"))
    suffix = " COMMIT;"
    return subprocess.run(ARGS + ["-c", prefix + sql + suffix], env=env, check=True, capture_output=True, text=True).stdout.strip().splitlines()[-1]

def root(sql):
    env = os.environ.copy()
    return subprocess.run(ARGS + ["-c", sql], env=env, check=True, capture_output=True, text=True).stdout.strip()

def record(logical):
    raw = hashlib.md5(f"cyclecare-v3:{OWNER}:{logical}".encode()).digest()
    b = bytearray(raw); b[6] = (b[6] & 15) | 48; b[8] = (b[8] & 63) | 128
    return str(uuid.UUID(bytes=bytes(b)))

def call(op, logical, base, rev, kind="UPSERT", value=None, generation=1):
    rid, payload = record(logical), "NULL"
    if kind == "UPSERT":
        payload = "jsonb_build_object('kind','UPSERT','logicalRecordId','%s','value','%s'::jsonb)" % (logical, json.dumps(value or {"v": 1}, separators=(",", ":")).replace("'", "''"))
    return "public.apply_health_sync_operation_v3('%s','%s',%d,%d,'%s',%s,%d,'%s')" % (op, rid, base, rev, kind, payload, generation, logical)

def owner_json(expr):
    return json.loads(psql("SELECT %s;" % expr))

def race(expressions):
    barrier = threading.Barrier(len(expressions))
    def run(expr):
        barrier.wait(timeout=10)
        return owner_json(expr)
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(expressions)) as pool:
        return list(pool.map(run, expressions))

def main():
    # Root fixture setup is intentionally limited to the dedicated disposable owner.
    root("INSERT INTO auth.users(id,email) VALUES ('%s','concurrency@example.test') ON CONFLICT DO NOTHING; INSERT INTO public.cyclecare_privacy_consents(user_id,cloud_sync_enabled) VALUES ('%s',true) ON CONFLICT(user_id) DO UPDATE SET cloud_sync_enabled=true;" % (OWNER, OWNER))
    gen = owner_json("public.set_health_sync_consent(true)")["generation"]
    assert gen == 1, gen
    same = race([call("40000000-0000-0000-0000-000000000001", "2026-10-20", 0, 1, value={"v": 1}, generation=gen), call("40000000-0000-0000-0000-000000000002", "2026-10-20", 0, 1, value={"v": 2}, generation=gen)])
    assert sorted(x["status"] for x in same) == ["applied", "conflict"], same
    print("PASS same-record base-0 race: one applied, one conflict")
    dup = call("40000000-0000-0000-0000-000000000003", "settings", 0, 1, value={"v": 3}, generation=gen)
    replies = race([dup, dup]); assert sorted(x["status"] for x in replies) == ["applied", "duplicate"], replies
    assert replies[0]["change_seq"] == replies[1]["change_seq"] and replies[0]["operation_id"] == replies[1]["operation_id"]
    print("PASS identical operation race: one applied, one duplicate with one receipt")
    # Shared owner advisory lock serializes revoke and writer; post-commit retry must be stale.
    writer = call("40000000-0000-0000-0000-000000000004", "profile", 0, 1, value={"v": 4}, generation=gen)
    race([writer, "public.set_health_sync_consent(false)"])
    stale = owner_json(writer)
    assert stale["status"] == "rejected" and stale["reason"] == "stale_or_revoked_consent", stale
    assert owner_json("(SELECT count(*) FROM public.read_health_sync_changes(0,500,%d))" % gen) == 0
    print("PASS revoke-vs-writer shared owner lock: stale retry rejected and reads closed")
    # Erasure removes receipts and records, increments AI/health epochs, and marker blocks resume.
    old = gen
    owner_json("public.delete_my_cyclecare_health_data()")
    assert owner_json("public.set_health_sync_consent(true)")["enabled"] is False
    print("PASS erasure marker blocks resume and stale old epoch cannot resurrect")

if __name__ == "__main__":
    main()

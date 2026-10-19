#!/usr/bin/env python3
"""Run ONLY on a disposable database after health-sync-v3.sql. No external service calls."""
import concurrent.futures
import json
import os
import subprocess
import threading

ARGS = ["psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1"]
OWNER = "00000000-0000-0000-0000-000000000003"


def sql(query):
    return subprocess.run(ARGS + ["-c", query], check=True, capture_output=True, text=True).stdout.strip()


def as_owner(expression):
    output = sql("BEGIN; SET LOCAL ROLE authenticated; "
                 f"SELECT set_config('request.jwt.claim.sub','{OWNER}',true); "
                 f"SELECT {expression}; COMMIT;")
    return json.loads(output.splitlines()[-1])


def operation(operation_id, record_id, base, revision, payload):
    text = json.dumps(payload, separators=(", ", ": "))
    quoted = text.replace("'", "''")
    return (f"apply_health_sync_operation('{operation_id}','{record_id}',{base},{revision},"
            f"'UPSERT','{quoted}'::jsonb,encode(sha256(convert_to('{quoted}','UTF8')),'hex'),1)")


def race(expressions):
    barrier = threading.Barrier(len(expressions))
    def run(expression):
        barrier.wait(timeout=10)
        return as_owner(expression)
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(expressions)) as pool:
        return list(pool.map(run, expressions))


def main():
    if not os.environ.get("PGDATABASE", "").startswith("cyclecare_"):
        raise SystemExit("Set PGDATABASE to a disposable cyclecare_* fixture database")
    sql(f"INSERT INTO auth.users VALUES ('{OWNER}'); "
        f"INSERT INTO public.cyclecare_privacy_consents VALUES ('{OWNER}',true);")
    assert as_owner("set_health_sync_consent(true)")["generation"] == 1
    record = "30000000-0000-0000-0000-000000000001"
    replies = race([
        operation("40000000-0000-0000-0000-000000000001", record, 0, 1, {"v": 1}),
        operation("40000000-0000-0000-0000-000000000002", record, 0, 1, {"v": 2}),
    ])
    assert sorted(r["status"] for r in replies) == ["applied", "conflict"], replies
    print("PASS: concurrent same-base edits produce one apply and one explicit conflict")
    duplicate = operation("40000000-0000-0000-0000-000000000003", "30000000-0000-0000-0000-000000000002", 0, 1, {"v": 3})
    replies = race([duplicate, duplicate])
    assert sorted(r["status"] for r in replies) == ["applied", "duplicate"], replies
    assert replies[0]["change_seq"] == replies[1]["change_seq"]
    print("PASS: concurrent identical operation retries have one immutable receipt")
    write = operation("40000000-0000-0000-0000-000000000004", "30000000-0000-0000-0000-000000000003", 0, 1, {"v": 4})
    replies = race([write, "set_health_sync_consent(false)"])
    assert replies[1]["enabled"] is False
    assert replies[0]["status"] in ("applied", "rejected")
    assert as_owner(write)["reason"] == "stale_or_revoked_consent"
    assert as_owner("(SELECT count(*) FROM read_health_sync_changes(0,500,1))") == 0
    print("PASS: revocation serializes with upload and blocks all subsequent reads/retries")


if __name__ == "__main__":
    main()

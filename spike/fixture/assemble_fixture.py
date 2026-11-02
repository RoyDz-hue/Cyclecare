#!/usr/bin/env python3
"""Spike S3: build the authoritative fixture SQL from the REAL Room schema dump
(sqlite_master + identity hash, produced by SpikeRoomSchemaDumpTest) plus the
committed synthetic rows. Also diff the hand-transcribed fixture DDL against
the Room-real schema: table/column mismatches are a hard failure; DEFAULT
clause drift is logged (Room emits none for these entities)."""
import re, sys

DUMP = "Androidapp/app/build/spike/room-schema-dump.txt"
ROWS = "spike/fixture/cyclecare-fixture-v9-rows.sql"
TRANSCRIBED = "spike/fixture/cyclecare-fixture-v9-transcribed.sql"
OUT_SQL = "spike/out/fixture-authoritative.sql"
OUT_DIFF = "spike/out/fixture-diff.txt"

tables_ddl, indexes_ddl, identity_hash = {}, [], None
real_cols = {}
with open(DUMP) as f:
    for line in f:
        parts = line.rstrip("\n").split("\t")
        if parts[0] == "MASTER" and parts[1] == "table":
            tables_ddl[parts[2]] = parts[3]
        elif parts[0] == "MASTER" and parts[1] == "index" and len(parts) > 3:
            indexes_ddl.append(parts[3])
        elif parts[0] == "IDENTITY_HASH":
            identity_hash = parts[1]
        elif parts[0] == "COL":
            real_cols.setdefault(parts[1], []).append((parts[2], parts[3], parts[4], parts[5], parts[6]))

assert identity_hash, "no identity hash in dump"
expected_tables = {"cycle_day_logs", "cycle_settings", "onboarding_profiles",
                   "health_sync_revisions", "health_sync_outbox", "health_sync_cursors",
                   "health_sync_conflicts", "room_master_table"}
missing = expected_tables - set(tables_ddl)
assert not missing, f"dump missing tables: {missing}"

with open(OUT_SQL, "w") as out:
    out.write("-- Authoritative fixture: DDL from the real Room stack (sqlite_master),\n")
    out.write("-- identity hash written by Room itself, rows synthetic.\n")
    for name in sorted(tables_ddl):
        out.write(tables_ddl[name] + ";\n")
    for ddl in indexes_ddl:
        out.write(ddl + ";\n")
    out.write(open(ROWS).read())
    out.write(f"INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42,'{identity_hash}');\n")

def transcribed_cols(sql_text, table):
    m = re.search(r"CREATE TABLE IF NOT EXISTS `%s` \((.*?)\);" % re.escape(table), sql_text, re.S)
    if not m:
        return None
    cols = []
    for raw in m.group(1).split(","):
        raw = raw.strip()
        cm = re.match(r"`(\w+)` (\w+)", raw)
        if cm:
            default = "DEFAULT" in raw.upper()
            cols.append((cm.group(1), cm.group(2).upper(), default))
    return cols

tr = open(TRANSCRIBED).read()
report, hard_fail = [], False
for table in sorted(expected_tables - {"room_master_table"}):
    tc = transcribed_cols(tr, table)
    rc = real_cols.get(table, [])
    if tc is None:
        report.append(f"{table}: MISSING from transcription")
        hard_fail = True
        continue
    real_names = [c[0] for c in rc]
    tr_names = [c[0] for c in tc]
    if real_names != tr_names:
        report.append(f"{table}: COLUMN MISMATCH real={real_names} transcribed={tr_names}")
        hard_fail = True
        continue
    real_types = {c[0]: c[1].upper() for c in rc}
    type_diffs = [(n, t, real_types.get(n)) for (n, t, _) in tc if real_types.get(n) != t.upper()]
    defaults_in_transcription = [c[0] for c in tc if c[2]]
    msg = f"{table}: columns match ({len(rc)})"
    if type_diffs:
        msg += f"; TYPE DRIFT {type_diffs}"
    if defaults_in_transcription:
        msg += f"; transcription adds DEFAULTs on {defaults_in_transcription} (Room emits none - drift, tolerated: Room skips default comparison when entity declares none)"
    report.append(msg)

with open(OUT_DIFF, "w") as f:
    f.write("\n".join(report) + "\n")
print("\n".join(report))
print("IDENTITY_HASH", identity_hash)
if hard_fail:
    print("FIXTURE_DIFF_HARD_FAIL")
    sys.exit(1)
print("FIXTURE_DIFF_OK")

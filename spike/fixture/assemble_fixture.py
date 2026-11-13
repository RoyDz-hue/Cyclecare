#!/usr/bin/env python3
"""Spike S3: build the fixture SQL from a schema source plus the committed
synthetic rows (spike/fixture/cyclecare-fixture-v9-rows.sql is the ONLY row
source - never mix in the transcribed file's INSERT lines, which exist only
for the column-diff check).

Two modes:
  1. ROOM-VERIFIED (default): DDL + identity hash come from the real Room
     schema dump (Androidapp/app/build/spike/room-schema-dump.txt, produced by
     SpikeRoomSchemaDumpTest). Output is authoritative.
  2. TRANSCRIPTION-GRADE (--transcribed): used when no dump exists (e.g. local
     runs without an Android toolchain). DDL + identity hash come from the
     hand transcription, INSERT lines are stripped, and every output line is
     labeled TRANSCRIPTION-GRADE. Parity proven against this fixture is parity
     vs hand transcription, NOT vs Room.

Both modes also diff the transcribed DDL against the DDL actually used:
table/column mismatches are a hard failure; DEFAULT-clause drift is logged
(Room emits none for these entities)."""
import re, sys

TRANSCRIBED_MODE = "--transcribed" in sys.argv

DUMP = "Androidapp/app/build/spike/room-schema-dump.txt"
ROWS = "spike/fixture/cyclecare-fixture-v9-rows.sql"
TRANSCRIBED = "spike/fixture/cyclecare-fixture-v9-transcribed.sql"
OUT_SQL = "spike/out/fixture-authoritative.sql"
OUT_DIFF = "spike/out/fixture-diff.txt"

expected_tables = {"cycle_day_logs", "cycle_settings", "onboarding_profiles",
                   "health_sync_revisions", "health_sync_outbox", "health_sync_cursors",
                   "health_sync_conflicts", "room_master_table"}

tables_ddl, indexes_ddl, identity_hash = {}, [], None
real_cols = {}

if not TRANSCRIBED_MODE:
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
else:
    tr_text = open(TRANSCRIBED).read()
    # DDL only: CREATE TABLE / CREATE INDEX statements; INSERT lines stripped.
    # Backticks optional (room_master_table has none in the transcription).
    for m in re.finditer(r"CREATE TABLE IF NOT EXISTS `?(\w+)`? \((.*?)\);", tr_text, re.S):
        tables_ddl[m.group(1)] = m.group(0).rstrip(";")
    for m in re.finditer(r"CREATE INDEX IF NOT EXISTS[^;]*;", tr_text):
        indexes_ddl.append(m.group(0).rstrip(";"))
    hm = re.search(r"INSERT INTO room_master_table VALUES \(42,'([^']+)'\);", tr_text)
    assert hm, "no room_master_table identity hash in transcription"
    identity_hash = hm.group(1)
    for table, ddl in tables_ddl.items():
        body = ddl[ddl.index("(") + 1: ddl.rindex(")")]
        cols = []
        for raw in body.split(","):
            raw = raw.strip()
            if re.match(r"(?i)^(PRIMARY|FOREIGN|UNIQUE|CONSTRAINT|CHECK)\b", raw):
                continue  # table constraint, not a column
            cm = re.match(r"`(\w+)` (\w+)|^(\w+) (\w+)", raw)
            if cm:
                name = cm.group(1) or cm.group(3)
                typ = cm.group(2) or cm.group(4)
                cols.append((name, typ.upper(), "", "", ""))
        real_cols[table] = cols

missing = expected_tables - set(tables_ddl)
assert not missing, f"schema source missing tables: {missing}"

label = "TRANSCRIPTION-GRADE (hand transcription, NOT Room-verified)" if TRANSCRIBED_MODE \
        else "ROOM-VERIFIED (sqlite_master from the real Room stack)"
with open(OUT_SQL, "w") as out:
    out.write(f"-- Fixture: {label}\n")
    out.write("-- Rows: synthetic (spike/fixture/cyclecare-fixture-v9-rows.sql only).\n")
    for name in sorted(tables_ddl):
        out.write(tables_ddl[name].rstrip(";") + ";\n")
    for ddl in indexes_ddl:
        out.write(ddl.rstrip(";") + ";\n")
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
report, hard_fail = [f"MODE {label}"], False
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
        report.append(f"{table}: COLUMN MISMATCH used={real_names} transcribed={tr_names}")
        hard_fail = True
        continue
    used_types = {c[0]: c[1].upper() for c in rc}
    type_diffs = [(n, t, used_types.get(n)) for (n, t, _) in tc if used_types.get(n) != t.upper()]
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

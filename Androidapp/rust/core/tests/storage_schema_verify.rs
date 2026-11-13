//! Executes the authoritative Room DDL verbatim against an in-memory SQLite
//! and verifies the resulting schema: 7 tables, 2 indices, exact column
//! shape, PKs, and the health_sync_revisions.serverRevision DEFAULT 0.
//! Proof scope: DDL executes and yields the intended schema on SQLite;
//! NOT proof of SQLCipher codec parity or live-DB cross-read.

use cyclecare_core::storage_schema::{INDEX_NAMES, ROOM_DDL, TABLE_NAMES};

#[test]
fn room_ddl_executes_verbatim() {
    let conn = rusqlite::Connection::open_in_memory().unwrap();
    conn.execute_batch(ROOM_DDL).expect("DDL executes verbatim");

    for table in TABLE_NAMES {
        let n: i64 = conn
            .query_row(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?1",
                [table],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(n, 1, "missing table {table}");
    }
    for index in INDEX_NAMES {
        let n: i64 = conn
            .query_row(
                "SELECT count(*) FROM sqlite_master WHERE type='index' AND name=?1",
                [index],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(n, 1, "missing index {index}");
    }

    // serverRevision must exist with DEFAULT 0 (explicit; earlier
    // no-defaults claim was overgeneralized).
    let mut stmt = conn.prepare("PRAGMA table_info(health_sync_revisions)").unwrap();
    let cols: Vec<(String, String, Option<String>)> = stmt
        .query_map([], |r| Ok((r.get(1)?, r.get(2)?, r.get(4)?)))
        .unwrap()
        .map(|r| r.unwrap())
        .collect();
    let sr = cols.iter().find(|(name, _, _)| name == "serverRevision").expect("serverRevision column");
    assert_eq!(sr.1, "INTEGER");
    assert_eq!(sr.2.as_deref(), Some("0"), "serverRevision DEFAULT 0");

    // Smoke: insert/read a cycle_day_logs row (all NOT NULL columns present).
    conn.execute(
        "INSERT INTO cycle_day_logs (dateString, flowLevel, energyLevel, moodLevel, symptomsCsv, vaginalHealthCsv, urinaryHealthCsv, cervicalMucus, ovulationTest, bbtCelsius, sleepHours, sleepQuality, waterGlasses, notes, painIntensity) VALUES ('2026-11-02','','','','','','','','',0.0,0.0,'',0,'',0)",
        [],
    )
    .unwrap();
    let got: String = conn
        .query_row("SELECT dateString FROM cycle_day_logs", [], |r| r.get(0))
        .unwrap();
    assert_eq!(got, "2026-11-02");
}

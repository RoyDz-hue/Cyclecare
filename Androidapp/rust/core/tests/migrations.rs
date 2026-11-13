//! Migration 4->5 PIN sweep transitions + byte-exact SQL fixture lock.
//! SQL expectations are JVM-EXECUTED (MigSqlParity.kt ran the source's own
//! trimIndent); the PIN-sweep branches are source-line-ported decisions.

use cyclecare_core::migrations::*;

fn unesc(s: &str) -> String {
    s.replace("·", " ").replace("\\t", "\t").replace("\\n", "\n").replace("\\\\", "\\")
}

#[test]
fn sql_bodies_byte_exact_against_jvm_trimindent_output() {
    let data = std::fs::read_to_string("fixtures/oracle/migration_sql.tsv").unwrap();
    let mut seen = 0;
    for line in data.lines() {
        let (k, v) = line.split_once('\t').unwrap();
        let expect = unesc(v);
        let actual = match k {
            "create" => SQL_4_TO_5_CREATE,
            "insert" => SQL_4_TO_5_INSERT,
            "drop" => SQL_4_TO_5_DROP,
            "rename" => SQL_4_TO_5_RENAME,
            other => panic!("bad key {other}"),
        };
        assert_eq!(actual, expect, "{k}: SQL body diverged from JVM trimIndent output");
        seen += 1;
    }
    assert_eq!(seen, 4);
    // The trailing-space quirk must survive (trimIndent keeps it).
    assert!(SQL_4_TO_5_INSERT.contains("SELECT \n"), "SELECT trailing space lost");
    assert_eq!(SQL_4_TO_5_PIN_QUERY, "SELECT appLockPin FROM cycle_settings WHERE id = 1");
}

#[test]
fn migration_5_to_9_sql_constants_locked() {
    assert_eq!(SQL_5_TO_6.len(), 4);
    assert!(SQL_5_TO_6[0].starts_with("CREATE TABLE IF NOT EXISTS `health_sync_revisions`"));
    assert!(SQL_5_TO_6[3].starts_with("CREATE INDEX IF NOT EXISTS `index_health_sync_outbox"));
    assert_eq!(SQL_6_TO_7.len(), 4);
    assert_eq!(SQL_6_TO_7[0], "ALTER TABLE `health_sync_outbox` ADD COLUMN `serverReceipt` TEXT");
    assert_eq!(SQL_7_TO_8.len(), 1);
    assert_eq!(SQL_8_TO_9[0], "ALTER TABLE `onboarding_profiles` ADD COLUMN `lastPeriodEndDateString` TEXT NOT NULL DEFAULT ''");
}

struct Mig45 {
    cursor: Result<PinCursor, ()>,
    auth_pin: Result<Option<String>, ()>,
    set_pin_ok: bool,
    set_enabled_ok: bool,
    close_ok: bool,
    calls: Vec<String>,
}

impl Migration45Fx for Mig45 {
    fn query_pin_cursor(&mut self) -> Result<PinCursor, ()> {
        self.calls.push("query".into());
        self.cursor.clone()
    }
    fn auth_prefs_pin(&mut self) -> Result<Option<String>, ()> {
        self.calls.push("auth_new+read".into());
        self.auth_pin.clone()
    }
    fn auth_prefs_set_pin(&mut self, pin: &str) -> Result<(), ()> {
        self.calls.push(format!("set_pin:{pin}"));
        if self.set_pin_ok { Ok(()) } else { Err(()) }
    }
    fn auth_prefs_set_pin_enabled(&mut self, e: bool) -> Result<(), ()> {
        self.calls.push(format!("set_enabled:{e}"));
        if self.set_enabled_ok { Ok(()) } else { Err(()) }
    }
    fn cursor_close(&mut self) -> Result<(), ()> {
        self.calls.push("close".into());
        if self.close_ok { Ok(()) } else { Err(()) }
    }
    fn exec_sql(&mut self, sql: &str) {
        let tag = match sql {
            s if s == SQL_4_TO_5_CREATE => "sql:create",
            s if s == SQL_4_TO_5_INSERT => "sql:insert",
            s if s == SQL_4_TO_5_DROP => "sql:drop",
            s if s == SQL_4_TO_5_RENAME => "sql:rename",
            _ => panic!("unexpected SQL"),
        };
        self.calls.push(tag.into());
    }
}

fn mig(pin: Option<&str>, existing_auth: Option<&str>) -> Mig45 {
    Mig45 {
        cursor: Ok(PinCursor { has_row: true, pin_index: 0, pin: pin.map(str::to_string) }),
        auth_pin: Ok(existing_auth.map(str::to_string)),
        set_pin_ok: true,
        set_enabled_ok: true,
        close_ok: true,
        calls: vec![],
    }
}

const SQL_TAIL: [&str; 4] = ["sql:create", "sql:insert", "sql:drop", "sql:rename"];

#[test]
fn legacy_pin_migrated_when_auth_prefs_empty() {
    let mut m = mig(Some("1234"), None);
    run_migration_4_to_5(&mut m);
    let mut expect = vec!["query", "auth_new+read", "set_pin:1234", "set_enabled:true", "close"];
    expect.extend(SQL_TAIL);
    assert_eq!(m.calls, expect);
}

#[test]
fn existing_auth_pin_never_overwritten() {
    // Preserved-account handoff: an existing AuthPreferences PIN wins.
    let mut m = mig(Some("1234"), Some("9999"));
    run_migration_4_to_5(&mut m);
    let mut expect = vec!["query", "auth_new+read", "close"];
    expect.extend(SQL_TAIL);
    assert_eq!(m.calls, expect);
}

#[test]
fn blank_or_null_stored_pin_not_migrated() {
    for pin in [None, Some(""), Some("   ")] {
        let mut m = mig(pin, None);
        run_migration_4_to_5(&mut m);
        let mut expect = vec!["query", "close"];
        expect.extend(SQL_TAIL);
        assert_eq!(m.calls, expect, "pin {pin:?} must not migrate");
    }
}

#[test]
fn missing_row_or_column_not_migrated() {
    let mut m = mig(Some("1234"), None);
    m.cursor = Ok(PinCursor { has_row: false, pin_index: 0, pin: None });
    run_migration_4_to_5(&mut m);
    let mut expect = vec!["query", "close"];
    expect.extend(SQL_TAIL);
    assert_eq!(m.calls, expect);

    let mut m = mig(Some("1234"), None);
    m.cursor = Ok(PinCursor { has_row: true, pin_index: -1, pin: None });
    run_migration_4_to_5(&mut m);
    let mut expect = vec!["query", "close"];
    expect.extend(SQL_TAIL);
    assert_eq!(m.calls, expect);
}

#[test]
fn query_fault_swallowed_sql_still_runs() {
    let mut m = mig(Some("1234"), None);
    m.cursor = Err(());
    run_migration_4_to_5(&mut m);
    let mut expect = vec!["query"]; // close SKIPPED on fault (leak quirk)
    expect.extend(SQL_TAIL);
    assert_eq!(m.calls, expect);
}

#[test]
fn set_pin_fault_skips_enable_and_close_but_not_sql() {
    let mut m = mig(Some("1234"), None);
    m.set_pin_ok = false;
    run_migration_4_to_5(&mut m);
    let mut expect = vec!["query", "auth_new+read", "set_pin:1234"];
    expect.extend(SQL_TAIL);
    assert_eq!(m.calls, expect, "isPinEnabled and close skipped after fault");
}

#[test]
fn close_fault_swallowed_sql_still_runs() {
    let mut m = mig(Some("1234"), None);
    m.close_ok = false;
    run_migration_4_to_5(&mut m);
    let mut expect = vec!["query", "auth_new+read", "set_pin:1234", "set_enabled:true", "close"];
    expect.extend(SQL_TAIL);
    assert_eq!(m.calls, expect);
}

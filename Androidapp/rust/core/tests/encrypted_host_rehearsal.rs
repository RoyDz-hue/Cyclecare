//! Host rehearsal of the DatabaseEncryption.ensureEncrypted state machine on
//! real SQLCipher-4 files with synthetic scratch data (feature sqlcipher-host).
//! Not a device proof, not a production migration.

#![cfg(feature = "sqlcipher-host")]

use cyclecare_core::encrypted_host::{ensure_encrypted, ConversionError, ConversionOutcome};
use rusqlite::Connection;
use std::fs;
use std::path::{Path, PathBuf};

const KEY: &str = "m7Q0pQ1w2e3r4t5y6u7i8o9p0aQ1w2e3r4t5yQ=="; // fixture passphrase (Base64-shaped, like the app's format)

struct Dir(PathBuf);
impl Dir {
    fn new(tag: &str) -> Self {
        let p = std::env::temp_dir().join(format!("cc-rehearsal-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&p);
        fs::create_dir_all(&p).unwrap();
        Dir(p)
    }
    fn path(&self, name: &str) -> PathBuf { self.0.join(name) }
}
impl Drop for Dir {
    fn drop(&mut self) { let _ = fs::remove_dir_all(&self.0); }
}

fn make_plaintext_db(path: &Path, note: &str) {
    let conn = Connection::open(path).unwrap();
    conn.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, note TEXT)", []).unwrap();
    conn.execute("INSERT INTO t(note) VALUES (?1)", [note]).unwrap();
    drop(conn);
}

fn make_encrypted_db(path: &Path, note: &str) {
    let conn = Connection::open(path).unwrap();
    conn.pragma_update(None, "key", KEY).unwrap();
    conn.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, note TEXT)", []).unwrap();
    conn.execute("INSERT INTO t(note) VALUES (?1)", [note]).unwrap();
    drop(conn);
}

fn read_note_encrypted(path: &Path) -> String {
    let conn = Connection::open(path).unwrap();
    conn.pragma_update(None, "key", KEY).unwrap();
    conn.query_row("SELECT note FROM t WHERE id=1", [], |r| r.get(0)).unwrap()
}

fn has_plaintext_header(path: &Path) -> bool {
    fs::read(path).map(|b| b.starts_with(b"SQLite format 3")).unwrap_or(false)
}

#[test]
fn converts_plaintext_db_and_preserves_rows() {
    let d = Dir::new("convert");
    let db = d.path("app.db");
    make_plaintext_db(&db, "synthetic-row");
    let out = ensure_encrypted(&d.0, "app.db", KEY).unwrap();
    assert_eq!(out, ConversionOutcome::Converted);
    assert!(!has_plaintext_header(&db));
    assert_eq!(read_note_encrypted(&db), "synthetic-row");
    // Post-conversion cleanup: no temp, no backup.
    assert!(!d.path("app.db.encrypted.tmp").exists());
    assert!(!d.path("app.db.plaintext.backup").exists());
    // Wrong key cannot read.
    let bad = Connection::open(&db).unwrap();
    bad.pragma_update(None, "key", "wrong").unwrap();
    assert!(bad.query_row::<String, _, _>("SELECT note FROM t", [], |r| r.get(0)).is_err());
}

#[test]
fn already_encrypted_is_a_noop_cleanup() {
    let d = Dir::new("already");
    let db = d.path("app.db");
    make_encrypted_db(&db, "already-enc");
    // Stray leftovers from a hypothetical prior run are removed.
    fs::write(d.path("app.db.plaintext.backup"), b"stale").unwrap();
    let out = ensure_encrypted(&d.0, "app.db", KEY).unwrap();
    assert_eq!(out, ConversionOutcome::AlreadyEncrypted);
    assert_eq!(read_note_encrypted(&db), "already-enc");
    assert!(!d.path("app.db.plaintext.backup").exists());
}

#[test]
fn interrupted_export_with_complete_temp_is_promoted() {
    let d = Dir::new("interrupted");
    let db = d.path("app.db");
    make_plaintext_db(&db, "old-plaintext");
    make_encrypted_db(&d.path("app.db.encrypted.tmp"), "exported-copy");
    let out = ensure_encrypted(&d.0, "app.db", KEY).unwrap();
    assert_eq!(out, ConversionOutcome::PromotedInterruptedExport);
    assert_eq!(read_note_encrypted(&db), "exported-copy");
    // The pre-promotion plaintext was kept as backup, then removed after final verification.
    assert!(!d.path("app.db.plaintext.backup").exists());
}

#[test]
fn missing_db_with_backup_is_restored_then_converted() {
    let d = Dir::new("restore");
    make_plaintext_db(&d.path("app.db.plaintext.backup"), "from-backup");
    let out = ensure_encrypted(&d.0, "app.db", KEY).unwrap();
    assert_eq!(out, ConversionOutcome::Converted);
    assert_eq!(read_note_encrypted(&d.path("app.db")), "from-backup");
}

#[test]
fn unreadable_db_is_preserved_and_errors() {
    let d = Dir::new("unreadable");
    let db = d.path("app.db");
    fs::write(&db, b"not a database at all, definitely not sqlite").unwrap();
    let err = ensure_encrypted(&d.0, "app.db", KEY).unwrap_err();
    assert!(matches!(err, ConversionError::UnreadablePreserved(_)));
    // Original file untouched.
    assert_eq!(fs::read(&db).unwrap(), b"not a database at all, definitely not sqlite");
}

#[test]
fn missing_db_entirely_reports_no_database() {
    let d = Dir::new("empty");
    let out = ensure_encrypted(&d.0, "app.db", KEY).unwrap();
    assert_eq!(out, ConversionOutcome::NoDatabase);
}

#[test]
fn wal_sidecars_are_folded_and_removed() {
    let d = Dir::new("wal");
    let db = d.path("app.db");
    {
        let conn = Connection::open(&db).unwrap();
        conn.pragma_update(None, "journal_mode", "WAL").unwrap();
        conn.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, note TEXT)", []).unwrap();
        conn.execute("INSERT INTO t(note) VALUES ('wal-row')", []).unwrap();
        drop(conn); // leaves -wal/-shm sidecars on some platforms
    }
    let out = ensure_encrypted(&d.0, "app.db", KEY).unwrap();
    assert_eq!(out, ConversionOutcome::Converted);
    assert_eq!(read_note_encrypted(&db), "wal-row");
    assert!(!d.path("app.db-wal").exists());
    assert!(!d.path("app.db-shm").exists());
    assert!(!d.path("app.db-journal").exists());
}

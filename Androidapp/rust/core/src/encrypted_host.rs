//! Host-only rehearsal of DatabaseEncryption.kt (repo main@31b88f5): the
//! plaintext -> SQLCipher conversion state machine, ported branch-for-branch
//! and executed on REAL SQLCipher 4 files with synthetic scratch data.
//!
//! Scope guardrails:
//! - Feature `sqlcipher-host` only; never in default/FFI builds.
//! - No production migration switch, no live data, no plaintext fallback.
//! - The app derives the passphrase as Base64(32 random bytes) wrapped by an
//!   Android Keystore key; this module takes a passphrase string and never
//!   models the Keystore wrap (device-bound, out of host reach by design).
//!
//! Format note: bidirectional cross-implementation read was proven between
//! this bundled SQLCipher 4.5.7 and the official sqlcipher CLI 4.6.1. That is
//! SQLCipher-4-format compatibility, not proof of byte-level behavior of the
//! app's exact net.zetetic library build.

use rusqlite::{Connection, OpenFlags};
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConversionOutcome {
    /// File already readable with the key; temp/backup/sidecars removed.
    AlreadyEncrypted,
    /// A complete encrypted temp from an interrupted run was promoted.
    PromotedInterruptedExport,
    /// Plaintext source exported, verified, and promoted.
    Converted,
    /// No database file and no backup: Room would create a fresh encrypted db.
    NoDatabase,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConversionError {
    /// Neither the file nor its backup was readable; originals preserved.
    UnreadablePreserved(String),
    /// Export or post-promote verification failed; plaintext preserved/restored.
    VerificationFailed(String),
    Io(String),
}

fn is_readable(file: &Path, key: Option<&str>, read_only: bool) -> bool {
    if !file.exists() || file.metadata().map(|m| m.len()).unwrap_or(0) == 0 {
        return false;
    }
    let flags = if read_only { OpenFlags::SQLITE_OPEN_READ_ONLY } else { OpenFlags::SQLITE_OPEN_READ_WRITE };
    let conn = match Connection::open_with_flags(file, flags) {
        Ok(c) => c,
        Err(_) => return false,
    };
    if let Some(k) = key {
        if conn.pragma_update(None, "key", k).is_err() {
            return false;
        }
    }
    // zetetic isDatabaseIntegrityOk == PRAGMA integrity_check first row "ok".
    conn.query_row::<String, _, _>("PRAGMA integrity_check", [], |r| r.get(0))
        .map(|r| r == "ok")
        .unwrap_or(false)
}

fn remove_or_throw(file: &Path) -> Result<(), ConversionError> {
    if file.exists() {
        fs::remove_file(file).map_err(|e| {
            ConversionError::Io("A temporary local database file could not be safely removed.".into())
                .and_io(e)
        })?;
    }
    Ok(())
}

fn rename_or_throw(src: &Path, dst: &Path) -> Result<(), ConversionError> {
    fs::rename(src, dst).map_err(|_| {
        ConversionError::Io("A local database recovery file could not be safely moved.".into())
    })
}

fn remove_sidecars(db: &Path) -> Result<(), ConversionError> {
    for suffix in ["-wal", "-shm", "-journal"] {
        let sidecar = PathBuf::from(format!("{}{}", db.display(), suffix));
        remove_or_throw(&sidecar)?;
    }
    Ok(())
}

fn export_plaintext_to_encrypted(source: &Path, dest: &Path, passphrase: &str) -> Result<(), ConversionError> {
    remove_or_throw(dest)?;
    let result = (|| -> Result<(), ConversionError> {
        // Empty key opens a standard SQLite database (Kotlin ByteArray(0));
        // default flags, matching SQLiteDatabase.openDatabase.
        let conn = Connection::open(source)
            .map_err(|e| ConversionError::VerificationFailed(e.to_string()))?;
        conn.query_row::<i32, _, _>("PRAGMA wal_checkpoint(TRUNCATE)", [], |r| r.get(0))
            .map_err(|e| ConversionError::VerificationFailed(e.to_string()))?;
        conn.execute(
            "ATTACH DATABASE ?1 AS encrypted KEY ?2",
            rusqlite::params![dest.to_string_lossy().as_ref(), passphrase],
        )
        .map_err(|e| ConversionError::VerificationFailed(e.to_string()))?;
        // Kotlin checks only cursor.moveToFirst(): row presence, not a value.
        let export: Result<(), rusqlite::Error> = (|| {
            let mut st = conn.prepare("SELECT sqlcipher_export('encrypted')")?;
            let mut rows = st.query([])?;
            rows.next()?.map(|_| ()).ok_or(rusqlite::Error::QueryReturnedNoRows)
        })();
        let detach = conn.execute("DETACH DATABASE encrypted", []);
        drop(conn);
        export.map_err(|e| ConversionError::VerificationFailed(e.to_string()))?;
        detach.map_err(|e| ConversionError::VerificationFailed(e.to_string()))?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(dest);
    }
    result.map_err(|e| match e {
        ConversionError::VerificationFailed(m) => ConversionError::VerificationFailed(format!(
            "Existing local data could not be encrypted; the plaintext source was preserved. {m}"
        )),
        other => other,
    })
}

fn promote_encrypted_file(temp: &Path, db: &Path, backup: &Path, key: &str) -> Result<(), ConversionError> {
    if db.exists() {
        remove_or_throw(backup)?;
        rename_or_throw(db, backup)?;
    }
    if fs::rename(temp, db).is_err() {
        if backup.exists() && !db.exists() {
            let _ = fs::rename(backup, db);
        }
        return Err(ConversionError::VerificationFailed(
            "Encrypted database could not replace the old file; original data was preserved.".into(),
        ));
    }
    if !is_readable(db, Some(key), true) {
        let _ = fs::remove_file(db);
        if backup.exists() {
            let _ = fs::rename(backup, db);
        }
        return Err(ConversionError::VerificationFailed(
            "Final encrypted database verification failed; original data was restored.".into(),
        ));
    }
    remove_or_throw(backup)?;
    remove_sidecars(db)?;
    Ok(())
}

/// ensureEncrypted(): full branch order from the Kotlin source.
pub fn ensure_encrypted(directory: &Path, database_name: &str, passphrase: &str) -> Result<ConversionOutcome, ConversionError> {
    let db = directory.join(database_name);
    let temp = directory.join(format!("{database_name}.encrypted.tmp"));
    let backup = directory.join(format!("{database_name}.plaintext.backup"));

    if is_readable(&db, Some(passphrase), true) {
        remove_or_throw(&temp)?;
        remove_or_throw(&backup)?;
        remove_sidecars(&db)?;
        return Ok(ConversionOutcome::AlreadyEncrypted);
    }
    if is_readable(&temp, Some(passphrase), true) {
        promote_encrypted_file(&temp, &db, &backup, passphrase)?;
        return Ok(ConversionOutcome::PromotedInterruptedExport);
    }
    remove_or_throw(&temp)?;
    if !db.exists() && backup.exists() {
        rename_or_throw(&backup, &db)?;
    }
    if !db.exists() {
        return Ok(ConversionOutcome::NoDatabase);
    }
    if !is_readable(&db, None, false) {
        if backup.exists() && is_readable(&backup, None, false) {
            remove_or_throw(&db)?;
            rename_or_throw(&backup, &db)?;
        } else {
            return Err(ConversionError::UnreadablePreserved(
                "Local database encryption could not be verified; original data was preserved.".into(),
            ));
        }
    }
    export_plaintext_to_encrypted(&db, &temp, passphrase)?;
    if !is_readable(&temp, Some(passphrase), true) {
        remove_or_throw(&temp)?;
        return Err(ConversionError::VerificationFailed(
            "Encrypted database verification failed; original data was preserved.".into(),
        ));
    }
    promote_encrypted_file(&temp, &db, &backup, passphrase)?;
    Ok(ConversionOutcome::Converted)
}

impl ConversionError {
    fn and_io(self, _e: std::io::Error) -> Self { self }
}

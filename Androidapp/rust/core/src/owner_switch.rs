//! CycleCareDatabase.kt getDatabase + buildDatabase (main 31b88f5, :100-170)
//! + DatabaseEncryption.kt ensureEncrypted: the owner-switch database
//! handoff. Three drivers, all effects injected:
//!
//! 1. [get_database] - the instance-cache resolution (:103-114): name
//!    resolution (null/blank -> legacy guest, else namespaceFor) then the
//!    double-checked cache: legacy name resolves through the single
//!    INSTANCE slot, any owner name through the OWNER_INSTANCES map. A
//!    cache hit performs NO build effects (source: the volatile read
//!    returns before the monitor); a miss builds once and stores. The
//!    source's synchronized(this) slow-path monitor is a HOST OBLIGATION:
//!    this port models the sequential outcome; the host must serialize the
//!    build path so racing first-builds cannot double-build.
//!
//! 2. [build_database] - the assembly order (:159-170), locked by
//!    transition test: loadLibrary("sqlcipher") -> getOrCreatePassphrase
//!    (composed [crate::db_key_manager] driver, typed faults propagate
//!    unguarded like the source) -> ensureEncrypted (conversion state
//!    machine, below) -> SupportOpenHelperFactory(passphrase UTF-8 bytes)
//!    -> Room build with the exact migration list [MIGRATION_STEPS].
//!
//! 3. [ensure_encrypted] - DatabaseEncryption.kt:11-58 + helpers, the
//!    preserved-account legacy->encrypted conversion state machine. Every
//!    probe (SQLCipher readability) and mutation (remove/rename/export) is
//!    injected; the ported surface is the BRANCH STRUCTURE and the exact
//!    IllegalStateException messages. Source quirks preserved 1:1:
//!    a removeOrThrow failure inside a failure path MASKS the original
//!    message (Kotlin: the new throw replaces the in-flight one);
//!    promoteEncryptedFile's rename failure path does a best-effort backup
//!    restore whose result is ignored; exportPlaintextToEncrypted's catch
//!    swallows the internal check() message into the export wrapper
//!    message (the check text survives only as the cause).
//!
//! NOT PORTED (host seams, labeled): the SQLCipher probes themselves
//! (openDatabase + isDatabaseIntegrityOk on device), the native library
//! load, Room's own builder/migration execution, and the file I/O. Actual
//! device compatibility remains open. Lone-surrogate owner identities:
//! JVM UTF-8 encodes them with '?' (0x3F) so namespaceFor digests differ
//! there; a Rust &str cannot hold a lone surrogate, so that input class is
//! STRUCTURALLY UNREACHABLE in this port (labeled, not a divergence).

use std::collections::HashMap;

use crate::db_key_manager::{get_or_create_passphrase, DbKeyEffects, DbKeyError};
use crate::owner_data_gate::database_name_for;

pub use crate::owner_data_gate::LEGACY_DATABASE_NAME;

/// addMigrations argument order (:168): 4->5 needs the Context (legacy PIN
/// sweep), the rest are pure SQL.
pub const MIGRATION_STEPS: [(u32, u32); 5] = [(4, 5), (5, 6), (6, 7), (7, 8), (8, 9)];

// ---------------------------------------------------------------------------
// 1. Instance-cache resolution
// ---------------------------------------------------------------------------

/// The two caches, ported: one legacy slot + one per-owner map. H is the
/// host's database handle type.
#[derive(Default)]
pub struct DatabaseCache<H> {
    /// INSTANCE (legacy guest DB). @Volatile in the source.
    pub legacy: Option<H>,
    /// OWNER_INSTANCES (ConcurrentHashMap keyed by resolved name).
    pub owners: HashMap<String, H>,
}

/// Which path getDatabase took. The handle stays in the cache; the host
/// reads it from there (this driver ports the DECISION, not handle moves).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Resolve {
    /// Fast-path hit: no build ran (source volatile read).
    Cached,
    /// Slow path: built once and stored.
    Built,
}

/// getDatabase(context, ownerIdentity) :103-114. `build` runs ONLY on a
/// cache miss and its result is stored under the resolved name. A legacy
/// result never enters the owner map and vice versa. Host must serialize
/// concurrent misses on the same name (source synchronized(this)).
pub fn get_database<H>(
    cache: &mut DatabaseCache<H>,
    owner_identity: Option<&str>,
    mut build: impl FnMut(&str) -> H,
) -> (String, Resolve) {
    let name = database_name_for(owner_identity);
    if name == LEGACY_DATABASE_NAME {
        if cache.legacy.is_some() {
            return (name, Resolve::Cached);
        }
        cache.legacy = Some(build(&name));
        return (name, Resolve::Built);
    }
    if cache.owners.contains_key(&name) {
        return (name, Resolve::Cached);
    }
    let h = build(&name);
    cache.owners.insert(name.clone(), h);
    (name, Resolve::Built)
}

// ---------------------------------------------------------------------------
// 2. buildDatabase assembly
// ---------------------------------------------------------------------------

/// Extra host effects beyond the key manager and the conversion FS.
pub trait BuildEffects {
    /// System.loadLibrary("sqlcipher") - host-only native load.
    fn load_sqlcipher(&mut self);
    /// Room.databaseBuilder(...).openHelperFactory(SupportOpenHelperFactory
    /// (passphrase utf8)).addMigrations(MIGRATION_STEPS).build() - host
    /// seam returning the handle. The factory bytes are handed over so the
    /// host cannot drift from the passphrase it was built from.
    fn room_build(&mut self, database_name: &str, passphrase_utf8: &[u8]) -> u64;
}

#[derive(Debug, PartialEq)]
pub enum BuildError {
    /// DatabaseKeyManager fault, propagated unguarded (source: no catching
    /// in buildDatabase).
    Key(DbKeyError),
    /// ensureEncrypted IllegalStateException message, verbatim.
    Encryption(&'static str),
}

/// buildDatabase (:159-170): assembly order is the contract.
pub fn build_database<E: DbKeyEffects + EncryptionFs + BuildEffects>(
    fx: &mut E,
    database_name: &str,
) -> Result<u64, BuildError> {
    fx.load_sqlcipher();
    let passphrase = get_or_create_passphrase(fx).map_err(BuildError::Key)?;
    // The SAME passphrase value feeds both the conversion probes/export
    // (:163) and the SupportOpenHelperFactory bytes (:164) - the driver
    // proves they cannot drift apart.
    ensure_encrypted(fx, &passphrase).map_err(BuildError::Encryption)?;
    Ok(fx.room_build(database_name, passphrase.as_bytes()))
}

// ---------------------------------------------------------------------------
// 3. ensureEncrypted conversion state machine
// ---------------------------------------------------------------------------

/// The files the state machine touches (DatabaseEncryption.kt helper
/// targets). Sidecars are the Room/SQLite journals next to the db file.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DbFile {
    Database,
    Temp,
    Backup,
    Sidecar(&'static str),
}

pub const ERR_DIR_UNAVAILABLE: &str = "Local database directory is unavailable.";
pub const ERR_DIR_CREATE: &str = "Local database directory could not be created.";
pub const ERR_UNREADABLE: &str =
    "Local database encryption could not be verified; original data was preserved.";
pub const ERR_EXPORT: &str =
    "Existing local data could not be encrypted; the plaintext source was preserved.";
pub const ERR_VERIFY_TEMP: &str =
    "Encrypted database verification failed; original data was preserved.";
pub const ERR_PROMOTE: &str =
    "Encrypted database could not replace the old file; original data was preserved.";
pub const ERR_VERIFY_FINAL: &str =
    "Final encrypted database verification failed; original data was restored.";
pub const ERR_REMOVE: &str = "A temporary local database file could not be safely removed.";
pub const ERR_RENAME: &str = "A local database recovery file could not be safely moved.";

/// Injected probes and mutations over the four files. Probe semantics model
/// the source helpers: `readable_with_key` = exists && length>0 &&
/// SQLCipher open + isDatabaseIntegrityOk; `readable_plaintext` = the same
/// with an empty key (READWRITE); `export` = the sqlcipher_export pipeline
/// (any internal failure, including the check(cursor.moveToFirst())
/// message, reaches the driver only as Err - the cause is host-side).
pub trait EncryptionFs {
    /// databaseFile.parentFile != null (:12-13).
    fn dir_available(&self) -> bool;
    fn dir_exists(&self) -> bool;
    fn mkdirs(&mut self) -> bool;
    fn exists(&self, f: DbFile) -> bool;
    /// isReadable(file, key) :117-135: exists && length>0 && SQLCipher
    /// openDatabase(file, key) + isDatabaseIntegrityOk. The key bytes are
    /// the passphrase UTF-8 bytes, ACTUALLY threaded from the driver.
    fn readable_with_key(&self, f: DbFile, key: &[u8]) -> bool;
    /// isReadablePlaintext :119 - empty key, OPEN_READWRITE.
    fn readable_plaintext(&self, f: DbFile) -> bool;
    /// File.delete(): false on failure -> removeOrThrow throws ERR_REMOVE.
    fn remove(&mut self, f: DbFile) -> bool;
    /// File.renameTo(): false on failure. renameOrThrow throws ERR_RENAME;
    /// the two BEST-EFFORT renames ignore the result.
    fn rename(&mut self, from: DbFile, to: DbFile) -> bool;
    /// exportPlaintextToEncrypted's SQLCipher pipeline (:61-92); the
    /// passphrase is the ATTACH ... KEY ? parameter.
    fn export(&mut self, passphrase: &str) -> Result<(), ()>;
}

/// removeOrThrow: only touches existing files; failed delete throws.
fn remove_or_throw<F: EncryptionFs>(fs: &mut F, f: DbFile) -> Result<(), &'static str> {
    if fs.exists(f) && !fs.remove(f) {
        return Err(ERR_REMOVE);
    }
    Ok(())
}

/// renameOrThrow.
fn rename_or_throw<F: EncryptionFs>(fs: &mut F, from: DbFile, to: DbFile) -> Result<(), &'static str> {
    if !fs.rename(from, to) {
        return Err(ERR_RENAME);
    }
    Ok(())
}

/// removeSidecars: three removeOrThrow calls in source order; a failure
/// stops the list (the throw propagates).
fn remove_sidecars<F: EncryptionFs>(fs: &mut F) -> Result<(), &'static str> {
    for s in ["-wal", "-shm", "-journal"] {
        remove_or_throw(fs, DbFile::Sidecar(s))?;
    }
    Ok(())
}

/// promoteEncryptedFile (:94-115).
fn promote_encrypted_file<F: EncryptionFs>(fs: &mut F, key: &[u8]) -> Result<(), &'static str> {
    if fs.exists(DbFile::Database) {
        remove_or_throw(fs, DbFile::Backup)?;
        rename_or_throw(fs, DbFile::Database, DbFile::Backup)?;
    }
    if !fs.rename(DbFile::Temp, DbFile::Database) {
        // Best-effort restore; source ignores the renameTo result.
        if fs.exists(DbFile::Backup) && !fs.exists(DbFile::Database) {
            let _ = fs.rename(DbFile::Backup, DbFile::Database);
        }
        return Err(ERR_PROMOTE);
    }
    if !fs.readable_with_key(DbFile::Database, key) {
        remove_or_throw(fs, DbFile::Database)?;
        if fs.exists(DbFile::Backup) {
            rename_or_throw(fs, DbFile::Backup, DbFile::Database)?;
        }
        return Err(ERR_VERIFY_FINAL);
    }
    remove_or_throw(fs, DbFile::Backup)?;
    remove_sidecars(fs)
}

/// ensureEncrypted (:11-58): the branch structure and messages, ported.
/// The name lives in the host's probe state. `passphrase` is threaded
/// INTO THE PROBES AND EXPORT as the source threads it: key probes receive
/// passphrase.toByteArray(UTF_8) (:19) and export receives the String
/// (:50). The driver never inspects the value itself.
pub fn ensure_encrypted<F: EncryptionFs>(fs: &mut F, passphrase: &str) -> Result<(), &'static str> {
    let key = passphrase.as_bytes();
    if !fs.dir_available() {
        return Err(ERR_DIR_UNAVAILABLE);
    }
    if !fs.dir_exists() && !fs.mkdirs() {
        return Err(ERR_DIR_CREATE);
    }
    if fs.readable_with_key(DbFile::Database, key) {
        remove_or_throw(fs, DbFile::Temp)?;
        remove_or_throw(fs, DbFile::Backup)?;
        remove_sidecars(fs)?;
        return Ok(());
    }
    // Resume an interrupted conversion: a verified temp export is promoted.
    if fs.readable_with_key(DbFile::Temp, key) {
        return promote_encrypted_file(fs, key);
    }
    remove_or_throw(fs, DbFile::Temp)?;
    // Recover the original if a prior run stopped after moving it aside.
    if !fs.exists(DbFile::Database) && fs.exists(DbFile::Backup) {
        rename_or_throw(fs, DbFile::Backup, DbFile::Database)?;
    }
    if !fs.exists(DbFile::Database) {
        return Ok(()); // Room will create a new encrypted database.
    }
    if !fs.readable_plaintext(DbFile::Database) {
        if fs.exists(DbFile::Backup) && fs.readable_plaintext(DbFile::Backup) {
            remove_or_throw(fs, DbFile::Database)?;
            rename_or_throw(fs, DbFile::Backup, DbFile::Database)?;
        } else {
            return Err(ERR_UNREADABLE);
        }
    }
    // exportPlaintextToEncrypted (:61) runs its OWN removeOrThrow
    // (destinationFile) BEFORE the try block - observable under races and
    // its failure is NOT wrapped by the export catch (it precedes it).
    remove_or_throw(fs, DbFile::Temp)?;
    if fs.export(passphrase).is_err() {
        // Source: catch { removeOrThrow(temp); throw ISE(ERR_EXPORT, cause) }.
        // A removeOrThrow failure inside the catch MASKS ERR_EXPORT.
        remove_or_throw(fs, DbFile::Temp)?;
        return Err(ERR_EXPORT);
    }
    if !fs.readable_with_key(DbFile::Temp, key) {
        remove_or_throw(fs, DbFile::Temp)?;
        return Err(ERR_VERIFY_TEMP);
    }
    promote_encrypted_file(fs, key)
}

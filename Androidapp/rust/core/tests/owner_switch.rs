//! getDatabase cache-resolution + buildDatabase assembly + ensureEncrypted
//! state machine: transition tests over a virtual FS. Every expectation is
//! a source line (CycleCareDatabase.kt / DatabaseEncryption.kt, main
//! 31b88f5). namespaceFor vectors are JVM-executed (NsParity.kt,
//! jdk-17.0.21+9) including Unicode identities; the lone-surrogate case is
//! JVM-'?'-substituted and STRUCTURALLY UNREACHABLE in Rust (labeled).

use std::cell::RefCell;
use std::collections::{HashMap, HashSet};

use cyclecare_core::db_key_manager::{DbKeyEffects, DbKeyError};
use cyclecare_core::owner_data_gate::{database_name_for, namespace_for, LEGACY_DATABASE_NAME};
use cyclecare_core::owner_switch::*;

// --- JVM-executed namespaceFor vectors (NsParity.kt) ---
#[test]
fn namespace_vectors_jvm_executed() {
    assert_eq!(
        namespace_for("user-Ä-123"),
        "cycle_care_owner_e3e72504aaf7a714c3a4b3f089f875ccde5d7f4d32c022cdfd1f3c1503fd688e"
    );
    assert_eq!(
        namespace_for("用户-42"),
        "cycle_care_owner_e2d5e0669e81c991f259e3033ac76c1ffff515439f701befd6c43a75f22c1d5f"
    );
    // JVM lone-surrogate identity "owner\ud800x" digests bytes 6f776e65723f78
    // ("owner?x") -> cycle_care_owner_ff18db45... ; a Rust &str cannot hold a
    // lone surrogate, so that database-name split is unreachable here.
    // Blank/whitespace resolution: "   " -> legacy; ZWSP is NOT Kotlin
    // whitespace -> namespaced (JVM b1/b2 rows).
    assert_eq!(database_name_for(Some("   ")), LEGACY_DATABASE_NAME);
    assert_eq!(database_name_for(None), LEGACY_DATABASE_NAME);
    assert_eq!(database_name_for(Some("​")), namespace_for("​"));
}

// --- 1. cache resolution transitions ---
#[test]
fn cache_resolution_transitions() {
    let mut cache = DatabaseCache::<usize>::default();
    let mut builds: Vec<String> = Vec::new();
    let mut next = 0usize;
    let mut build = |name: &str| {
        builds.push(name.to_string());
        next += 1;
        next
    };

    // null identity -> legacy; first builds, second CACHED with no effects.
    let (n, r) = get_database(&mut cache, None, &mut build);
    assert_eq!((n.as_str(), r), (LEGACY_DATABASE_NAME, Resolve::Built));
    let (n, r) = get_database(&mut cache, None, &mut build);
    assert_eq!((n.as_str(), r), (LEGACY_DATABASE_NAME, Resolve::Cached));

    // blank identity also resolves to the SAME legacy slot (:105 takeIf).
    let (n, r) = get_database(&mut cache, Some("  "), &mut build);
    assert_eq!((n.as_str(), r), (LEGACY_DATABASE_NAME, Resolve::Cached));

    // owner A builds its own; owner B builds separately; back to A is CACHED
    // (OWNER_INSTANCES persists across switches - the account handoff).
    let a = namespace_for("owner-a");
    let b = namespace_for("owner-b");
    assert_eq!(get_database(&mut cache, Some("owner-a"), &mut build).1, Resolve::Built);
    assert_eq!(get_database(&mut cache, Some("owner-b"), &mut build).1, Resolve::Built);
    assert_eq!(get_database(&mut cache, Some("owner-a"), &mut build).1, Resolve::Cached);
    // legacy slot survived the owner switches.
    assert_eq!(get_database(&mut cache, None, &mut build).1, Resolve::Cached);

    assert_eq!(builds, vec![LEGACY_DATABASE_NAME.to_string(), a, b]);
}

// --- 2. build assembly order ---
struct AssemblyFx {
    calls: RefCell<Vec<String>>,
    key: [u8; 32],
    fail_key: bool,
    fs: VirtFs,
}

impl DbKeyEffects for AssemblyFx {
    fn wrapping_key(&self) -> Result<[u8; 32], DbKeyError> {
        if self.fail_key { Err(DbKeyError::KeystoreFailed) } else { Ok(self.key) }
    }
    fn get_wrapped(&self) -> Option<String> {
        self.calls.borrow_mut().push("get_wrapped".into());
        None
    }
    fn secure_random_32(&mut self) -> [u8; 32] {
        self.calls.borrow_mut().push("random".into());
        [9u8; 32]
    }
    fn gcm_iv(&mut self) -> [u8; 12] {
        self.calls.borrow_mut().push("iv".into());
        [5u8; 12]
    }
    fn commit_wrapped(&mut self, _v: &str) -> bool {
        self.calls.borrow_mut().push("commit".into());
        true
    }
}

impl EncryptionFs for AssemblyFx {
    fn dir_available(&self) -> bool { true }
    fn dir_exists(&self) -> bool { true }
    fn mkdirs(&mut self) -> bool { true }
    fn exists(&self, f: DbFile) -> bool { self.fs.exists(f) }
    fn readable_with_key(&self, f: DbFile) -> bool { self.fs.readable_key(f) }
    fn readable_plaintext(&self, f: DbFile) -> bool { self.fs.readable_plain(f) }
    fn remove(&mut self, f: DbFile) -> bool { self.fs.remove(f) }
    fn rename(&mut self, from: DbFile, to: DbFile) -> bool { self.fs.rename(from, to) }
    fn export(&mut self) -> Result<(), ()> { self.fs.export() }
}

impl BuildEffects for AssemblyFx {
    fn load_sqlcipher(&mut self) {
        self.calls.borrow_mut().push("load_sqlcipher".into());
    }
    fn room_build(&mut self, name: &str, passphrase_utf8: &[u8]) -> u64 {
        assert_eq!(passphrase_utf8.len(), 44, "Base64(32 bytes) is 44 utf8 bytes");
        self.calls.borrow_mut().push(format!("room_build:{name}"));
        42
    }
}

fn assembly_fs_new_device() -> AssemblyFx {
    // Fresh install: dir exists, no db file at all.
    AssemblyFx {
        calls: RefCell::new(vec![]),
        key: [3u8; 32],
        fail_key: false,
        fs: VirtFs::default(),
    }
}

#[test]
fn build_assembly_order_fresh_install() {
    let mut fx = assembly_fs_new_device();
    let h = build_database(&mut fx, LEGACY_DATABASE_NAME).unwrap();
    assert_eq!(h, 42);
    // :161 loadLibrary BEFORE :162 passphrase BEFORE :163 ensureEncrypted
    // (get_wrapped=read, no files) BEFORE :164-169 factory+Room build.
    assert_eq!(
        fx.calls.borrow().as_slice(),
        vec!["load_sqlcipher", "get_wrapped", "random", "iv", "commit", "room_build:cycle_care_database"]
    );
}

#[test]
fn build_assembly_key_fault_stops_before_encryption() {
    let mut fx = assembly_fs_new_device();
    fx.fail_key = true;
    assert_eq!(
        build_database(&mut fx, LEGACY_DATABASE_NAME),
        Err(BuildError::Key(DbKeyError::KeystoreFailed))
    );
    assert_eq!(fx.calls.borrow().as_slice(), vec!["load_sqlcipher"], "no ensureEncrypted/Room after key fault");
}

#[test]
fn migration_list_order_locked() {
    assert_eq!(MIGRATION_STEPS, [(4, 5), (5, 6), (6, 7), (7, 8), (8, 9)]);
}

// --- 3. ensureEncrypted state machine over a virtual FS ---
#[derive(Default)]
struct VFile {
    exists: bool,
    readable_key: bool,
    readable_plain: bool,
}

#[derive(Default)]
struct VirtFs {
    files: HashMap<&'static str, VFile>,
    dir_ok: bool,
    dir_there: bool,
    mkdirs_ok: bool,
    fail_remove: HashMap<&'static str, usize>,
    rm_counts: HashMap<&'static str, usize>,
    fail_rename: HashSet<(&'static str, &'static str)>,
    export_ok: bool,
    log: Vec<String>,
}

fn key_of(f: DbFile) -> &'static str {
    match f {
        DbFile::Database => "db",
        DbFile::Temp => "tmp",
        DbFile::Backup => "bak",
        DbFile::Sidecar("-wal") => "wal",
        DbFile::Sidecar("-shm") => "shm",
        DbFile::Sidecar("-journal") => "jrnl",
        DbFile::Sidecar(_) => unreachable!(),
    }
}

impl VirtFs {
    fn put(&mut self, k: &'static str, readable_key: bool, readable_plain: bool) {
        self.files.insert(k, VFile { exists: true, readable_key, readable_plain });
    }
    fn exists(&self, f: DbFile) -> bool {
        self.files.get(key_of(f)).map(|v| v.exists).unwrap_or(false)
    }
    fn readable_key(&self, f: DbFile) -> bool {
        self.files.get(key_of(f)).map(|v| v.exists && v.readable_key).unwrap_or(false)
    }
    fn readable_plain(&self, f: DbFile) -> bool {
        self.files.get(key_of(f)).map(|v| v.exists && v.readable_plain).unwrap_or(false)
    }
    fn remove(&mut self, f: DbFile) -> bool {
        let k = key_of(f);
        self.log.push(format!("rm:{k}"));
        let n = self.rm_counts.entry(k).or_insert(0);
        *n += 1;
        if self.fail_remove.get(k) == Some(n) {
            return false;
        }
        if let Some(v) = self.files.get_mut(k) {
            v.exists = false;
        }
        true
    }
    fn rename(&mut self, from: DbFile, to: DbFile) -> bool {
        let (a, b) = (key_of(from), key_of(to));
        self.log.push(format!("mv:{a}->{b}"));
        if self.fail_rename.contains(&(a, b)) {
            return false;
        }
        let v = self.files.remove(a).unwrap_or_default();
        if v.exists {
            self.files.insert(b, v);
        }
        true
    }
    fn export(&mut self) -> Result<(), ()> {
        self.log.push("export".to_string());
        if self.export_ok {
            // export produces a key-readable temp file
            self.put("tmp", true, false);
            Ok(())
        } else {
            Err(())
        }
    }
}

fn fs_base() -> VirtFs {
    VirtFs { dir_ok: true, dir_there: true, mkdirs_ok: true, export_ok: true, ..Default::default() }
}

impl EncryptionFs for VirtFs {
    fn dir_available(&self) -> bool { self.dir_ok }
    fn dir_exists(&self) -> bool { self.dir_there }
    fn mkdirs(&mut self) -> bool {
        if self.mkdirs_ok { self.dir_there = true; }
        self.mkdirs_ok
    }
    fn exists(&self, f: DbFile) -> bool { VirtFs::exists(self, f) }
    fn readable_with_key(&self, f: DbFile) -> bool { self.readable_key(f) }
    fn readable_plaintext(&self, f: DbFile) -> bool { self.readable_plain(f) }
    fn remove(&mut self, f: DbFile) -> bool { VirtFs::remove(self, f) }
    fn rename(&mut self, from: DbFile, to: DbFile) -> bool { VirtFs::rename(self, from, to) }
    fn export(&mut self) -> Result<(), ()> { VirtFs::export(self) }
}

#[test]
fn dir_unavailable_and_mkdirs_failure_messages() {
    let mut fs = fs_base();
    fs.dir_ok = false;
    assert_eq!(ensure_encrypted(&mut fs, "pw"), Err(ERR_DIR_UNAVAILABLE));
    let mut fs = fs_base();
    fs.dir_there = false;
    fs.mkdirs_ok = false;
    assert_eq!(ensure_encrypted(&mut fs, "pw"), Err(ERR_DIR_CREATE));
}

#[test]
fn already_encrypted_cleans_temp_backup_sidecars() {
    let mut fs = fs_base();
    fs.put("db", true, false); // key-readable: already encrypted
    fs.put("tmp", true, false);
    fs.put("bak", false, true);
    fs.put("wal", false, false);
    fs.put("shm", false, false);
    fs.put("jrnl", false, false);
    assert_eq!(ensure_encrypted(&mut fs, "pw"), Ok(()));
    assert_eq!(fs.log, vec!["rm:tmp", "rm:bak", "rm:wal", "rm:shm", "rm:jrnl"]);
    assert!(fs.exists(DbFile::Database));
}

#[test]
fn interrupted_conversion_resumes_from_temp() {
    let mut fs = fs_base();
    fs.put("db", false, true); // old plaintext still present
    fs.put("tmp", true, false); // verified encrypted export left behind
    assert_eq!(ensure_encrypted(&mut fs, "pw"), Ok(()));
    // promote: db->bak, tmp->db, verify ok, rm bak + sidecars (none exist)
    // promote: bak absent (no rm), db->bak, tmp->db, verify ok, rm bak
    assert_eq!(fs.log, vec!["mv:db->bak", "mv:tmp->db", "rm:bak"]);
    assert!(fs.readable_key(DbFile::Database));
    assert!(!fs.exists(DbFile::Temp));
    assert!(!fs.exists(DbFile::Backup));
}

#[test]
fn fresh_install_returns_without_effects() {
    let mut fs = fs_base();
    assert_eq!(ensure_encrypted(&mut fs, "pw"), Ok(()));
    // removeOrThrow(tmp) probes but tmp is absent -> delete() never runs
    assert_eq!(fs.log, Vec::<String>::new());
}

#[test]
fn backup_restored_then_converted_in_same_call() {
    // Process died after moving db aside: db missing, plaintext backup left.
    let mut fs = fs_base();
    fs.put("bak", false, true);
    assert_eq!(ensure_encrypted(&mut fs, "pw"), Ok(()));
    // tmp absent (no rm), restore bak->db, plaintext-ok, export, promote:
    // bak absent (moved), db->bak, tmp->db, rm bak, sidecars absent
    assert_eq!(
        fs.log,
        vec!["mv:bak->db", "export", "mv:db->bak", "mv:tmp->db", "rm:bak"]
    );
    assert!(fs.readable_key(DbFile::Database));
}

#[test]
fn full_plaintext_to_encrypted_conversion() {
    let mut fs = fs_base();
    fs.put("db", false, true); // legacy plaintext
    assert_eq!(ensure_encrypted(&mut fs, "pw"), Ok(()));
    assert_eq!(fs.log, vec!["export", "mv:db->bak", "mv:tmp->db", "rm:bak"]);
    assert!(fs.readable_key(DbFile::Database));
    assert!(!fs.exists(DbFile::Backup), "backup removed after final verification");
}

#[test]
fn unreadable_db_with_no_usable_backup_throws_and_preserves() {
    let mut fs = fs_base();
    fs.put("db", false, false); // neither key- nor plaintext-readable
    assert_eq!(ensure_encrypted(&mut fs, "pw"), Err(ERR_UNREADABLE));
    assert!(fs.exists(DbFile::Database), "original preserved");
    assert_eq!(fs.log, Vec::<String>::new(), "no mutation of the unreadable file");
}

#[test]
fn unreadable_db_falls_back_to_plaintext_backup() {
    let mut fs = fs_base();
    fs.put("db", false, false);
    fs.put("bak", false, true);
    assert_eq!(ensure_encrypted(&mut fs, "pw"), Ok(()));
    assert_eq!(
        fs.log,
        vec!["rm:db", "mv:bak->db", "export", "mv:db->bak", "mv:tmp->db", "rm:bak"]
    );
}

#[test]
fn export_failure_preserves_plaintext_source() {
    let mut fs = fs_base();
    fs.put("db", false, true);
    fs.export_ok = false;
    assert_eq!(ensure_encrypted(&mut fs, "pw"), Err(ERR_EXPORT));
    assert!(fs.readable_plain(DbFile::Database), "plaintext source preserved");
    assert!(!fs.exists(DbFile::Temp));
    // catch { removeOrThrow(temp); throw } - tmp absent, delete never runs
    assert_eq!(fs.log, vec!["export"]);
}

#[test]
fn export_cleanup_failure_masks_the_export_message() {
    // Source quirk: the catch's removeOrThrow(temp) throws ERR_REMOVE and
    // the original ERR_EXPORT is replaced by the in-flight throw. Reachable
    // when the failed export LEFT a partial temp behind.
    struct PartialExport(VirtFs);
    impl EncryptionFs for PartialExport {
        fn dir_available(&self) -> bool { true }
        fn dir_exists(&self) -> bool { true }
        fn mkdirs(&mut self) -> bool { true }
        fn exists(&self, f: DbFile) -> bool { self.0.exists(f) }
        fn readable_with_key(&self, f: DbFile) -> bool { self.0.readable_key(f) }
        fn readable_plaintext(&self, f: DbFile) -> bool { self.0.readable_plain(f) }
        fn remove(&mut self, f: DbFile) -> bool { self.0.remove(f) }
        fn rename(&mut self, from: DbFile, to: DbFile) -> bool { self.0.rename(from, to) }
        fn export(&mut self) -> Result<(), ()> {
            self.0.log.push("export".into());
            self.0.put("tmp", false, false); // partial output left behind
            Err(())
        }
    }
    let mut fx = PartialExport({
        let mut v = fs_base();
        v.put("db", false, true);
        v.fail_remove.insert("tmp", 1);
        v
    });
    assert_eq!(ensure_encrypted(&mut fx, "pw"), Err(ERR_REMOVE));
    assert_eq!(fx.0.log, vec!["export", "rm:tmp"]);
}

#[test]
fn export_pre_remove_failure_is_not_wrapped() {
    // The export helper's OWN removeOrThrow(destination) precedes its try:
    // a failure there surfaces as ERR_REMOVE, never as ERR_EXPORT. Race
    // model: the flow's removeOrThrow(temp) succeeds but a new temp file
    // appears before the export helper runs its own delete, which fails.
    struct RaceFs(VirtFs, bool);
    impl EncryptionFs for RaceFs {
        fn dir_available(&self) -> bool { true }
        fn dir_exists(&self) -> bool { true }
        fn mkdirs(&mut self) -> bool { true }
        fn exists(&self, f: DbFile) -> bool { self.0.exists(f) }
        fn readable_with_key(&self, f: DbFile) -> bool { self.0.readable_key(f) }
        fn readable_plaintext(&self, f: DbFile) -> bool { self.0.readable_plain(f) }
        fn remove(&mut self, f: DbFile) -> bool {
            let ok = self.0.remove(f);
            if ok && matches!(f, DbFile::Temp) && !self.1 {
                self.1 = true; // arm once
                self.0.put("tmp", false, false); // concurrent recreation
            }
            ok
        }
        fn rename(&mut self, from: DbFile, to: DbFile) -> bool { self.0.rename(from, to) }
        fn export(&mut self) -> Result<(), ()> {
            self.0.log.push("export".into());
            Ok(())
        }
    }
    let mut fx = RaceFs(
        {
            let mut v = fs_base();
            v.put("db", false, true);
            v.put("tmp", false, false);
            v.fail_remove.insert("tmp", 2); // the SECOND rm (export helper) fails
            v
        },
        false,
    );
    // First rm:tmp succeeds (concurrent recreation), the export helper's
    // own rm:tmp fails -> ERR_REMOVE, export pipeline never ran.
    assert_eq!(ensure_encrypted(&mut fx, "pw"), Err(ERR_REMOVE));
    assert_eq!(fx.0.log, vec!["rm:tmp", "rm:tmp"], "helper remove fired before export");
}

#[test]
fn temp_verification_failure_removes_temp_and_throws() {
    // Custom fs whose export "succeeds" but leaves the temp unreadable.
    struct BadExport(VirtFs);
    impl EncryptionFs for BadExport {
        fn dir_available(&self) -> bool { true }
        fn dir_exists(&self) -> bool { true }
        fn mkdirs(&mut self) -> bool { true }
        fn exists(&self, f: DbFile) -> bool { self.0.exists(f) }
        fn readable_with_key(&self, f: DbFile) -> bool { self.0.readable_key(f) }
        fn readable_plaintext(&self, f: DbFile) -> bool { self.0.readable_plain(f) }
        fn remove(&mut self, f: DbFile) -> bool { self.0.remove(f) }
        fn rename(&mut self, from: DbFile, to: DbFile) -> bool { self.0.rename(from, to) }
        fn export(&mut self) -> Result<(), ()> {
            self.0.log.push("export".into());
            self.0.put("tmp", false, false); // produced but unreadable
            Ok(())
        }
    }
    let mut fx = BadExport({
        let mut v = fs_base();
        v.put("db", false, true);
        v
    });
    assert_eq!(ensure_encrypted(&mut fx, "pw"), Err(ERR_VERIFY_TEMP));
    assert_eq!(fx.0.log, vec!["export", "rm:tmp"]);
    assert!(fx.0.readable_plain(DbFile::Database), "original preserved");
}

#[test]
fn promote_rename_failure_best_effort_restores_backup() {
    let mut fs = fs_base();
    fs.put("db", false, true);
    fs.fail_rename.insert(("tmp", "db"));
    assert_eq!(ensure_encrypted(&mut fs, "pw"), Err(ERR_PROMOTE));
    // db moved to bak first, tmp->db failed, best-effort bak->db restored it
    assert!(fs.readable_plain(DbFile::Database), "original restored best-effort");
    assert_eq!(fs.log, vec!["export", "mv:db->bak", "mv:tmp->db", "mv:bak->db"]);
}

#[test]
fn final_verification_failure_restores_backup_and_throws() {
    struct FinalBad(VirtFs);
    impl EncryptionFs for FinalBad {
        fn dir_available(&self) -> bool { true }
        fn dir_exists(&self) -> bool { true }
        fn mkdirs(&mut self) -> bool { true }
        fn exists(&self, f: DbFile) -> bool { self.0.exists(f) }
        fn readable_with_key(&self, f: DbFile) -> bool {
            // temp verifies; the PROMOTED db does not (corrupt in place)
            match f {
                DbFile::Temp => self.0.readable_key(f),
                DbFile::Database => {
                    // only after promotion (tmp consumed) is db probed; make
                    // the promoted file unreadable
                    !self.0.exists(DbFile::Temp) && false || self.0.exists(DbFile::Temp) && self.0.readable_key(DbFile::Database)
                }
                _ => false,
            }
        }
        fn readable_plaintext(&self, f: DbFile) -> bool { self.0.readable_plain(f) }
        fn remove(&mut self, f: DbFile) -> bool { self.0.remove(f) }
        fn rename(&mut self, from: DbFile, to: DbFile) -> bool { self.0.rename(from, to) }
        fn export(&mut self) -> Result<(), ()> {
            self.0.put("tmp", true, false);
            self.0.log.push("export".into());
            Ok(())
        }
    }
    let mut fx = FinalBad({
        let mut v = fs_base();
        v.put("db", false, true);
        v
    });
    assert_eq!(ensure_encrypted(&mut fx, "pw"), Err(ERR_VERIFY_FINAL));
    assert_eq!(
        fx.0.log,
        vec!["export", "mv:db->bak", "mv:tmp->db", "rm:db", "mv:bak->db"]
    );
    assert!(fx.0.readable_plain(DbFile::Database), "backup restored after final failure");
}

#[test]
fn remove_sidecar_failure_stops_the_list() {
    let mut fs = fs_base();
    fs.put("db", true, false);
    fs.put("shm", false, false);
    fs.fail_remove.insert("shm", 1);
    assert_eq!(ensure_encrypted(&mut fs, "pw"), Err(ERR_REMOVE));
    // wal attempted (absent -> no rm call logged? exists=false so remove()
    // not invoked), shm fails, jrnl never attempted
    assert_eq!(fs.log, vec!["rm:shm"]);
}

//! Guard, single-commit, and RESTART-recovery proofs for the health-sync
//! consent paths, run against the source-faithful HealthAuthPrefs over a
//! file-backed scratch prefs model (not an in-memory fake). Scope precision:
//! "restart recovery" = ordinary process-restart recovery via atomic
//! rename; power-loss durability is NOT claimed (no fsync/dirsync, no crash
//! faults). commit()-failure all-or-nothing in-memory is the scratch model's
//! assumption, not established Android contract. Synthetic only; Android
//! secure store and OwnerDataGate remain injected seams.
use cyclecare_core::auth_prefs_health::*;
use cyclecare_core::prefs_scratch::ScratchPrefs;
use cyclecare_core::v3_consent::*;
use cyclecare_core::v3_sync::V3ConsentReceipt;

const OWNER: &str = "owner-uid";

fn scratch_file(tag: &str) -> std::path::PathBuf {
    std::env::temp_dir().join(format!("cyclecare-prefs-{tag}-{}.txt", std::process::id()))
}

struct Harness {
    file: std::path::PathBuf,
}
impl Harness {
    /// Signed-in owner, session 11, cloud allowed, health generation 3,
    /// a stored enable-receipt (server 7, local 3, session 11).
    fn new(tag: &str) -> Self {
        let file = scratch_file(tag);
        let _ = std::fs::remove_file(&file);
        let mut p = ScratchPrefs::open_file(&file).unwrap();
        {
            let mut h = HealthAuthPrefs {
                prefs: &mut p, secure_available: true,
                secure_user_id: Some(OWNER.into()), device_fallback_owner: "dev-fallback".into(),
                owner_wiped: false,
                        live_user_id: None, live_owner_wiped: None,
            };
            h.prefs.edit().put_long("session_generation_v3", 11).commit();
            h.set_is_cloud_sync_allowed(true).unwrap(); // generation 0 -> 1
            h.set_health_consent_generation(3);
            assert!(h.save_server_health_consent_receipt(OWNER, 11, 3, 7, true));
            assert_eq!(h.server_health_consent_generation(), Some(7));
        }
        Harness { file }
    }
    fn with<R>(&self, f: impl FnOnce(&mut HealthAuthPrefs) -> R) -> R {
        let mut p = ScratchPrefs::open_file(&self.file).unwrap();
        let mut h = HealthAuthPrefs {
            prefs: &mut p, secure_available: true,
            secure_user_id: Some(OWNER.into()), device_fallback_owner: "dev-fallback".into(),
            owner_wiped: false,
                    live_user_id: None, live_owner_wiped: None,
        };
        f(&mut h)
    }
}
impl Drop for Harness {
    fn drop(&mut self) { let _ = std::fs::remove_file(&self.file); let _ = std::fs::remove_file(self.file.with_extension("tmp")); }
}

#[test]
fn pause_persists_across_reopen() {
    let h = Harness::new("pause");
    // First "process": pause (the withdrawal's local step).
    let gen = h.with(|p| p.pause_health_sync_for(OWNER, 11).unwrap());
    assert_eq!(gen, Some(4)); // allowed -> addExact(3, 1)
    // Reopen from disk: every element of the single commit survives.
    h.with(|p| {
        assert!(!p.is_cloud_sync_allowed());
        assert_eq!(p.health_consent_generation(), 4);
        assert!(p.has_pending_health_sync_revoke(), "durable pending-revoke flag");
        assert_eq!(p.server_health_consent_generation(), None, "receipt keys removed");
    });
}

#[test]
fn commit_failure_changes_memory_but_not_disk_aosp_grounded() {
    // AOSP 14 SharedPreferencesImpl.commit(): commitToMemory() lands the
    // batch in memory, then the disk write fails -> false. So after the
    // failed pause: THIS process sees the paused state, while a restart
    // (disk) sees the OLD state. Kotlin pause returns null either way.
    let h = Harness::new("commitfail");
    h.with(|p| {
        p.prefs.inject_commit_failure = true;
        assert_eq!(p.pause_health_sync_for(OWNER, 11).unwrap(), None, "commit() false -> Kotlin null");
        assert!(!p.is_cloud_sync_allowed(), "commitToMemory landed: memory already changed");
        assert!(p.has_pending_health_sync_revoke(), "pending flag set in memory");
        assert_eq!(p.health_consent_generation(), 4);
    });
    // Reopen from disk: the failed write left the old file intact.
    h.with(|p| {
        assert!(p.is_cloud_sync_allowed(), "disk kept the old state");
        assert_eq!(p.health_consent_generation(), 3);
        assert!(!p.has_pending_health_sync_revoke());
        assert_eq!(p.server_health_consent_generation(), Some(7), "prior receipt untouched on disk");
    });
}

#[test]
fn carriage_returns_and_tabs_round_trip() {
    // escape() must cover \r (str::lines strips a trailing \r) and \t.
    let h = Harness::new("crlf");
    h.with(|p| {
        p.set_last_sync_message("line one\r\nline two\ttrail\r");
    });
    h.with(|p| assert_eq!(p.last_sync_message(), "line one\r\nline two\ttrail\r"));
}

struct FailTransport;
impl V3ConsentTransport for FailTransport {
    fn set_consent(&mut self, _e: bool, _t: &str) -> Result<V3ConsentReceipt, ()> { Err(()) }
}

#[test]
fn failed_revocation_leaves_durable_pending_flag_across_reopen() {
    // The source-comment obligation ("Failed revocation leaves the durable
    // pending flag set", SupabaseSyncService.kt:40) proved end-to-end: the
    // REAL request_v3_consent against the REAL prefs implementation, a
    // transport failure, then a restart.
    let h = Harness::new("revoke");
    h.with(|p| {
        let mut t = FailTransport;
        let mut store = cyclecare_core::v3_consent::testing::NoopSnapshotStore;
        let r = request_v3_consent(false, &mut t, "tok", false, p, &mut store, &mut || true, &mut || "op".into(), &mut || 0);
        assert_eq!(r, Err(V3ConsentError::Transport));
    });
    h.with(|p| {
        assert!(!p.is_cloud_sync_allowed(), "pause committed");
        assert_eq!(p.health_consent_generation(), 4);
        assert!(p.has_pending_health_sync_revoke(), "DURABLE pending flag survives the failed revocation + restart");
        assert_eq!(p.server_health_consent_generation(), None);
    });
}

#[test]
fn withdrawal_save_clears_flag_and_removes_receipt_durably() {
    let h = Harness::new("withdraw");
    h.with(|p| {
        let _ = p.pause_health_sync_for(OWNER, 11).unwrap();
        assert!(p.save_server_health_consent_receipt(OWNER, 11, 4, 9, false));
    });
    h.with(|p| {
        assert!(!p.has_pending_health_sync_revoke(), "pending flag cleared by the save");
        assert_eq!(p.server_health_consent_generation(), None, "receipt keys removed on disable");
        assert!(!p.is_cloud_sync_allowed());
    });
}

#[test]
fn save_receipt_guards_hold_against_real_state() {
    let h = Harness::new("guards");
    // serverGeneration <= 0 rejected.
    h.with(|p| assert!(!p.save_server_health_consent_receipt(OWNER, 11, 3, 0, true)));
    // Stale local generation rejected.
    h.with(|p| assert!(!p.save_server_health_consent_receipt(OWNER, 11, 2, 9, true)));
    // Wrong session rejected.
    h.with(|p| assert!(!p.save_server_health_consent_receipt(OWNER, 12, 3, 9, true)));
    // Wrong owner rejected.
    h.with(|p| assert!(!p.save_server_health_consent_receipt("other", 11, 3, 9, true)));
    // Wiped owner rejects an ENABLED save (:189 OwnerDataGate guard).
    h.with(|p| { p.owner_wiped = true; assert!(!p.save_server_health_consent_receipt(OWNER, 11, 3, 9, true)); });
    // Guards reject nothing durably: original receipt intact.
    h.with(|p| assert_eq!(p.server_health_consent_generation(), Some(7)));
}

#[test]
fn server_generation_getter_guard_matrix_on_real_state() {
    let h = Harness::new("matrix");
    // Baseline: all guards green.
    h.with(|p| assert_eq!(p.server_health_consent_generation(), Some(7)));
    // Health generation advanced without a new receipt -> stale.
    h.with(|p| { p.set_health_consent_generation(4); assert_eq!(p.server_health_consent_generation(), None); });
    // Restore + mark pending revoke -> None.
    h.with(|p| { p.set_health_consent_generation(3); p.mark_pending_health_sync_revoke(); assert_eq!(p.server_health_consent_generation(), None); });
    // Cloud off -> None even with a fresh receipt.
    h.with(|p| { let _ = p.set_is_cloud_sync_allowed(false); assert_eq!(p.server_health_consent_generation(), None); });
}

#[test]
fn cloud_allowed_setter_single_commit_and_noop_parity() {
    let h = Harness::new("setter");
    // Same value -> no-op: no generation advance, no pending flag.
    h.with(|p| {
        p.set_is_cloud_sync_allowed(true).unwrap();
        assert_eq!(p.health_consent_generation(), 3, "unchanged value is a no-op");
        assert!(!p.has_pending_health_sync_revoke());
    });
    // Change true -> false: ONE commit -> decision + generation + pending flag atomic.
    h.with(|p| p.set_is_cloud_sync_allowed(false).unwrap());
    h.with(|p| {
        assert!(!p.is_cloud_sync_allowed());
        assert_eq!(p.health_consent_generation(), 4);
        assert!(p.has_pending_health_sync_revoke());
    });
    // Generation overflow is a typed ArithmeticException parity, nothing committed.
    h.with(|p| {
        p.set_health_consent_generation(i64::MAX);
        p.prefs.edit().put_bool(&format!("cloud_sync_allowed_v2_{OWNER}"), true).commit();
        p.prefs.edit().put_bool(&format!("health_sync_pending_revoke_{OWNER}"), false).commit();
        assert_eq!(p.set_is_cloud_sync_allowed(false), Err(AuthPrefsError::GenerationOverflow));
    });
    h.with(|p| assert!(p.is_cloud_sync_allowed(), "overflow threw before the commit"));
}

#[test]
fn last_sync_writes_apply_and_persist() {
    let h = Harness::new("lastsync");
    h.with(|p| {
        assert_eq!(p.last_sync_message(), "Never synced");
        p.set_last_sync_timestamp(1_700_000_000_000);
        p.set_last_sync_message("Backup complete (2 sent, 1 received).");
        p.set_last_sync_success(true);
    });
    h.with(|p| {
        assert_eq!(p.last_sync_timestamp(), 1_700_000_000_000);
        assert_eq!(p.last_sync_message(), "Backup complete (2 sent, 1 received).");
        assert!(p.last_sync_success());
    });
}

#[test]
fn effective_user_id_scopes_keys_and_falls_back_to_device() {
    let h = Harness::new("effective");
    // Signed out: keys scope to the injected device fallback owner instead.
    h.with(|p| {
        p.secure_user_id = None;
        assert_eq!(p.effective_user_id(), "dev-fallback");
        assert!(!p.is_cloud_sync_allowed(), "device fallback has its own key scope");
        assert_eq!(p.health_consent_generation(), 0);
    });
}

#[test]
fn live_handles_share_one_in_memory_instance_while_alive() {
    // AOSP SharedPreferencesImpl parity: same file, one live in-memory
    // instance. Two handles see each other's commits immediately; once ALL
    // handles drop, the next open reloads from disk (restart recovery).
    let f = scratch_file("shared");
    let _ = std::fs::remove_file(&f);
    let mut a = ScratchPrefs::open_file(&f).unwrap();
    a.edit().put_long("k", 41).commit();
    let b = ScratchPrefs::open_file(&f).unwrap(); // opened while a is alive
    assert_eq!(b.get_long("k", 0), 41, "second handle shares the live instance, not a stale copy");
    a.edit().put_bool("flag", true).commit();
    assert!(b.get_bool("flag", false), "commits after BOTH opens are visible to both");
    drop(a);
    drop(b);
    let c = ScratchPrefs::open_file(&f).unwrap(); // all handles dropped -> reload from disk
    assert_eq!(c.get_long("k", 0), 41);
    assert!(c.get_bool("flag", false));
    let _ = std::fs::remove_file(&f);
}

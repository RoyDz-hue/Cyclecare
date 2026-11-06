//! requestV3Consent + journalExplicitSnapshot corpus
//! (SupabaseSyncService.kt:31-57, AuthPreferences.kt:168-194). Synthetic only.
//! MemPrefs reproduces the Kotlin prefs internals EXACTLY (guards, single
//! commit, commit() failure) so the service port is locked against them.
//! MemSnapshot snapshots state and restores on Err (rollback model); real-DB
//! rollback proofs for the pull path live in v3_sqlite_txn.rs.
use cyclecare_core::sync_codec::{CycleDayLog, CycleSettings, OnboardingProfile};
use cyclecare_core::v3_consent::*;
use cyclecare_core::v3_outbox::OutboxEntry;
use cyclecare_core::v3_pull::RevisionRow;
use cyclecare_core::v3_sync::V3ConsentReceipt;

const OWNER: &str = "owner-uid";

// --- MemPrefs: AuthPreferences.kt internals, 1:1 ---------------------------
struct MemPrefs {
    secure: bool,
    user_id: Option<String>,
    session: i64,
    cloud_allowed: bool,
    health_gen: i64,
    pending_revoke: bool,
    receipt: Option<(i64, i64, i64)>, // (server, local, session)
    wiped: bool,
    commit_ok: bool,
    /// Models a sessionGeneration read that races a later pauseHealthSyncFor
    /// (the value changes between the service's read and the prefs mutation).
    reported_session: Option<i64>,
}
impl MemPrefs {
    fn healthy() -> Self {
        MemPrefs {
            secure: true, user_id: Some(OWNER.into()), session: 11,
            cloud_allowed: true, health_gen: 3, pending_revoke: false,
            receipt: Some((7, 3, 11)), wiped: false, commit_ok: true,
            reported_session: None,
        }
    }
    /// current(owner, session, local, server) — SupabaseSyncService.kt:27.
    fn current(&self, owner: &str, session: i64, local: i64, server: i64) -> bool {
        self.user_id.as_deref() == Some(owner)
            && self.session == session
            && self.health_gen == local
            && self.receipt.map(|(s, _, _)| s) == Some(server)
            && self.cloud_allowed
            && !self.wiped
    }
}
impl ConsentPrefs for MemPrefs {
    fn user_id(&mut self) -> Option<String> { self.user_id.clone() }
    fn session_generation(&mut self) -> i64 { self.reported_session.unwrap_or(self.session) }
    fn is_cloud_sync_allowed(&mut self) -> bool { self.cloud_allowed }
    fn has_pending_health_sync_revoke(&mut self) -> bool { self.secure && self.pending_revoke }
    fn health_consent_generation(&mut self) -> i64 { self.health_gen }
    fn pause_health_sync_for(&mut self, owner: &str, session: i64) -> Result<Option<i64>, V3ConsentError> {
        if !self.secure || self.user_id.as_deref() != Some(owner) || self.session != session {
            return Ok(None);
        }
        let prev = self.health_gen;
        // Math.addExact(previousGeneration, 1L) only when sync was allowed.
        let generation = if self.cloud_allowed {
            prev.checked_add(1).ok_or(V3ConsentError::RevisionOverflow)?
        } else { prev };
        if !self.commit_ok { return Ok(None); }
        self.cloud_allowed = false;
        self.health_gen = generation;
        self.pending_revoke = true;
        self.receipt = None; // remove server/local/session receipt keys
        Ok(Some(generation))
    }
    fn save_server_health_consent_receipt(&mut self, owner: &str, session: i64, local: i64, server: i64, enabled: bool) -> bool {
        if !self.secure
            || self.user_id.as_deref() != Some(owner)
            || self.session != session
            || self.health_gen != local
            || server <= 0
            || (enabled && (!self.cloud_allowed || self.pending_revoke || self.wiped))
        { return false; }
        if !self.commit_ok { return false; }
        self.pending_revoke = false;
        self.receipt = if enabled { Some((server, local, session)) } else { None };
        true
    }
}

// --- Transport -------------------------------------------------------------
struct T { calls: Vec<(bool, String)>, next: Result<V3ConsentReceipt, ()> }
impl V3ConsentTransport for T {
    fn set_consent(&mut self, enabled: bool, token: &str) -> Result<V3ConsentReceipt, ()> {
        self.calls.push((enabled, token.to_string()));
        self.next.clone()
    }
}

// --- MemSnapshot: snapshot/restore transaction model ------------------------
#[derive(Clone, Default)]
struct SState {
    revisions: std::collections::HashMap<String, RevisionRow>,
    removed_pending: Vec<(String, i64)>,
    outbox: Vec<OutboxEntry>,
    logs: Vec<CycleDayLog>,
    settings: Option<CycleSettings>,
    profile: Option<OnboardingProfile>,
}
#[derive(Default)]
struct SStore { st: SState, fail_enqueue_at: Option<usize>, enqueues: usize }
struct STx<'a> { st: &'a mut SState, fail_at: Option<usize>, n: &'a mut usize }
impl V3SnapshotTx for STx<'_> {
    fn revision(&mut self, _o: &str, id: &str) -> Result<Option<RevisionRow>, V3ConsentError> { Ok(self.st.revisions.get(id).cloned()) }
    fn remove_reviewed_pending(&mut self, _o: &str, id: &str, through: i64) -> Result<(), V3ConsentError> { self.st.removed_pending.push((id.into(), through)); Ok(()) }
    fn put_revision(&mut self, _o: &str, id: &str, rev: i64, _h: Option<String>, _t: bool, _n: i64, srv: i64) -> Result<(), V3ConsentError> {
        self.st.revisions.insert(id.into(), RevisionRow { revision: rev, server_revision: srv }); Ok(())
    }
    fn enqueue(&mut self, e: OutboxEntry) -> Result<(), V3ConsentError> {
        *self.n += 1;
        if Some(*self.n) == self.fail_at { return Err(V3ConsentError::Db); }
        self.st.outbox.push(e); Ok(())
    }
    fn all_logs(&mut self) -> Result<Vec<CycleDayLog>, V3ConsentError> { Ok(self.st.logs.clone()) }
    fn settings(&mut self) -> Result<Option<CycleSettings>, V3ConsentError> { Ok(self.st.settings.clone()) }
    fn profile(&mut self) -> Result<Option<OnboardingProfile>, V3ConsentError> { Ok(self.st.profile.clone()) }
}
impl V3SnapshotStore for SStore {
    fn transaction(&mut self, body: &mut dyn FnMut(&mut dyn V3SnapshotTx) -> Result<(), V3ConsentError>) -> Result<(), V3ConsentError> {
        let snap = self.st.clone();
        self.enqueues = 0;
        let r = { let mut tx = STx { st: &mut self.st, fail_at: self.fail_enqueue_at, n: &mut self.enqueues }; body(&mut tx) };
        if r.is_err() { self.st = snap; } // ROLLBACK EVERYTHING
        r
    }
}

fn log(date: &str) -> CycleDayLog {
    CycleDayLog {
        date: date.into(), flow: "LIGHT".into(), energy: "".into(), mood: "".into(),
        symptoms: "".into(), vaginal_health: "".into(), urinary_health: "".into(),
        cervical_mucus: "".into(), ovulation_test: "".into(), bbt_celsius: 0.0,
        sleep_hours: 0.0, sleep_quality: "".into(), water_glasses: 0, notes: "".into(),
        pain_intensity: 0,
    }
}
fn live() -> impl FnMut() -> bool { || true }
fn now() -> impl FnMut() -> i64 { || 1_700_000_000_000 }
fn uuids() -> impl FnMut() -> String {
    let mut n = 0u32;
    move || { n += 1; format!("00000000-0000-4000-8000-{n:012}") }
}
fn withdraw(prefs: &mut MemPrefs, t: &mut T, store: &mut SStore) -> Result<V3ConsentReceipt, V3ConsentError> {
    request_v3_consent(false, t, "tok", false, prefs, store, &mut live(), &mut uuids(), &mut now())
}

#[test]
fn withdrawal_happy_path_pauses_then_revokes_and_clears_receipt() {
    let mut prefs = MemPrefs::healthy();
    let mut t = T { calls: vec![], next: Ok(V3ConsentReceipt { enabled: false, generation: 9, reason: None }) };
    let mut store = SStore::default();
    let r = withdraw(&mut prefs, &mut t, &mut store).expect("withdrawal succeeds while release closed");
    assert_eq!(r, V3ConsentReceipt { enabled: false, generation: 9, reason: None });
    assert_eq!(t.calls, vec![(false, "tok".to_string())]);
    // pauseHealthSyncFor: allowed=true -> addExact(3,1)=4; one commit applies all.
    assert!(!prefs.cloud_allowed);
    assert_eq!(prefs.health_gen, 4);
    // save(..., enabled=false) clears the pending flag and removes receipt keys.
    assert!(!prefs.pending_revoke);
    assert_eq!(prefs.receipt, None);
}

#[test]
fn enabled_true_always_release_policy_closed_no_side_effects() {
    // Production parity: conflictSafeProtocolReleased()=false gates the
    // enabled arm BEFORE any prefs read/mutation or transport call.
    let mut prefs = MemPrefs::healthy();
    let before_gen = prefs.health_gen;
    let mut t = T { calls: vec![], next: Ok(V3ConsentReceipt { enabled: true, generation: 9, reason: None }) };
    let mut store = SStore::default();
    let err = request_v3_consent(true, &mut t, "tok", true, &mut prefs, &mut store, &mut live(), &mut uuids(), &mut now());
    assert_eq!(err, Err(V3ConsentError::ReleasePolicyClosed));
    assert!(t.calls.is_empty(), "transport never touched");
    assert_eq!(prefs.health_gen, before_gen);
    assert!(prefs.cloud_allowed && !prefs.pending_revoke && prefs.receipt.is_some());
}

#[test]
fn missing_owner_is_owner_required_before_anything() {
    let mut prefs = MemPrefs::healthy();
    prefs.user_id = None;
    let mut t = T { calls: vec![], next: Ok(V3ConsentReceipt { enabled: false, generation: 9, reason: None }) };
    let mut store = SStore::default();
    assert_eq!(withdraw(&mut prefs, &mut t, &mut store), Err(V3ConsentError::OwnerRequired));
    assert!(t.calls.is_empty());
}

#[test]
fn pause_guard_failure_is_owner_changed_and_never_calls_transport() {
    let mut prefs = MemPrefs::healthy();
    prefs.session = 99; // generation advanced after the service read it
    prefs.reported_session = Some(11); // -> pauseHealthSyncFor(owner, 11) mismatches
    let mut t = T { calls: vec![], next: Ok(V3ConsentReceipt { enabled: false, generation: 9, reason: None }) };
    let mut store = SStore::default();
    assert_eq!(withdraw(&mut prefs, &mut t, &mut store), Err(V3ConsentError::OwnerChanged));
    assert!(t.calls.is_empty(), "setConsent happens only after a local pause");
    assert!(prefs.cloud_allowed && !prefs.pending_revoke, "prefs untouched");
}

#[test]
fn failed_revocation_leaves_durable_pending_flag_set() {
    // Source comment (:40): "Failed revocation leaves the durable pending
    // flag set." setConsent throws -> Transport; pause state persists.
    let mut prefs = MemPrefs::healthy();
    let mut t = T { calls: vec![], next: Err(()) };
    let mut store = SStore::default();
    assert_eq!(withdraw(&mut prefs, &mut t, &mut store), Err(V3ConsentError::Transport));
    assert!(!prefs.cloud_allowed);
    assert_eq!(prefs.health_gen, 4);
    assert!(prefs.pending_revoke, "durable pending-revoke flag survives the failed revocation");
}

#[test]
fn receipt_enabled_mismatch_short_circuits_save() {
    // receipt.enabled != enabled -> && short-circuits: saveServerHealthConsentReceipt
    // is NOT attempted, so the pending flag from pause stays set.
    let mut prefs = MemPrefs::healthy();
    let mut t = T { calls: vec![], next: Ok(V3ConsentReceipt { enabled: true, generation: 9, reason: None }) };
    let mut store = SStore::default();
    assert_eq!(withdraw(&mut prefs, &mut t, &mut store), Err(V3ConsentError::StaleOrInvalidConsentReceipt));
    assert!(prefs.pending_revoke, "save skipped by short-circuit: flag not cleared");
}

#[test]
fn nonpositive_server_generation_fails_the_save_guard() {
    let mut prefs = MemPrefs::healthy();
    let mut t = T { calls: vec![], next: Ok(V3ConsentReceipt { enabled: false, generation: 0, reason: None }) };
    let mut store = SStore::default();
    assert_eq!(withdraw(&mut prefs, &mut t, &mut store), Err(V3ConsentError::StaleOrInvalidConsentReceipt));
    assert!(prefs.pending_revoke);
}

#[test]
fn pause_addexact_overflow_is_typed_error_and_commits_nothing() {
    let mut prefs = MemPrefs::healthy();
    prefs.health_gen = i64::MAX;
    let mut t = T { calls: vec![], next: Ok(V3ConsentReceipt { enabled: false, generation: 9, reason: None }) };
    let mut store = SStore::default();
    assert_eq!(withdraw(&mut prefs, &mut t, &mut store), Err(V3ConsentError::RevisionOverflow));
    assert!(prefs.cloud_allowed && !prefs.pending_revoke, "ArithmeticException parity: throw precedes the commit");
    assert!(t.calls.is_empty());
}

#[test]
fn journal_snapshots_in_source_order_with_prior_based_sequences() {
    let mut store = SStore::default();
    store.st.logs = vec![log("2026-11-01"), log("2026-11-02")];
    store.st.settings = Some(CycleSettings::default());
    store.st.profile = Some(OnboardingProfile::default());
    store.st.revisions.insert("2026-11-01".into(), RevisionRow { revision: 5, server_revision: 2 });
    let mut prefs = MemPrefs::healthy();
    let (session, local, server) = (prefs.session, prefs.health_gen, 7);
    let mut cur = || prefs.current(OWNER, session, local, server);
    journal_explicit_snapshot(OWNER, session, local, server, &mut store, &mut cur, &mut uuids(), &mut now()).unwrap();
    // Source order: logs in DAO order, then settings, then profile.
    let ids: Vec<&str> = store.st.outbox.iter().map(|e| e.record_id.as_str()).collect();
    assert_eq!(ids, vec!["2026-11-01", "2026-11-02", "settings", "profile"]);
    // Prior-based sequencing: addExact(prior.revision,1); base = prior.serverRevision.
    let first = &store.st.outbox[0];
    assert_eq!((first.revision, first.base_revision), (6, 2));
    // removeReviewedPending is called for EVERY record (through = prior?.revision ?: 0).
    assert_eq!(store.st.removed_pending, vec![
        ("2026-11-01".to_string(), 5), ("2026-11-02".to_string(), 0),
        ("settings".to_string(), 0), ("profile".to_string(), 0),
    ]);
    // Fresh rows default attempts/nextAttemptAt = 0; consent generation = local.
    for e in &store.st.outbox {
        assert_eq!((e.kind.as_str(), e.attempts, e.next_attempt_at, e.consent_generation), ("UPSERT", 0, 0, local));
        assert_eq!(e.payload_hash, cyclecare_core::sync_codec::hash(e.payload_json.as_deref()));
    }
    // Distinct runtime-provided operation ids (UUID provider used per entry).
    let ops: std::collections::HashSet<&str> = store.st.outbox.iter().map(|e| e.operation_id.as_str()).collect();
    assert_eq!(ops.len(), 4);
    assert_eq!(store.st.revisions["2026-11-01"], RevisionRow { revision: 6, server_revision: 2 });
}

#[test]
fn journal_rolls_back_everything_when_consent_changes_mid_snapshot() {
    let mut store = SStore::default();
    store.st.logs = vec![log("2026-11-01")];
    let mut calls = 0;
    // Gate flips false on the FIRST re-check inside the transaction.
    let mut cur = move || { calls += 1; false };
    let err = journal_explicit_snapshot(OWNER, 11, 3, 7, &mut store, &mut cur, &mut uuids(), &mut now());
    assert_eq!(err, Err(V3ConsentError::ConsentChanged));
    assert!(store.st.outbox.is_empty() && store.st.revisions.is_empty() && store.st.removed_pending.is_empty());
}

#[test]
fn journal_db_fault_rolls_back_the_whole_snapshot() {
    let mut store = SStore::default();
    store.st.logs = vec![log("2026-11-01"), log("2026-11-02")];
    store.fail_enqueue_at = Some(2); // second enqueue throws
    let err = journal_explicit_snapshot(OWNER, 11, 3, 7, &mut store, &mut live(), &mut uuids(), &mut now());
    assert_eq!(err, Err(V3ConsentError::Db));
    assert!(store.st.outbox.is_empty(), "first record's enqueue rolled back");
    assert!(store.st.revisions.is_empty(), "first record's putRevision rolled back");
    assert!(store.st.removed_pending.is_empty());
}

#[test]
fn journal_revision_overflow_is_typed_and_rolls_back() {
    let mut store = SStore::default();
    store.st.logs = vec![log("2026-11-01"), log("2026-11-02")];
    store.st.revisions.insert("2026-11-02".into(), RevisionRow { revision: i64::MAX, server_revision: 1 });
    let err = journal_explicit_snapshot(OWNER, 11, 3, 7, &mut store, &mut live(), &mut uuids(), &mut now());
    assert_eq!(err, Err(V3ConsentError::RevisionOverflow));
    assert!(store.st.outbox.is_empty() && store.st.revisions.len() == 1, "first record rolled back; prior row untouched");
    assert_eq!(store.st.revisions["2026-11-02"].revision, i64::MAX);
}

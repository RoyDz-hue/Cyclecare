//! Portable sequencing for `CycleCareViewModel.deleteCloudHealthCopy`
//! (:719-747, main 31b88f5): an ACCOUNT-MUTATION (consent/data) privacy
//! sequence ported as INJECTED-EFFECTS CONTROL FLOW. `run_deletion` drives
//! the exact source order; every host capability (pause persistence, privacy
//! effects, token fetch, the delete RPC itself, message display) is injected
//! through `DeletionEffects`, so the sequence — persist pause BEFORE any
//! RPC, abort online-untouched when the pause cannot be saved, owner/session
//! rechecks at :741 and :743 — cannot be reordered or skipped by a caller.
//!
//! The deletion RPC stays host-executed and owner-authorized: it is the
//! `delete_cloud_health_copy` effect, invoked by this driver exactly once,
//! only after a persisted pause and a passed first recheck.
//! Line citations grep-verified against main 31b88f5.

use crate::kotlin_text::kotlin_is_blank;

/// :721-723 no connected online account.
pub const MSG_NO_ONLINE_ACCOUNT: &str =
    "No online account is connected, so only this device's records were erased.";
/// :731-733 the withdrawal could not be persisted; nothing was erased online.
pub const MSG_SAVE_FAILED: &str =
    "Your choice couldn't be saved, so nothing was erased online. Please try again.";
/// :738 in-flight progress message (trailing U+2026).
pub const MSG_ERASING: &str = "Erasing your online copy\u{2026}";
/// :745-746 confirmed deletion.
pub const MSG_DELETED: &str = "Your online records were erased. The copies on this device remain, and backup is off. Your account, purchases and coins are kept.";
/// :746-747 unconfirmed deletion: backup stays off, do not assume erasure.
pub const MSG_UNCONFIRMED: &str = "The online erase couldn't be confirmed. Backup stays off. Please try again before assuming your online copy is gone.";

/// Host-injected effects, called by the driver in source order.
pub trait DeletionEffects {
    /// Live owner/session reads for the mid-flight rechecks (:725-726 vs
    /// :741/:743).
    fn current_owner(&self) -> &str;
    fn current_session(&self) -> i64;
    /// :730 pauseHealthSyncFor(owner, session): persist the sync pause +
    /// consent withdrawal BEFORE any RPC. Returns false when the source
    /// returns null.
    fn persist_pause(&mut self, owner: &str, session: i64) -> bool;
    /// :735-737 force AI sharing off + reset the private conversation.
    fn apply_privacy_effects(&mut self);
    /// :738 set deleting-in-flight + cloud-sync-off flags (message is
    /// delivered via show_message(MSG_ERASING)).
    fn set_in_flight(&mut self, in_flight: bool, cloud_sync_allowed: bool);
    /// :740 validAccessToken fetch.
    fn fetch_access_token(&mut self) -> Option<String>;
    /// :742 the deletion RPC. Host-executed; returns the source's `deleted`.
    fn delete_cloud_health_copy(&mut self, token: Option<&str>) -> bool;
    /// Show a terminal/progress message.
    fn show_message(&mut self, message: &'static str);
    /// :744 clear the in-flight flag when the result is applied.
    fn clear_in_flight(&mut self);
}

/// Terminal outcome of the driven sequence.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DeletionOutcome {
    /// :720 already in flight; nothing happened.
    Busy,
    /// :721-724 no online account.
    NoOnlineAccount,
    /// :730-733 pause could not be persisted; nothing online was touched.
    SaveFailed,
    /// :741 owner/session changed before the RPC; abandoned silently.
    StaleBeforeRpc,
    /// :743 owner/session changed during the RPC; result discarded.
    StaleAfterRpc,
    /// Result applied with MSG_DELETED.
    Deleted,
    /// Result applied with MSG_UNCONFIRMED.
    Unconfirmed,
}

/// Drive the full source sequence. `owner`/`session` are captured at
/// :725-726 from the FIRST current_owner/current_session reads after the
/// entry guards (the host must report a consistent pair).
pub fn run_deletion<E: DeletionEffects>(
    fx: &mut E,
    is_deleting_cloud_health: bool,
    is_local_only_mode: bool,
    user_id: Option<&str>,
) -> DeletionOutcome {
    // :720
    if is_deleting_cloud_health {
        return DeletionOutcome::Busy;
    }
    // :721-724
    if is_local_only_mode || user_id.map(kotlin_is_blank).unwrap_or(true) {
        fx.show_message(MSG_NO_ONLINE_ACCOUNT);
        return DeletionOutcome::NoOnlineAccount;
    }
    // :725-726
    let owner = fx.current_owner().to_string();
    let session = fx.current_session();
    // :727-733 persist the withdrawal BEFORE any RPC; abort on failure.
    if !fx.persist_pause(&owner, session) {
        fx.show_message(MSG_SAVE_FAILED);
        return DeletionOutcome::SaveFailed;
    }
    // :735-738 privacy effects, in-flight flags, progress message.
    fx.apply_privacy_effects();
    fx.set_in_flight(true, false);
    fx.show_message(MSG_ERASING);
    // :740 token fetch.
    let token = fx.fetch_access_token();
    // :741 first recheck.
    if owner != fx.current_owner() || session != fx.current_session() {
        return DeletionOutcome::StaleBeforeRpc;
    }
    // :742 the RPC (exactly once, only here).
    let deleted = fx.delete_cloud_health_copy(token.as_deref());
    // :743 second recheck.
    if owner != fx.current_owner() || session != fx.current_session() {
        return DeletionOutcome::StaleAfterRpc;
    }
    // :744-747
    fx.clear_in_flight();
    if deleted {
        fx.show_message(MSG_DELETED);
        DeletionOutcome::Deleted
    } else {
        fx.show_message(MSG_UNCONFIRMED);
        DeletionOutcome::Unconfirmed
    }
}

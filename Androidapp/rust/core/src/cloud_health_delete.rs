//! Portable sequencing shell for `CycleCareViewModel.deleteCloudHealthCopy`
//! (:719-747, main 31b88f5): an ACCOUNT-MUTATION (consent/data) privacy
//! sequence. This is a TYPE-STATE control-flow port, not a bag of helpers:
//! the RPC permit (`RpcPermit`) is unconstructable until the sync pause has
//! been reported persisted, and the result can only be applied through the
//! post-RPC owner/session recheck. Ordering is enforced by construction:
//!
//!   begin (:720-724) -> Ready
//!   Ready::after_pause (:730-733) - pause_persisted=false => SaveFailed
//!   Paused: privacy_effects (:735-738) applied by host
//!   Paused::permit_rpc (:741 first owner/session recheck) -> RpcPermit|Stale
//!   host performs the deletion RPC (host capability, owner-authorized)
//!   RpcPermit::complete (:743 second recheck) -> Applied|Stale
//!
//! The deletion RPC itself stays host-executed; this module holds no
//! capability to perform it. Line citations grep-verified against main
//! 31b88f5.

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

/// Outcome of `begin` (:720-724).
pub enum BeginOutcome {
    /// :720 a deletion is already in flight; source returns silently.
    Busy,
    /// :721-724 local-only mode or null/blank user id; show
    /// MSG_NO_ONLINE_ACCOUNT, nothing else happens.
    NoOnlineAccount,
    /// Entry guards passed; persist the sync pause next.
    Ready(Ready),
}

/// Entry guards passed. `owner`/`session` are captured at :725-726.
pub struct Ready {
    owner: String,
    session: i64,
}

pub fn begin(
    is_deleting_cloud_health: bool,
    is_local_only_mode: bool,
    user_id: Option<&str>,
    owner: &str,
    session: i64,
) -> BeginOutcome {
    if is_deleting_cloud_health {
        return BeginOutcome::Busy;
    }
    if is_local_only_mode || user_id.map(kotlin_is_blank).unwrap_or(true) {
        return BeginOutcome::NoOnlineAccount;
    }
    BeginOutcome::Ready(Ready {
        owner: owner.to_string(),
        session,
    })
}

/// Outcome of persisting the pause (:730-733).
pub enum PauseOutcome {
    /// pauseHealthSyncFor returned null: show MSG_SAVE_FAILED; nothing online
    /// is touched. No `Paused` value exists on this path, so no later stage
    /// can run.
    SaveFailed,
    /// Pause persisted BEFORE any RPC (:727-733 ordering).
    Persisted(Paused),
}

impl Ready {
    pub fn after_pause(self, pause_persisted: bool) -> PauseOutcome {
        if pause_persisted {
            PauseOutcome::Persisted(Paused {
                owner: self.owner,
                session: self.session,
            })
        } else {
            PauseOutcome::SaveFailed
        }
    }
}

/// Pause persisted; privacy effects pending, RPC not yet permitted.
pub struct Paused {
    owner: String,
    session: i64,
}

/// :735-738 state applied once the pause is persisted: AI sharing forced off,
/// private conversation reset (host effect), cloud sync flagged off, deletion
/// marked in flight, progress message shown.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DeleteInFlightState {
    pub is_deleting_cloud_health: bool,
    pub is_cloud_sync_allowed: bool,
    pub is_ai_sharing_allowed: bool,
    pub message: &'static str,
}

impl Paused {
    pub fn privacy_effects(&self) -> DeleteInFlightState {
        DeleteInFlightState {
            is_deleting_cloud_health: true,
            is_cloud_sync_allowed: false,
            is_ai_sharing_allowed: false,
            message: MSG_ERASING,
        }
    }

    /// :741 FIRST owner/session recheck, evaluated after the access token is
    /// fetched and before the delete RPC. Only on a match does the host
    /// receive an `RpcPermit`; a mismatch abandons the request silently
    /// (source `return@launch`).
    pub fn permit_rpc(self, current_owner: &str, current_session: i64) -> Result<RpcPermit, Stale> {
        if self.owner == current_owner && self.session == current_session {
            Ok(RpcPermit {
                owner: self.owner,
                session: self.session,
            })
        } else {
            Err(Stale)
        }
    }
}

/// Owner/session still matched at :741; the host may now perform the delete
/// RPC exactly once with this permit.
pub struct RpcPermit {
    owner: String,
    session: i64,
}

/// Silent abandonment on owner/session change (:741/:743 `return@launch`).
#[derive(Debug)]
pub struct Stale;

/// Outcome of applying the RPC result (:743-747).
pub enum Completion {
    /// :743 owner/session changed during the RPC; result discarded silently.
    Stale,
    /// Result applied: deletion flag cleared, message per confirmation.
    Applied { message: &'static str },
}

impl RpcPermit {
    /// :743 SECOND owner/session recheck, then :744-747 final state. An
    /// unconfirmed outcome keeps backup off and must NOT be presented as
    /// erased.
    pub fn complete(self, deleted: bool, current_owner: &str, current_session: i64) -> Completion {
        if self.owner != current_owner || self.session != current_session {
            return Completion::Stale;
        }
        Completion::Applied {
            message: if deleted { MSG_DELETED } else { MSG_UNCONFIRMED },
        }
    }
}

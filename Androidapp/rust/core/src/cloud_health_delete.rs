//! Portable shell for `CycleCareViewModel.deleteCloudHealthCopy` (:719-747,
//! main 31b88f5): an ACCOUNT-MUTATION (consent/data) privacy sequence. Only
//! gates, ordering and messages are ported. The deletion RPC itself stays
//! host-executed and owner-authorized; this module cannot and does not
//! perform any mutation. Sequence semantics preserved from source:
//! persist the sync pause BEFORE any RPC, abort without touching anything
//! online when the pause cannot be saved, and re-check owner+session twice
//! (before the delete RPC and before applying the result).
//!
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

/// Entry decision (:720-724).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DeleteEntry {
    /// :720 a deletion is already in flight; source returns silently.
    Busy,
    /// :721-724 local-only mode or blank user id: show MSG_NO_ONLINE_ACCOUNT.
    NoOnlineAccount,
    /// Proceed to persisting the sync pause.
    Proceed,
}

pub fn delete_entry(is_deleting_cloud_health: bool, is_local_only_mode: bool, user_id: Option<&str>) -> DeleteEntry {
    if is_deleting_cloud_health {
        return DeleteEntry::Busy;
    }
    // :721 isLocalOnlyMode || userId.isNullOrBlank()
    if is_local_only_mode || user_id.map(|u| kotlin_is_blank(u)).unwrap_or(true) {
        return DeleteEntry::NoOnlineAccount;
    }
    DeleteEntry::Proceed
}

/// :730-733 the pause-withdrawal MUST be persisted before any RPC. When
/// pauseHealthSyncFor returns null the source aborts with MSG_SAVE_FAILED and
/// nothing online is touched. true = persisted, proceed to the RPC phase.
pub fn pause_must_precede_rpc(pause_persisted: bool) -> bool {
    pause_persisted
}

/// :735-738 state applied once the pause is persisted: AI sharing forced off,
/// private conversation reset (host), cloud sync flagged off, deletion marked
/// in flight, progress message shown. Returned as flags for the host to apply.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DeleteInFlightState {
    pub is_deleting_cloud_health: bool,
    pub is_cloud_sync_allowed: bool,
    pub is_ai_sharing_allowed: bool,
    pub message: &'static str,
}

pub fn in_flight_state() -> DeleteInFlightState {
    DeleteInFlightState {
        is_deleting_cloud_health: true,
        is_cloud_sync_allowed: false,
        is_ai_sharing_allowed: false,
        message: MSG_ERASING,
    }
}

/// :741/:743 owner+session must still match both before the delete RPC and
/// before applying its result; a mismatch abandons the request silently.
pub fn still_current(owner: &str, session: i64, current_owner: &str, current_session: i64) -> bool {
    owner == current_owner && session == current_session
}

/// :744-747 final state after the RPC: deletion flag cleared, message by
/// confirmation. An unconfirmed outcome keeps backup off and must NOT be
/// presented as erased.
pub fn final_message(deleted: bool) -> &'static str {
    if deleted { MSG_DELETED } else { MSG_UNCONFIRMED }
}

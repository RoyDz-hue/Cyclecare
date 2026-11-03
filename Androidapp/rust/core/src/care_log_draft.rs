//! Port of the care-log draft machine in CycleCareViewModel
//! (editCareLog :856-860, cancelCareLog :862-866, confirmCareLog :868-882,
//! main 31b88f5). The consent-gate delegate (careLogConsentGate) and the
//! repository writes stay host glue; the null-guards, state clears, owner
//! rechecks and the saved-message literal are ported.

/// :878 saved message after merge+save.
pub const CARE_LOG_SAVED_MESSAGE: &str =
    "Saved to your health log. No coins were awarded for this health entry.";

/// Draft state: whether a proposal is pending (source stores the whole
/// proposal; only presence matters to the state machine).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CareLogDraft {
    pub pending: bool,
}

/// :856-860 editCareLog always (re)proposes and shows the interpretation.
pub fn edit_proposed() -> CareLogDraft {
    CareLogDraft { pending: true }
}

/// :862-866 cancelCareLog clears the draft and turn id.
pub fn cancelled() -> CareLogDraft {
    CareLogDraft { pending: false }
}

/// Outcome of confirmCareLog (:868-882).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConfirmOutcome {
    /// :869 no pending proposal; source returns silently, state untouched.
    NoPending,
    /// :870 the gate's confirm returned null; source returns silently with
    /// the draft still pending.
    ConfirmRejected,
    /// Draft cleared; host proceeds to the async merge/save phase.
    Cleared,
}

pub fn confirm(draft: CareLogDraft, gate_confirmed: bool) -> ConfirmOutcome {
    if !draft.pending {
        return ConfirmOutcome::NoPending;
    }
    if !gate_confirmed {
        return ConfirmOutcome::ConfirmRejected;
    }
    ConfirmOutcome::Cleared
}

/// :874/:876 owner rechecks around the existing-log read: the confirmed
/// proposal's owner must still be the effective user at BOTH checkpoints.
pub fn confirmed_owner_still_current(confirmed_owner: &str, current_owner: &str) -> bool {
    confirmed_owner == current_owner
}

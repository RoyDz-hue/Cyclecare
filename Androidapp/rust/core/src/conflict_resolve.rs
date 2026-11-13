//! User-initiated sync-conflict resolution.
//! Port of CycleCareViewModel.resolveSyncConflict (main@31b88f5): guarded by
//! an owner/session snapshot re-checked after EVERY async step, re-entrant
//! calls refused, and outcomes reported honestly (no silent merge claims).

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConflictSnapshot {
    pub owner: String,
    pub session_generation: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResolveChoice {
    KeepLocal,
    AcceptRemote,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResolveMessage {
    /// "That choice couldn't be saved. Please check again and retry."
    SaveFailed,
    /// "This record changed while you were reviewing it. Please check again before choosing."
    ChangedDuringReview,
    /// "Done - your choice was saved. Nothing was merged automatically."
    SavedNotMerged,
}

#[derive(Debug, Default)]
pub struct ConflictResolver {
    resolving: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BeginVerdict {
    Started,
    AlreadyResolving,
    StaleSnapshot,
}

impl ConflictResolver {
    pub fn new() -> Self {
        Self::default()
    }

    /// First guard: refuse re-entry; capture and verify the snapshot.
    pub fn begin(&mut self, snapshot: &ConflictSnapshot, current: &ConflictSnapshot) -> BeginVerdict {
        if self.resolving {
            return BeginVerdict::AlreadyResolving;
        }
        if snapshot != current {
            return BeginVerdict::StaleSnapshot;
        }
        self.resolving = true;
        BeginVerdict::Started
    }

    /// Final guard + verdict. The caller supplies: whether the snapshot still
    /// matches after the write AND after the re-read, whether the write
    /// failed, and whether the record is still unresolved on re-read.
    /// Any stale snapshot aborts WITHOUT publishing a result.
    pub fn finish(
        &mut self,
        snapshot: &ConflictSnapshot,
        current: &ConflictSnapshot,
        write_failed: bool,
        still_unresolved: bool,
    ) -> Option<ResolveMessage> {
        if snapshot != current {
            return None; // abort: the new session's own events drive its work
        }
        self.resolving = false;
        Some(if write_failed {
            ResolveMessage::SaveFailed
        } else if still_unresolved {
            ResolveMessage::ChangedDuringReview
        } else {
            ResolveMessage::SavedNotMerged
        })
    }

    /// Identity change resets the guard (port of resetIdentityUiIfNeeded).
    pub fn on_identity_change(&mut self) {
        self.resolving = false;
    }
}

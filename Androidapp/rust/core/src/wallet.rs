//! Identity-bound wallet display state machine.
//! Port of the wallet semantics in CycleCareViewModel.kt (main@31b88f5):
//! refreshWalletNow, lastReadyWalletOwner, resetIdentityUiIfNeeded and the
//! post-await owner/session re-verification added in 8a91712.
//!
//! Modeling note: the Kotlin code runs the fetch asynchronously and re-checks
//! owner/session when the response lands. Here the fetch itself is the
//! caller's job; `begin_refresh` captures the identity snapshot and
//! `apply_result` re-verifies it, so interleavings (identity switch mid-
//! flight) are directly testable.

/// Wallet display state, always bound to the current identity.
/// - NoSession: credential-less mode; nothing to fetch.
/// - Loading: first fetch in flight, nothing trustworthy on screen.
/// - Ready: a verified fetch for the CURRENT owner/session was applied.
/// - Stale: last-known value for THIS owner, refresh failed.
/// - Unavailable: no trustworthy value for this owner; never a fake zero.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WalletStatus {
    NoSession,
    Loading,
    Ready,
    Stale,
    Unavailable,
}

/// A verified economy profile as the server returned it. Display-only here;
/// the amount is whatever the server said, including a real zero.
#[derive(Debug, Clone, PartialEq)]
pub struct VerifiedProfile {
    pub owner: String,
    pub balance_coins: f64,
    pub is_banned: bool,
    pub is_suspended: bool,
    pub suspension_reason: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum FetchOutcome {
    Success(VerifiedProfile),
    Failure,
}

/// Snapshot of the caller's identity at fetch start.
#[derive(Debug, Clone, PartialEq)]
pub struct IdentitySnapshot {
    pub owner: String,
    pub session_generation: u64,
}

#[derive(Debug, Default)]
pub struct WalletMachine {
    status: Option<WalletStatus>,
    last_ready_owner: Option<String>,
    last_profile: Option<VerifiedProfile>,
}

/// What begin_refresh decided.
#[derive(Debug, Clone, PartialEq)]
pub enum RefreshPlan {
    /// Truly credential-less: show NoSession, do not fetch.
    NoFetch,
    /// A refresh is already running; do not stack another.
    AlreadyRunning,
    /// Deduped: same owner+session fetched within the cooldown window.
    CooldownSkip,
    /// Start a fetch with this identity snapshot.
    Fetch(IdentitySnapshot),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ApplyVerdict {
    /// Owner/session changed mid-flight; the response belongs to an old
    /// identity and MUST NOT populate the current card.
    IgnoredStaleIdentity,
    Applied,
}

const REFRESH_MIN_INTERVAL_MS: u64 = 30_000;

impl WalletMachine {
    pub fn new() -> Self {
        // Credential-less at birth, like the Kotlin default uiState.
        WalletMachine {
            status: Some(WalletStatus::NoSession),
            last_ready_owner: None,
            last_profile: None,
        }
    }

    pub fn status(&self) -> WalletStatus {
        self.status.unwrap_or(WalletStatus::NoSession)
    }

    pub fn last_profile(&self) -> Option<&VerifiedProfile> {
        self.last_profile.as_ref()
    }

    /// Port of refreshWalletNow's pre-fetch logic.
    /// `local_only`/`user_id` describe the CURRENT identity; `now_ms` is the
    /// caller's clock; `refresh_active` says a fetch job is already running;
    /// `last_fetch` is the (owner, session, timestamp) of the last STARTED
    /// successful-completion-marking fetch for dedupe.
    pub fn begin_refresh(
        &mut self,
        local_only: bool,
        identity: Option<IdentitySnapshot>,
        force: bool,
        refresh_active: bool,
        last_fetch: Option<&(String, u64, u64)>,
        now_ms: u64,
    ) -> RefreshPlan {
        // Only truly credential-less modes have no wallet to show. A guest
        // with a live anonymous session has a real cloud wallet and fetches
        // like any signed-in account.
        let Some(identity) = identity.filter(|_| !local_only) else {
            self.status = Some(WalletStatus::NoSession);
            return RefreshPlan::NoFetch;
        };
        if refresh_active {
            return RefreshPlan::AlreadyRunning;
        }
        if !force {
            if let Some((owner, session, at_ms)) = last_fetch {
                if *owner == identity.owner
                    && *session == identity.session_generation
                    && now_ms.saturating_sub(*at_ms) < REFRESH_MIN_INTERVAL_MS
                {
                    return RefreshPlan::CooldownSkip;
                }
            }
        }
        // Show Loading only when there is nothing trustworthy on screen; a
        // Ready or Stale balance keeps showing while the refresh runs quietly.
        match self.status() {
            WalletStatus::NoSession | WalletStatus::Unavailable => {
                self.status = Some(WalletStatus::Loading)
            }
            _ => {}
        }
        RefreshPlan::Fetch(identity)
    }

    /// Port of the post-await apply block, incl. the owner/session re-check.
    /// `started_as` is the snapshot from begin_refresh; `current_identity` is
    /// read AFTER the await. Returns whether the fetch marks the freshness
    /// cache (only a successful, still-current fetch earns that).
    pub fn apply_result(
        &mut self,
        started_as: &IdentitySnapshot,
        current_identity: Option<&IdentitySnapshot>,
        outcome: FetchOutcome,
    ) -> (ApplyVerdict, bool) {
        // A response that outlived its identity must never populate the card.
        if current_identity != Some(started_as) {
            return (ApplyVerdict::IgnoredStaleIdentity, false);
        }
        match outcome {
            FetchOutcome::Success(profile) => {
                self.last_ready_owner = Some(started_as.owner.clone());
                self.last_profile = Some(profile);
                self.status = Some(WalletStatus::Ready);
                (ApplyVerdict::Applied, true)
            }
            FetchOutcome::Failure => {
                // A failure must never look like a real zero balance. Show the
                // last-known value only when it belongs to THIS owner.
                self.status = Some(if self.last_ready_owner.as_deref() == Some(started_as.owner.as_str()) {
                    WalletStatus::Stale
                } else {
                    WalletStatus::Unavailable
                });
                (ApplyVerdict::Applied, false)
            }
        }
    }

    /// Port of resetIdentityUiIfNeeded's wallet part: an identity switch wipes
    /// the trust chain (last-ready owner) and drops the card to NoSession.
    pub fn on_identity_change(&mut self) {
        self.last_ready_owner = None;
        self.last_profile = None;
        self.status = Some(WalletStatus::NoSession);
    }
}

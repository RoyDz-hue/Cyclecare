//! Port of util/NetworkHelper.kt (main@31b88f5, 64 lines) - decision core
//! only. Android seams (ConnectivityManager, Network, NetworkCapabilities,
//! the NetworkCallback/Flow plumbing) are host glue; what is portable and
//! pinned here is:
//!   isOnline (:15-23): three-step guard - no ConnectivityManager -> false,
//!   no activeNetwork -> false, no capabilities -> false, else
//!   NET_CAPABILITY_INTERNET && NET_CAPABILITY_VALIDATED.
//!   observeNetwork (:25-63): initial trySend(isOnline) (:34), then
//!   onAvailable -> trySend(true) (:37-38), onLost ->
//!   trySend(isOnline(context)) (:40-42, the FULL guard chain re-evaluated),
//!   onCapabilitiesChanged -> trySend(INTERNET && VALIDATED on the GIVEN
//!   capabilities) (:44-48 - note the asymmetry: it does NOT re-check
//!   activeNetwork), all under .distinctUntilChanged() (:63).
//! The missing-ConnectivityManager branch emits false then closes (:28-32);
//! that is a one-shot observation: next() yields false once and the stream
//! ends - model it by seeding with NoConnectivityManager.

/// The four possible outcomes of the isOnline lookup chain, with the
/// resolved capabilities attached when every step succeeded.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NetworkQuery {
    NoConnectivityManager,
    NoActiveNetwork,
    NoCapabilities,
    Caps { internet: bool, validated: bool },
}

/// isOnline (:15-23).
pub fn is_online(q: NetworkQuery) -> bool {
    match q {
        NetworkQuery::Caps { internet, validated } => internet && validated,
        _ => false,
    }
}

/// NetworkCallback events with the data the Kotlin callbacks actually use.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NetworkEvent {
    /// onAvailable (:37-38): unconditional trySend(true).
    Available,
    /// onLost (:40-42): trySend(isOnline(context)) - full guard chain.
    Lost(NetworkQuery),
    /// onCapabilitiesChanged (:44-48): INTERNET && VALIDATED on the given
    /// capabilities only.
    CapabilitiesChanged { internet: bool, validated: bool },
}

/// distinctUntilChanged (:63) reducer over the emission stream. `last`
/// holds the most recently EMITTED value; the flow's initial trySend is the
/// first candidate through the same dedup (:34 precedes registration but is
/// upstream of the dedup operator).
#[derive(Debug, Default)]
pub struct NetworkObservation {
    last: Option<bool>,
}

impl NetworkObservation {
    pub fn new() -> Self { NetworkObservation { last: None } }

    /// One candidate through distinctUntilChanged: Some(v) when emitted,
    /// None when deduped.
    pub fn next(&mut self, candidate: bool) -> Option<bool> {
        if self.last == Some(candidate) {
            None
        } else {
            self.last = Some(candidate);
            Some(candidate)
        }
    }

    /// The flow's initial emission (:34): isOnline at registration time.
    pub fn initial(&mut self, q: NetworkQuery) -> Option<bool> {
        self.next(is_online(q))
    }

    /// A callback event mapped to its candidate, then deduped.
    pub fn on_event(&mut self, e: NetworkEvent) -> Option<bool> {
        let candidate = match e {
            NetworkEvent::Available => true,
            NetworkEvent::Lost(q) => is_online(q),
            NetworkEvent::CapabilitiesChanged { internet, validated } => internet && validated,
        };
        self.next(candidate)
    }
}

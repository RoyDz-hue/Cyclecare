//! Reconnect-work policy.
//! Port of domain/sync/ReconnectWorkPolicy.kt (main@31b88f5): reconnect work
//! runs only on an offline->online TRANSITION, at most once per 60s per
//! owner, throttled transitions are deferred (one trailing run) not dropped,
//! and an owner change resets the interval. Throttles redundant work only;
//! startup work and manual refreshes have their own paths.

pub const MIN_INTERVAL_MS: i64 = 60_000;

#[derive(Debug, Default)]
pub struct ReconnectState {
    pub was_connected: bool,
    pub last_run_at_ms: i64,
    pub owner: Option<String>,
}

impl ReconnectState {
    pub fn new() -> Self {
        // Kotlin default: wasConnected = true, so the initial online emit is
        // not treated as a reconnect.
        ReconnectState { was_connected: true, last_run_at_ms: 0, owner: None }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Decision {
    /// Run the reconnect work now.
    Run,
    /// Not a transition, not eligible, offline, or stale owner.
    Skip,
    /// A real transition arrived inside the cooldown: deferred, not dropped.
    /// Schedule one trailing run after this wait.
    Throttled { retry_after_ms: i64 },
}

fn throttle(state: &ReconnectState, now_ms: i64) -> Option<Decision> {
    // Kotlin: `if (state.lastRunAtMs <= 0L) return null` - 0 means this owner
    // has never had reconnect work.
    if state.last_run_at_ms <= 0 {
        return None;
    }
    // Kotlin Long arithmetic wraps on overflow (no panic); a backwards clock
    // yields a negative elapsed, so the wait MIN - elapsed is LONGER than MIN.
    let elapsed = now_ms.wrapping_sub(state.last_run_at_ms);
    if elapsed >= MIN_INTERVAL_MS {
        return None;
    }
    Some(Decision::Throttled { retry_after_ms: MIN_INTERVAL_MS.wrapping_sub(elapsed) })
}

/// Evaluate a connectivity emission. Mutates state exactly like the Kotlin:
/// was_connected always tracks the latest emission; an owner change resets
/// the throttle window.
pub fn decide(
    state: &mut ReconnectState,
    is_connected: bool,
    is_eligible: bool,
    current_owner: Option<&str>,
    now_ms: i64,
) -> Decision {
    let just_reconnected = is_connected && !state.was_connected;
    state.was_connected = is_connected;
    if current_owner != state.owner.as_deref() {
        state.owner = current_owner.map(|s| s.to_string());
        state.last_run_at_ms = 0;
    }
    if !just_reconnected || !is_eligible {
        return Decision::Skip;
    }
    if let Some(t) = throttle(state, now_ms) {
        return t;
    }
    state.last_run_at_ms = now_ms;
    Decision::Run
}

/// Evaluate a deferred trailing run for an earlier throttled transition. No
/// fresh transition required, but the run fires only for the SAME owner,
/// while online and eligible, and only once the cooldown expired. If newer
/// work already ran, this reports the remaining cooldown instead of running
/// twice.
pub fn decide_trailing(
    state: &mut ReconnectState,
    is_connected: bool,
    is_eligible: bool,
    current_owner: Option<&str>,
    now_ms: i64,
) -> Decision {
    if !is_connected || !is_eligible {
        return Decision::Skip;
    }
    if current_owner != state.owner.as_deref() {
        return Decision::Skip;
    }
    if let Some(t) = throttle(state, now_ms) {
        return t;
    }
    state.last_run_at_ms = now_ms;
    Decision::Run
}

#[cfg(test)]
mod backwards_clock_tests {
    use super::*;

    #[test]
    fn backwards_clock_yields_longer_throttle_wait_kotlin_wrapping() {
        // Kotlin: elapsed = nowMs - lastRunAtMs (wrapping Long). A backwards
        // clock gives a negative elapsed, so the wait MIN - elapsed is LONGER
        // than MIN_INTERVAL_MS, not clamped to MIN.
        let mut st = ReconnectState {
            was_connected: false,
            last_run_at_ms: 10_000,
            owner: Some("o".into()),
        };
        match decide(&mut st, true, true, Some("o"), 5_000) {
            Decision::Throttled { retry_after_ms } => {
                assert_eq!(retry_after_ms, MIN_INTERVAL_MS + 5_000);
            }
            other => panic!("expected Throttled, got {:?}", other),
        }
    }
}

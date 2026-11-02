//! Framework-neutral CycleCare domain/state contracts.
//!
//! Ported from main@31b88f5 (Kotlin) with the corrected semantics kept and the
//! known bug mechanics deliberately NOT ported:
//! - CONFLICTED local state never routes to ONBOARDING (old vc6 bug).
//! - A failed/missing wallet fetch never renders as a real zero balance.
//! - Storage-error fallbacks never silently delete credentials (not modeled
//!   here at all; the session store contract will own that).
//!
//! Everything here is pure state: no UI toolkit, no network, no database.
//! UI (Slint/Dioxus), storage (rusqlite) and transport (reqwest) plug in at
//! the edges.

pub mod account_start;
pub mod startup_route;
pub mod wallet;

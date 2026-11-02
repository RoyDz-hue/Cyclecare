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

pub mod kotlin_text;
pub mod account_start;
pub mod startup_route;
pub mod wallet;
pub mod owner_data_gate;
pub mod sync_gate;
pub mod conflict_resolve;
pub mod coin_amount;
pub mod log_parser;
pub mod reconnect_policy;
pub mod pin;
pub mod cycle_math;
pub mod notifications;
pub mod pattern_engine;
pub mod safety;
pub mod something_changed;
pub mod attention;
pub mod learning;
pub mod economy_status;
pub mod economy_catalog;
pub mod payment_validation;
pub mod coin_format;
pub mod payment_safety;
pub mod economy_rules;
pub mod storage_schema;
pub mod earn_coins;
pub mod sync_codec;
pub mod sync_protocol;
pub mod md5;
pub mod lifecycle;
pub mod session_token;
pub mod verified_profile;
pub mod reconcile;

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
pub mod pin_verify;
pub mod app_lock;
pub mod supabase_config;
pub mod v3_sync;
pub mod secure_store;
pub mod cycle_math;
pub mod notifications;
pub mod pattern_engine;
pub mod safety;
pub mod something_changed;
pub mod attention;
pub mod learning;
pub mod pregnancy;
pub mod guided_chats;
pub mod community_guides;
pub mod community_transport;
pub mod community_service;
pub mod economy_status;
pub mod economy_catalog;
pub mod payment_validation;
pub mod coin_format;
pub mod payment_safety;
pub mod economy_rules;
#[cfg(any(feature = "sqlite", feature = "sqlcipher-host"))]
pub mod storage_schema;
pub mod earn_coins;
pub mod sync_codec;
pub mod track_log;
pub mod track_vm;
pub mod sync_protocol;
pub mod md5;
pub mod lifecycle;
pub mod session_token;
pub mod verified_profile;
pub mod reconcile;
pub mod ob_cycle;
pub mod onboarding_vm;
pub mod onboarding_complete;
pub mod onboarding_questions;
pub mod ai_consent_tap;
pub mod care_policy;
pub mod currency_locale;
pub mod care_knowledge;
pub mod care_ai;
pub mod care_privacy;
pub mod layered_chat;
pub mod payment_poll;
#[cfg(all(feature = "sqlite", feature = "sqlcipher-host"))]
compile_error!("features `sqlite` and `sqlcipher-host` are mutually exclusive: exactly one bundled SQLite/codec library may be linked");

#[cfg(any(feature = "sqlite", feature = "sqlcipher-host"))]
pub mod storage_db;
pub mod auth_errors;
pub mod auth_uri;
pub mod auth_link;
pub mod pkce;
pub mod auth_flows;
pub mod auth_google;
pub mod auth_session;
pub mod home_anchor;

/// Host-only SQLCipher conversion rehearsal (feature `sqlcipher-host`).
#[cfg(feature = "sqlcipher-host")]
pub mod encrypted_host;

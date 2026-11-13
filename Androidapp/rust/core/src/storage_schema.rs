//! Authoritative Room-generated DDL mirror for the Rust storage-compat layer.
//! Source: KSP-generated CycleCareDatabase_Impl @ spike 18dd603 (ops-generated,
//! 7 tables + 2 indices), preserved verbatim in
//! fixtures/schema/room-ddl-18dd603.sql. This is generated-source evidence of
//! what Room executes at open; cross-read against a live encrypted production
//! DB remains an OPEN proof (SQLCipher codec + Keystore-derived key are host
//! concerns; a Keystore-invalidated DB is unrecoverable - detect, preserve,
//! never delete).

/// The exact DDL statements, in generated order.
pub const ROOM_DDL: &str = include_str!("../fixtures/schema/room-ddl-18dd603.sql");

pub const TABLE_NAMES: &[&str] = &[
    "cycle_day_logs",
    "cycle_settings",
    "onboarding_profiles",
    "health_sync_revisions",
    "health_sync_outbox",
    "health_sync_cursors",
    "health_sync_conflicts",
];

pub const INDEX_NAMES: &[&str] = &[
    "index_health_sync_outbox_ownerId_nextAttemptAt",
    "index_health_sync_conflicts_ownerId_resolvedAt",
];

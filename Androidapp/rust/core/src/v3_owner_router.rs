//! Per-owner database routing — REQUIRED, not optional
//! (OwnerScopedDatabaseProvider.kt + CycleCareDatabase.kt:100-125).
//!
//! The domain tables (cycle_day_logs, cycle_settings, onboarding_profiles)
//! carry NO ownerId column - a single database file cannot hold two owners'
//! domain data without cross-owner reads and overwrites. The app solves
//! this by resolving a per-owner database AT CALL TIME: `namespaceFor`
//! derives the file name from a SHA-256 of the identity (raw auth IDs never
//! become filenames, stable across restarts), a null or blank identity maps
//! to the deliberately unassigned legacy guest database, and instances are
//! cached per resolved name so a switch never reuses a prior owner's handle.
//! This is the host-store port of exactly that contract.
//!
//! SCOPE - file mapping only. Not yet ported: SQLCipher key management and
//! encryption (separate open integration), Room migrations 4..9, the Room
//! master-table identity hash / user version check, OwnerDataGate wipe
//! handling, and instance cache close/eviction. The owner/session gate is
//! still the caller-injected `current` closure (Kotlin parity: current()
//! reads AuthPreferences live); the ported flows evaluate it INSIDE the
//! transaction, but no owner-protection claim stands until the real gate is
//! integrated at this layer.
#![cfg(feature = "sqlite")]

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use crate::v3_consent::{journal_explicit_snapshot, V3ConsentError};
use crate::v3_dao_sqlite::SqliteV3Store;
use crate::v3_outbox::{run_v3_outbox, V3DaoError, V3Transport};
use crate::v3_pull::{run_v3_pull, V3PullError, V3ReadTransport};

/// CycleCareDatabase.kt:25 — the deliberately unassigned guest database
/// (null/blank identity), verbatim.
pub const LEGACY_DATABASE_NAME: &str = "cycle_care_database";

/// CycleCareDatabase.kt:121-125 — "cycle_care_owner_" + lowercase hex
/// SHA-256 of the UTF-8 identity. Locked against a known-answer vector in
/// the corpus tests (SHA-256 is FIPS 180-4; vector computed independently).
pub fn namespace_for(owner_identity: &str) -> String {
    format!("cycle_care_owner_{}", crate::sync_codec::sha256_hex(owner_identity))
}

/// Resolves stores at call time, mirroring the provider + the per-name
/// instance caches (INSTANCE / OWNER_INSTANCES, CycleCareDatabase.kt:107-114).
/// Owner-binding failure: the operation owner cannot be bound to a resolved
/// store identity. Blank/whitespace owners are rejected here rather than
/// silently routed to the legacy guest database with an owner argument
/// (SupabaseSyncService.kt:117 - owner_required).
#[derive(Debug, PartialEq)]
pub enum OwnerBindError {
    /// The operation owner resolved to a different store identity than its
    /// own namespace - refused before any I/O.
    Mismatch { resolved_name: String },
    /// Opening the resolved store failed.
    Store(String),
}

pub struct OwnerScopedStores {
    dir: PathBuf,
    stores: HashMap<String, SqliteV3Store>,
}

impl OwnerScopedStores {
    pub fn new(dir: impl Into<PathBuf>) -> Self {
        OwnerScopedStores { dir: dir.into(), stores: HashMap::new() }
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    /// CycleCareDatabase.kt:104-106 — non-blank identity namespaces, null or
    /// blank maps to the legacy guest database name. Blankness is Kotlin
    /// Char.isBlank parity via the central, JVM-corpus-locked helper -
    /// Char.isWhitespace includes U+00A0/U+2007/U+202F and U+001C-001F and
    /// excludes U+0085, which does NOT match Rust's char::is_whitespace, so
    /// str::trim here would misroute those identities.
    fn name_for(identity: Option<&str>) -> String {
        match identity {
            Some(id) if !crate::kotlin_text::kotlin_is_blank(id) => namespace_for(id),
            _ => LEGACY_DATABASE_NAME.to_string(),
        }
    }

    /// getDatabase(context, ownerIdentity): resolve the name at call time,
    /// open on first use, then serve the cached per-name instance. Two
    /// identities NEVER share a file; one identity always gets the same
    /// store for this provider's lifetime.
    pub fn for_identity(&mut self, identity: Option<&str>) -> rusqlite::Result<&mut SqliteV3Store> {
        let name = Self::name_for(identity);
        if !self.stores.contains_key(&name) {
            let store = SqliteV3Store::open_file(&self.dir.join(&name))?;
            self.stores.insert(name.clone(), store);
        }
        Ok(self.stores.get_mut(&name).expect("just inserted"))
    }

    /// THE binding entry point for sync operations: the operation owner is
    /// used BOTH to resolve the store and as the operation's owner argument,
    /// so store identity and operation owner cannot diverge. A blank owner
    /// resolves to the legacy name, which never equals its namespace - the
    /// mismatch is refused before any I/O. Per-owner generation checks stay
    /// with the flow-level `current` gate inside each transaction.
    ///
    /// The closure receives an OwnerBoundStore whose dispatch methods derive
    /// the owner from the binding - the caller never supplies an owner
    /// argument, so a mismatched owner/file dispatch is unrepresentable
    /// through this API. (The ported primitives keep their owner parameter;
    /// they are 1:1 ports and this wrapper is the sanctioned call path.)
    pub fn run_for_owner<R>(
        &mut self,
        owner: &str,
        body: impl FnOnce(&mut OwnerBoundStore<'_>) -> R,
    ) -> Result<R, OwnerBindError> {
        let name = Self::name_for(Some(owner));
        if name != namespace_for(owner) {
            return Err(OwnerBindError::Mismatch { resolved_name: name });
        }
        let store = self
            .for_identity(Some(owner))
            .map_err(|e| OwnerBindError::Store(e.to_string()))?;
        let mut bound = OwnerBoundStore { store, owner: owner.to_string() };
        Ok(body(&mut bound))
    }

    /// OwnerScopedDatabaseProvider.current(): the owner comes from
    /// authPreferences.userId AT CALL TIME (AuthPreferences.kt:356-361) —
    /// an account switch resolves a different database on the very next
    /// call, never reusing the prior owner's handle.
    pub fn current(
        &mut self,
        user_id: &mut dyn FnMut() -> Option<String>,
    ) -> rusqlite::Result<&mut SqliteV3Store> {
        let id = user_id();
        self.for_identity(id.as_deref())
    }
}

/// A store bound to one owner at dispatch time (OwnerScopedStores::
/// run_for_owner). The owner is private and set only from the verified
/// binding; every dispatch derives it internally.
pub struct OwnerBoundStore<'a> {
    /// Raw store access for reads/assertions; writes that carry an owner go
    /// through the dispatch methods so the owner can never be supplied
    /// inconsistently.
    pub store: &'a mut SqliteV3Store,
    owner: String,
}

impl OwnerBoundStore<'_> {
    /// The owner this dispatch is bound to (the identity that resolved the
    /// underlying file).
    pub fn owner(&self) -> &str {
        &self.owner
    }

    /// run_v3_pull with the bound owner (SupabaseSyncService.kt:150-187).
    #[allow(clippy::too_many_arguments)]
    pub fn run_pull(
        &mut self,
        transport: &mut dyn V3ReadTransport,
        access_token: &str,
        local: i64,
        server: i64,
        current: &mut dyn FnMut() -> bool,
        now_ms: &mut dyn FnMut() -> i64,
    ) -> Result<i32, V3PullError> {
        run_v3_pull(transport, access_token, self.store, &self.owner, local, server, current, now_ms)
    }

    /// run_v3_outbox with the bound owner (SupabaseSyncService.kt:61-113).
    #[allow(clippy::too_many_arguments)]
    pub fn run_outbox(
        &mut self,
        transport: &mut dyn V3Transport,
        access_token: &str,
        session: i64,
        local: i64,
        server: i64,
        current: &mut dyn FnMut() -> bool,
        now_ms: &mut dyn FnMut() -> i64,
    ) -> Result<i32, V3DaoError> {
        run_v3_outbox(transport, access_token, self.store, &self.owner, session, local, server, current, now_ms)
    }

    /// journalExplicitSnapshot with the bound owner
    /// (SupabaseSyncService.kt:45-57).
    #[allow(clippy::too_many_arguments)]
    pub fn journal_snapshot(
        &mut self,
        session: i64,
        local: i64,
        server: i64,
        current: &mut dyn FnMut() -> bool,
        uuid: &mut dyn FnMut() -> String,
        now_ms: &mut dyn FnMut() -> i64,
    ) -> Result<(), V3ConsentError> {
        journal_explicit_snapshot(&self.owner, session, local, server, self.store, current, uuid, now_ms)
    }
}

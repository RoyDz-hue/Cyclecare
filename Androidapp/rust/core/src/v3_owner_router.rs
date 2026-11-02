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

use crate::v3_dao_sqlite::SqliteV3Store;

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

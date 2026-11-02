//! Owner data lifecycle gate.
//! Port of data/local/OwnerDataGate.kt + CycleCareDatabase.namespaceFor
//! (main@31b88f5). A wipe is durable: no later pull or stale worker may undo
//! it; only an explicit user action resumes the namespace.
//!
//! Abstract-state proof only: the store here is an in-memory map. The Android
//! adapter binds this to SharedPreferences (PREFS =
//! "cyclecare_owner_data_lifecycle_v1"); physical persistence is verified at
//! the integration layer, not here.
use crate::kotlin_text::{kotlin_trim};

use sha2::{Digest, Sha256};
use std::collections::HashMap;

pub const LEGACY_DATABASE_NAME: &str = "cycle_care_database";
pub const GATE_PREFS_NAME: &str = "cyclecare_owner_data_lifecycle_v1";

/// Port of namespaceFor: raw auth IDs never become filenames; stable across
/// restarts. Input is the Supabase auth user UUID.
pub fn namespace_for(owner_identity: &str) -> String {
    let digest = Sha256::digest(owner_identity.as_bytes());
    let mut hex = String::with_capacity(64);
    for b in digest {
        hex.push_str(&format!("{b:02x}"));
    }
    format!("cycle_care_owner_{hex}")
}

/// Port of OwnerDataGate.key: null/blank identity is the unassigned guest DB.
pub fn database_name_for(identity: Option<&str>) -> String {
    match identity {
        Some(id) if !kotlin_trim(id).is_empty() => namespace_for(id),
        _ => LEGACY_DATABASE_NAME.to_string(),
    }
}

pub fn wipe_marker_key(identity: Option<&str>) -> String {
    match identity {
        Some(id) if !kotlin_trim(id).is_empty() => format!("wiped_{}", namespace_for(id)),
        _ => "wiped_legacy_guest".to_string(),
    }
}

/// In-memory stand-in for the durable marker store. Same semantics: a set
/// marker survives every read; only `resume_after_explicit_user_action`
/// clears it.
#[derive(Default)]
pub struct OwnerDataGate {
    markers: HashMap<String, bool>,
}

impl OwnerDataGate {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn is_wiped(&self, identity: Option<&str>) -> bool {
        self.markers.get(&wipe_marker_key(identity)).copied().unwrap_or(false)
    }

    pub fn mark_wiped(&mut self, identity: Option<&str>) {
        self.markers.insert(wipe_marker_key(identity), true);
    }

    /// Only an explicit user action may resume a wiped namespace.
    pub fn resume_after_explicit_user_action(&mut self, identity: Option<&str>) {
        self.markers.remove(&wipe_marker_key(identity));
    }
}

//! sync_protocol.rs — Rust port of domain/sync/HealthSyncProtocol.kt:
//! the conflict-safe v3 sync state machine (owner, consent generation,
//! revision chain, idempotency, tombstones). Pure; no Room/HTTP.
//! Comparator: fixtures/oracle/results_syncproto.tsv (12 scenarios executed
//! against verbatim-extracted Kotlin, kotlinc 2.1.0/JRE 21).

use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SyncOperationKind {
    Upsert,
    Delete,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SyncOperation {
    pub operation_id: String,
    pub owner_id: String,
    pub record_id: String,
    pub kind: SyncOperationKind,
    pub base_revision: i64,
    pub revision: i64,
    pub payload_hash: String,
    pub consent_generation: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SyncRecord {
    pub revision: i64,
    pub payload_hash: Option<String>,
    pub tombstone: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SyncConflict {
    pub record_id: String,
    pub local: SyncOperation,
    pub remote: SyncRecord,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ApplyResult {
    Applied(SyncRecord),
    Duplicate(SyncRecord),
    Rejected(String),
    Conflict(SyncConflict),
}

/// Kotlin LinkedHashMap iteration order matters only for `record(...)`-style
/// lookups, which are keyed; HashMap suffices.
pub struct HealthSyncStateMachine {
    owner_id: String,
    operations: HashMap<String, SyncOperation>,
    records: HashMap<String, SyncRecord>,
    consent_generation: i64,
    consent_granted: bool,
}

impl HealthSyncStateMachine {
    pub fn new(owner_id: impl Into<String>) -> Self {
        HealthSyncStateMachine {
            owner_id: owner_id.into(),
            operations: HashMap::new(),
            records: HashMap::new(),
            consent_generation: 0,
            consent_granted: false,
        }
    }

    pub fn grant_consent(&mut self) -> i64 {
        self.consent_generation += 1;
        self.consent_granted = true;
        self.consent_generation
    }

    pub fn revoke_consent(&mut self) {
        self.consent_generation += 1;
        self.consent_granted = false;
    }

    pub fn current_consent_generation(&self) -> i64 {
        self.consent_generation
    }

    pub fn record(&self, record_id: &str) -> Option<&SyncRecord> {
        self.records.get(record_id)
    }

    pub fn apply(&mut self, operation: SyncOperation) -> ApplyResult {
        if operation.owner_id != self.owner_id {
            return ApplyResult::Rejected("owner_mismatch".to_string());
        }
        if !self.consent_granted || operation.consent_generation != self.consent_generation {
            return ApplyResult::Rejected("stale_or_revoked_consent".to_string());
        }
        if operation.base_revision < 0
            || operation.revision <= 0
            || operation.revision != operation.base_revision + 1
        {
            return ApplyResult::Rejected("revision_must_be_base_plus_one".to_string());
        }
        if let Some(prior) = self.operations.get(&operation.operation_id) {
            return if *prior == operation {
                ApplyResult::Duplicate(SyncRecord {
                    revision: prior.revision,
                    payload_hash: if prior.kind == SyncOperationKind::Delete {
                        None
                    } else {
                        Some(prior.payload_hash.clone())
                    },
                    tombstone: prior.kind == SyncOperationKind::Delete,
                })
            } else {
                ApplyResult::Rejected("operation_id_payload_reuse".to_string())
            };
        }
        let current = self.records.get(&operation.record_id);
        match current {
            None => {
                if operation.base_revision != 0 {
                    return ApplyResult::Conflict(SyncConflict {
                        record_id: operation.record_id.clone(),
                        local: operation,
                        remote: SyncRecord { revision: 0, payload_hash: None, tombstone: false },
                    });
                }
            }
            Some(cur) => {
                if operation.base_revision != cur.revision {
                    return ApplyResult::Conflict(SyncConflict {
                        record_id: operation.record_id.clone(),
                        local: operation,
                        remote: cur.clone(),
                    });
                }
                if cur.tombstone && operation.kind == SyncOperationKind::Upsert {
                    return ApplyResult::Rejected("tombstone_resurrection".to_string());
                }
            }
        }
        let next = SyncRecord {
            revision: operation.revision,
            payload_hash: if operation.kind == SyncOperationKind::Delete {
                None
            } else {
                Some(operation.payload_hash.clone())
            },
            tombstone: operation.kind == SyncOperationKind::Delete,
        };
        self.operations.insert(operation.operation_id.clone(), operation.clone());
        self.records.insert(operation.record_id.clone(), next.clone());
        ApplyResult::Applied(next)
    }
}

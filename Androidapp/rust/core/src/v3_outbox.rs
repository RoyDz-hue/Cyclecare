//! V3 outbox execution (SupabaseSyncService.kt:61-113, main@31b88f5).
//! RELEASE-CLOSED PARITY: production executeV3Outbox returns 0 immediately
//! because conflictSafeProtocolReleased() = false. The gated body is ported
//! as run_v3_outbox for the release path ONLY; the public entry mirrors
//! production and never runs it.
//!
//! DAO seam: production runs inside Room withTransaction; the injected
//! V3SyncDao implementation owns atomicity of each commit group (adapter
//! obligation). All `current()` drift checks are injected as a closure
//! reading live preferences, matching the Kotlin predicate reads.

use crate::sync_codec::JsonValue;
use crate::v3_sync::{envelope, valid_logical_id, wire_record_id, V3WireOperation, V3WireReceipt};

#[derive(Debug, Clone, PartialEq)]
pub struct OutboxEntry {
    pub owner: String,
    pub operation_id: String,
    pub record_id: String,
    pub kind: String, // "UPSERT" | "DELETE"
    pub base_revision: i64,
    pub revision: i64,
    pub payload_json: Option<String>,
    pub payload_hash: String,
    pub consent_generation: i64,
    pub attempts: i64,
    pub next_attempt_at: i64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ConflictRow {
    pub owner: String,
    pub record_id: String,
    pub operation_id: String,
    pub local_revision: i64,
    pub payload_json: Option<String>,
    pub kind: String,
    pub remote_revision: i64,
    pub remote_payload: Option<String>,
    pub remote_tombstone: bool,
    pub created_ms: i64,
}

/// Injected DAO seam. Production: HealthSyncDao inside withTransaction;
/// the adapter owns atomicity of each commit group.
pub trait V3SyncDao {
    fn pending(&mut self, owner: &str) -> Vec<OutboxEntry>;
    /// The withTransaction commit group for an applied/duplicate ACK.
    /// ONE FALLIBLE ATOMIC TRANSACTION. Inside it the implementation MUST,
    /// in order: (1) evaluate `gate` (the in-transaction current() check) -
    /// false => roll back and return GateDrift; (2) READ the existing
    /// revision row; (3) update server_revision to
    /// max(existing.serverRevision, new_revision) ONLY when the row exists
    /// (never insert, never lower); (4) acknowledgeMatched; (5)
    /// rebasePending. Any database error => roll back everything and return
    /// DbError (source: the exception reaches the catch branch -> blocked +
    /// retry). Caller obligations (owner/server consent/db-current) are
    /// carried by `gate`, evaluated INSIDE the transaction.
    fn commit_applied(
        &mut self,
        owner: &str,
        operation_id: &str,
        record_id: &str,
        entry_revision: i64,
        base_revision: i64,
        new_revision: i64,
        gate: &mut dyn FnMut() -> bool,
    ) -> CommitOutcome;
    fn put_conflict(&mut self, conflict: ConflictRow);
    fn retry(&mut self, owner: &str, operation_id: &str, next_attempt_at: i64, message: &str);
}

/// Typed commit result: gate drift (no retry, not counted) is distinct from
/// a database error (source catch branch: blocked + retry).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CommitOutcome {
    Committed,
    GateDrift,
    DbError,
}

/// Injected V3 transport; Err(()) mirrors a thrown exception (IOException,
/// HTTP failure) -> the catch branch (blocked + retry).
pub trait V3Transport {
    fn apply(&mut self, op: &V3WireOperation, access_token: &str) -> Result<V3WireReceipt, ()>;
}

/// Retry backoff: 1000L shl attempts.coerceIn(0,6) (1s..64s).
pub fn retry_backoff_ms(attempts: i64) -> i64 {
    1_000i64 << attempts.clamp(0, 6)
}

/// Production parity: the release policy is closed; nothing is sent.
pub fn execute_v3_outbox() -> i32 {
    0
}

/// The release-gated body, ported 1:1. `owner/session/local/server` are the
/// gate captures; `current` re-reads the live gate (Kotlin current());
/// `now_ms` is the wall clock (System.currentTimeMillis seam).
pub fn run_v3_outbox(
    transport: &mut dyn V3Transport,
    access_token: &str,
    dao: &mut dyn V3SyncDao,
    owner: &str,
    session: i64,
    local: i64,
    server: i64,
    current: &mut dyn FnMut() -> bool,
    now_ms: &mut dyn FnMut() -> i64,
) -> i32 {
    let _ = session; // session participates via the `current` closure.
    if !current() {
        return 0;
    }
    let mut applied = 0i32;
    let mut blocked_records: std::collections::HashSet<String> = std::collections::HashSet::new();
    // pending sorted by revision, take 100, operationIds only (re-read per op).
    let mut ops = dao.pending(owner);
    ops.sort_by_key(|e| e.revision);
    let operation_ids: Vec<String> = ops.into_iter().take(100).map(|e| e.operation_id).collect();
    for operation_id in operation_ids {
        if !current() {
            break;
        }
        // Re-read after each ACK: a prior operation may have rebased this one.
        let e = match dao.pending(owner).into_iter().find(|p| p.operation_id == operation_id) {
            Some(e) => e,
            None => continue,
        };
        if blocked_records.contains(&e.record_id) || e.consent_generation != local {
            continue;
        }
        if e.attempts >= 8 || e.next_attempt_at > now_ms() {
            blocked_records.insert(e.record_id.clone());
            continue;
        }
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            if !valid_logical_id(&e.record_id) {
                panic!("invalid_logical_identity");
            }
            let revision = e.base_revision.checked_add(1).expect("addExact");
            let payload: Option<JsonValue> = if e.kind == "DELETE" {
                None
            } else {
                let raw = e.payload_json.as_deref().unwrap_or_else(|| panic!("missing_payload"));
                let parsed = crate::sync_codec::JsonParser::new(raw).parse()
                    .unwrap_or_else(|_| panic!("missing_payload"));
                Some(envelope(&e.record_id, parsed))
            };
            transport.apply(
                &V3WireOperation {
                    operation_id: e.operation_id.clone(),
                    record_id: wire_record_id(owner, &e.record_id),
                    base_revision: e.base_revision,
                    revision,
                    kind: e.kind.clone(),
                    payload,
                    consent_generation: server,
                    logical_record_id: Some(e.record_id.clone()),
                },
                access_token,
            )
        }));
        let r = match result {
            Ok(Ok(r)) => r,
            // thrown (validation panic or transport error): blocked + retry.
            _ => {
                blocked_records.insert(e.record_id.clone());
                if !current() {
                    break;
                }
                let next = now_ms() + retry_backoff_ms(e.attempts);
                dao.retry(owner, &e.operation_id, next, "Backup needs another try");
                continue;
            }
        };
        if !current() {
            break;
        }
        match r.status.as_str() {
            "applied" | "duplicate" => {
                let valid_ack = r.record_id.as_deref() == Some(wire_record_id(owner, &e.record_id).as_str())
                    && r.operation_id.as_deref() == Some(e.operation_id.as_str())
                    && r.revision == Some(e.base_revision + 1)
                    && r.logical_record_id.as_deref() == Some(e.record_id.as_str())
                    && r.payload_hash.as_deref().map(is_lower_hex_64) == Some(true);
                if !valid_ack {
                    // check("invalid_ack") throws -> catch branch.
                    blocked_records.insert(e.record_id.clone());
                    if !current() {
                        break;
                    }
                    let next = now_ms() + retry_backoff_ms(e.attempts);
                    dao.retry(owner, &e.operation_id, next, "Backup needs another try");
                    continue;
                }
                let new_revision = e.base_revision + 1;
                match dao.commit_applied(owner, &e.operation_id, &e.record_id, e.revision, e.base_revision, new_revision, current) {
                    CommitOutcome::Committed => applied += 1,
                    // withTransaction returned false: nothing written, no retry.
                    CommitOutcome::GateDrift => {}
                    // Exception inside withTransaction -> source catch branch.
                    CommitOutcome::DbError => {
                        blocked_records.insert(e.record_id.clone());
                        if !current() {
                            break;
                        }
                        let next = now_ms() + retry_backoff_ms(e.attempts);
                        dao.retry(owner, &e.operation_id, next, "Backup needs another try");
                    }
                }
            }
            "conflict" => {
                blocked_records.insert(e.record_id.clone());
                if !current() {
                    continue;
                }
                // Latest pending for the record: maxByOrNull(revision) = FIRST
                // max on ties (Kotlin), not last.
                let latest = dao
                    .pending(owner)
                    .into_iter()
                    .filter(|p| p.record_id == e.record_id)
                    .reduce(|a, b| if b.revision > a.revision { b } else { a })
                    .unwrap_or_else(|| e.clone());
                let remote_value = r
                    .remote_payload
                    .as_ref()
                    .and_then(|p| obj_get(p, "value").cloned());
                let conflict_valid = r.remote_tombstone || r.revision == Some(0) || remote_value.is_some();
                if !conflict_valid {
                    blocked_records.insert(e.record_id.clone());
                    if !current() {
                        break;
                    }
                    let next = now_ms() + retry_backoff_ms(e.attempts);
                    dao.retry(owner, &e.operation_id, next, "Backup needs another try");
                    continue;
                }
                let remote_revision = match r.revision {
                    Some(v) => v,
                    None => {
                        if !current() {
                            break;
                        }
                        let next = now_ms() + retry_backoff_ms(e.attempts);
                        dao.retry(owner, &e.operation_id, next, "Backup needs another try");
                        continue;
                    }
                };
                dao.put_conflict(ConflictRow {
                    owner: owner.to_string(),
                    record_id: e.record_id.clone(),
                    operation_id: latest.operation_id.clone(),
                    local_revision: latest.revision,
                    payload_json: latest.payload_json.clone(),
                    kind: latest.kind.clone(),
                    remote_revision,
                    remote_payload: remote_value.map(|v| crate::sync_codec::json_value_to_string(&v)),
                    remote_tombstone: r.remote_tombstone,
                    created_ms: now_ms(),
                });
            }
            _ => {
                // error("v3_operation_rejected") -> catch branch.
                blocked_records.insert(e.record_id.clone());
                if !current() {
                    break;
                }
                let next = now_ms() + retry_backoff_ms(e.attempts);
                dao.retry(owner, &e.operation_id, next, "Backup needs another try");
            }
        }
    }
    applied
}

fn obj_get<'a>(obj: &'a JsonValue, key: &str) -> Option<&'a JsonValue> {
    match obj {
        JsonValue::Object(pairs) => pairs.iter().find(|(k, _)| k == key).map(|(_, v)| v),
        _ => None,
    }
}

/// ^[a-f0-9]{64}$ full match.
fn is_lower_hex_64(s: &str) -> bool {
    s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
}

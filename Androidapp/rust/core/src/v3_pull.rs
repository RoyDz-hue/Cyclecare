//! V3 pull (SupabaseSyncService.kt:150-187, main@31b88f5). Release-gated in
//! production (pullRemoteUpdates returns 0 unless conflictSafeProtocolReleased
//! AND isCloudSyncAllowed); the gated body is ported as run_v3_pull.
//!
//! check() failures inside Kotlin's withTransaction roll back and throw out
//! of pull; the port surfaces them as Result::Err. The remote-apply step
//! (settings/profile/log upsert + tombstone reset) is injected so
//! HealthSyncPayloadCodec lands as its own slice; the injected closure owns
//! the Room-transaction-equivalent atomicity with the DAO writes.

use crate::v3_sync::{valid_logical_id, wire_record_id, V3WireChange};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum V3PullError {
    /// check("consent_changed") - the gate drifted inside the transaction.
    ConsentChanged,
    /// change.logicalRecordId missing -> error("missing_logical_identity").
    MissingLogicalIdentity,
    /// validLogicalId + wireRecordId match failed -> check("record_identity_mismatch").
    RecordIdentityMismatch,
    /// tombstone || (logicalRecordId+kind UPSERT+value) failed ->
    /// check("invalid_remote_payload").
    InvalidRemotePayload,
}

#[derive(Debug, Clone, PartialEq)]
pub struct RevisionRow {
    pub revision: i64,
    pub server_revision: i64,
}

/// Injected read-side DAO seam (production: HealthSyncDao in withTransaction).
pub trait V3PullDao {
    fn cursor(&mut self, owner: &str) -> Option<i64>;
    fn put_cursor(&mut self, owner: &str, change_seq: i64);
    fn revision(&mut self, owner: &str, record_id: &str) -> Option<RevisionRow>;
    fn put_revision(
        &mut self,
        owner: &str,
        record_id: &str,
        revision: i64,
        payload_hash: Option<String>,
        tombstone: bool,
        now_ms: i64,
        server_revision: i64,
    );
    fn pending_for_record(&mut self, owner: &str, record_id: &str) -> Vec<crate::v3_outbox::OutboxEntry>;
    fn put_conflict(&mut self, conflict: crate::v3_outbox::ConflictRow);
}

/// Injected readChanges transport; Err(()) = thrown exception (propagates
/// out of pull in Kotlin via the caller's catch; here it ends the pull).
pub trait V3ReadTransport {
    fn read_changes(
        &mut self,
        after_seq: i64,
        limit: i64,
        consent_generation: i64,
        access_token: &str,
    ) -> Result<Vec<V3WireChange>, ()>;
}

/// The release-gated pull body. Returns applied count or the first check
/// failure. `apply_remote(db-id, value_json)` and `apply_tombstone(id)`
/// perform the domain writes (codec slice); `current` re-reads the gate.
pub fn run_v3_pull(
    transport: &mut dyn V3ReadTransport,
    access_token: &str,
    dao: &mut dyn V3PullDao,
    owner: &str,
    local: i64,
    server: i64,
    current: &mut dyn FnMut() -> bool,
    now_ms: &mut dyn FnMut() -> i64,
    apply_remote: &mut dyn FnMut(&str, &str),
    apply_tombstone: &mut dyn FnMut(&str),
) -> Result<i32, V3PullError> {
    let mut cursor = dao.cursor(owner).unwrap_or(0);
    let mut count = 0i32;
    while current() {
        let page = match transport.read_changes(cursor, 100, server, access_token) {
            Ok(p) => p,
            Err(()) => break, // thrown transport error: Kotlin propagates to the caller's catch; the pure seam ends the pull
        };
        if !current() || page.is_empty() {
            break;
        }
        let short_page = page.len() < 100;
        for change in page {
            if !current() {
                return Err(V3PullError::ConsentChanged);
            }
            let id = match change.logical_record_id.as_deref() {
                Some(id) => id.to_string(),
                None => return Err(V3PullError::MissingLogicalIdentity),
            };
            if !(valid_logical_id(&id) && change.record_id == wire_record_id(owner, &id)) {
                return Err(V3PullError::RecordIdentityMismatch);
            }
            let value = change
                .payload
                .as_ref()
                .and_then(|p| match p { crate::sync_codec::JsonValue::Object(pairs) => pairs.iter().find(|(k, _)| k == "value").map(|(_, v)| v.clone()), _ => None });
            let payload_id = change.payload.as_ref().map(|p| crate::sync_codec::aosp_opt_string(p, "logicalRecordId", ""));
            let payload_kind = change.payload.as_ref().map(|p| crate::sync_codec::aosp_opt_string(p, "kind", ""));
            let shape_ok = change.tombstone
                || (payload_id.as_deref() == Some(id.as_str())
                    && payload_kind.as_deref() == Some("UPSERT")
                    && value.is_some());
            if !shape_ok {
                return Err(V3PullError::InvalidRemotePayload);
            }
            let value_json = value.as_ref().map(crate::sync_codec::json_value_to_string);
            let prior = dao.revision(owner, &id);
            if change.revision > prior.as_ref().map(|p| p.server_revision).unwrap_or(0) {
                // Latest pending for the record: maxByOrNull(revision) =
                // FIRST max on ties.
                let pending = dao
                    .pending_for_record(owner, &id)
                    .into_iter()
                    .reduce(|a, b| if b.revision > a.revision { b } else { a });
                if let Some(pending) = pending {
                    dao.put_conflict(crate::v3_outbox::ConflictRow {
                        owner: owner.to_string(),
                        record_id: id.clone(),
                        operation_id: pending.operation_id.clone(),
                        local_revision: prior.as_ref().map(|p| p.revision).unwrap_or(pending.revision),
                        payload_json: pending.payload_json.clone(),
                        kind: pending.kind.clone(),
                        remote_revision: change.revision,
                        remote_payload: value_json.clone(),
                        remote_tombstone: change.tombstone,
                        created_ms: now_ms(),
                    });
                } else {
                    if change.tombstone {
                        apply_tombstone(&id);
                    } else {
                        let v = value_json.clone().expect("shape_ok guarantees value for non-tombstone");
                        apply_remote(&id, &v);
                    }
                    let new_local = prior
                        .as_ref()
                        .map(|p| p.revision.checked_add(1).expect("addExact"))
                        .unwrap_or(1);
                    dao.put_revision(
                        owner,
                        &id,
                        new_local,
                        value_json.as_deref().map(|j| crate::sync_codec::hash(Some(j))),
                        change.tombstone,
                        now_ms(),
                        change.revision,
                    );
                    count += 1;
                }
            }
            cursor = change.change_seq;
            dao.put_cursor(owner, cursor);
        }
        if short_page {
            break; // page.size < 100 -> last page
        }
    }
    Ok(count)
}

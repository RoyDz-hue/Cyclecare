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
    /// The injected domain write threw (e.g. parse*Payload rejection):
    /// Kotlin's withTransaction rolls back and the exception leaves pull().
    ApplyFailed,
    /// readChanges threw: propagates out of pull() (NOT a clean Ok(count)).
    Transport,
    /// Database failure inside the page transaction (whole page rolled back).
    Db,
}

#[derive(Debug, Clone, PartialEq)]
pub struct RevisionRow {
    pub revision: i64,
    pub server_revision: i64,
}

/// Operations available INSIDE the page transaction. Every method is
/// fallible: a database error aborts the page (rollback of everything,
/// including domain writes and the cursor). DomainStore (Room DAO surface
/// for settings/profile/logs) is a supertrait so domain writes and
/// bookkeeping writes share ONE transaction.
pub trait V3PullTx: DomainStore {
    fn revision(&mut self, owner: &str, record_id: &str) -> Result<Option<RevisionRow>, V3PullError>;
    fn put_revision(
        &mut self,
        owner: &str,
        record_id: &str,
        revision: i64,
        payload_hash: Option<String>,
        tombstone: bool,
        now_ms: i64,
        server_revision: i64,
    ) -> Result<(), V3PullError>;
    fn pending_for_record(&mut self, owner: &str, record_id: &str) -> Result<Vec<crate::v3_outbox::OutboxEntry>, V3PullError>;
    fn put_conflict(&mut self, conflict: crate::v3_outbox::ConflictRow) -> Result<(), V3PullError>;
    fn put_cursor(&mut self, owner: &str, change_seq: i64) -> Result<(), V3PullError>;
}

/// Page-transaction seam. Source: the WHOLE page (every change, domain
/// writes, revisions, conflicts and the cursor) runs inside ONE Room
/// withTransaction. Contract: `transaction` begins, runs `body`, COMMITS on
/// Ok and ROLLS BACK EVERYTHING on Err (body error or database failure),
/// returning the error. The pre-loop cursor read is outside the txn, as in
/// the source.
pub trait V3PullStore {
    fn cursor(&mut self, owner: &str) -> Option<i64>;
    fn transaction(
        &mut self,
        body: &mut dyn FnMut(&mut dyn V3PullTx) -> Result<i32, V3PullError>,
    ) -> Result<i32, V3PullError>;
}

/// Injected readChanges transport; Err(()) = thrown exception.
pub trait V3ReadTransport {
    fn read_changes(
        &mut self,
        after_seq: i64,
        limit: i64,
        consent_generation: i64,
        access_token: &str,
    ) -> Result<Vec<V3WireChange>, ()>;
}

/// The release-gated pull body. A transport error propagates as a typed
/// Err(Transport) (Kotlin: the exception leaves pull(); NOT Ok(count)).
/// Any failure inside a page rolls the whole page back (cursor included)
/// via the store's transaction seam.
pub fn run_v3_pull(
    transport: &mut dyn V3ReadTransport,
    access_token: &str,
    store: &mut dyn V3PullStore,
    owner: &str,
    local: i64,
    server: i64,
    current: &mut dyn FnMut() -> bool,
    now_ms: &mut dyn FnMut() -> i64,
) -> Result<i32, V3PullError> {
    let _ = local; // participates via the `current` closure.
    let mut count = 0i32;
    let _cursor_start = store.cursor(owner).unwrap_or(0);
    let mut cursor = _cursor_start;
    while current() {
        let page = transport
            .read_changes(cursor, 100, server, access_token)
            .map_err(|_| V3PullError::Transport)?;
        if !current() || page.is_empty() {
            break;
        }
        let short_page = page.len() < 100;
        let last_seq = page.last().map(|c| c.change_seq).unwrap_or(cursor);
        let n = store.transaction(&mut |tx: &mut dyn V3PullTx| -> Result<i32, V3PullError> {
            if !current() {
                return Err(V3PullError::ConsentChanged);
            }
            let mut applied = 0i32;
            for change in &page {
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
                let value = change.payload.as_ref().and_then(|p| match p {
                    crate::sync_codec::JsonValue::Object(pairs) => {
                        pairs.iter().find(|(k, _)| k == "value").map(|(_, v)| v.clone())
                    }
                    _ => None,
                });
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
                let prior = tx.revision(owner, &id)?;
                if change.revision > prior.as_ref().map(|p| p.server_revision).unwrap_or(0) {
                    // maxByOrNull(revision) = FIRST max on ties.
                    let pending = tx
                        .pending_for_record(owner, &id)?
                        .into_iter()
                        .reduce(|a, b| if b.revision > a.revision { b } else { a });
                    if let Some(pending) = pending {
                        tx.put_conflict(crate::v3_outbox::ConflictRow {
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
                        })?;
                    } else {
                        if change.tombstone {
                            apply_tombstone_payload(tx, &id).map_err(|_| V3PullError::ApplyFailed)?;
                        } else {
                            let v = value_json.clone().expect("shape_ok guarantees value for non-tombstone");
                            apply_remote_payload(tx, &id, &v).map_err(|_| V3PullError::ApplyFailed)?;
                        }
                        let new_local = prior
                            .as_ref()
                            .map(|p| p.revision.checked_add(1).expect("addExact"))
                            .unwrap_or(1);
                        tx.put_revision(
                            owner,
                            &id,
                            new_local,
                            value_json.as_deref().map(|j| crate::sync_codec::hash(Some(j))),
                            change.tombstone,
                            now_ms(),
                            change.revision,
                        )?;
                        applied += 1;
                    }
                }
                tx.put_cursor(owner, change.change_seq)?;
            }
            Ok(applied)
        })?;
        count += n;
        cursor = last_seq;
        if short_page {
            break; // page.size < 100 -> last page
        }
    }
    Ok(count)
}

// ---------------------------------------------------------------------------
// Concrete domain apply (applyRemote + tombstone branch, Kotlin :180-187 and
// :169). The DomainStore seam is the Room DAO surface; implementations own
// transaction-equivalent atomicity with the V3PullDao writes.
// ---------------------------------------------------------------------------
use crate::sync_codec::{
    parse_log_payload, parse_profile_payload, parse_settings_payload, profile_payload,
    settings_payload, CycleDayLog, CycleSettings, OnboardingProfile,
};

/// Domain writes are FALLIBLE: a database error inside the page transaction
/// must abort the page (Err -> V3PullError::ApplyFailed -> rollback).
pub trait DomainStore {
    fn get_settings(&mut self) -> Option<CycleSettings>;
    fn save_settings(&mut self, s: CycleSettings) -> Result<(), String>;
    fn get_profile(&mut self) -> Option<OnboardingProfile>;
    fn save_profile(&mut self, p: OnboardingProfile) -> Result<(), String>;
    fn upsert_log(&mut self, l: CycleDayLog) -> Result<(), String>;
    fn delete_log(&mut self, date: &str) -> Result<(), String>;
}

/// applyRemote: settings/profile merge over the local row; any other id is a
/// day log (id already passed valid_logical_id upstream). Parse failure is
/// an Err (Kotlin throws; the surrounding transaction rolls back).
pub fn apply_remote_payload(store: &mut dyn DomainStore, id: &str, payload: &str) -> Result<(), String> {
    match id {
        "settings" => {
            let local = store.get_settings();
            let v = parse_settings_payload(payload, id, local.as_ref())?;
            store.save_settings(v)?;
        }
        "profile" => {
            let local = store.get_profile();
            let v = parse_profile_payload(payload, id, local.as_ref())?;
            store.save_profile(v)?;
        }
        _ => {
            let v = parse_log_payload(payload, id)?;
            store.upsert_log(v)?;
        }
    }
    Ok(())
}

/// Tombstone branch: settings/profile are RESET to defaults by round-tripping
/// the default payload through the parser over the local row (so local-only
/// fields survive exactly as the parser merges them); a log id is deleted.
pub fn apply_tombstone_payload(store: &mut dyn DomainStore, id: &str) -> Result<(), String> {
    match id {
        "settings" => {
            let local = store.get_settings();
            let v = parse_settings_payload(&settings_payload(&CycleSettings::default()), id, local.as_ref())?;
            store.save_settings(v)?;
        }
        "profile" => {
            let local = store.get_profile();
            let v = parse_profile_payload(&profile_payload(&OnboardingProfile::default()), id, local.as_ref())?;
            store.save_profile(v)?;
        }
        _ => store.delete_log(id)?,
    }
    Ok(())
}

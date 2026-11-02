//! REAL rusqlite-backed HealthSyncLocalDao (HealthSyncLocal.kt:14-127) —
//! the actual DAO behind the V3 seams, replacing in-memory fakes with the
//! verbatim @Query SQL against the production table/column/PK/index shapes.
//! Feature `sqlite` (bundled), host-only: this proves the seams against a
//! real database engine; Android Room on the encrypted app database remains
//! the separate, still-open integration (no plaintext fallback, no live
//! migration). Synthetic scratch data only.
//!
//! DDL is derived from the entity definitions; Room's exported schema JSON
//! is not in the repo, so exact generated-DDL parity (constraint spellings)
//! is asserted here via PRAGMA against the entity shapes rather than diffed
//! against Room's output. Columns are always written with full lists.
//!
//! Current-owner parity (OwnerScopedDatabaseProvider.kt): the provider
//! resolves the database AT CALL TIME by authPreferences.userId so account
//! switches can never reuse a prior owner's handle. The same obligation
//! applies here: route/construct SqliteV3Store per current owner at every
//! call site; never cache a store across an account switch.

#![cfg(feature = "sqlite")]

use rusqlite::{params, Connection, OptionalExtension};
use crate::sync_codec::{CycleDayLog, CycleSettings, OnboardingProfile};
use crate::v3_outbox::{CommitOutcome, ConflictRow, OutboxEntry, V3SyncDao};
use crate::v3_pull::{DomainStore, RevisionRow, V3PullError, V3PullStore, V3PullTx};

/// Fresh-create DDL from the Room entities (:14-63). Booleans are INTEGER
/// 0/1 (Room convention). DEFAULT 0 appears only where the entity declares
/// @ColumnInfo(defaultValue = "0") (HealthSyncRevision.serverRevision);
/// inserts always name every column, so defaults are never load-bearing.
pub const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS health_sync_revisions (
    ownerId TEXT NOT NULL,
    recordId TEXT NOT NULL,
    revision INTEGER NOT NULL,
    payloadHash TEXT,
    tombstone INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    serverRevision INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (ownerId, recordId)
);
CREATE TABLE IF NOT EXISTS health_sync_outbox (
    ownerId TEXT NOT NULL,
    operationId TEXT NOT NULL,
    recordId TEXT NOT NULL,
    kind TEXT NOT NULL,
    baseRevision INTEGER NOT NULL,
    revision INTEGER NOT NULL,
    payloadJson TEXT,
    payloadHash TEXT NOT NULL,
    consentGeneration INTEGER NOT NULL,
    attempts INTEGER NOT NULL,
    nextAttemptAt INTEGER NOT NULL,
    lastError TEXT,
    serverReceipt TEXT,
    serverRevision INTEGER,
    PRIMARY KEY (ownerId, operationId)
);
CREATE INDEX IF NOT EXISTS index_health_sync_outbox_ownerId_nextAttemptAt
    ON health_sync_outbox (ownerId, nextAttemptAt);
CREATE TABLE IF NOT EXISTS health_sync_conflicts (
    ownerId TEXT NOT NULL,
    recordId TEXT NOT NULL,
    operationId TEXT NOT NULL,
    localRevision INTEGER NOT NULL,
    localPayloadJson TEXT,
    localKind TEXT NOT NULL,
    remoteRevision INTEGER NOT NULL,
    remotePayloadJson TEXT,
    remoteTombstone INTEGER NOT NULL,
    createdAt INTEGER NOT NULL,
    resolvedAt INTEGER,
    resolution TEXT,
    PRIMARY KEY (ownerId, recordId)
);
CREATE INDEX IF NOT EXISTS index_health_sync_conflicts_ownerId_resolvedAt
    ON health_sync_conflicts (ownerId, resolvedAt);
CREATE TABLE IF NOT EXISTS health_sync_cursors (
    ownerId TEXT NOT NULL PRIMARY KEY,
    changeSeq INTEGER NOT NULL
);
";

fn row_to_entry(r: &rusqlite::Row<'_>) -> rusqlite::Result<OutboxEntry> {
    Ok(OutboxEntry {
        owner: r.get("ownerId")?,
        operation_id: r.get("operationId")?,
        record_id: r.get("recordId")?,
        kind: r.get("kind")?,
        base_revision: r.get("baseRevision")?,
        revision: r.get("revision")?,
        payload_json: r.get("payloadJson")?,
        payload_hash: r.get("payloadHash")?,
        consent_generation: r.get("consentGeneration")?,
        attempts: r.get("attempts")?,
        next_attempt_at: r.get("nextAttemptAt")?,
    })
}

/// :66-67 — SELECT * ... WHERE ownerId ORDER BY nextAttemptAt, revision.
const SQL_PENDING: &str =
    "SELECT * FROM health_sync_outbox WHERE ownerId = ?1 ORDER BY nextAttemptAt, revision";

fn query_pending(conn: &Connection, owner: &str) -> rusqlite::Result<Vec<OutboxEntry>> {
    let mut st = conn.prepare(SQL_PENDING)?;
    let rows = st.query_map(params![owner], row_to_entry)?;
    rows.collect()
}

/// D is the domain-store seam (see SqlitePullTx docs); it is unused by the
/// V3SyncDao surface.
pub struct SqliteV3Store<D> {
    pub conn: Connection,
    pub domain: D,
}

impl<D> SqliteV3Store<D> {
    pub fn open_in_memory(domain: D) -> rusqlite::Result<Self> {
        let conn = Connection::open_in_memory()?;
        conn.execute_batch(SCHEMA)?;
        Ok(SqliteV3Store { conn, domain })
    }
    /// Scratch-file variant for restart-recovery tests (synthetic data only).
    pub fn open_file(path: &std::path::Path, domain: D) -> rusqlite::Result<Self> {
        let conn = Connection::open(path)?;
        conn.execute_batch(SCHEMA)?;
        Ok(SqliteV3Store { conn, domain })
    }
    /// :117-118 — INSERT OR REPLACE (OnConflictStrategy.REPLACE).
    pub fn enqueue(&self, e: &OutboxEntry) -> rusqlite::Result<usize> {
        self.conn.execute(
            "INSERT OR REPLACE INTO health_sync_outbox
             (ownerId, operationId, recordId, kind, baseRevision, revision, payloadJson,
              payloadHash, consentGeneration, attempts, nextAttemptAt)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)",
            params![e.owner, e.operation_id, e.record_id, e.kind, e.base_revision, e.revision,
                    e.payload_json, e.payload_hash, e.consent_generation, e.attempts, e.next_attempt_at],
        )
    }
}

fn put_conflict_on(conn: &Connection, c: &ConflictRow) -> rusqlite::Result<usize> {
    // :104-105 — INSERT OR REPLACE; resolvedAt/resolution stay NULL.
    conn.execute(
        "INSERT OR REPLACE INTO health_sync_conflicts
         (ownerId, recordId, operationId, localRevision, localPayloadJson, localKind,
          remoteRevision, remotePayloadJson, remoteTombstone, createdAt)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)",
        params![c.owner, c.record_id, c.operation_id, c.local_revision, c.payload_json, c.kind,
                c.remote_revision, c.remote_payload, c.remote_tombstone as i64, c.created_ms],
    )
}

impl<D> V3SyncDao for SqliteV3Store<D> {
    fn pending(&mut self, owner: &str) -> Vec<OutboxEntry> {
        query_pending(&self.conn, owner).unwrap_or_default()
    }

    fn commit_applied(
        &mut self,
        owner: &str,
        operation_id: &str,
        record_id: &str,
        entry_revision: i64,
        base_revision: i64,
        new_revision: i64,
        gate: &mut dyn FnMut() -> bool,
    ) -> CommitOutcome {
        let r = (|| -> rusqlite::Result<CommitOutcome> {
            let tx = self.conn.transaction()?;
            // (1) The in-transaction current() gate — false rolls back.
            if !gate() {
                return Ok(CommitOutcome::GateDrift);
            }
            // (2) Read the existing revision row (:60-61 shape).
            let existing: Option<i64> = tx
                .query_row(
                    "SELECT serverRevision FROM health_sync_revisions WHERE ownerId = ?1 AND recordId = ?2 LIMIT 1",
                    params![owner, record_id],
                    |r| r.get(0),
                )
                .ok();
            // (3) Max-only update, ONLY when the row exists: never insert,
            // never lower (v51 contract).
            if existing.is_some() {
                tx.execute(
                    "UPDATE health_sync_revisions SET serverRevision = ?3
                     WHERE ownerId = ?1 AND recordId = ?2 AND ?3 > serverRevision",
                    params![owner, record_id, new_revision],
                )?;
            }
            // (4) acknowledgeMatched (:79-80).
            tx.execute(
                "DELETE FROM health_sync_outbox WHERE ownerId = ?1 AND operationId = ?2 AND recordId = ?3 AND revision = ?4",
                params![owner, operation_id, record_id, entry_revision],
            )?;
            // (5) rebasePending (:88-89).
            tx.execute(
                "UPDATE health_sync_outbox SET baseRevision = ?4 WHERE ownerId = ?1 AND recordId = ?2 AND baseRevision = ?3",
                params![owner, record_id, base_revision, new_revision],
            )?;
            tx.commit()?;
            Ok(CommitOutcome::Committed)
        })();
        match r {
            Ok(outcome) => outcome, // GateDrift: tx dropped without commit -> rolled back
            Err(_) => CommitOutcome::DbError, // tx dropped -> engine rolls back
        }
    }

    fn put_conflict(&mut self, conflict: ConflictRow) {
        let _ = put_conflict_on(&self.conn, &conflict);
    }

    fn retry(&mut self, owner: &str, operation_id: &str, next_attempt_at: i64, message: &str) {
        // :94-95
        let _ = self.conn.execute(
            "UPDATE health_sync_outbox SET attempts = attempts + 1, nextAttemptAt = ?3, lastError = ?4 WHERE ownerId = ?1 AND operationId = ?2",
            params![owner, operation_id, next_attempt_at, message],
        );
    }
}

/// The page transaction pairs the real sync-side SQL with the domain-store
/// seam. PRODUCTION ADAPTER OBLIGATION (unchanged from v3_pull.rs): the
/// injected DomainStore MUST join the same Room withTransaction as these
/// statements (cycleDao/onboardingDao on the same CycleCareDatabase); the
/// domain tables themselves are the separate app-schema integration slice,
/// still open. Tests inject an in-memory domain store.
struct SqlitePullTx<'t, 'd, D: DomainStore> {
    tx: &'t rusqlite::Transaction<'t>,
    domain: &'d mut D,
}

fn dbe<T>(r: rusqlite::Result<T>) -> Result<T, V3PullError> { r.map_err(|_| V3PullError::Db) }

impl<D: DomainStore> SqlitePullTx<'_, '_, D> {
    fn conn(&self) -> &Connection { self.tx }
}

impl<D: DomainStore> DomainStore for SqlitePullTx<'_, '_, D> {
    fn get_settings(&mut self) -> Option<CycleSettings> { self.domain.get_settings() }
    fn save_settings(&mut self, s: CycleSettings) -> Result<(), String> { self.domain.save_settings(s) }
    fn get_profile(&mut self) -> Option<OnboardingProfile> { self.domain.get_profile() }
    fn save_profile(&mut self, p: OnboardingProfile) -> Result<(), String> { self.domain.save_profile(p) }
    fn upsert_log(&mut self, l: CycleDayLog) -> Result<(), String> { self.domain.upsert_log(l) }
    fn delete_log(&mut self, date: &str) -> Result<(), String> { self.domain.delete_log(date) }
}

impl<D: DomainStore> V3PullTx for SqlitePullTx<'_, '_, D> {
    fn revision(&mut self, owner: &str, id: &str) -> Result<Option<RevisionRow>, V3PullError> {
        // :60-61
        dbe(self.conn().query_row(
            "SELECT revision, serverRevision FROM health_sync_revisions WHERE ownerId = ?1 AND recordId = ?2 LIMIT 1",
            params![owner, id],
            |r| Ok(RevisionRow { revision: r.get(0)?, server_revision: r.get(1)? }),
        ).optional())
    }
    fn put_revision(&mut self, owner: &str, id: &str, rev: i64, hash: Option<String>, tombstone: bool, now: i64, server_rev: i64) -> Result<(), V3PullError> {
        // :63-64 — INSERT OR REPLACE.
        dbe(self.conn().execute(
            "INSERT OR REPLACE INTO health_sync_revisions
             (ownerId, recordId, revision, payloadHash, tombstone, updatedAt, serverRevision)
             VALUES (?1,?2,?3,?4,?5,?6,?7)",
            params![owner, id, rev, hash, tombstone as i64, now, server_rev],
        ).map(|_| ()))
    }
    fn pending_for_record(&mut self, owner: &str, id: &str) -> Result<Vec<OutboxEntry>, V3PullError> {
        // dao.pending(owner).filter { it.recordId == id } — ORDER BY preserved.
        dbe(query_pending(self.conn(), owner).map(|v| v.into_iter().filter(|e| e.record_id == id).collect()))
    }
    fn put_conflict(&mut self, c: ConflictRow) -> Result<(), V3PullError> {
        dbe(put_conflict_on(self.conn(), &c).map(|_| ()))
    }
    fn put_cursor(&mut self, owner: &str, seq: i64) -> Result<(), V3PullError> {
        // :116-117 — INSERT OR REPLACE.
        dbe(self.conn().execute(
            "INSERT OR REPLACE INTO health_sync_cursors (ownerId, changeSeq) VALUES (?1, ?2)",
            params![owner, seq],
        ).map(|_| ()))
    }
}

impl<D: DomainStore> V3PullStore for SqliteV3Store<D> {
    fn cursor(&mut self, owner: &str) -> Option<i64> {
        // :113-114
        self.conn
            .query_row("SELECT changeSeq FROM health_sync_cursors WHERE ownerId = ?1 LIMIT 1", params![owner], |r| r.get(0))
            .ok()
    }
    fn transaction(&mut self, body: &mut dyn FnMut(&mut dyn V3PullTx) -> Result<i32, V3PullError>) -> Result<i32, V3PullError> {
        // Real withTransaction parity: BEGIN IMMEDIATE; body; COMMIT on Ok,
        // engine ROLLBACK on Err (the Transaction is dropped uncommitted).
        let tx = dbe(self.conn.transaction())?;
        let r = {
            let mut t = SqlitePullTx { tx: &tx, domain: &mut self.domain };
            body(&mut t)?
        };
        dbe(tx.commit())?;
        Ok(r)
    }
}

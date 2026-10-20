package com.example.domain.sync

/** Conflict-safe v3 primitives. This module is pure Kotlin and deliberately independent of Room/HTTP. */
enum class SyncOperationKind { UPSERT, DELETE }

data class SyncOperation(
    val operationId: String,
    val ownerId: String,
    val recordId: String,
    val kind: SyncOperationKind,
    val baseRevision: Long,
    val revision: Long,
    val payloadHash: String,
    val consentGeneration: Long
)

data class SyncRecord(val revision: Long, val payloadHash: String?, val tombstone: Boolean)
data class SyncConflict(val recordId: String, val local: SyncOperation, val remote: SyncRecord)
sealed interface ApplyResult {
    data class Applied(val record: SyncRecord) : ApplyResult
    data class Duplicate(val record: SyncRecord) : ApplyResult
    data class Rejected(val reason: String) : ApplyResult
    data class Conflict(val value: SyncConflict) : ApplyResult
}

class HealthSyncStateMachine(private val ownerId: String) {
    private val operations = LinkedHashMap<String, SyncOperation>()
    private val records = LinkedHashMap<String, SyncRecord>()
    private var consentGeneration = 0L
    private var consentGranted = false

    @Synchronized fun grantConsent(): Long { consentGeneration++; consentGranted = true; return consentGeneration }
    @Synchronized fun revokeConsent() { consentGeneration++; consentGranted = false }
    @Synchronized fun currentConsentGeneration(): Long = consentGeneration
    @Synchronized fun record(recordId: String): SyncRecord? = records[recordId]

    @Synchronized fun apply(operation: SyncOperation): ApplyResult {
        if (operation.ownerId != ownerId) return ApplyResult.Rejected("owner_mismatch")
        if (!consentGranted || operation.consentGeneration != consentGeneration) return ApplyResult.Rejected("stale_or_revoked_consent")
        if (operation.baseRevision < 0 || operation.revision <= 0 || operation.revision != operation.baseRevision + 1) {
            return ApplyResult.Rejected("revision_must_be_base_plus_one")
        }
        val priorOperation = operations[operation.operationId]
        if (priorOperation != null) {
            return if (priorOperation == operation) {
                ApplyResult.Duplicate(SyncRecord(priorOperation.revision,
                    if (priorOperation.kind == SyncOperationKind.DELETE) null else priorOperation.payloadHash,
                    priorOperation.kind == SyncOperationKind.DELETE))
            } else ApplyResult.Rejected("operation_id_payload_reuse")
        }
        val current = records[operation.recordId]
        if (current == null && operation.baseRevision != 0L) return ApplyResult.Conflict(
            SyncConflict(operation.recordId, operation, SyncRecord(0, null, false))
        )
        if (current != null && operation.baseRevision != current.revision) {
            return ApplyResult.Conflict(SyncConflict(operation.recordId, operation, current))
        }
        if (current?.tombstone == true && operation.kind == SyncOperationKind.UPSERT) return ApplyResult.Rejected("tombstone_resurrection")
        val next = SyncRecord(operation.revision, if (operation.kind == SyncOperationKind.DELETE) null else operation.payloadHash, operation.kind == SyncOperationKind.DELETE)
        operations[operation.operationId] = operation
        records[operation.recordId] = next
        return ApplyResult.Applied(next)
    }
}

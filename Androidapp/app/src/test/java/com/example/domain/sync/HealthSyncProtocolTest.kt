package com.example.domain.sync

import org.junit.Assert.*
import org.junit.Test

class HealthSyncProtocolTest {
    private fun op(id: String, owner: String = "A", record: String = "r", base: Long, rev: Long, kind: SyncOperationKind = SyncOperationKind.UPSERT, hash: String = "h", generation: Long = 1) =
        SyncOperation(id, owner, record, kind, base, rev, hash, generation)

    @Test fun concurrentABEditProducesExplicitConflict() {
        val s = HealthSyncStateMachine("A"); s.grantConsent()
        assertTrue(s.apply(op("a", base=0, rev=1)).isApplied())
        assertTrue(s.apply(op("b", base=0, rev=1, hash="other")) is ApplyResult.Conflict)
    }
    @Test fun revokedConsentRejectsStaleOperation() {
        val s = HealthSyncStateMachine("A"); val g=s.grantConsent(); s.revokeConsent()
        assertEquals("stale_or_revoked_consent", (s.apply(op("x", base=0, rev=1, generation=g)) as ApplyResult.Rejected).reason)
    }
    @Test fun retryIsIdempotentButPayloadReuseIsRejected() {
        val s=HealthSyncStateMachine("A"); s.grantConsent(); val x=op("x",base=0,rev=1)
        assertTrue(s.apply(x).isApplied()); assertTrue(s.apply(x) is ApplyResult.Duplicate)
        assertEquals("operation_id_payload_reuse", (s.apply(x.copy(payloadHash="tampered")) as ApplyResult.Rejected).reason)
    }
    @Test fun deleteWinsOnlyAtItsBaseAndCannotBeResurrected() {
        val s=HealthSyncStateMachine("A"); s.grantConsent()
        assertTrue(s.apply(op("u",base=0,rev=1)).isApplied())
        assertTrue(s.apply(op("d",base=1,rev=2,kind=SyncOperationKind.DELETE)).isApplied())
        assertEquals("tombstone_resurrection", (s.apply(op("r",base=2,rev=3)) as ApplyResult.Rejected).reason)
    }
    @Test fun accountSwitchAndStaleAckCannotMutateOwner() {
        val a=HealthSyncStateMachine("A"); a.grantConsent()
        assertEquals("owner_mismatch", (a.apply(op("x",owner="B",base=0,rev=1)) as ApplyResult.Rejected).reason)
        assertTrue(a.record("r") == null)
    }
    @Test fun missingRecordRequiresZeroBaseAndRevisionIncrementsExactlyOnce() {
        val s=HealthSyncStateMachine("A"); s.grantConsent()
        assertTrue(s.apply(op("bad",record="new",base=1,rev=2)) is ApplyResult.Conflict)
        assertEquals("revision_must_be_base_plus_one", (s.apply(op("skip",record="new",base=0,rev=2)) as ApplyResult.Rejected).reason)
    }
    @Test fun deleteErasesPayloadHashAndCannotBeResurrected() {
        val s=HealthSyncStateMachine("A"); s.grantConsent()
        assertTrue(s.apply(op("u",base=0,rev=1)).isApplied())
        val deleted=s.apply(op("d",base=1,rev=2,kind=SyncOperationKind.DELETE)) as ApplyResult.Applied
        assertNull(deleted.record.payloadHash)
        assertEquals("tombstone_resurrection", (s.apply(op("r",base=2,rev=3)) as ApplyResult.Rejected).reason)
    }
    @Test fun oldRetryAcknowledgesOriginalRevisionWithoutChangingNewerRecord() {
        val s = HealthSyncStateMachine("A"); s.grantConsent()
        val first = op("first", base=0, rev=1, hash="original")
        assertTrue(s.apply(first).isApplied())
        assertTrue(s.apply(op("second", base=1, rev=2, hash="newer")).isApplied())
        val duplicate = s.apply(first) as ApplyResult.Duplicate
        assertEquals(1L, duplicate.record.revision)
        assertEquals("original", duplicate.record.payloadHash)
        assertEquals(2L, s.record("r")?.revision)
        assertEquals("newer", s.record("r")?.payloadHash)
    }
    private fun ApplyResult.isApplied() = this is ApplyResult.Applied
}

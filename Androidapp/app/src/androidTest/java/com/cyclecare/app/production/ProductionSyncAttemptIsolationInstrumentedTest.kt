package com.cyclecare.app.production

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.rust.RustBoundRoomHost
import com.cyclecare.app.data.local.rust.RustTrackNative
import com.cyclecare.app.data.repository.CycleCareComposition
import com.cyclecare.app.data.repository.RustPortInstallResolver
import com.cyclecare.app.data.repository.RustPortInstaller
import com.cyclecare.app.data.supabase.HealthSyncV3Transport
import com.cyclecare.app.data.supabase.SupabaseSyncService
import com.cyclecare.app.data.supabase.V3WireChange
import com.cyclecare.app.data.supabase.V3WireOperation
import com.cyclecare.app.data.supabase.V3WireReceipt
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DEVICE acceptance of the REAL [SupabaseSyncService.pullAttempt] body on the
 * BOUND Holder/repository route: the service is bound to the process
 * repository with a REAL installed Rust port, so the attempt dispatches
 * through repository.dispatchApplyRemotePage, exactly like the production
 * worker's bound service. NOT protocol-released acceptance:
 * conflictSafeProtocolReleased() stays hardcoded false, so syncAll never
 * reaches these attempts today; the OUTBOX attempt and any
 * released-protocol coverage remain an explicitly preserved gap.
 *
 * Positive control: with a stable identity the EXACT seeded pending set
 * (date log + default settings, both enqueued by the fresh-owner save) is
 * first acknowledged through the production outbox-ack route (scripted
 * transport echoing valid applied receipts bound to the stored content
 * hashes), because production conflict rules route ANY remote change with
 * a pending local op to health_sync_conflicts, never to apply; with NO
 * pending op remaining, a single valid tombstone
 * page is then fetched, validated, dispatched and applied (exactly one
 * fetch call), and the revision row, pull cursor and domain state all
 * advance. Mid-flight
 * cases: the captured owner/session/consent identity moves DURING the first
 * in-flight page; the production attempt must abort cleanly BEFORE
 * validation/dispatch - exactly one call, zero applied, zero recorded remote
 * revisions. An always-abort bug cannot fake the positive control; a
 * missing-check bug cannot fake the aborts (it would proceed into validation
 * and throw an identity error on the deliberate placeholders).
 *
 * Isolation: process-wide fail-closed network deny (counted), WorkManager
 * barrier before any synthetic state, synthetic owners only, cleanup resets
 * owned identities to DEFAULTS and deletes only owned databases.
 */
@RunWith(AndroidJUnit4::class)
class ProductionSyncAttemptIsolationInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val ownedOwners = mutableListOf<String>()

    @Before
    fun baselineAndBarriers() {
        ProductionTestControls.cancelAndConfirmNoUnfinishedSyncWork(context)
        ProductionTestControls.installNetworkDeny()
        val prefs = CycleCareComposition.current(context).authPreferences
        ProductionTestControls.assertCleanBaseline(prefs)
    }

    @After
    fun resetToDefaults() {
        // STOP-state discipline: if the holder's recovery does not reach a
        // terminal state within the bound, cleanup STOPS here - no identity
        // resets, no database deletion, NO selector restore. The holder
        // RETAINS ownership (resetForTestsAndAwait drops it only after a
        // terminal join) and the deny wall stays installed, so the next
        // install fails closed. This check PROPAGATES out of the @After.
        check(runBlocking { CycleCareComposition.resetForTestsAndAwait() }) {
            "holder recovery did not quiesce within the bound; STOPPING cleanup (wall left installed, ownership retained)"
        }
        val failures = mutableListOf<String>()
        try {
            val prefs = CycleCareComposition.current(context).authPreferences
            for (owner in ownedOwners) {
                runCatching { ProductionTestControls.resetOwnedIdentity(prefs, owner) }
                    .onFailure { failures += "reset $owner: $it" }
            }
            for (owner in ownedOwners) {
                runCatching { ProductionTestControls.deleteOwnedDatabase(context, owner) }
                    .onFailure { failures += "delete $owner: $it" }
            }
            ownedOwners.clear()
        } catch (t: Throwable) {
            failures += "cleanup: $t"
        }
        runCatching { ProductionTestControls.cancelAndConfirmNoUnfinishedSyncWork(context) }
            .onFailure { failures += "work barrier: $it" }
        val leftovers = runCatching { ProductionTestControls.uninstallNetworkDenyAndDrainAttempts() }
            .getOrElse { failures += "network deny uninstall: $it"; listOf("<uninstall failed>") }
        assertTrue("cleanup failed: $failures", failures.isEmpty())
        assertTrue(
            "no network attempt may escape any test on this class, unexplained: $leftovers",
            leftovers.isEmpty()
        )
    }

    @Test
    fun stableIdentity_positiveControl_fetchesAppliesAndStops() {
        val fixture = newBoundFixture("control")
        // A REAL local row under a VALID logical id (validLogicalId admits
        // "profile", "settings" or an ISO LocalDate), seeded through the
        // port-routed production write path. That path ALSO enqueues a real
        // outbox op for the target (RustRoomBatch.apply), and production
        // conflict rules (RustRemoteRows.apply) route ANY remote change for
        // a record with a pending local op to health_sync_conflicts, never
        // to apply - so the tombstone below can only apply after the seeded
        // op is acknowledged through the REAL ack route.
        val date = LocalDate.now().minusDays(5).toString()
        runBlocking {
            fixture.repository.saveDayLogBound(CycleDayLog(dateString = date, flowLevel = "LIGHT"))
        }
        ProductionTestControls.awaitUntilSuspending(10_000, "seeded log visible before the ack baseline") {
            val logs = fixture.repository.allLogs.first()
            if (logs.any { it.dateString == date }) Unit else null
        }

        // PHASE 1 - synthetic remote-ack baseline via the REAL ack route.
        // The fresh-owner save enqueues BOTH the date log AND the default
        // settings (RustRoomBatch.apply: one outbox row per mutation), so
        // the seeded pending set is asserted EXACTLY, then every op is
        // acknowledged through the production outbox attempt body. Each
        // scripted receipt echoes the STORED payload hash after re-verifying
        // it against HealthSyncPayloadCodec.hash(stored payloadJson): a
        // content-hash equality proof, not a syntactic fixture.
        val dao = fixture.db.healthSyncDao()
        val seededPending = runBlocking { dao.pending(fixture.owner) }
        assertEquals(
            "the fresh-owner save must enqueue exactly the date log and the default settings",
            setOf(date to "UPSERT", "settings" to "UPSERT"),
            seededPending.map { it.recordId to it.kind }.toSet()
        )
        var ackCalls = 0
        val ackTransport = object : HealthSyncV3Transport {
            override suspend fun apply(operation: V3WireOperation, accessToken: String): V3WireReceipt {
                ackCalls++
                val stored = fixture.db.healthSyncDao().pending(fixture.owner)
                    .firstOrNull { it.operationId == operation.operationId }
                    ?: throw AssertionError("ack requested for an unknown operation: ${operation.operationId}")
                check(stored.payloadHash == com.cyclecare.app.data.local.HealthSyncPayloadCodec.hash(stored.payloadJson)) {
                    "stored payload hash mismatch for ${stored.recordId}"
                }
                return V3WireReceipt(
                    status = "applied",
                    revision = operation.revision,
                    changeSeq = null,
                    payloadHash = stored.payloadHash,
                    reason = null,
                    recordId = operation.recordId,
                    operationId = operation.operationId,
                    logicalRecordId = operation.logicalRecordId
                )
            }
            override suspend fun readChanges(afterSeq: Long, limit: Int, consentGeneration: Long, accessToken: String): List<V3WireChange> =
                throw AssertionError("unexpected readChanges during the ack baseline")
        }
        val acked = runBlocking {
            fixture.service.executeV3OutboxAttempt(ackTransport, SYNTHETIC_TOKEN, fixture.owner, fixture.session, fixture.local, 1L, fixture.db, fixture.origin)
        }
        assertEquals("every seeded operation must be offered for ack exactly once", seededPending.size, ackCalls)
        assertEquals("the real ack route must commit every seeded operation", seededPending.size, acked)
        val remaining = runBlocking { dao.pending(fixture.owner) }
        assertTrue(
            "no pending op may remain before the pull (production conflict rules), still pending: $remaining",
            remaining.isEmpty()
        )

        // PHASE 2 - the pull: the ack raised the target's serverRevision to
        // 1, so the remote tombstone arrives at revision 2 and must APPLY
        // through the port dispatch. The observable domain change is the
        // actual acceptance signal, plus the recorded revision and cursor.
        var calls = 0
        val scripted = object : HealthSyncV3Transport {
            override suspend fun apply(operation: V3WireOperation, accessToken: String): V3WireReceipt =
                throw AssertionError("unexpected transport apply: this test exercises the pull attempt only")
            override suspend fun readChanges(afterSeq: Long, limit: Int, consentGeneration: Long, accessToken: String): List<V3WireChange> {
                calls++
                // ONE valid tombstone page: recordId uses the production wire
                // formula (SupabaseSyncService.wireRecordId: UUID v3 of
                // "cyclecare-v3:<owner>:<logicalId>"), so the real validation
                // and the port dispatch both accept it.
                return listOf(
                    V3WireChange(
                        recordId = wireRecordId(fixture.owner, date),
                        revision = 2L,
                        changeSeq = 1L,
                        payload = null,
                        tombstone = true,
                        payloadHash = null,
                        logicalRecordId = date
                    )
                )
            }
        }
        val applied = runBlocking {
            fixture.service.pullAttempt(scripted, SYNTHETIC_TOKEN, fixture.owner, fixture.session, fixture.local, 1L, fixture.db, fixture.origin)
        }
        assertEquals("the stable-identity attempt must apply the single valid change", 1, applied)
        assertEquals("a one-item page must stop after exactly one fetch", 1, calls)
        val revision = runBlocking { fixture.db.healthSyncDao().revision(fixture.owner, date) }
        assertNotNull("the applied tombstone must record a revision row", revision)
        assertEquals("the recorded server revision must be the tombstone's", 2L, revision!!.serverRevision)
        assertTrue("the recorded revision must be a tombstone", revision.tombstone)
        val cursor = runBlocking { fixture.db.healthSyncDao().cursor(fixture.owner)?.changeSeq }
        assertEquals("the pull cursor must advance to the applied page", 1L, cursor)
        ProductionTestControls.awaitUntilSuspending(10_000, "remote tombstone removes the seeded log (domain change)") {
            val logs = fixture.repository.allLogs.first()
            if (logs.none { it.dateString == date }) Unit else null
        }
    }

    @Test
    fun consentRevokedMidFlight_pullAttemptAbortsBeforeApplying() {
        midFlightAbortCase("consent") { prefs -> prefs.isCloudSyncAllowed = false }
    }

    @Test
    fun ownerChangedMidFlight_pullAttemptAbortsBeforeApplying() {
        // The moved-TO owner is tracked BEFORE the mutation: the transition
        // watch may install a port (and database) for it during the abort.
        val moved = "synthetic-prod-attempt-moved-${UUID.randomUUID()}"
        ownedOwners += moved
        midFlightAbortCase("owner") { prefs -> prefs.userId = moved }
    }

    @Test
    fun sessionChangedMidFlight_pullAttemptAbortsBeforeApplying() {
        // Lever: the production local-mode setter bumps session_generation_v3
        // (AuthPreferences.isLocalOnlyMode). NOTE: this moves the session AND
        // the local-mode flag together; distinguishing the two signals is a
        // preserved gap (no public session-only mutation exists).
        midFlightAbortCase("session") { prefs -> prefs.isLocalOnlyMode = true }
    }

    private fun midFlightAbortCase(
        tag: String,
        mutate: (com.cyclecare.app.security.AuthPreferences) -> Unit
    ) {
        val fixture = newBoundFixture(tag)
        var calls = 0
        val scripted = object : HealthSyncV3Transport {
            override suspend fun apply(operation: V3WireOperation, accessToken: String): V3WireReceipt =
                throw AssertionError("unexpected transport apply: this test exercises the pull attempt only")
            override suspend fun readChanges(afterSeq: Long, limit: Int, consentGeneration: Long, accessToken: String): List<V3WireChange> {
                calls++
                // The identity moves WHILE the first page is in flight.
                mutate(fixture.prefs)
                // A full 100-item page of deliberate placeholders: without the
                // post-fetch current() check, production proceeds into
                // validation (identity error) and would ask for page 2.
                return (1..100).map { i ->
                    V3WireChange(
                        recordId = "placeholder-$i",
                        revision = 1L,
                        changeSeq = i.toLong(),
                        payload = null,
                        tombstone = true,
                        payloadHash = null,
                        logicalRecordId = "logical-$i"
                    )
                }
            }
        }
        val applied = runBlocking {
            fixture.service.pullAttempt(scripted, SYNTHETIC_TOKEN, fixture.owner, fixture.session, fixture.local, 1L, fixture.db, fixture.origin)
        }
        assertEquals("the attempt must abort before applying anything once identity moves", 0, applied)
        assertEquals("the attempt must not pull a second page after identity movement", 1, calls)
        val leaked = runBlocking { fixture.db.healthSyncDao().revision(fixture.owner, "logical-1") }
        assertNull("no remote revision may be recorded for the moved identity", leaked)
    }

    private class BoundFixture(
        val prefs: com.cyclecare.app.security.AuthPreferences,
        val owner: String,
        val session: Long,
        val local: Long,
        val db: CycleCareDatabase,
        val repository: com.cyclecare.app.data.repository.CycleRepository,
        val service: SupabaseSyncService,
        val origin: com.cyclecare.app.data.repository.CycleRepository.SyncApplyOrigin?
    )

    private fun newBoundFixture(tag: String): BoundFixture {
        val prefs = CycleCareComposition.current(context).authPreferences
        val owner = "synthetic-prod-attempt-$tag-${UUID.randomUUID()}"
        ownedOwners += owner
        prefs.userId = owner
        prefs.isCloudSyncAllowed = true

        // Warm the native library OFF main BEFORE the install: the same
        // call MainActivity makes on its startup route. This fixture has no
        // Activity, so it warms explicitly - no hidden ordering on a prior
        // UI run having loaded the library.
        try {
            RustTrackNative.warmUp()
        } catch (t: UnsatisfiedLinkError) {
            throw AssertionError("rust native library unavailable for the bound fixture", t)
        } catch (t: NoClassDefFoundError) {
            throw AssertionError("rust native library unloadable for the bound fixture", t)
        }

        // REAL initial install, the same machinery MainActivity runs, so the
        // service binds against a real Holder repository port.
        val repository = CycleCareComposition.current(context).repository
        when (val resolved = RustPortInstallResolver(context, prefs).resolve()) {
            is RustPortInstallResolver.Result.Resolved -> {
                val outcome = RustPortInstaller(repository, RustBoundRoomHost(prefs), resolved.resolution).install()
                assertTrue("port install must succeed for the fixture, got $outcome", outcome is RustPortInstaller.Outcome.Installed)
            }
            else -> throw AssertionError("install resolver did not resolve for the fixture: $resolved")
        }
        assertNotNull("a current port must be installed", repository.installedRustPort())
        assertTrue("the installed port must be current", repository.installedRustPort()!!.isCurrent())

        // Seed a coherent consent receipt so the attempt's current() holds.
        val session = prefs.sessionGeneration
        val local = prefs.healthConsentGeneration
        assertTrue(
            "seeding the synthetic consent receipt must succeed",
            prefs.saveServerHealthConsentReceipt(owner, session, local, 1L, true)
        )
        assertEquals("server consent receipt must resolve", 1L, prefs.serverHealthConsentGeneration)

        val db = CycleCareDatabase.getDatabase(context, prefs)
        val service = SupabaseSyncService(db, prefs)
        service.bindSyncApplyRepository(repository)
        val origin = service.captureSyncApplyBinding(db, owner, session, local, 1L)
        assertNotNull("a bound service with an installed port must capture a real apply origin", origin)
        return BoundFixture(prefs, owner, session, local, db, repository, service, origin)
    }

    private fun wireRecordId(owner: String, logicalId: String): String =
        UUID.nameUUIDFromBytes("cyclecare-v3:$owner:$logicalId".toByteArray(Charsets.UTF_8)).toString()

    private companion object {
        const val SYNTHETIC_TOKEN = "synthetic-token-never-sent"
    }
}

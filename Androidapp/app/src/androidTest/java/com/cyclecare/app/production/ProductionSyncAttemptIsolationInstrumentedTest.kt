package com.cyclecare.app.production

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.rust.RustBoundRoomHost
import com.cyclecare.app.data.repository.CycleCareComposition
import com.cyclecare.app.data.repository.RustPortInstallResolver
import com.cyclecare.app.data.repository.RustPortInstaller
import com.cyclecare.app.data.supabase.HealthSyncV3Transport
import com.cyclecare.app.data.supabase.SupabaseSyncService
import com.cyclecare.app.data.supabase.V3WireChange
import com.cyclecare.app.data.supabase.V3WireOperation
import com.cyclecare.app.data.supabase.V3WireReceipt
import java.util.UUID
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
 * Positive control: with a stable identity a single valid page is fetched,
 * validated, dispatched and counted (exactly one transport call). Mid-flight
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
        val failures = mutableListOf<String>()
        try {
            val prefs = CycleCareComposition.current(context).authPreferences
            for (owner in ownedOwners) {
                runCatching { ProductionTestControls.resetOwnedIdentity(prefs, owner) }
                    .onFailure { failures += "reset $owner: $it" }
            }
            // Shut down the Holder recovery/watch before deleting databases.
            runCatching { CycleCareComposition.resetForTests() }
                .onFailure { failures += "composition reset: $it" }
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
        val attempts = runCatching { ProductionTestControls.uninstallNetworkDenyAndDrainAttempts() }
            .getOrElse { failures += "network deny uninstall: $it"; -1 }
        assertTrue("cleanup failed: $failures", failures.isEmpty())
        assertEquals("no network attempt may escape any test on this class", 0, attempts)
    }

    @Test
    fun stableIdentity_positiveControl_fetchesAppliesAndStops() {
        val fixture = newBoundFixture("control")
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
                        recordId = wireRecordId(fixture.owner, "logical-1"),
                        revision = 1L,
                        changeSeq = 1L,
                        payload = null,
                        tombstone = true,
                        payloadHash = null,
                        logicalRecordId = "logical-1"
                    )
                )
            }
        }
        val applied = runBlocking {
            fixture.service.pullAttempt(scripted, SYNTHETIC_TOKEN, fixture.owner, fixture.session, fixture.local, 1L, fixture.db, fixture.origin)
        }
        assertEquals("the stable-identity attempt must apply the single valid change", 1, applied)
        assertEquals("a one-item page must stop after exactly one fetch", 1, calls)
    }

    @Test
    fun consentRevokedMidFlight_pullAttemptAbortsBeforeApplying() {
        midFlightAbortCase("consent") { prefs -> prefs.isCloudSyncAllowed = false }
    }

    @Test
    fun ownerChangedMidFlight_pullAttemptAbortsBeforeApplying() {
        midFlightAbortCase("owner") { prefs ->
            prefs.userId = "synthetic-prod-attempt-moved-${UUID.randomUUID()}"
        }
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
        val service: SupabaseSyncService,
        val origin: com.cyclecare.app.data.repository.CycleRepository.SyncApplyOrigin?
    )

    private fun newBoundFixture(tag: String): BoundFixture {
        val prefs = CycleCareComposition.current(context).authPreferences
        val owner = "synthetic-prod-attempt-$tag-${UUID.randomUUID()}"
        ownedOwners += owner
        prefs.userId = owner
        prefs.isCloudSyncAllowed = true

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
        return BoundFixture(prefs, owner, session, local, db, service, origin)
    }

    private fun wireRecordId(owner: String, logicalId: String): String =
        UUID.nameUUIDFromBytes("cyclecare-v3:$owner:$logicalId".toByteArray(Charsets.UTF_8)).toString()

    private companion object {
        const val SYNTHETIC_TOKEN = "synthetic-token-never-sent"
    }
}

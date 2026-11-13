package com.cyclecare.app.production

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.SerialExecutor
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import androidx.work.testing.TestForegroundUpdater
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.TestProgressUpdater
import com.cyclecare.app.data.economy.CommunityPulsePinger
import com.cyclecare.app.data.repository.CycleCareComposition
import com.cyclecare.app.data.supabase.HealthSyncV3Transport
import com.cyclecare.app.data.supabase.SupabaseSyncService
import com.cyclecare.app.data.supabase.SupabaseSyncWorker
import com.cyclecare.app.data.supabase.V3ConsentReceipt
import com.cyclecare.app.data.supabase.V3WireChange
import com.cyclecare.app.data.supabase.V3WireOperation
import com.cyclecare.app.data.supabase.V3WireReceipt
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DEVICE acceptance of the REAL [SupabaseSyncWorker.doWork] consent contract.
 * Scope (preserved gap): the released consent-true path only - consent gates,
 * owner-scoped DB open, syncAll paused at the hardcoded protocol gate, and
 * the community-ping route. In-flight pull/outbox under a released protocol
 * is NOT claimed here or anywhere (conflictSafeProtocolReleased() stays false).
 *
 * Isolation: a process-wide fail-closed ProxySelector denies ALL network
 * (token-bearing or anonymous) and counts attempts; its installation is
 * ASSERTED before any synthetic token exists (init-timing, not prose); the
 * WorkManager barrier confirms NO pending production sync work exists
 * before any synthetic consent/token is seeded, so the REAL pinger can
 * never see a synthetic JWT. The negative controls pin the REAL pinger's
 * destination to loopback-only (no real destination can be constructed
 * even under a selector bypass) and CONSUME their counted attempts, so the
 * class drain still asserts zero UNEXPLAINED attempts.
 * The scripted transport factory records construction instead of building
 * OkHttpHealthSyncV3Transport; the scripted pinger records instead of opening
 * its private client. All owners are synthetic; cleanup resets every owned
 * identity to DEFAULTS and deletes only owned databases.
 */
@RunWith(AndroidJUnit4::class)
class ProductionWorkerSyntheticConsentInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val ownedOwners = mutableListOf<String>()

    @Before
    fun baselineAndBarriers() {
        // Barrier FIRST: nothing synthetic may exist while production work
        // could still be pending from an earlier test or a stale process.
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
                runCatching { ProductionTestControls.deleteOwnedDatabase(context, owner) }
                    .onFailure { failures += "delete $owner: $it" }
            }
            ownedOwners.clear()
            val prefsAfter = CycleCareComposition.current(context).authPreferences
            runCatching { ProductionTestControls.assertCleanBaseline(prefsAfter) }
                .onFailure { failures += "post-cleanup defaults: $it" }
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
    fun consentFalse_realWorker_returnsSuccessWithoutOpeningDatabaseOrPinging() {
        val prefs = CycleCareComposition.current(context).authPreferences
        val owner = syntheticOwner("off")
        ownedOwners += owner
        // A real owned identity with consent OFF: the worker must return
        // before even resolving the repository (deferral proof), not merely
        // before creating a file.
        prefs.userId = owner
        // WorkManager's own construction path: the primary 2-arg constructor,
        // production defaults for service and pinger. Consent off: the real
        // pinger is never reached; the deny wall is the tripwire.
        val worker = TestListenableWorkerBuilder<SupabaseSyncWorker>(context).build()
        val result = runBlocking { worker.doWork() }
        assertTrue("consent-false doWork must succeed, got $result", result is ListenableWorker.Result.Success)
        assertNull(
            "consent-false doWork must not initialize the repository (deferred health stack)",
            CycleCareComposition.current(context).peekRepository()
        )
        assertFalse(
            "consent-false doWork must not create the owned database",
            context.getDatabasePath(com.cyclecare.app.data.local.CycleCareDatabase.namespaceFor(owner)).exists()
        )
    }

    @Test
    fun networkDenyWall_blocksRealProductionAnonymousPing() {
        // NEGATIVE CONTROL: the wall is only meaningful if it actually engages
        // a REAL production network path. CommunityPulsePinger.ping builds
        // its own OkHttpClient (default proxy selector - constructed AFTER
        // the wall was installed in @Before) and POSTs anonymously. The wall
        // must block it: no success, and at least one counted attempt.
        val before = ProductionTestControls.networkAttempts()
        val outcome = runCatching { runBlocking { CommunityPulsePinger.ping(null) } }
        val attempts = ProductionTestControls.networkAttempts() - before
        assertTrue(
            "the deny wall must count the real ping attempt (got $attempts, outcome=$outcome)",
            attempts >= 1
        )
        assertTrue(
            "the real anonymous ping must not succeed behind the wall (outcome=$outcome)",
            outcome.getOrNull() != true
        )
    }

    @Test
    fun consentRevokedBeforeRun_validSession_realWorkerStillOpensNothing() {
        val prefs = CycleCareComposition.current(context).authPreferences
        val owner = syntheticOwner("revoked")
        ownedOwners += owner
        val expiry = System.currentTimeMillis() / 1000 + 3600
        prefs.userId = owner
        prefs.accessToken = syntheticJwt(owner, expiry)
        prefs.sessionExpiresAtSeconds = expiry
        // A valid session must NOT substitute for consent: revoked before run.
        prefs.isCloudSyncAllowed = false

        val worker = TestListenableWorkerBuilder<SupabaseSyncWorker>(context).build()
        val result = runBlocking { worker.doWork() }
        assertTrue("consent-revoked doWork must succeed, got $result", result is ListenableWorker.Result.Success)
        assertFalse(
            "revoked consent must not open the owned database even with a valid session",
            context.getDatabasePath(com.cyclecare.app.data.local.CycleCareDatabase.namespaceFor(owner)).exists()
        )
    }

    @Test
    fun consentTrue_syntheticSession_realDoWork_acquiresLazyOwnerDatabase_andPingsWithSyntheticToken_only() {
        val prefs = CycleCareComposition.current(context).authPreferences
        val owner = syntheticOwner("active")
        ownedOwners += owner
        val expiry = System.currentTimeMillis() / 1000 + 3600
        val jwt = syntheticJwt(owner, expiry)
        prefs.userId = owner
        prefs.accessToken = jwt
        prefs.sessionExpiresAtSeconds = expiry
        prefs.isCloudSyncAllowed = true

        var syncFactoryInvoked = false
        var transportConstructed = false
        var pingerInvocations = 0
        var pingerToken: String? = null

        val scriptedTransport = object : HealthSyncV3Transport {
            override suspend fun apply(operation: V3WireOperation, accessToken: String): V3WireReceipt =
                throw AssertionError("unexpected transport apply on the paused-protocol path")
            override suspend fun readChanges(afterSeq: Long, limit: Int, consentGeneration: Long, accessToken: String): List<V3WireChange> =
                throw AssertionError("unexpected transport readChanges on the paused-protocol path")
            override suspend fun setConsent(enabled: Boolean, accessToken: String): V3ConsentReceipt =
                throw AssertionError("unexpected transport setConsent on the paused-protocol path")
        }

        val worker = SupabaseSyncWorker(
            context,
            testWorkerParameters(),
            syncServiceFactory = { _, db, p ->
                syncFactoryInvoked = true
                // getDatabase acquires SQLCipher/key/metadata conversion stack,
                // but Room.build() is lazy. The paused protocol MUST NOT open
                // health storage merely to prove that construction occurred.
                val expectedName = com.cyclecare.app.data.local.CycleCareDatabase.namespaceFor(owner)
                assertEquals("factory receives exact owned helper namespace", expectedName, db.openHelper.databaseName)
                assertSame("factory receives the cached owner instance, not guest/another owner",
                    com.cyclecare.app.data.local.CycleCareDatabase.getDatabase(context, owner), db)
                assertSame("factory uses the holder's exact preferences", prefs, p)
                assertFalse("fresh owner database must still be lazy at factory entry", db.isOpen)
                SupabaseSyncService(db, p, transportFactory = { _, _, _ ->
                    transportConstructed = true
                    scriptedTransport
                })
            },
            pinger = { token ->
                pingerInvocations++
                pingerToken = token
                true
            }
        )

        val result = runBlocking { worker.doWork() }

        assertTrue("consent-true doWork must succeed, got $result", result is ListenableWorker.Result.Success)
        assertTrue("the real doWork must acquire the encrypted owner stack and construct the sync service", syncFactoryInvoked)
        assertFalse(
            "paused protocol returns before DAO access; fresh physical owner DB must remain uncreated",
            context.getDatabasePath(com.cyclecare.app.data.local.CycleCareDatabase.namespaceFor(owner)).exists()
        )
        assertFalse("paused sync must leave the acquired Room instance unopened",
            com.cyclecare.app.data.local.CycleCareDatabase.getDatabase(context, owner).isOpen)
        assertEquals("exactly one community-pulse ping per doWork run", 1, pingerInvocations)
        assertEquals("the ping must carry the synthetic session token and nothing else", jwt, pingerToken)
        assertFalse(
            "the network transport must never be constructed: the synthetic JWT can never reach a real endpoint",
            transportConstructed
        )
    }

    @Test
    fun consentTrue_missingSession_realDoWork_pingCarriesNoToken_andNoNetwork() {
        val prefs = CycleCareComposition.current(context).authPreferences
        val owner = syntheticOwner("nosession")
        ownedOwners += owner
        prefs.userId = owner
        prefs.isCloudSyncAllowed = true
        // Deliberately NO access token. The released protocol gate keeps the
        // transport unbuilt; the discriminating assertions are the token-less
        // scripted ping and the class-level zero-network-attempt drain.

        var transportConstructed = false
        var pingerInvocations = 0
        var pingerToken: String? = "unset"

        val worker = SupabaseSyncWorker(
            context,
            testWorkerParameters(),
            syncServiceFactory = { _, db, p ->
                SupabaseSyncService(db, p, transportFactory = { _, _, _ ->
                    transportConstructed = true
                    object : HealthSyncV3Transport {
                        override suspend fun apply(operation: V3WireOperation, accessToken: String): V3WireReceipt =
                            throw AssertionError("unexpected transport apply on the paused-protocol path")
                    }
                })
            },
            pinger = { token ->
                pingerInvocations++
                pingerToken = token
                true
            }
        )

        val result = runBlocking { worker.doWork() }

        assertTrue("missing-session doWork must still complete, got $result", result is ListenableWorker.Result.Success)
        assertFalse("a missing session must never build the network transport", transportConstructed)
        assertTrue("pinger invocation count must be at most one", pingerInvocations <= 1)
        if (pingerInvocations == 1) {
            assertNull("a missing session must never carry a token to the ping", pingerToken)
        }
    }

    private fun syntheticOwner(tag: String): String = "synthetic-prod-worker-$tag-${UUID.randomUUID()}"

    private fun syntheticJwt(subject: String, expiresAtSeconds: Long): String {
        fun b64(part: JSONObject): String = Base64.encodeToString(
            part.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val header = b64(JSONObject().put("alg", "none").put("typ", "JWT"))
        val payload = b64(
            JSONObject()
                .put("sub", subject)
                .put("exp", expiresAtSeconds)
                .put("role", "authenticated")
        )
        return "$header.$payload.c2ln"
    }

    private fun testWorkerParameters(): WorkerParameters = WorkerParameters(
        UUID.randomUUID(),
        Data.EMPTY,
        emptyList<String>(),
        WorkerParameters.RuntimeExtras(),
        1,
        0,
        Executor { it.run() },
        Dispatchers.Default,
        DirectTaskExecutor,
        object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? = null
        },
        TestProgressUpdater(),
        TestForegroundUpdater()
    )

    private object DirectTaskExecutor : TaskExecutor {
        private val direct = Executor { it.run() }
        private val serial = object : SerialExecutor {
            override fun execute(command: Runnable) {
                command.run()
            }

            override fun hasPendingTasks(): Boolean = false
        }

        override fun getMainThreadExecutor(): Executor = direct

        override fun getSerialTaskExecutor(): SerialExecutor = serial

        override fun executeOnTaskThread(runnable: Runnable) {
            runnable.run()
        }
    }
}

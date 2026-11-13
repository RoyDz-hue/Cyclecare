package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.rust.RustBoundRoomHost
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.repository.RustPortInstallResolver
import com.cyclecare.app.data.repository.RustPortInstaller
import com.cyclecare.app.data.supabase.SupabaseAuthService
import com.cyclecare.app.data.supabase.SupabaseSyncService
import com.cyclecare.app.security.AuthPreferences
import com.cyclecare.app.security.IdentityPublicationAdmission
import com.cyclecare.app.ui.viewmodel.CycleCareViewModel
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage-D first-install UI freshness witness (reviewer item: an install that
 * does NOT change owner/session must still invalidate already-loaded legacy
 * UI state; and the asynchronous-baseline race must be closed). The
 * ViewModel stamps repository.currentRustPortRevision() SYNCHRONOUSLY at
 * construction, then tracks rustPortRevisions; a bump forces the same
 * invalidation the admitted replacement path performs (activeUiSession =
 * Long.MIN_VALUE -> resetIdentityUiIfNeeded). Assertions inspect REAL state
 * (uiSessionForTests restored to the preferences session = the reset body
 * actually ran), not only callback counts. Tier: host observer-wiring
 * evidence (Robolectric; no native Rust claims - the invalidation trigger
 * and state are witnessed, not rust-backed content).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StageDFirstInstallUiFreshnessTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val openDbs = mutableListOf<CycleCareDatabase>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "stage-d-freshness-it").apply { isDaemon = true } }

    @After fun cleanup() {
        CycleCareViewModel.afterRustRevisionObservationForTests = null
        openDbs.forEach { if (it.isOpen) it.close() }
        openDbs.clear()
        pool.shutdownNow()
        Dispatchers.resetMain()
    }

    private class Harness(val vm: CycleCareViewModel, val db: CycleCareDatabase, val prefs: AuthPreferences, val repo: CycleRepository)

    private fun newPrefs(): AuthPreferences =
        AuthPreferences(
            context,
            context.getSharedPreferences("stage-d-fresh-${UUID.randomUUID()}", Context.MODE_PRIVATE),
            true
        ).apply {
            userId = "owner-a"
            isCloudSyncAllowed = false
            isAiSharingAllowed = false
            accountMode = "ANONYMOUS"
            hasCompletedInitialAuth = true
            attachPublicationAdmission(IdentityPublicationAdmission.forAndroid())
        }

    private fun newDb(): CycleCareDatabase =
        Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java)
            .allowMainThreadQueries()
            .addMigrations(com.cyclecare.app.data.local.rust.RUST_FLOAT_METADATA_DRAFT)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    com.cyclecare.app.data.local.rust.applyRustMetadataDraft(db)
                }
            })
            .build().also(openDbs::add)

    private fun harness(): Harness {
        val prefs = newPrefs(); val db = newDb(); val repo = CycleRepository(db)
        val vm = CycleCareViewModel(repo, prefs, SupabaseSyncService(db, prefs), SupabaseAuthService(prefs))
        return Harness(vm, db, prefs, repo)
    }

    private fun install(h: Harness) = offMain("composition-driven install") {
        RustPortInstaller(h.repo, RustBoundRoomHost(h.prefs),
            RustPortInstallResolver.Resolution.bind(h.prefs, h.prefs.rustOperationSnapshot(), h.db)).install()
    }

    /** Runs the REAL installer off the Robolectric main thread WITHOUT
     * blocking it. The install/admission path legitimately queues MAIN
     * runnables (publication-admission main scheduling), so a plain
     * Future.get(30s) self-deadlocks: the worker waits for main, main
     * waits for the worker (the run-6aeda76f TimeoutException, with main
     * runnables observed queued in the XML). Poll the future under a
     * bounded overall deadline and pump the REAL main looper while
     * pending, so queued main work progresses exactly as it would on a
     * device. On timeout: capture the worker + cyclecare-thread stacks,
     * cancel the attempt, and FAIL - the 30s no-hang budget is NOT
     * extended and the real installer/admission/observer are untouched. */
    private fun <T> offMain(what: String, block: () -> T): T {
        val future = pool.submit(java.util.concurrent.Callable { block() })
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            try {
                return future.get(50, TimeUnit.MILLISECONDS)
            } catch (tick: java.util.concurrent.TimeoutException) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                if (System.currentTimeMillis() >= deadline) {
                    val state = Thread.getAllStackTraces().entries
                        .filter { (t, stack) ->
                            t.name.startsWith("stage-d-freshness-it") ||
                                stack.any { it.className.contains("cyclecare") }
                        }
                        .joinToString("
") { (t, stack) ->
                            "THREAD ${t.name} [${t.state}]
  " +
                                stack.take(12).joinToString("
  ")
                        }.ifEmpty { "(no live worker/cyclecare threads)" }
                    future.cancel(true)
                    org.junit.Assert.fail(
                        "$what did not complete within the bounded 30s " +
                            "(deadline NOT extended). Thread state at timeout:
$state")
                }
            }
        }
    }

    @Test fun compositionDrivenInstallInvalidatesAlreadyLoadedUiSession() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val observations = AtomicInteger(0)
        val initialized = CountDownLatch(1)
        val invalidated = CountDownLatch(1)
        CycleCareViewModel.afterRustRevisionObservationForTests = { _, _, didInvalidate ->
            if (observations.incrementAndGet() == 1) initialized.countDown()
            if (didInvalidate) invalidated.countDown()
        }
        val h = harness()
        // The observer's first emission matches the construction-time stamp:
        // an already-running ViewModel with legacy-loaded state sees NO
        // invalidation before any install happens.
        assertTrue("observer initialized", initialized.await(15, TimeUnit.SECONDS))
        assertEquals("no invalidation from the initial emission", 1L, invalidated.count)
        // Composition-driven first install (the MainActivity path): owner and
        // session do NOT change, but the read source moves.
        assertEquals(RustPortInstaller.Outcome.Installed(1L), install(h))
        assertTrue("revision bump invalidated the loaded UI session", invalidated.await(15, TimeUnit.SECONDS))
        assertEquals("exactly two observations (baseline + bump)", 2, observations.get())
        // The forced reset re-routes through the admission's main scheduling;
        // drain it before asserting state (the callback fires when the
        // invalidation is QUEUED, the reset itself may run a tick later).
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        h.prefs.publicationAdmission!!.awaitIdle().join()
        // REAL state: the reset body actually ran (session restored to the
        // preferences value), it did not early-return at MIN_VALUE.
        assertEquals("reset restored the live session", h.prefs.sessionGeneration, h.vm.uiSessionForTests)
        assertEquals("owner untouched by a first install", "owner-a", h.vm.uiOwnerForTests)
        assertNotNull(h.repo.currentRustPortForOnboarding())
    }

    @Test fun installLandingBeforeCollectorFirstEmissionStillInvalidates() = runBlocking {
        // DETERMINISTIC paused-collector witness for the reviewer's race:
        // with a StandardTestDispatcher main, the ViewModel's collector has
        // NOT started when the install lands. The synchronous construction
        // stamp (0) must make the first collected emission (1) invalidate;
        // an asynchronous -1 baseline would have swallowed it.
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val observations = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, Boolean>>())
        CycleCareViewModel.afterRustRevisionObservationForTests = { _, revision, didInvalidate ->
            observations += revision to didInvalidate
        }
        val h = harness()
        assertEquals(RustPortInstaller.Outcome.Installed(1L), install(h))
        assertTrue("collector has not run yet", observations.isEmpty())
        main.scheduler.advanceUntilIdle() // collector + posted reset drain
        main.scheduler.advanceUntilIdle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        h.prefs.publicationAdmission!!.awaitIdle().join()
        assertEquals("first emission post-install invalidates", listOf(1L to true), observations)
        assertEquals("reset restored the live session", h.prefs.sessionGeneration, h.vm.uiSessionForTests)
    }

    @Test fun viewModelCreatedAfterInstallSeesNoSpuriousInvalidation() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val observations = AtomicInteger(0)
        CycleCareViewModel.afterRustRevisionObservationForTests = { _, _, _ -> observations.incrementAndGet() }
        // Install BEFORE the ViewModel exists (late load): construction
        // stamps the installed revision, so the first emission is a
        // no-invalidation baseline - the VM reads the installed source
        // from the start.
        val prefs = newPrefs(); val db = newDb(); val repo = CycleRepository(db)
        val outcome = offMain("install before first collector emission") {
            RustPortInstaller(repo, RustBoundRoomHost(prefs),
                RustPortInstallResolver.Resolution.bind(prefs, prefs.rustOperationSnapshot(), db)).install()
        }
        assertEquals(RustPortInstaller.Outcome.Installed(1L), outcome)
        val vm = CycleCareViewModel(repo, prefs, SupabaseSyncService(db, prefs), SupabaseAuthService(prefs))
        assertNotNull(vm)
        delay(400)
        assertEquals("exactly one non-invalidating baseline observation", 1, observations.get())
        assertEquals("session is the live one from the start", prefs.sessionGeneration, vm.uiSessionForTests)
    }
}

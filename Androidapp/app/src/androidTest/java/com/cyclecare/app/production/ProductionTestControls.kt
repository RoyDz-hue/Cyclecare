package com.cyclecare.app.production

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.cyclecare.app.data.economy.CommunityPulsePinger
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.repository.CycleCareComposition
import com.cyclecare.app.security.AuthPreferences
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared fail-closed controls for the stage-G production acceptance tests.
 * Every control FAILS LOUDLY; nothing here swallows a missing precondition.
 */
internal object ProductionTestControls {

    /** The worker's production unique-work name (SupabaseSyncWorker.WORK_NAME). */
    private const val DAILY_SYNC_WORK_NAME = "cyclecare_daily_supabase_sync"

    // ---- Network deny: bounded claim, actual audited routes only. ----
    // OkHttp clients built without an explicit proxy selector resolve through
    // ProxySelector.getDefault() AT CONSTRUCTION. Audit (main Kotlin +
    // core-ffi Rust): no explicit proxy / NO_PROXY / custom selector / raw
    // socket / HttpURLConnection anywhere; the only WebSocket
    // (WalletRealtimeObserver) requires a non-local valid session; the
    // AppLock WebView loads app assets only. Every HTTP client on the
    // executed routes is therefore covered when it is constructed AFTER
    // installation (asserted via assertNetworkDenyInstalled at the points
    // that require it, NOT merely claimed in prose). This is NOT an
    // OS-level egress wall: native code, WebViews, or a client built around
    // the installation could bypass it. The negative controls in
    // ProductionWorkerSyntheticConsentInstrumentedTest therefore ALSO pin
    // the production caller's destination to loopback-only, so no real
    // destination can be constructed even under a selector bypass; the
    // counted attempts here are the owned pre-connect observation that the
    // wall engages the real path, and are CONSUMED by those tests
    // (consumeDeniedAttempts) so the class drain still asserts zero
    // UNEXPLAINED attempts with no class-wide waiver.

    /** Every denied attempt's URI, in order: attribution for the scoped
     * consumption protocol (consumeDeniedAttempts) and for the drain's
     * failure messages. Baseline is EMPTY at install; anything left at
     * uninstall is an unexplained attempt and fails the class.
     *
     * OBSERVATION SCOPE (honest bound): OkHttp 4.x passes Address.url to
     * ProxySelector.select - the ORIGIN only (scheme://host:port/), never
     * the request path or headers. These records therefore attribute
     * attempts by ORIGIN; path/header claims come from the production call
     * site (the pinger builds exactly one fixed-path request from the
     * overridden base), never from the selector. The two negative controls
     * use DISTINCT loopback origins, so origin-level attribution is exact
     * and per-control. */
    // ONE lock guards the producer (selector append), the consumer
    // (consumeDeniedAttempts check-and-remove), install and drain alike, so
    // a same-origin concurrent record can never slip between a consumer's
    // snapshot and its removal and be silently consumed or lost. A plain
    // list under the single lock replaces the earlier split
    // AtomicInteger + CopyOnWriteArrayList, whose snapshot/removeAll was not
    // atomic against the producer.
    private val recordLock = Any()
    private val attemptRecords = ArrayList<String>()
    private var previousSelector: ProxySelector? = null

    /** Thrown pre-connect by the selector while hard-throw mode is active. */
    class DeniedNetworkException(uri: String) : RuntimeException("network denied by stage-G wall: $uri")

    /** Hard-throw mode (negative controls only, serial + unsharded): the
     * selector records the attempt and throws BEFORE any connection is
     * constructed - denial is proven by the throw itself, with no reliance
     * on an unowned sink port. Off elsewhere: the loopback-proxy routing
     * mode stays safe for Activity/screen routes (no process-risk from an
     * unchecked throw on an unaudited background thread). */
    @Volatile private var hardThrowActive = false

    private val denyingSelector = object : ProxySelector() {
        override fun select(uri: URI?): MutableList<Proxy> {
            val target = uri?.toString() ?: "<null>"
            synchronized(recordLock) { attemptRecords += target }
            Log.w("StageGNetworkDeny", "denied: $target")
            if (hardThrowActive) throw DeniedNetworkException(target)
            return mutableListOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 1)))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    fun installNetworkDeny() {
        check(previousSelector == null) { "network deny already installed" }
        previousSelector = ProxySelector.getDefault()
        synchronized(recordLock) { attemptRecords.clear() }
        hardThrowActive = false
        ProxySelector.setDefault(denyingSelector)
    }

    fun networkAttempts(): Int = synchronized(recordLock) { attemptRecords.size }

    /** Guarded scoped lease for the REAL pinger's destination, paired with
     * pre-connect hard-throw: requires the initial (unset) state, validates
     * the target is LOOPBACK-ONLY, and must be finally-restored via
     * [restoreNegativeControl]. SERIAL + UNSHARDED: the checks fail closed
     * if two negative controls ever overlap in a process. */
    fun installNegativeControl(pingerUrl: String) {
        check(CommunityPulsePinger.endpointBaseOverrideForTests == null) {
            "pinger endpoint override already set - negative controls must run serially (unsharded)"
        }
        check(!hardThrowActive) { "hard-throw already active - negative controls must run serially (unsharded)" }
        val parsed = java.net.URI(pingerUrl)
        val host = parsed.host ?: ""
        check(host.startsWith("127.") || host == "::1" || host == "localhost") {
            "pinger override must be LOOPBACK-only (127.0.0.0/8, ::1 or localhost), got $pingerUrl"
        }
        CommunityPulsePinger.endpointBaseOverrideForTests = pingerUrl
        hardThrowActive = true
    }

    /** Restores the defaults captured by [installNegativeControl]; safe to
     * call repeatedly (cleanup paths run it unconditionally). */
    fun restoreNegativeControl() {
        hardThrowActive = false
        CommunityPulsePinger.endpointBaseOverrideForTests = null
    }

    /** Init-timing ASSERTION (not prose): clients on the audited routes
     * resolve the default selector AT CONSTRUCTION, so tests assert the
     * wall is the installed default BEFORE any synthetic token/session
     * exists and before any Activity or client construction. */
    fun assertNetworkDenyInstalled() {
        check(ProxySelector.getDefault() === denyingSelector) {
            "network deny wall not installed at a point that requires it"
        }
    }

    /** Scoped consumption for deliberate wall-engagement tests, with full
     * attribution: ANY recorded attempt outside [expectedUriPrefix] fails
     * immediately (unrelated traffic can never hide inside a count); the
     * matching records must number within [expectedCount] and ONLY they are
     * consumed; anything left afterwards is still caught by the class
     * drain. No class-wide waiver, no bulk reset. */
    fun consumeDeniedAttempts(expectedCount: IntRange, expectedUriPrefix: String, description: String): Int =
        // The check-and-remove runs as ONE atomic observation: a concurrent
        // record can never slip between the snapshot and the removal and be
        // silently consumed.
        synchronized(recordLock) {
            val records = attemptRecords.toList()
            val matching = records.filter { it.startsWith(expectedUriPrefix) }
            val others = records.filter { !it.startsWith(expectedUriPrefix) }
            if (others.isNotEmpty()) {
                throw AssertionError("$description: UNEXPECTED network attempt(s) recorded: $others")
            }
            if (matching.size !in expectedCount) {
                throw AssertionError(
                    "$description: expected ${expectedCount.first}..${expectedCount.last} denied attempt(s) " +
                        "for origin '$expectedUriPrefix', saw ${matching.size}: $matching"
                )
            }
            attemptRecords.removeAll(matching.toSet())
            matching.size
        }

    /** Restores the prior selector and returns the URIs of every attempt
     * recorded but never consumed - the class drain asserts this is EMPTY
     * and prints the leftovers on failure. */
    fun uninstallNetworkDenyAndDrainAttempts(): List<String> {
        val prior = previousSelector
        check(prior != null) { "network deny not installed" }
        ProxySelector.setDefault(prior)
        previousSelector = null
        hardThrowActive = false
        return synchronized(recordLock) {
            val leftovers = attemptRecords.toList()
            attemptRecords.clear()
            leftovers
        }
    }

    // ---- WorkManager barrier: no real worker may run with test state. ----
    // clearPackageData=false on the device runner means pending work and prefs
    // survive process restarts. Before ANY synthetic consent/token exists and
    // after every test, the production periodic work must be CONFIRMED absent:
    // otherwise a queued real worker could fire the REAL community pinger with
    // a synthetic JWT. Bounded await, terminal-state assertion, fail closed.
    fun cancelAndConfirmNoUnfinishedSyncWork(context: Context, timeoutMs: Long = 15_000) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(DAILY_SYNC_WORK_NAME).result.get(timeoutMs, TimeUnit.MILLISECONDS)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val infos = wm.getWorkInfosForUniqueWork(DAILY_SYNC_WORK_NAME).get(timeoutMs, TimeUnit.MILLISECONDS)
            if (infos.all { it.state.isFinished }) return
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("unfinished sync work survives cancel: " + infos.joinToString { "${it.id}:${it.state}" })
            }
            Thread.sleep(200)
        }
    }

    // ---- Owned-identity reset: DEFAULTS, not restoration of prior values. ----
    fun resetOwnedIdentity(prefs: AuthPreferences, owner: String) {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "owned reset must await admission off main"
        }
        val gate = checkNotNull(prefs.publicationAdmission)
        // One admitted operation: no listener observes the intermediate owned
        // identity as a fresh login. Setters inside transition run inline.
        gate.transitionIo {
            prefs.userId = owner
            prefs.isCloudSyncAllowed = false
            check(prefs.saveServerHealthConsentReceipt(owner, prefs.sessionGeneration,
                prefs.healthConsentGeneration, 1L, false)) { "owned receipt reset failed" }
            prefs.accessToken = null
            prefs.refreshToken = null
            prefs.sessionExpiresAtSeconds = 0L
            prefs.isLocalOnlyMode = false
            // Checked identity commit flushes preceding apply() writes too.
            prefs.userId = null
        }.get(15, TimeUnit.SECONDS)
        gate.awaitIdle().get(15, TimeUnit.SECONDS)
        assertCleanBaseline(prefs)
        check(prefs.sessionExpiresAtSeconds == 0L) { "owned expiry must be clear" }
        android.util.Log.i("StageGCleanup", "owned identity reset and admission drain complete; defaults verified")
    }

    fun assertCleanBaseline(prefs: AuthPreferences) {
        check(prefs.userId == null) { "baseline: userId must be clear" }
        check(prefs.accessToken == null) { "baseline: accessToken must be clear" }
        check(prefs.refreshToken == null) { "baseline: refreshToken must be clear" }
        check(!prefs.isCloudSyncAllowed) { "baseline: cloud consent must be off" }
        check(!prefs.isLocalOnlyMode) { "baseline: local-only mode must be off" }
    }

    // ---- Owned database disposal: exact scope, existing handles only. ----
    // NEVER opens or creates a database: closeAndEvictCachedOwnerInstance
    // only closes an already-cached handle. Deterministic quiescence FIRST:
    // CycleCareComposition.resetForTestsAndAwait closes the transition watch
    // (no new recovery requests) and cancels AND JOINS any in-flight
    // recovery - a plain cancel returns while blocking transition/resolver/
    // SQL work is still unwinding, and a live recovery could reopen or
    // re-cache the owned handle after the deletion checks. The barrier
    // fails closed if the owned job does not finish within its bound.
    fun deleteOwnedDatabase(context: Context, owner: String) {
        val name = CycleCareDatabase.namespaceFor(owner)
        check(runBlocking { CycleCareComposition.resetForTestsAndAwait() }) {
            "holder recovery did not quiesce within the bound before deleting $name"
        }
        CycleCareDatabase.closeAndEvictCachedOwnerInstance(owner)
        val file = context.getDatabasePath(name)
        if (file.exists()) {
            check(context.deleteDatabase(name)) { "deleteDatabase($name) returned false" }
        }
        // The main file, every Room sidecar, and DatabaseEncryption's
        // conversion temp (.encrypted.tmp) / plaintext backup
        // (.plaintext.backup) must ALL be gone: retry deletes inside a
        // bounded await, then fail loudly on ANY survivor.
        val artifacts = listOf("-wal", "-shm", "-journal", ".encrypted.tmp", ".plaintext.backup")
            .map { File(file.absolutePath + it) }
        awaitUntil(5_000, "all owned database artifacts absent for $owner", stepMs = 200) {
            if (file.exists()) context.deleteDatabase(name)
            artifacts.forEach { candidate -> if (candidate.exists()) candidate.delete() }
            if (!file.exists() && artifacts.none { it.exists() }) Unit else null
        }
    }

    // ---- Bounded discriminating predicate: no single-shot flow races. ----
    // Suspending probes get their OWN inner timeout: a flow that never emits
    // must not park the poll past its budget.
    fun <T> awaitUntilSuspending(timeoutMs: Long, description: String, stepMs: Long = 250, probe: suspend () -> T?): T =
        awaitUntil(timeoutMs, description, stepMs) {
            runBlocking { withTimeoutOrNull(2_000) { probe() } }
        }

    fun <T> awaitUntil(timeoutMs: Long, description: String, stepMs: Long = 250, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            Thread.sleep(stepMs)
        }
        throw AssertionError("timed out (${timeoutMs}ms) awaiting: $description")
    }

    // ---- Screenshot evidence: hard fail on any missing/empty artifact. ----
    fun saveScreenshot(context: Context, name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val shot = instrumentation.uiAutomation.takeScreenshot()
            ?: throw AssertionError("uiAutomation screenshot unavailable for $name")
        val dir = context.getExternalFilesDir(null)
            ?: throw AssertionError("external files dir unavailable for $name")
        val out = File(dir, "$name.png")
        val compressed = out.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
        if (!compressed) throw AssertionError("PNG compress returned false for ${out.absolutePath}")
        if (!out.isFile || out.length() <= 0L) throw AssertionError("screenshot missing or empty: ${out.absolutePath}")
        Log.i("StageGEvidence", "screenshot saved: ${out.absolutePath} (${out.length()} bytes)")
        return out
    }
}

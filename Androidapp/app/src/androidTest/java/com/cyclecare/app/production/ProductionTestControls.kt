package com.cyclecare.app.production

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.security.AuthPreferences
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared fail-closed controls for the stage-G production acceptance tests.
 * Every control FAILS LOUDLY; nothing here swallows a missing precondition.
 */
internal object ProductionTestControls {

    /** The worker's production unique-work name (SupabaseSyncWorker.WORK_NAME). */
    private const val DAILY_SYNC_WORK_NAME = "cyclecare_daily_supabase_sync"

    // ---- Network deny: no route under test may touch a real endpoint. ----
    // OkHttp clients built without an explicit proxy selector resolve through
    // ProxySelector.getDefault(); every production client in scope (sync
    // transport, session refresh, community pinger, economy/pricing public
    // calls) is built that way, so this selector is a hard egress wall for
    // the whole process. ANY attempted connection (token-bearing or
    // anonymous) is counted and routed to a dead local address.
    private val networkAttempts = AtomicInteger(0)
    private var previousSelector: ProxySelector? = null

    private val denyingSelector = object : ProxySelector() {
        override fun select(uri: URI?): MutableList<Proxy> {
            networkAttempts.incrementAndGet()
            return mutableListOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 1)))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    fun installNetworkDeny() {
        check(previousSelector == null) { "network deny already installed" }
        previousSelector = ProxySelector.getDefault()
        networkAttempts.set(0)
        ProxySelector.setDefault(denyingSelector)
    }

    /** Restores the prior selector and returns the attempts seen while installed. */
    fun uninstallNetworkDenyAndDrainAttempts(): Int {
        val prior = previousSelector
        check(prior != null) { "network deny not installed" }
        ProxySelector.setDefault(prior)
        previousSelector = null
        return networkAttempts.get()
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

    // ---- Owned database disposal: exact scope, closed handles first. ----
    fun deleteOwnedDatabase(context: Context, owner: String) {
        val name = CycleCareDatabase.namespaceFor(owner)
        runCatching { CycleCareDatabase.getDatabase(context, owner).close() }
        val file = context.getDatabasePath(name)
        if (file.exists()) {
            check(context.deleteDatabase(name)) { "deleteDatabase($name) returned false" }
        }
        // Room may leave WAL/SHM sidecars if a handle was open at delete time.
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val sidecar = File(file.absolutePath + suffix)
            if (sidecar.exists()) check(sidecar.delete()) { "failed to delete $sidecar" }
        }
        check(!file.exists()) { "owned database file survives deletion: $file" }
    }

    // ---- Bounded discriminating predicate: no single-shot flow races. ----
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

package com.cyclecare.app.data.supabase

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cyclecare.app.data.economy.CommunityPulsePinger
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.OwnerDataGate
import com.cyclecare.app.security.AuthPreferences
import java.util.concurrent.TimeUnit

class SupabaseSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    /** Test-only seam: the PRODUCTION path is the 2-arg primary constructor
     * WorkManager's default WorkerFactory instantiates by reflection; that
     * path is unchanged and always uses the production defaults below. This
     * internal secondary constructor exists only so instrumented tests can
     * run the REAL doWork body against scripted sync/ping implementations
     * (no global hooks, no state shared with production instances). */
    internal constructor(
        appContext: Context,
        params: WorkerParameters,
        syncServiceFactory: (Context, CycleCareDatabase, AuthPreferences) -> SupabaseSyncService,
        pinger: suspend (String?) -> Boolean
    ) : this(appContext, params) {
        this.syncServiceFactoryOverride = syncServiceFactory
        this.pingerOverride = pinger
    }

    private var syncServiceFactoryOverride: ((Context, CycleCareDatabase, AuthPreferences) -> SupabaseSyncService)? = null
    private var pingerOverride: (suspend (String?) -> Boolean)? = null

    override suspend fun doWork(): Result {
        return try {
            // Consent is checked before opening the encrypted DB or constructing
            // a network service. Revocation therefore cannot enqueue a transfer.
            val authPreferences = AuthPreferences(applicationContext)
            if (!authPreferences.isCloudSyncAllowed) return Result.success()
            val ownerAtStart = authPreferences.userId
            if (OwnerDataGate.isWiped(applicationContext, ownerAtStart)) return Result.success()
            val database = CycleCareDatabase.getDatabase(applicationContext, ownerAtStart)
            val syncService = syncServiceFactoryOverride?.invoke(applicationContext, database, authPreferences) ?: SupabaseSyncService(database, authPreferences)
            // Share the authoritative routing: bind the process repository
            // when the composition already published it. Fresh service each
            // run, so the binding never goes stale across runs; when no
            // composition exists yet (cold worker start), the service stays
            // unbound and keeps original unprotected legacy behavior.
            com.cyclecare.app.data.repository.CycleRepository.processRepository.get()?.let { syncService.bindSyncApplyRepository(it) }

            val result = syncService.syncAll()

            // One community-pulse ping per daily sync. The server enforces the
            // kill switch, lease, cooldowns and budgets; failure never fails sync.
            try {
                val token = SupabaseSessionManager.validAccessToken(authPreferences)
                (pingerOverride ?: { t -> CommunityPulsePinger.ping(t) }).invoke(token)
            } catch (_: Exception) { }

            when {
                result.isSuccess -> Result.success()
                result.shouldRetry -> Result.retry()
                else -> {
                    // A later periodic run or a fresh sign-in can retry; do not spin on 401/4xx.
                    Result.success()
                }
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "cyclecare_daily_supabase_sync"

        fun scheduleDailySync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val dailySyncRequest = PeriodicWorkRequestBuilder<SupabaseSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                dailySyncRequest
            )
        }

        /** Call on consent revocation/account change; this is intentionally idempotent. */
        fun cancelSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

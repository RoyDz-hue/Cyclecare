package com.example.data.supabase

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.local.CycleCareDatabase
import com.example.data.local.OwnerDataGate
import com.example.security.AuthPreferences
import java.util.concurrent.TimeUnit

class SupabaseSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            // Consent is checked before opening the encrypted DB or constructing
            // a network service. Revocation therefore cannot enqueue a transfer.
            val authPreferences = AuthPreferences(applicationContext)
            if (!authPreferences.isCloudSyncAllowed) return Result.success()
            val ownerAtStart = authPreferences.userId
            if (OwnerDataGate.isWiped(applicationContext, ownerAtStart)) return Result.success()
            val database = CycleCareDatabase.getDatabase(applicationContext, ownerAtStart)
            val syncService = SupabaseSyncService(database, authPreferences)

            val result = syncService.syncAll()
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

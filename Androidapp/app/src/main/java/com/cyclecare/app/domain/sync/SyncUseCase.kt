package com.cyclecare.app.domain.sync

import com.cyclecare.app.data.supabase.SupabaseSyncService
import com.cyclecare.app.data.supabase.SyncResult
import com.cyclecare.app.security.AuthPreferences

class SyncUseCase(
    private val syncService: SupabaseSyncService,
    private val authPreferences: AuthPreferences
) {
    suspend fun executeSync(): SyncResult {
        return syncService.syncAll()
    }

    fun isCloudSyncAllowed(): Boolean {
        return authPreferences.isCloudSyncAllowed
    }

    fun setCloudSyncAllowed(allowed: Boolean) {
        authPreferences.isCloudSyncAllowed = allowed
    }
}

package com.example.domain.sync

import com.example.data.supabase.SupabaseSyncService
import com.example.data.supabase.SyncResult
import com.example.security.AuthPreferences

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

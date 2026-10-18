package com.example.data.local

import android.content.Context
import com.example.security.AuthPreferences

/** Resolves the database at call time, so account switches cannot reuse a prior owner's handle. */
class OwnerScopedDatabaseProvider(
    context: Context,
    private val authPreferences: AuthPreferences
) {
    private val appContext = context.applicationContext

    fun current(): CycleCareDatabase =
        CycleCareDatabase.getDatabase(appContext, authPreferences.userId)

    fun forIdentity(identity: String?): CycleCareDatabase =
        CycleCareDatabase.getDatabase(appContext, identity)
}

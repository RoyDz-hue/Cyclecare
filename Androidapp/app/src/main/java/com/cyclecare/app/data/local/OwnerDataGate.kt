package com.cyclecare.app.data.local

import android.content.Context

/** Durable lifecycle gate: a wipe cannot be undone by a later pull or stale worker. */
object OwnerDataGate {
    private const val PREFS = "cyclecare_owner_data_lifecycle_v1"
    private fun key(identity: String?): String =
        "wiped_" + (identity?.takeIf { it.isNotBlank() }?.let(CycleCareDatabase::namespaceFor) ?: "legacy_guest")

    fun isWiped(context: Context, identity: String?): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key(identity), false)

    fun markWiped(context: Context, identity: String?) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key(identity), true).commit()
    }

    /** Only an explicit user action may resume a wiped namespace. */
    fun resumeAfterExplicitUserAction(context: Context, identity: String?) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(key(identity)).commit()
    }
}

package com.cyclecare.app.data.repository

import android.content.Context
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.security.AuthPreferences

/** Stage-C first-publish resolver: closes the RustPortInstaller RESOLUTION
 * BINDING contract by CONSTRUCTING the (database, owner) pair from one identity
 * read and handing it over as a private-constructor CAPABILITY, instead of
 * letting any caller supply an arbitrary pair.
 *
 * - The owner identity is captured once from rustOperationSnapshot; the
 *   database is resolved through CycleCareDatabase.getDatabase namespacing,
 *   which returns exactly the preopened namespace owned by that identity.
 *   The resolver never opens, re-keys, migrates or infers a namespace itself:
 *   keys stay with the existing provider (DatabaseKeyManager), untouched.
 * - The slow Room open can finish after an identity move, so the captured
 *   snapshot is re-verified (runIfRustOperationUnchanged) AFTER resolution;
 *   a moved identity yields Stale and the caller re-resolves fresh.
 * - The capability carries the FULL captured snapshot; the installer
 *   revalidates it at the admitted commit, so same-owner session/localOnly/
 *   accountMode moves between resolution and commit abort as StaleIdentity.
 * - One resolution binds one immutable pair: it NEVER rebinds the database to
 *   another identity. An owner move requires a fresh resolver AND a fresh
 *   installer.
 * - Admission is required BEFORE resolution: without it there is no identity
 *   exclusion during seal+install, so resolution fails closed.
 * - Off-main only: Room opening and preference reads never run on main. */
internal class RustPortInstallResolver(
    private val appContext: Context,
    private val preferences: AuthPreferences,
) {
    /** Resolution capability: constructible only through [bind]. Carries the
     * exact preferences instance, the captured snapshot and the preopened
     * database AS ONE binding. [owner] is derived, never supplied. */
    internal class Resolution private constructor(
        internal val preferences: AuthPreferences,
        internal val captured: AuthPreferences.RustOperationSnapshot,
        internal val database: CycleCareDatabase,
    ) {
        val owner: String? get() = captured.operation.owner
        /** FULL snapshot equality (operation + localOnly + accountMode). */
        internal fun stillCurrent(): Boolean =
            preferences.runIfRustOperationUnchanged(captured) { true } == true

        internal companion object {
            /** The production caller is RustPortInstallResolver.resolve(); the
             * witness suite uses this to bind its own preopened fixtures. No
             * other production construction may exist. */
            fun bind(
                preferences: AuthPreferences,
                captured: AuthPreferences.RustOperationSnapshot,
                database: CycleCareDatabase,
            ): Resolution = Resolution(preferences, captured, database)
        }
    }

    internal sealed class Result {
        /** Pair verified against the unchanged captured snapshot. */
        data class Resolved(val resolution: Resolution) : Result()
        /** No publication admission attached: no identity exclusion exists,
         * so no binding may be published. Fail closed. */
        object NoAdmission : Result()
        /** Identity moved while the preopened instance was being resolved. */
        object Stale : Result()
    }

    fun resolve(): Result {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "rust_resolver_on_main_thread" }
        if (preferences.publicationAdmission == null) return Result.NoAdmission
        val captured = preferences.rustOperationSnapshot()
        // Bound by construction: getDatabase namespacing maps exactly this
        // identity to its owned, preopened instance (OWNER_INSTANCES).
        val database = CycleCareDatabase.getDatabase(appContext, captured.operation.owner)
        // The open above may have outlived the identity it was started for.
        return preferences.runIfRustOperationUnchanged(captured) {
            Result.Resolved(Resolution.bind(preferences, captured, database))
        } ?: Result.Stale
    }
}

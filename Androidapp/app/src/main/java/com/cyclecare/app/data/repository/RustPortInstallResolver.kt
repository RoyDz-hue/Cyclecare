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
 *   database INSTANCE is resolved through CycleCareDatabase.getDatabase
 *   namespacing, which maps exactly this identity to its owned namespace.
 *   That instance is LAZY: the resolver itself forces the actual (slow) Room
 *   open - create/upgrade included - via openHelper.writableDatabase, and
 *   only then re-verifies. The resolver never re-keys, migrates or infers a
 *   namespace itself: keys stay with the existing provider
 *   (DatabaseKeyManager), untouched.
 * - The forced open can finish after an identity move, so the captured
 *   snapshot is re-verified (runIfRustOperationUnchanged) AFTER the actual
 *   open; a moved identity yields Stale and the caller re-resolves fresh.
 *   The installer's schema gate then reads the version off the ALREADY open
 *   helper: no second slow open hides between resolution and install.
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
    /** Production default is CycleCareDatabase.getDatabase namespacing. The
     * seam exists so host witnesses can run the REAL resolve() path
     * (snapshot capture, lazy instance, actual open, post-open
     * re-verification) over plain Room fixtures - production SQLCipher is
     * unloadable on the host. */
    private val databaseProvider: (Context, String?) -> CycleCareDatabase =
        { context, owner -> CycleCareDatabase.getDatabase(context, owner) },
) {

    /** Resolution capability: private constructor, minted through [bind].
     * Carries the exact preferences instance, the captured snapshot and the
     * OPENED database AS ONE binding. [owner] is derived, never supplied. */
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
            /** RESTRICTED MINT (enforced by convention, not by the compiler):
             * the ONLY production caller is RustPortInstallResolver.resolve().
             * Kotlin has no package-private, so this internal factory remains
             * callable module-wide; the Stage-C witness suite uses it to bind
             * its own opened fixtures. Any new production call site is a
             * contract violation - audit with:
             *   rg -n 'Resolution.bind' Androidapp/app/src/main */
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
        val database = databaseProvider(appContext, captured.operation.owner)
        // Force the ACTUAL open (create/upgrade included) before binding: the
        // instance returned above is lazy, and a post-resolution check that
        // precedes the slow open would not cover identity movement DURING it.
        database.openHelper.writableDatabase
        // The open above may have outlived the identity it was started for.
        return preferences.runIfRustOperationUnchanged(captured) {
            Result.Resolved(Resolution.bind(preferences, captured, database))
        } ?: Result.Stale
    }
}

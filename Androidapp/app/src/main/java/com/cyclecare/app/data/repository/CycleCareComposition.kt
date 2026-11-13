package com.cyclecare.app.data.repository

import android.content.Context
import com.cyclecare.app.security.AuthPreferences
import com.cyclecare.app.security.IdentityPublicationAdmission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-lifetime composition: the ONE AuthPreferences instance and the ONE
 * CycleRepository every entry point (MainActivity, SupabaseSyncWorker) shares.
 *
 * WHY ONE AUTHORITY (not per-entry construction or a latest-wins reference):
 * - Port tokens are issued to a preferences INSTANCE: the Rust host rejects a
 *   bound writer whose service preferences are not the instance the port was
 *   minted against (isBoundToPreferences). A worker that constructed its own
 *   AuthPreferences and bound the activity's repository therefore failed every
 *   guarded write. Here both entries receive the SAME instance.
 * - A latest-wins reference could leave a cold worker unbound for its whole
 *   run (unprotected legacy path) and an active worker holding a REPLACED
 *   repository after activity recreation (competing repositories). Here the
 *   holder is created ONCE per process by whichever entry runs first and is
 *   never replaced: cold workers bind immediately, active workers keep the
 *   SAME repository, and an install fences in-flight work through that
 *   repository's own seal/drain (trySealLegacyDispatch), which every bound
 *   service's legacy admission passes through.
 * - The publication admission is attached at holder creation, before any
 *   repository or port work exists, so identity exclusion is active no matter
 *   which entry initialized the process. Without it the resolver fails
 *   closed (Result.NoAdmission).
 *
 * CONNECTED CUTOVER: an installed port token is full-snapshot bound
 * (verifyPreferences), so ANY owner/session/localOnly/accountMode move
 * stales it. The holder registers a transition watch at creation that routes
 * every admitted identity transition to RustPortRecovery (owned, coalesced,
 * bounded-retry recovery with observable outcomes) - never
 * presence-as-success, never fire-and-forget. After the bounded rounds the
 * stale port stays selected (safe refusal) and the next transition retries.
 *
 * CONSENT/DEFERRAL: the AuthPreferences production constructor performs its
 * usual MasterKey + EncryptedSharedPreferences work (every entry always did
 * that). The ENCRYPTED HEALTH database stack (SQLCipher load, database key
 * acquisition, ensureEncrypted) is deferred: the repository is created on
 * first actual use, and the transition watch peeks - a prefs-only process
 * (e.g. a consent-disabled cold worker) never initializes the health
 * database just to discover there is no port to recover. */
internal object CycleCareComposition {

    internal class Holder internal constructor(
        private val appContext: Context,
        val authPreferences: AuthPreferences,
    ) {
        private val repositoryLock = Any()
        private var repositoryInstance: CycleRepository? = null

        /** DEFERRED on purpose: repository construction opens the encrypted
         * health database stack. Entries that must establish cloud consent
         * BEFORE any such work (SupabaseSyncWorker) read authPreferences
         * first and touch this property only after consent holds. */
        val repository: CycleRepository
            get() = synchronized(repositoryLock) {
                repositoryInstance ?: (repositoryFactoryForTests?.invoke(appContext, authPreferences)
                    ?: CycleRepository(appContext, authPreferences)).also { repositoryInstance = it }
            }

        /** Null until [repository] was first requested. The transition watch
         * uses this so a never-initialized process performs NO health-DB work
         * on identity transitions (no port can exist without a repository). */
        internal fun peekRepository(): CycleRepository? = synchronized(repositoryLock) { repositoryInstance }

        /** Owned recovery state; created with the holder, its transition
         * lambda reaches the repository only after a peek succeeded. */
        internal var recovery: RustPortRecovery? = null
        internal var transitionWatch: AutoCloseable? = null
        internal val watchLock = Any()
        internal var lastWatchedSnapshot: com.cyclecare.app.security.AuthPreferences.RustOperationSnapshot? = null
    }

    private val lock = Any()
    @Volatile private var holder: Holder? = null

    /** Test-only seams: host witnesses cannot run the production
     * AuthPreferences MasterKey/EncryptedSharedPreferences construction nor
     * the SQLCipher-backed getDatabase, so they substitute construction -
     * the holder, watch, dedup, peek-guard, recovery and transitioner
     * protocol under test remain the production objects. */
    @Volatile internal var authPreferencesFactoryForTests: ((Context) -> AuthPreferences)? = null
    @Volatile internal var repositoryFactoryForTests: ((Context, AuthPreferences) -> CycleRepository)? = null
    /** Fakeable at the lambda level (RustPortTransitioner is final): tests
     * can count passes, force Busy/Failed and trigger internal mutations to
     * prove the watch dedup kills the recovery self-feed. The repository is
     * a DEFERRED provider: eagerly requiring it would initialize the health
     * store from the seam itself and make the prefs-only peek-guard
     * unfalsifiable. The pass runs only after the watch's peek succeeded. */
    @Volatile internal var runTransitionFactoryForTests: ((Context, AuthPreferences, () -> CycleRepository) -> () -> RustPortTransitioner.Outcome)? = null

    /** Process-lifetime scope for transition recovery; a SupervisorJob so one
     * failed transition never cancels later ones. */
    private val transitionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Returns the process-lifetime holder, creating it on first call from
     * ANY entry point. Fast path is a volatile read; creation is locked and
     * the fully constructed holder is published last. */
    fun current(context: Context): Holder {
        holder?.let { return it }
        synchronized(lock) {
            holder?.let { return it }
            val app = context.applicationContext
            val prefs = authPreferencesFactoryForTests?.invoke(app) ?: AuthPreferences(app)
            val admission = IdentityPublicationAdmission.forAndroid()
            prefs.attachPublicationAdmission(admission)
            val created = Holder(app, prefs)
            val runTransition = runTransitionFactoryForTests?.invoke(app, prefs, { created.repository })
                ?: ({ RustPortTransitioner(app, prefs, created.repository).ensureCurrentRouting() })
            created.recovery = RustPortRecovery(transitionScope, runTransition = runTransition)
            // SNAPSHOT-DEDUP: admission transitions notify on EVERY admitted
            // mutation - including the recovery's OWN replace commit, which
            // changes no identity. Without dedup those notifications would
            // re-request recovery and (via pending) defeat its budget: a
            // self-feeding loop under a held commit lease. Only an ACTUAL
            // snapshot change (owner/session/localOnly/accountMode) requests.
            synchronized(created.watchLock) { created.lastWatchedSnapshot = prefs.rustOperationSnapshot() }
            created.transitionWatch = admission.onTransition {
                val snap = prefs.rustOperationSnapshot()
                val changed = synchronized(created.watchLock) {
                    if (snap != created.lastWatchedSnapshot) {
                        created.lastWatchedSnapshot = snap; true
                    } else false
                }
                // Peek, never initialize: no repository means no port can be
                // installed, so there is nothing to recover.
                if (changed && created.peekRepository() != null) created.recovery?.request()
            }
            holder = created
            return created
        }
    }

    /** Test-only process reset; production never replaces the holder.
     * Cancels any in-flight recovery tied to the old holder. */
    internal fun resetForTests() {
        synchronized(lock) {
            holder?.transitionWatch?.close()
            holder?.recovery?.close()
            holder = null
            authPreferencesFactoryForTests = null
            repositoryFactoryForTests = null
            runTransitionFactoryForTests = null
        }
    }

    /** Test-only process reset WITH quiescence: closes the transition watch
     * (no new recovery requests can be submitted), then cancels AND JOINS
     * any in-flight recovery. The holder is dropped ONLY AFTER the recovery
     * is terminal: on a bounded-timeout miss the holder RETAINS ownership
     * (the recovery refuses new requests once closed), this returns false,
     * and a later call retries the join. Callers must treat false as a STOP
     * state: no destructive cleanup, no new holder, no selector restore.
     * resetForTests() alone returns while a cancelled recovery pass may
     * still be running blocking transition/resolver/SQL work that can
     * reopen or re-cache owned state after a test's deletion checks. */
    internal suspend fun resetForTestsAndAwait(timeoutMs: Long = 10_000): Boolean {
        val recovery = synchronized(lock) {
            holder?.transitionWatch?.close()
            holder?.recovery
        }
        if (recovery == null) {
            // No holder (or one without recovery): nothing to join.
            synchronized(lock) {
                authPreferencesFactoryForTests = null
                repositoryFactoryForTests = null
                runTransitionFactoryForTests = null
            }
            return true
        }
        if (!recovery.closeAndJoinForTests(timeoutMs)) return false
        synchronized(lock) {
            holder = null
            authPreferencesFactoryForTests = null
            repositoryFactoryForTests = null
            runTransitionFactoryForTests = null
        }
        return true
    }
}

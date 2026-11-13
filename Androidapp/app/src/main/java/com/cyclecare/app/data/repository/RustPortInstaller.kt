package com.cyclecare.app.data.repository

import com.cyclecare.app.data.local.rust.RustBoundRoomHost
import com.cyclecare.app.data.local.rust.RustRepositoryPort
import com.cyclecare.app.security.AuthPreferences

/** Stage-C live install orchestration. INERT: no production caller. Activation
 * (MainActivity wiring after off-main native warmup) is separately gated.
 *
 * The order is load-bearing:
 * 1. The caller warms the native library OFF the main thread (RustTrackNative
 *    documents the cost) and constructs the host; this class refuses the main
 *    thread and never suspends.
 * 2. RESOLUTION BINDING (CAPABILITY): [resolution] is the private-constructor
 *    RustPortInstallResolver.Resolution carrying the exact preferences
 *    instance, the FULL captured operation snapshot and the preopened
 *    database AS ONE binding; no caller can supply an arbitrary
 *    (database, owner) pair. The binding is revalidated TWICE: before publish
 *    and inside the admitted commit (mutatePublicationIdentityIfCurrent runs
 *    the action only while the full snapshot still matches, with
 *    Resolution.stillCurrent as the second fence), so same-owner
 *    session/consent/accountMode/localOnly moves between resolution and
 *    commit abort as StaleIdentity. The binding is immutable for this
 *    installer's life: it NEVER rebinds its database to another identity; an
 *    owner move requires re-resolution and a fresh installer.
 * 3. ADMISSION REQUIRED: the preferences must carry an attached publication
 *    admission before any publish, because identity exclusion during
 *    seal+install comes from that admission (every identity-mutating setter
 *    routes through it). mutatePublicationIdentityIfCurrent runs actions
 *    WITHOUT exclusion when no admission is attached, so this installer fails
 *    closed instead of silently losing the exclusion.
 * 4. publish: mints a token from a captured snapshot, advancing from the
 *    previously minted token when one exists, so a retried install never
 *    replays a stale epoch. Publication alone routes nothing.
 * 5. seal+install run INSIDE mutatePublicationIdentityIfCurrent: the captured
 *    snapshot cannot change between the currency check and the routing swap.
 *    seal: trySealLegacyDispatch atomically requires no inflight legacy
 *    dispatch, no selected port and an unsealed gate. No wait-for-zero: a
 *    contended attempt fails and the caller retries on its own dispatcher.
 *    The seal is released in finally whatever the outcome.
 *
 * Token ownership: this installer owns the token it mints until an install
 * succeeds; success hands the token to the repository inside the port and
 * clears the owner's reference. Cancellation policy: the path is synchronous
 * and never suspends; a thrown CancellationException or Error is rethrown,
 * any other Throwable becomes Failed. Failure before a successful install
 * leaves the repository legacy-only; no routing change is visible to
 * readers. */
internal class RustPortInstaller(
    private val repository: CycleRepository,
    private val host: RustBoundRoomHost,
    private val resolution: RustPortInstallResolver.Resolution,
) {
    private val preferences: AuthPreferences get() = resolution.preferences

    sealed class Outcome {
        data class Installed(val epoch: Long) : Outcome()
        /** Legacy dispatch stayed inflight through every attempt; retry later. */
        object Busy : Outcome()
        /** Identity moved away from the resolution binding, or the captured
         * snapshot went stale; re-resolve and restart with a fresh installer. */
        object StaleIdentity : Outcome()
        data class Failed(val cause: Throwable) : Outcome()
    }

    /** The token this installer most recently minted and still owns; null
     * before the first publish and after a successful install. */
    private var minted: RustBoundRoomHost.Token? = null

    /** One synchronous install pass: verify the resolution binding, publish
     * (advancing from the owned token when present), then up to [maxAttempts]
     * admission-excluded sealed install tries. Never sleeps; spacing retries
     * is the caller's job. Safe to call again after Busy or Failed while the
     * binding still holds: the next publish advances from the owned token. */
    fun install(maxAttempts: Int = 3): Outcome {
        // Preference and owner-gate reads happen on this path; it must never
        // run on the main thread. Native warmup and host construction also
        // stay off main (RustTrackNative documents the load cost).
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "rust_install_on_main_thread" }
        // Fail closed: without the admission there is NO identity exclusion
        // (mutatePublicationIdentityIfCurrent runs unserialized), so publish
        // nothing.
        val admission = preferences.publicationAdmission
            ?: return Outcome.Failed(IllegalStateException("rust_install_without_admission"))
        val captured = resolution.captured
        // Pre-publish binding check; the FULL snapshot is revalidated again at
        // the admitted commit below, so same-owner session/localOnly/
        // accountMode moves in between cannot slip a stale binding through.
        if (!resolution.stillCurrent()) return Outcome.StaleIdentity
        val token = when (val published = host.publish(minted, resolution.database, captured)) {
            is RustBoundRoomHost.Result.Value -> published.value
            RustBoundRoomHost.Result.Stale -> return Outcome.StaleIdentity
            else -> return Outcome.Failed(IllegalStateException("rust_publish_rejected:$published"))
        }
        minted = token
        repeat(maxAttempts) {
            var installed = false
            var failure: Throwable? = null
            val committed = preferences.mutatePublicationIdentityIfCurrent(captured, resolution::stillCurrent) {
                if (repository.trySealLegacyDispatch()) {
                    try {
                        // PUBLICATION BOUNDARY: installRustPort assigns raw
                        // MutableStateFlows (activeDatabase/rustPortRevision),
                        // and a synchronous/Unconfined collector resumes INLINE
                        // on this thread mid-assignment. callbacks demotes this
                        // stack from transition privilege to publication kind,
                        // so a reentrant raw identity setter from such a
                        // collector QUEUES behind this job (mutate) or is
                        // rejected (mutateResult) instead of mutating identity
                        // inline during the routing swap.
                        admission.callbacks {
                            repository.installRustPort(RustRepositoryPort(host, token))
                        }
                        installed = true
                    } catch (t: Throwable) {
                        // Captured, not folded: the policy decision happens
                        // after the admission action returns, so a thrown
                        // CancellationException/Error is never wrapped by the
                        // admission's queued completion.
                        failure = t
                    } finally {
                        repository.releaseLegacyDispatchSeal()
                    }
                }
            }
            if (!committed) return Outcome.StaleIdentity
            failure?.let {
                when (it) {
                    is kotlin.coroutines.cancellation.CancellationException -> throw it
                    is Error -> throw it
                    else -> return Outcome.Failed(it)
                }
            }
            if (installed) {
                minted = null
                return Outcome.Installed(token.epoch)
            }
        }
        return Outcome.Busy
    }
}

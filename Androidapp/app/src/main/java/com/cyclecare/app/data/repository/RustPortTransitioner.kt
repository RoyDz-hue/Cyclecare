package com.cyclecare.app.data.repository

import android.content.Context
import com.cyclecare.app.data.local.rust.RustBoundRoomHost
import com.cyclecare.app.data.local.rust.RustRepositoryPort
import com.cyclecare.app.security.AuthPreferences

/** Stage-D connected cutover: after a first install, ANY owner/session/
 * localOnly/accountMode move stales the selected port token
 * (verifyPreferences is full-snapshot equality). Without this transitioner
 * the process stayed safe-but-dead - CycleRepository.activateCurrentOwner
 * refuses stale routing ("rust_owner_transition_requires_preopened_port")
 * until process death.
 *
 * ensureCurrentRouting() performs the RECOVERY: re-resolve the CURRENT
 * identity through the real resolver (lazy instance, actual open forced,
 * post-open re-verification), publish a fresh token on a FRESH host, and
 * REPLACE the stale port under the same admitted, commit-time-revalidated
 * protocol as the initial install (mutatePublicationIdentityIfCurrent +
 * stillCurrent + callbacks publication boundary). One resolution binds one
 * immutable pair; a moved identity aborts as StaleIdentity and a later
 * transition retries.
 *
 * SAFE REFUSAL PRESERVED: NoAdmission/publish rejection/native failure leave
 * the stale port selected (fail-closed); nothing here routes partially.
 * Presence of an installed port is NEVER treated as success. Off-main only. */
internal class RustPortTransitioner(
    private val appContext: Context,
    private val preferences: AuthPreferences,
    private val repository: CycleRepository,
    private val resolverFactory: (Context, AuthPreferences) -> RustPortInstallResolver =
        { context, prefs -> RustPortInstallResolver(context, prefs) },
) {
    internal sealed class Outcome {
        /** No port selected at all; the initial-install path owns that. */
        object NotInstalled : Outcome()
        /** Selected port still current; nothing to do. */
        object Current : Outcome()
        /** Stale port replaced by a freshly resolved, current one. */
        object Replaced : Outcome()
        /** Identity moved during the transition; nothing was rebound. */
        object StaleIdentity : Outcome()
        /** No publication admission: no identity exclusion; fail closed. */
        object NoAdmission : Outcome()
        /** A commit lease was held through every attempt; retry later. */
        object Busy : Outcome()
        data class Failed(val cause: Throwable) : Outcome()
    }

    fun ensureCurrentRouting(maxAttempts: Int = 3): Outcome {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "rust_transition_on_main_thread" }
        val installed = repository.installedRustPort() ?: return Outcome.NotInstalled
        if (installed.isCurrent()) return Outcome.Current
        val admission = preferences.publicationAdmission ?: return Outcome.NoAdmission
        repeat(maxAttempts) { attempt ->
            when (val resolved = resolverFactory(appContext, preferences).resolve()) {
                is RustPortInstallResolver.Result.NoAdmission -> return Outcome.NoAdmission
                is RustPortInstallResolver.Result.Stale -> return Outcome.StaleIdentity
                is RustPortInstallResolver.Result.Resolved -> {
                    val resolution = resolved.resolution
                    if (!resolution.stillCurrent()) return Outcome.StaleIdentity
                    val host = RustBoundRoomHost(preferences)
                    val token = when (val published = host.publish(null, resolution.database, resolution.captured)) {
                        is RustBoundRoomHost.Result.Value -> published.value
                        RustBoundRoomHost.Result.Stale -> return Outcome.StaleIdentity
                        else -> return Outcome.Failed(IllegalStateException("rust_publish_rejected:$published"))
                    }
                    var replaced = false
                    var failure: Throwable? = null
                    val committed = preferences.mutatePublicationIdentityIfCurrent(resolution.captured, resolution::stillCurrent) {
                        try {
                            // PUBLICATION BOUNDARY: same rationale as the
                            // installer - reentrant identity setters from
                            // synchronous observers queue behind this job.
                            admission.callbacks {
                                repository.replaceRustPort(RustRepositoryPort(host, token))
                            }
                            replaced = true
                        } catch (t: Throwable) {
                            // Captured, not folded: the policy decision happens
                            // after the admission action returns.
                            failure = t
                        }
                    }
                    if (!committed) return Outcome.StaleIdentity
                    failure?.let {
                        when {
                            it is kotlin.coroutines.cancellation.CancellationException -> throw it
                            it is Error -> throw it
                            // A sync commit held the routing lease through the
                            // admitted window; retry the whole transition.
                            it.message == "rust_replacement_during_commit" -> {
                                if (attempt < maxAttempts - 1) {
                                    Thread.sleep(250)
                                    return@repeat
                                }
                                return Outcome.Busy
                            }
                            else -> return Outcome.Failed(it)
                        }
                    }
                    if (replaced) return Outcome.Replaced
                }
            }
        }
        return Outcome.Busy
    }
}

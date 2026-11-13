package com.cyclecare.app.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Stage-D OWNED recovery state behind the process transition watch. Firing
 * RustPortTransitioner and dropping the outcome (the first watch draft) let
 * Busy (commit lease held), Failed and StaleIdentity passes vanish: with no
 * further identity event, the stale port stayed selected until process
 * death - bounded refusal, but no assured recovery.
 *
 * This coordinator owns recovery instead:
 * - requests COALESCE: transitions arriving while a pass runs set one
 *   pending bit and produce exactly one follow-up episode, never a pile of
 *   competing recoveries;
 * - recoverable outcomes (Busy/Failed/StaleIdentity) RETRY on a bounded
 *   schedule, so a released commit lease or a settled identity is picked up
 *   WITHOUT waiting for a new mutation;
 * - a HARD ABSOLUTE BUDGET caps total passes even while requests keep
 *   arriving: pending follow-ups reset the per-episode round counter but
 *   never the absolute one, so a self-feeding notification source cannot
 *   spin forever (the composition watch additionally snapshot-dedups, which
 *   stops the known self-feed at its source);
 * - CancellationException and Error PROPAGATE; only ordinary Throwables
 *   become retryable Failed;
 * - OWNERSHIP IS GENERATION-SAFE: every run carries a generation token.
 *   The settle path releases ownership inside the decision lock; EVERY
 *   other exit (cancellation before the body starts, cancellation in a
 *   delay, fatal Error) is finalized by the job's invokeOnCompletion -
 *   which runs even for a never-started coroutine - and only when the
 *   completing generation still owns the coordinator. A stale run can
 *   therefore never clear a newer run's inFlight/pending, and close()
 *   cannot miss a just-launched job (closed is checked under the same
 *   lock that stores the job; a close racing launch cancels immediately);
 * - every pass records lastOutcome + roundsRun and recoveryInFlight is
 *   observable, so recovery state can be asserted (tests, startup logging)
 *   instead of disappearing;
 * - after the budget the stale port stays selected (safe refusal
 *   preserved); the next identity transition requests again. */
internal class RustPortRecovery(
    private val scope: CoroutineScope,
    private val runTransition: () -> RustPortTransitioner.Outcome,
    internal val maxRounds: Int = 5,
    private val retryDelayMs: Long = 2000,
    internal val hardBudget: Int = maxRounds * 4,
) {
    private val stateLock = Any()
    private var inFlight = false
    private var pending = false
    private var generation = 0L
    private var job: Job? = null
    private var closed = false

    @Volatile internal var lastOutcome: RustPortTransitioner.Outcome? = null
        private set
    @Volatile internal var roundsRun = 0
        private set
    internal val recoveryInFlight: Boolean get() = synchronized(stateLock) { inFlight }

    fun request() {
        // LAZY launch-publication protocol: the coroutine is created
        // UNSTARTED, its job is stored under the SAME lock that publishes
        // the generation and that close()/closeAndJoinForTests() read, and
        // only then is it started. There is therefore NO window where a
        // pass is running (or runnable) while job is still null or holds a
        // superseded handle: a racing close() either reads closed=true here
        // (the unstarted job is cancelled below) or finds the stored job
        // and cancels it before/without start.
        var gen = 0L
        val launched = scope.launch(start = CoroutineStart.LAZY) { run(gen) }
        synchronized(stateLock) {
            if (closed) { launched.cancel(); return }
            if (inFlight) { launched.cancel(); pending = true; return }
            inFlight = true
            ++generation
            gen = generation
            job = launched
        }
        // Single finalization point for every NON-settle exit. Fires even
        // when the coroutine is cancelled before its body starts, so
        // inFlight can never be stranded.
        launched.invokeOnCompletion { cause ->
            if (cause != null) finalizeAbnormal(gen)
        }
        launched.start()
    }

    /** Cancels any in-flight pass and refuses further requests. Used by test
     * resets so a stale holder's recovery never outlives it. The job read is
     * race-free: request() publishes the job under this same lock BEFORE the
     * coroutine can start. */
    internal fun close() {
        val j = synchronized(stateLock) { closed = true; job }
        j?.cancel()
    }

    /** Test-only: cancel AND JOIN the in-flight pass. close() alone returns
     * while the cancelled coroutine may still be unwinding blocking
     * transition/resolver/SQL work, which can reopen or re-cache owned
     * state after a reset's deletion checks. Returns false if the job does
     * not finish within [timeoutMs] so callers fail closed. */
    internal suspend fun closeAndJoinForTests(timeoutMs: Long = 10_000): Boolean {
        val j = synchronized(stateLock) { closed = true; job } ?: return true
        j.cancel()
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { j.join(); true } ?: false
    }

    private fun finalizeAbnormal(gen: Long) {
        synchronized(stateLock) {
            if (generation == gen) { inFlight = false; pending = false }
        }
    }

    private suspend fun run(gen: Long) {
        var round = 0
        var absolute = 0
        while (true) {
            round++
            absolute++
            val outcome = try {
                runTransition()
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Error) {
                throw t
            } catch (t: Throwable) {
                RustPortTransitioner.Outcome.Failed(t)
            }
            val (again, delayed) = synchronized(stateLock) {
                // A newer generation owns the coordinator: stop without
                // touching its state.
                if (generation != gen) return
                lastOutcome = outcome
                roundsRun += 1
                val recoverable = outcome is RustPortTransitioner.Outcome.Busy ||
                    outcome is RustPortTransitioner.Outcome.Failed ||
                    outcome is RustPortTransitioner.Outcome.StaleIdentity
                val decision=com.cyclecare.app.rust.CycleCareCoreBridge.recoveryAfterPass(
                    round,absolute,maxRounds,hardBudget,pending,recoverable)
                if(decision!=null) {
                    round=decision.round
                    pending=decision.pending
                    inFlight=decision.inFlight
                    decision.again to decision.delayed
                } else {
                when {
                    // Hard budget outranks EVERYTHING, including pending.
                    absolute >= hardBudget -> { inFlight = false; pending = false; false to false }
                    // A transition arrived mid-pass: re-resolve promptly,
                    // the pass above may have used a superseded identity.
                    pending -> { pending = false; round = 0; true to false }
                    recoverable && round < maxRounds -> true to true
                    else -> { inFlight = false; false to false }
                }
                }
            }
            if (!again) {
                android.util.Log.i("CycleCare", "rust recovery settled: $outcome after $absolute pass(es)")
                return
            }
            if (delayed) delay(retryDelayMs)
        }
    }
}

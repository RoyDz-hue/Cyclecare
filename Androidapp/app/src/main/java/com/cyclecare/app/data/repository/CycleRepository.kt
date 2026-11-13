package com.cyclecare.app.data.repository

import android.content.Context
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.local.OnboardingProfile
import com.cyclecare.app.data.local.OwnerDataGate
import com.cyclecare.app.data.local.HealthSyncOutboxEntry
import com.cyclecare.app.data.local.HealthSyncPayloadCodec
import com.cyclecare.app.data.local.HealthSyncRevision
import com.cyclecare.app.data.model.CyclePhase
import com.cyclecare.app.data.model.DayPrediction
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import androidx.room.withTransaction
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Data structure storing 'period_start_date' and 'period_end_date'.
 * Used across the onboarding step logic and cycle algorithms to validate and track period boundaries.
 */
data class PeriodDateRange(
    val period_start_date: LocalDate,
    val period_end_date: LocalDate
) {
    val startDate: LocalDate get() = period_start_date
    val endDate: LocalDate get() = period_end_date

    val startDateString: String get() = period_start_date.toString()
    val endDateString: String get() = period_end_date.toString()

    /**
     * Calculates the total bleeding/period duration in days inclusive.
     * Always at least 1 day.
     */
    val durationDays: Int
        get() = (ChronoUnit.DAYS.between(period_start_date, period_end_date) + 1).toInt().coerceAtLeast(1)

    /**
     * Validates whether period_end_date is on or after period_start_date (end >= start).
     */
    fun isValid(): Boolean = !period_end_date.isBefore(period_start_date)

    companion object {
        fun fromStrings(period_start_date: String, period_end_date: String): PeriodDateRange? {
            return try {
                val start = LocalDate.parse(period_start_date)
                val end = LocalDate.parse(period_end_date)
                PeriodDateRange(start, end)
            } catch (e: Exception) {
                null
            }
        }
    }
}

typealias PeriodDates = PeriodDateRange

/**
 * Result of validating a period start and end date pair for the onboarding step logic.
 */
data class PeriodDateValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null,
    val durationDays: Int = 0,
    val range: PeriodDateRange? = null,
    // Gentle confirmation text for unusual-but-real durations; null when the
    // pair is typical. Never promises the entry is saved.
    val warningMessage: String? = null
)

/** The preferences owner and the repository's database owner disagree: a switch is in progress, so the write is refused. */
class OwnerTransitionException : IllegalStateException("owner_transition")

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CycleRepository(database: CycleCareDatabase) {
    companion object {
    }
    private val routingLock=Any()
    private val replacingPort=java.util.concurrent.atomic.AtomicBoolean(false)
    // A JVM monitor is reentrant. Explicit count rejects same-thread callbacks
    // attempting replacement while a routing lease owns an actual transaction.
    private var routingLeases=0
    // Stage B legacy-dispatch registry: every legacy Room dispatch (including
    // write-producing reads) registers here. The rustPort/seal check and the
    // increment are ONE routingLock critical section, so a future install's
    // seal+drain sees an atomically meaningful count. The lock never wraps a
    // suspension, a Room call, or a wait-for-zero.
    private var legacyInflight=0
    private var legacySealed=false
    @Volatile private var rustPort:com.cyclecare.app.data.local.rust.RustRepositoryPort?=null
    /** Instance-private issuance capability: a SyncApplyOrigin can only be
     * constructed through THIS repository's capture path. */
    private val syncApplyIssuance = Any()
    /** Reversible injection seam only. No production call constructs this yet.
     * Until every writer is routed, unsupported mutations fail closed, never
     * fall through to legacy DAOs on the selected Rust namespace.
     */
    internal constructor(database:CycleCareDatabase,port:com.cyclecare.app.data.local.rust.RustRepositoryPort):this(database){rustPort=port;current=StoredContext(database,port.token.preference.operation.owner,port.token.epoch)}
    internal fun currentRustPortForOnboarding()=rustPort
    private val activeDatabase = MutableStateFlow(database)
    private val rustPortRevision=MutableStateFlow(0L)
    /** Port install/replacement revision stream. The ViewModel tracks it to
     * invalidate already-loaded legacy UI state when a composition-driven
     * first install moves the read source without changing owner/session. */
    internal val rustPortRevisions:kotlinx.coroutines.flow.Flow<Long> = rustPortRevision
    /** Synchronous current revision: the ViewModel stamps this at
     * construction so an install landing BEFORE its collector's first
     * emission is still detected (no asynchronous-baseline race). */
    internal fun currentRustPortRevision(): Long = rustPortRevision.value
    private val activeReadSource:kotlinx.coroutines.flow.Flow<CycleCareDatabase> = kotlinx.coroutines.flow.combine(activeDatabase,rustPortRevision){db,_->db}
    private var lifecyclePreferences: AuthPreferences? = null
    private var lifecycleContext: Context? = null
    private var lifecycleIdentity: String? = null
    /** Bumped on every owner/database switch, so a switch away and back is still detectable. */
    private var contextEpoch = 0L
    /** Single immutable value, replaced atomically, so one read can never mix database, owner and epoch. */
    @Volatile private var current = StoredContext(database, null, 0L)

    /** The database, owner and switch epoch captured together before any suspension. */
    data class StoredContext(val database: CycleCareDatabase, val owner: String?, val epoch: Long)
    data class StoredSnapshot(val context: StoredContext, val settings: CycleSettings, val profile: OnboardingProfile?)
    internal sealed class UiRead {
        data class Ready(val context:StoredContext,val settings:CycleSettings,val profile:OnboardingProfile?,val logs:List<CycleDayLog>):UiRead()
        object Uninitialized:UiRead()
        object Superseded:UiRead()
        data class Failed(val cause:com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result<*>):UiRead()
    }
    /** Pure coherent IO read inside existing admitted selected-port operation.
     * Does not create settings, acquire keys, or adopt another port. */
    internal fun readRustUiRowsAdmitted():UiRead {
        val port=rustPort?:return UiRead.Superseded
        val context=current
        return when(val result=port.host.readUiRows(port.token)){
            is com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Value->{
                val rows=result.value
                if(rustPort!==port||current!=context||!port.isCurrent())UiRead.Superseded
                else rows.settings?.let{UiRead.Ready(context,it,rows.profile,rows.logs)}?:UiRead.Uninitialized
            }
            com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Stale->UiRead.Superseded
            else->UiRead.Failed(result)
        }
    }
    data class OnboardingCompletion(val context: StoredContext, val settings: CycleSettings)

    private fun captureContext(): StoredContext = current

    /**
     * Intent-time operation token: database, owner, switch epoch, preferences
     * session and the consent generation of THAT owner, captured as one coherent
     * set when the user acts. Mutations take the token, so a delayed coroutine can
     * only write to the database and owner the user acted under, never to
     * whichever owner is active when it finally runs.
     */
    /** Immutable operation authority: context/session/consent plus the EXACT port
     * instance the token was minted under, when any. The port binding is private
     * provenance - no data-class copy/component can silently drop or relabel it;
     * transforms must go through the explicit factory methods. */
    class OperationToken private constructor(val context: StoredContext, val session: Long, val consentGeneration: Long, private val mintedPort: com.cyclecare.app.data.local.rust.RustRepositoryPort?) {
        internal val hasMintedPort: Boolean get() = mintedPort != null
        internal fun isMintedFor(port: com.cyclecare.app.data.local.rust.RustRepositoryPort?): Boolean = mintedPort === port
        internal fun withConsentGeneration(generation: Long) = OperationToken(context, session, generation, mintedPort)
        override fun toString(): String = "OperationToken(context=$context, session=$session, consentGeneration=$consentGeneration)"
        override fun equals(other: Any?): Boolean = other is OperationToken && other.context == context && other.session == session && other.consentGeneration == consentGeneration && other.mintedPort === mintedPort
        override fun hashCode(): Int = 31 * (31 * (31 * context.hashCode() + session.hashCode()) + consentGeneration.hashCode()) + (mintedPort?.let { System.identityHashCode(it) } ?: 0)
        companion object {
            fun legacy(context: StoredContext, session: Long, consentGeneration: Long) = OperationToken(context, session, consentGeneration, null)
            internal fun minted(context: StoredContext, session: Long, consentGeneration: Long, port: com.cyclecare.app.data.local.rust.RustRepositoryPort) = OperationToken(context, session, consentGeneration, port)
        }
    }

    /**
     * Rejects a token whose owner, session or consent generation no longer matches the
     * preferences (logout, owner switch, consent change). A token is an intent-time
     * capture, never standing authority. This check is not atomic with the database
     * write that follows: a change landing after it is not caught.
     */
    private fun requireTokenFresh(token: OperationToken) {
        // A token minted under a port must never be served by the legacy path.
        if (token.hasMintedPort && rustPort == null) throw OwnerTransitionException()
        val prefs = lifecyclePreferences ?: return
        // Same database, owner and switch epoch as when the user acted. Skipped without
        // preferences, where tests deliberately write with an older token.
        if (current != token.context) throw OwnerTransitionException()
        val now = prefs.operationSnapshot()
        if (now.owner != token.context.owner || now.session != token.session ||
            now.consentGeneration != token.consentGeneration) throw OwnerTransitionException()
    }

    /** Rejects when the token was minted for a different (or no) port instance,
     * or the port has since been superseded. Identity freshness alone cannot
     * bind an intent to the exact port it was captured under. */
    private fun requireExactTokenPort(token: OperationToken, port: com.cyclecare.app.data.local.rust.RustRepositoryPort) {
        if (!token.isMintedFor(port) || !port.isCurrent()) throw OwnerTransitionException()
    }

    internal var beforeBoundPortDispatchForTests:(()->Unit)?=null
    /** Deterministic post-commit/pre-cache-seed seam for the replacement-race
     * witness. Invoked outside every lock, after the port commit returns. */
    internal var afterPortCommitBeforeCacheSeedForTests:(()->Unit)?=null
    private fun boundPort(token:OperationToken):com.cyclecare.app.data.local.rust.RustRepositoryPort? = synchronized(routingLock){
        val selected=rustPort
        if(!token.isMintedFor(selected)||(selected!=null&&current!=token.context))throw OwnerTransitionException()
        selected
    }
    private fun commitLease(token:OperationToken,port:com.cyclecare.app.data.local.rust.RustRepositoryPort)=object:com.cyclecare.app.data.local.rust.RustBoundRoomHost.CommitLease {
        override fun <T> admit(action:()->T):T? = synchronized(routingLock){
            if(rustPort!==port||current!=token.context||!token.isMintedFor(port))null else {
                routingLeases++
                try{action()}finally{routingLeases--}
            }
        }
    }

    /** Privately issued exact-origin capture for the DORMANT sync apply routes
     * (B1). Binds the issuing repository, the exact selected port, the token
     * minted for it, the captured owner-scoped database instance and the
     * attach-scoped preferences whose INSTANCE identity was narrowed to the
     * host. Captured BEFORE a transport attempt; revalidated after the
     * response. No production caller yet: loop wiring is a separate scope. */
    internal class SyncApplyOrigin private constructor(
        issuance: Any,
        private val issuer: CycleRepository,
        internal val token: OperationToken,
        internal val port: com.cyclecare.app.data.local.rust.RustRepositoryPort,
        internal val database: CycleCareDatabase,
        internal val preferences: AuthPreferences,
        val owner: String,
        val session: Long,
        val localConsentGeneration: Long,
        val serverConsentGeneration: Long,
    ) {
        init { require(issuance === issuer.syncApplyIssuance) { "sync_origin_issuance" } }
        internal fun isIssuedBy(repository: CycleRepository): Boolean = issuer === repository
        companion object {
            internal fun issue(
                issuance: Any,
                issuer: CycleRepository,
                token: OperationToken,
                port: com.cyclecare.app.data.local.rust.RustRepositoryPort,
                database: CycleCareDatabase,
                preferences: AuthPreferences,
                owner: String,
                session: Long,
                localConsentGeneration: Long,
                serverConsentGeneration: Long,
            ) = SyncApplyOrigin(issuance, issuer, token, port, database, preferences, owner, session, localConsentGeneration, serverConsentGeneration)
        }
    }

    /** Typed outcome of a repository-bound sync apply dispatch. Stale is
     * terminal for the attempt: never a fresh token, never a legacy fallback.
     * NoChange is the OCC-safe case: the host reports the effect already
     * superseded; re-reads stay inside the same captured scope. */
    internal sealed class SyncApplyOutcome<out T> {
        data class Applied<T>(val value: T) : SyncApplyOutcome<T>()
        object NoChange : SyncApplyOutcome<Nothing>()
        object Stale : SyncApplyOutcome<Nothing>()
    }

    /** Identity changed mid-attempt: escapes per-entry retry catch chains and
     * maps to a NON-retry outcome at the attempt boundary. Never a fresh
     * token, never a legacy fallback. */
    internal class SyncApplyStaleException : Exception("sync_apply_stale")

    /** Exact-origin capture for the dormant sync apply routes, taken BEFORE a
     * transport attempt. Returns null ONLY when no port is selected (the
     * caller stays on the legacy path); every inconsistency with a selected
     * port fails closed. The repository's own lifecyclePreferences may be null
     * (ported fixture); identity narrows through the attach-scoped
     * [servicePreferences], which must BE the host's preferences instance.
     * The coherent live snapshot must EQUAL the already-captured request
     * identity the wire operation was validated against - fresher values are
     * never adopted. Null-origin legacy effects are admitted per effect through
     * this repository's routing authority (a selection/seal before an effect
     * types Stale). Residual Stage-C install-contract prerequisites: excluding a
     * hypothetical installer that ignores legacyInflight, and detecting a
     * transient install/uninstall between effects. */
    internal fun captureSyncApplyOrigin(
        servicePreferences: AuthPreferences,
        currentDatabase: CycleCareDatabase,
        requestOwner: String,
        requestSession: Long,
        requestLocalConsent: Long,
        requestServerConsent: Long,
    ): SyncApplyOrigin? {
        val selected = synchronized(routingLock) { rustPort } ?: return null
        if (!selected.isBoundToPreferences(servicePreferences)) throw OwnerTransitionException()
        if (selected.token.preference.operation.owner != requestOwner) throw OwnerTransitionException()
        val snapshot = servicePreferences.syncIdentitySnapshot()
        if (snapshot.owner != requestOwner || snapshot.session != requestSession ||
            snapshot.localConsentGeneration != requestLocalConsent ||
            snapshot.serverConsentGeneration != requestServerConsent) throw OwnerTransitionException()
        if (currentDatabase !== selected.token.database) throw OwnerTransitionException()
        val token = beginOperation()
        // beginOperation may race a selection change A->B; re-verify under lock.
        synchronized(routingLock) {
            if (rustPort !== selected || !token.isMintedFor(selected) || current !== token.context) throw OwnerTransitionException()
        }
        return SyncApplyOrigin.issue(syncApplyIssuance, this, token, selected, currentDatabase, servicePreferences,
            requestOwner, requestSession, requestLocalConsent, requestServerConsent)
    }

    /** Post-response revalidation + leased dispatch of one host sync apply.
     * NEVER mints a token: a failed revalidation is Stale, not a fresh
     * same-owner token for an old ACK/page. A non-null origin never falls
     * back to legacy. Storage/Corrupt/Rejected/Overflow surface through the
     * existing Failure channel (bounded-failure semantics stay with the
     * caller; this layer makes no retry-row/shouldRetry claim).
     * CancellationException propagates. */
    internal fun <T> dispatchSyncApply(
        origin: SyncApplyOrigin,
        currentDatabase: CycleCareDatabase,
        hostCall: (com.cyclecare.app.data.local.rust.RustBoundRoomHost, com.cyclecare.app.data.local.rust.RustBoundRoomHost.Token) -> com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result<T>,
    ): SyncApplyOutcome<T> {
        if (!origin.isIssuedBy(this)) throw OwnerTransitionException()
        // Full cross-field validation: a forged origin cannot pass with
        // mismatched fields, and one copied from a real capture buys nothing
        // the live revalidation + lease would not independently enforce.
        if (!origin.token.isMintedFor(origin.port)) throw OwnerTransitionException()
        if (origin.database !== origin.port.token.database) throw OwnerTransitionException()
        if (!origin.port.isBoundToPreferences(origin.preferences)) throw OwnerTransitionException()
        if (origin.token.context.owner != origin.owner || origin.token.session != origin.session ||
            origin.token.consentGeneration != origin.localConsentGeneration) throw OwnerTransitionException()
        if (currentDatabase !== origin.database) return SyncApplyOutcome.Stale
        val live = origin.preferences.syncIdentitySnapshot()
        if (live.owner != origin.owner || live.session != origin.session ||
            live.localConsentGeneration != origin.localConsentGeneration ||
            live.serverConsentGeneration != origin.serverConsentGeneration) return SyncApplyOutcome.Stale
        try {
            requireTokenFresh(origin.token)
            requireExactTokenPort(origin.token, origin.port)
        } catch (transition: OwnerTransitionException) {
            return SyncApplyOutcome.Stale
        }
        val lease = commitLease(origin.token, origin.port)
        val result = origin.port.host.withCommitLease(lease) { hostCall(origin.port.host, origin.port.token) }
        return when (result) {
            is com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Value -> {
                val value = result.value
                if (value is Boolean && !value) SyncApplyOutcome.NoChange else SyncApplyOutcome.Applied(value)
            }
            com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Stale -> SyncApplyOutcome.Stale
            else -> throw com.cyclecare.app.data.local.rust.RustRepositoryPort.Failure(result)
        }
    }

    /** Tightly scoped typed dispatches for the four DORMANT sync apply routes.
     * The SERVER consent generation comes from the bound origin, never from a
     * caller parameter, so the SQL server binding is structural rather than
     * conventional. Loop wiring (production callers) is a separate, deferred
     * scope. */
    internal fun dispatchAcknowledgeSync(origin: SyncApplyOrigin, currentDatabase: CycleCareDatabase, entry: HealthSyncOutboxEntry, serverRevision: Long): SyncApplyOutcome<Boolean> =
        dispatchSyncApply(origin, currentDatabase) { host, token -> host.acknowledgeSync(token, origin.serverConsentGeneration, entry, serverRevision) }
    internal fun dispatchConflictSync(origin: SyncApplyOrigin, currentDatabase: CycleCareDatabase, entry: HealthSyncOutboxEntry, remoteRevision: Long, payload: String?, tombstone: Boolean, now: Long): SyncApplyOutcome<Boolean> =
        dispatchSyncApply(origin, currentDatabase) { host, token -> host.conflictSync(token, origin.serverConsentGeneration, entry, remoteRevision, payload, tombstone, now) }
    internal fun dispatchRetrySync(origin: SyncApplyOrigin, currentDatabase: CycleCareDatabase, entry: HealthSyncOutboxEntry, nextAttempt: Long, error: String): SyncApplyOutcome<Boolean> =
        dispatchSyncApply(origin, currentDatabase) { host, token -> host.retrySync(token, origin.serverConsentGeneration, entry, nextAttempt, error) }
    internal fun dispatchApplyRemotePage(origin: SyncApplyOrigin, currentDatabase: CycleCareDatabase, changes: List<com.cyclecare.app.data.local.rust.RustRemoteRows.Change>, now: Long): SyncApplyOutcome<Int> =
        dispatchSyncApply(origin, currentDatabase) { host, token -> host.applyRemotePage(token, origin.serverConsentGeneration, changes, now) }

    /** Exactly-once release; the decrement is checked so a double-close or a
     * cancellation racing a close can never drive the count negative. */
    internal inner class LegacyDispatchGuard {
        private val released=java.util.concurrent.atomic.AtomicBoolean(false)
        val active:Boolean get()=!released.get()
        fun close(){ if(released.compareAndSet(false,true)) synchronized(routingLock){ check(legacyInflight>0){"legacy_dispatch_underflow"}; legacyInflight-- } }
    }
    /** Registers one legacy Room dispatch under routingLock (register/release are
     * short critical sections only; no suspension, Room call or wait under the
     * lock). A nested same-operation admission whose parent is still active joins
     * WITHOUT re-checking the seal: an admitted outer transaction is never
     * invalidated by its own write-producing reads. Otherwise a selected port or
     * a sealed gate fails closed. Callers release in finally AFTER their
     * transaction completes, so the registration truly covers the SQL in flight. */
    /** Parentless admission for external per-effect callers (sync-apply legacy
     * effects). The nested-parent join stays internal-only below. */
    internal fun admitLegacyDispatch():LegacyDispatchGuard = admitLegacyDispatch(null)
    private fun admitLegacyDispatch(parent:LegacyDispatchGuard?=null):LegacyDispatchGuard = synchronized(routingLock){
        if(parent!=null&&parent.active){legacyInflight++;return LegacyDispatchGuard()}
        if(rustPort!=null||legacySealed)throw OwnerTransitionException()
        legacyInflight++;LegacyDispatchGuard()
    }
    /** Stage-B bounded seam: atomic check-and-seal for a future install. Seals
     * ONLY when no legacy dispatch is inflight, the gate is unsealed and no port
     * is selected; rejects otherwise WITHOUT sealing (no wait-for-zero, no
     * queue). No production caller: Stage C install wiring is separate,
     * unapproved work. */
    internal fun trySealLegacyDispatch():Boolean = synchronized(routingLock){
        if(legacySealed||legacyInflight!=0||rustPort!=null)false else {legacySealed=true;true}
    }
    internal fun releaseLegacyDispatchSeal(){ synchronized(routingLock){legacySealed=false} }

    /** Kept as the internal name used by the write paths. */
    private fun captureWriteContext(): OperationToken = beginOperation()

    /**
     * Coherent capture: the preferences owner, session and consent generation come
     * from one locked read (AuthPreferences.operationSnapshot) and must name the same
     * owner as the repository context, which must be unchanged afterwards. The null
     * guest owner maps to the legacy_guest database key while its generation is keyed
     * by the derived effective id; that mapping is deliberate and unchanged.
     */
    fun beginOperation(): OperationToken {
        val pair=synchronized(routingLock){rustPort to current}
        pair.first?.let{port->
            if(!port.isCurrent())throw OwnerTransitionException()
            return synchronized(routingLock){
                if(rustPort!==port||current!==pair.second)throw OwnerTransitionException()
                OperationToken.minted(pair.second,port.token.preference.operation.session,port.token.preference.operation.consentGeneration,port)
            }
        }
        val prefs = lifecyclePreferences ?: return OperationToken.legacy(current, 0L, 0L)
        repeat(5) {
            val before = current
            // Owner, session, effective id and generation come from ONE locked read.
            val snapshot = prefs.operationSnapshot()
            if (snapshot.owner == before.owner && current === before) {
                return OperationToken.legacy(before, snapshot.session, snapshot.consentGeneration)
            }
        }
        throw OwnerTransitionException()
    }

    fun currentContext(): StoredContext = current

    /** Non-throwing probe of the same alignment captureWriteContext enforces. */
    fun isWriteContextStable(): Boolean = try { captureWriteContext(); true } catch (_: OwnerTransitionException) { false }

    private fun ownerKey(owner: String?) = owner?.takeIf { it.isNotBlank() } ?: "legacy_guest"

    /** True only while the database, owner and switch epoch are exactly the captured ones. */
    fun isCurrentContext(context: StoredContext): Boolean {
        if(rustPort?.isCurrent()==false)return false
        val now = current
        return context.database === now.database && context.owner == now.owner && context.epoch == now.epoch
    }

    /** Switches database, owner and epoch together; the database is resolved before anything is published. */
    @Synchronized
    private fun publishContext(database: CycleCareDatabase, owner: String?) {
        lifecycleIdentity = owner
        contextEpoch++
        storedPeriodDateRange = null
        current = StoredContext(database, owner, contextEpoch)
        activeDatabase.value = database
    }

    /** Test seam: runs right after a read/write captures its context, before its first database call. Null in production. */
    internal var afterOnboardingSettingsReadForTests:(suspend ()->Unit)?=null
    /** Test-only probe at the START of the legacy scoped write transaction body,
     * i.e. after Room transaction acquisition but before the preferences-monitor
     * freshness read. Used by the bounded lock-order witness fixture. */
    internal var writeTxEntryProbeForTests:(()->Unit)?=null
    internal var afterMissingSettingsReadForTests:(suspend ()->Unit)?=null
    internal var afterContextCaptured: (suspend (StoredContext) -> Unit)? = null

    /** Preferred account-bound construction; signed-in users never fall back to legacy storage. */
    constructor(context: Context, authPreferences: AuthPreferences) : this(context, authPreferences, authPreferences.userId)

    /**
     * The owner is read from the preferences ONCE; the database and the identity both come from
     * that single value, so they cannot name different owners if an identity change lands during
     * construction. A change after this read is reconciled by the ViewModel's identity reset,
     * which publishes through activateCurrentOwner.
     */
    private constructor(context: Context, authPreferences: AuthPreferences, owner: String?) : this(
        CycleCareDatabase.getDatabase(context.applicationContext, owner)
    ) {
        lifecycleContext = context.applicationContext
        lifecyclePreferences = authPreferences
        lifecycleIdentity = owner
        current = StoredContext(activeDatabase.value, owner, contextEpoch)
    }
    private val dao get() = activeDatabase.value.cycleDao()
    private val onboardingDao get() = activeDatabase.value.onboardingDao()
    private val consentGeneration: Long get() = lifecyclePreferences?.healthConsentGeneration ?: 0L

    /** Candidate ports are replaced only with a fully published preopened
     * owner/token. A raw identity transition must not reopen or reuse old data.
     */
    internal fun replaceRustPort(port:com.cyclecare.app.data.local.rust.RustRepositoryPort){
        // A competing/reentrant replacement is rejected, never silently reordered
        // across observer publication. Caller retains its exact operation outcome.
        check(replacingPort.compareAndSet(false,true)){"rust_replacement_in_progress"}
        try{
            check(port.isCurrent()){"rust_port_not_current"}
            val next=synchronized(routingLock){
                check(routingLeases==0){"rust_replacement_during_commit"}
                check(rustPort!=null){"rust_port_not_installed"}
                val revision=Math.addExact(rustPortRevision.value,1L)
                rustPort=port
                current=StoredContext(port.token.database,port.token.preference.operation.owner,port.token.epoch)
                lifecycleIdentity=port.token.preference.operation.owner
                storedPeriodDateRange=null
                revision
            }
            // No repository monitor during synchronous callbacks. The replacement
            // slot remains owned through both observer publications.
            activeDatabase.value=port.token.database
            rustPortRevision.value=next
        }finally{replacingPort.set(false)}
    }
    /** Read-only view of the currently installed port for shared writers
     * (SupabaseSyncService reads its ONLY port selection through here).
     * Locked read: pairs the port with the routing state installed under the
     * same routingLock critical section. Null while legacy-only. */
    internal fun installedRustPort(): com.cyclecare.app.data.local.rust.RustRepositoryPort? = synchronized(routingLock){rustPort}

    /** Test-only routing-lease hold: occupies the commit slot that
     * replaceRustPort checks (rust_replacement_during_commit) so recovery
     * witnesses can force a Busy pass and then release it. */
    internal fun holdRoutingLeaseForTests(): AutoCloseable {
        synchronized(routingLock){routingLeases++}
        return AutoCloseable { synchronized(routingLock){routingLeases--} }
    }

    fun activateCurrentOwner() {
        rustPort?.let{check(it.isCurrent()){"rust_owner_transition_requires_preopened_port"};return}
        val context = lifecycleContext ?: return
        val preferences = lifecyclePreferences ?: return
        repeat(5) {
            val owner = preferences.userId
            val session = preferences.sessionGeneration
            if (current.owner == owner) return
            val database = CycleCareDatabase.getDatabase(context, owner)
            // The slow lookup above can finish after a newer switch. Publish only if
            // the preferences still name this owner and session; otherwise retry with
            // the newest identity instead of rolling the repository back.
            // The identity check holds the preferences lock while publishing, so an identity
            // change cannot land between the check and the publish. Lock order is always
            // preferences, then this repository; nothing takes them the other way round.
            val published = preferences.runIfIdentityUnchanged(owner, session) {
                synchronized(this) { publishContext(database, owner) }
                true
            }
            if (published == true) return
        }
    }

    internal fun activateDatabaseForTest(database: CycleCareDatabase, owner: String?) {
        publishContext(database, owner)
    }

    // In-memory cache for validated period date boundaries
    private var storedPeriodDateRange: PeriodDateRange? = null

    data class ScopedLogSnapshot(val database: CycleCareDatabase,val logs:List<CycleDayLog>,internal val rustToken:Any?=null)
    val scopedLogs: Flow<ScopedLogSnapshot> = activeReadSource.flatMapLatest { db -> flow {
        rustPort?.let{port->port.observations.collect{emit(ScopedLogSnapshot(db,it.rows,it.token))};return@flow}
        emit(ScopedLogSnapshot(db, emptyList()))
        db.cycleDao().getAllLogs().collect { emit(ScopedLogSnapshot(db, it)) }
    } }
    fun isCurrentSnapshot(snapshot: ScopedLogSnapshot): Boolean = snapshot.database === activeDatabase.value && (rustPort?.let{snapshot.rustToken===it.token&&it.isCurrent()}?: (snapshot.rustToken==null))

    val allLogs: Flow<List<CycleDayLog>> = activeReadSource.flatMapLatest { db -> flow {
        rustPort?.let{port->port.observations.collect{emit(it.rows)};return@flow}
        emit(emptyList())
        emitAll(db.cycleDao().getAllLogs())
    } }
    val settingsFlow: Flow<CycleSettings?> = activeReadSource.flatMapLatest { db->rustPort?.let{port->port.settingsObservations}?:db.cycleDao().getSettingsFlow() }
    val onboardingProfileFlow: Flow<OnboardingProfile?> = activeReadSource.flatMapLatest { db->rustPort?.let{port->port.profileObservations}?:db.onboardingDao().getProfileFlow() }

    fun storePeriodDates(period_start_date: LocalDate, period_end_date: LocalDate): Boolean {
        if (!validatePeriodDates(period_start_date, period_end_date)) {
            return false
        }
        storedPeriodDateRange = PeriodDateRange(period_start_date, period_end_date)
        return true
    }

    fun storePeriodDates(range: PeriodDateRange): Boolean {
        return storePeriodDates(range.period_start_date, range.period_end_date)
    }

    fun getStoredPeriodDates(): PeriodDateRange? = storedPeriodDateRange

    fun validatePeriodDates(
        period_start_date: LocalDate?,
        period_end_date: LocalDate?
    ): Boolean = Companion.validatePeriodDates(period_start_date, period_end_date)

    fun validatePeriodDates(range: PeriodDateRange?): Boolean =
        Companion.validatePeriodDates(range)

    fun validatePeriodDatePair(
        period_start_date: LocalDate?,
        period_end_date: LocalDate?
    ): PeriodDateValidationResult =
        Companion.validatePeriodDatePair(period_start_date, period_end_date)

    suspend fun getOnboardingProfile(): OnboardingProfile? = rustPort?.domain()?.profile ?: if(rustPort==null)onboardingDao.getProfile()else null

    /** Dormant (no production callers). Same binding parity as the live writes. */
    /** SELECTED-BRANCH binding only. The legacy body below never sees [token]:
     * with no explicit token the default is null and a token is minted at
     * INVOCATION time only when a port is selected (invocation-time, NOT
     * user-intent-time; production callers pass entry-minted tokens).
     * Legacy content AND semantics are byte-unchanged. */
    suspend fun saveOnboardingProfile(profile: OnboardingProfile, token: OperationToken? = null) {
        rustPort?.let{port->
        val operation=token?:beginOperation()
        requireTokenFresh(operation);requireExactTokenPort(operation,port);beforeBoundPortDispatchForTests?.invoke();port.saveRows(listOf(com.cyclecare.app.data.local.rust.RustDomainRows.Row.Profile(profile)),commitLease(operation,port));return}
        val guard=admitLegacyDispatch()
        try{
        val db = activeDatabase.value
        val selectedOnboardingDao = db.onboardingDao()
        journalMutation(db, "profile", HealthSyncPayloadCodec.profilePayload(profile)) {
            selectedOnboardingDao.insertOrUpdateProfile(profile)
        }
        }finally{guard.close()}
    }

    suspend fun getSettings(): CycleSettings {
        rustPort?.let{return it.settingsSnapshot().settings!!}
        val guard=admitLegacyDispatch()
        return try{settingsFor(captureContext(),guard)}finally{guard.close()}
    }

    /** Settings and profile read from ONE captured database/owner, never two independent lookups. */
    suspend fun getStoredSnapshot(): StoredSnapshot {
        rustPort?.let{val d=it.settingsSnapshot();return StoredSnapshot(current,d.settings!!,d.profile)}
        val guard=admitLegacyDispatch()
        try{
        val context = captureContext()
        afterContextCaptured?.invoke(context)
        // One transaction on the captured database: settings (including the
        // default-row creation, which nests) and profile are read from the same
        // consistent state, not two independent reads.
        return context.database.withTransaction {
            val settings = settingsFor(context,guard)
            val profile = context.database.onboardingDao().getProfile()
            StoredSnapshot(context, settings, profile)
        }
        }finally{guard.close()}
    }

    private suspend fun settingsFor(context: StoredContext, parent: LegacyDispatchGuard?=null): CycleSettings {
        // Registered like any other legacy dispatch: this read CREATEs and
        // JOURNALS a default settings row when none exists.
        val guard=admitLegacyDispatch(parent)
        return try{context.database.withTransaction {
        val selectedDatabase = context.database
        val dao = selectedDatabase.cycleDao()
        val onboardingDao = selectedDatabase.onboardingDao()
        var settings = dao.getSettings()
        val profile = onboardingDao.getProfile()
        if (settings == null) {
            afterMissingSettingsReadForTests?.invoke()
            settings = CycleSettings(
                isOnboardingCompleted = profile?.isCompleted == true,
                selectedGoalsCsv = profile?.selectedGoalsCsv.orEmpty(),
                lastPeriodStartDate = profile?.lastPeriodDateString.orEmpty(),
                averageCycleLength = profile?.cycleLengthDays ?: 28,
                periodDuration = profile?.periodDurationDays ?: 5,
                lutealPhaseDays = 14,
                remindFertile = profile?.remindFertileWindow ?: false
            )
            // Persist the default row only under a coherent token for THIS context. During
            // an owner transition (or if the context moved) return the in-memory default
            // without writing, rather than journaling another owner's consent generation.
            val token = try { beginOperation() } catch (_: OwnerTransitionException) { null }
            if (token != null && token.context.epoch == context.epoch && token.context.database === context.database) {
                journalMutation(selectedDatabase, "settings", HealthSyncPayloadCodec.settingsPayload(settings),
                    ownerKey = ownerKey(context.owner), consentGeneration = token.consentGeneration) {
                    dao.saveSettings(settings)
                }
            }
        }
            settings
        } }finally{guard.close()}
    }

    suspend fun completeOnboardingWithProfile(
        profile: OnboardingProfile,
        pin: String = "",
        enableLock: Boolean = false,
        token: OperationToken? = null
    ): OnboardingCompletion {
        if(rustPort!=null){
            // Binding parity with updateSettingsBound/saveDayLogBound/setPeriodAnchor
            // (Stage A/B3): served only by the exact port the intent-time token was
            // minted for, under a registered commit lease. No token mints an
            // intent-time token for the CURRENT port (existing beginOperation
            // contract); an explicit legacy, pre-choice or superseded-port token
            // fails closed and is never upgraded. Returns the token's CAPTURED
            // context, not a post-commit fresh read.
            val write=token?:beginOperation()
            requireTokenFresh(write)
            val port=boundPort(write)?:throw OwnerTransitionException()
            requireExactTokenPort(write,port)
            beforeBoundPortDispatchForTests?.invoke()
            val d=port.completeOnboarding(profile,enableLock,commitLease(write,port))
            afterPortCommitBeforeCacheSeedForTests?.invoke()
            // Cache seed, closed against the replacement race: compute locally,
            // verify HOST freshness OUTSIDE routingLock (isCurrent takes the
            // prefs/gate/host monitors - never take those under routingLock,
            // which the commit path already holds beneath them), then under
            // routingLock require this port to STILL be the selected repository
            // port and the captured context to still be current. A separate-host
            // replacement leaves the old host token current but clears the cache
            // under the same lock, so the seed is atomic vs that clear and a
            // stale continuation can never reseed old dates.
            val seed=profile.lastPeriodDateString.takeIf(String::isNotBlank)?.let{date->runCatching{val start=LocalDate.parse(date);PeriodDateRange(start,start.plusDays((profile.periodDurationDays.coerceIn(2,10)-1).toLong()))}.getOrNull()}
            if(port.isCurrent())synchronized(routingLock){if(rustPort===port&&current==write.context)storedPeriodDateRange=seed}
            return OnboardingCompletion(write.context,d.settings!!)
        }
        // Uses the caller's intent-time token when given; otherwise captures one now,
        // before the first suspension. Either way the transaction and journal keys use
        // that token's database, owner and consent generation only.
        val guard=admitLegacyDispatch()
        try{
        val writeContext = token ?: captureWriteContext()
        requireTokenFresh(writeContext)
        val capturedContext = writeContext.context
        afterContextCaptured?.invoke(capturedContext)
        val selectedDatabase = capturedContext.database
        val dao = selectedDatabase.cycleDao()
        val onboardingDao = selectedDatabase.onboardingDao()
        val owner = ownerKey(capturedContext.owner)
        val capturedConsentGeneration = writeContext.consentGeneration
        val updated=selectedDatabase.withTransaction {
            requireTokenFresh(writeContext)
            val current = dao.getSettings() ?: CycleSettings()
            afterOnboardingSettingsReadForTests?.invoke()
            val updated = com.cyclecare.app.domain.onboarding.OnboardingCompletionMapping.settings(current,profile,enableLock)
            val sync = selectedDatabase.healthSyncDao()
            val profilePayload = HealthSyncPayloadCodec.profilePayload(profile)
            val profilePrior = sync.revision(owner, "profile")
            onboardingDao.insertOrUpdateProfile(profile)
            sync.putRevision(HealthSyncRevision(owner, "profile", (profilePrior?.revision ?: 0L) + 1L, HealthSyncPayloadCodec.hash(profilePayload), false, System.currentTimeMillis(), profilePrior?.serverRevision ?: 0L))
            sync.enqueue(HealthSyncOutboxEntry(owner, java.util.UUID.randomUUID().toString(), "profile", "UPSERT", profilePrior?.serverRevision ?: 0L, (profilePrior?.revision ?: 0L) + 1L, profilePayload, HealthSyncPayloadCodec.hash(profilePayload), capturedConsentGeneration))
            val settingsPayload = HealthSyncPayloadCodec.settingsPayload(updated)
            val settingsPrior = sync.revision(owner, "settings")
            dao.saveSettings(updated)
            sync.putRevision(HealthSyncRevision(owner, "settings", (settingsPrior?.revision ?: 0L) + 1L, HealthSyncPayloadCodec.hash(settingsPayload), false, System.currentTimeMillis(), settingsPrior?.serverRevision ?: 0L))
            sync.enqueue(HealthSyncOutboxEntry(owner, java.util.UUID.randomUUID().toString(), "settings", "UPSERT", settingsPrior?.serverRevision ?: 0L, (settingsPrior?.revision ?: 0L) + 1L, settingsPayload, HealthSyncPayloadCodec.hash(settingsPayload), capturedConsentGeneration))
            updated
        }

        // The period-date cache is per active owner: a completion that finished
        // after a switch must not seed the new owner's cache.
        if (isCurrentContext(capturedContext)) {
            val periodStartDate = profile.lastPeriodDateString
                .takeIf(String::isNotBlank)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (periodStartDate == null) {
                storedPeriodDateRange = null
            } else {
                val periodDuration = profile.periodDurationDays.coerceIn(2, 10)
                val periodEndDate = periodStartDate.plusDays((periodDuration - 1).toLong())
                storePeriodDates(periodStartDate, periodEndDate)
            }
        }
        return OnboardingCompletion(capturedContext, updated)
        }finally{guard.close()}
    }

    /**
     * Settings change plus the matching profile adjustment, all against ONE captured
     * database/owner: the profile is read from, and both writes go to, the context
     * captured here. Returns the context and the profile that was current for it.
     */
    /** Candidate editing route binds source-issued base to the exact installed
     * repository context. No fallback to legacy/current full-row data. */
    internal class SettingsEditSession private constructor(internal val repository:CycleRepository,internal val operation:OperationToken,internal val port:com.cyclecare.app.data.local.rust.RustRepositoryPort,internal val base:com.cyclecare.app.data.local.rust.RustBoundRoomHost.SettingsEditBase){
        val settings get()=base.settings
        companion object{internal fun issue(repository:CycleRepository,operation:OperationToken,port:com.cyclecare.app.data.local.rust.RustRepositoryPort,base:com.cyclecare.app.data.local.rust.RustBoundRoomHost.SettingsEditBase)=SettingsEditSession(repository,operation,port,base)}
    }
    internal class PreparedSettingsChange private constructor(internal val session:SettingsEditSession,internal val prepared:com.cyclecare.app.data.local.rust.RustBoundRoomHost.PreparedSettingsEdit){
        val settings get()=prepared.projection.settings!!
        companion object{internal fun issue(session:SettingsEditSession,prepared:com.cyclecare.app.data.local.rust.RustBoundRoomHost.PreparedSettingsEdit)=PreparedSettingsChange(session,prepared)}
    }
    internal suspend fun settingsEditSession():SettingsEditSession {
        val operation=beginOperation();val port=boundPort(operation)?:throw OwnerTransitionException()
        return SettingsEditSession.issue(this,operation,port,port.settingsEditBase(commitLease(operation,port)))
    }
    internal fun isSettingsSessionCurrent(session:SettingsEditSession):Boolean = try{
        session.repository===this&&boundPort(session.operation)===session.port&&session.port.isCurrent()
    }catch(_:OwnerTransitionException){false}
    internal suspend fun prepareSettingsChange(session:SettingsEditSession,intent:com.cyclecare.app.data.local.rust.RustBoundRoomHost.SettingsIntent,operation:String,children:List<String>,now:Long):PreparedSettingsChange {
        check(session.repository===this){"wrong_settings_repository"}
        check(session.base.host===session.port.host&&session.base.token===session.port.token){"mismatched_settings_session"}
        val port=boundPort(session.operation)?:throw OwnerTransitionException()
        check(port===session.port)
        return PreparedSettingsChange.issue(session,port.prepareSettingsIntent(session.base,intent,operation,children,now,commitLease(session.operation,port)))
    }
    internal suspend fun commitSettingsChange(change:PreparedSettingsChange):com.cyclecare.app.data.local.rust.RustRoomBatch.Outcome {
        val session=change.session;check(session.repository===this){"wrong_settings_repository"}
        check(session.base.host===session.port.host&&session.base.token===session.port.token&&change.prepared.host===session.port.host&&change.prepared.token===session.base.token){"mismatched_prepared_settings"}
        val port=boundPort(session.operation)?:throw OwnerTransitionException();check(port===session.port)
        return port.commitSettingsIntent(change.prepared,commitLease(session.operation,port))
    }

    data class SettingsUpdate(val context: StoredContext, val profile: OnboardingProfile?)
    suspend fun updateSettingsBound(
        newSettings: CycleSettings,
        previousAverageCycleLength: Int,
        token: OperationToken? = null
    ): SettingsUpdate {
        if(rustPort!=null){
            // Binding parity with saveDayLogBound/setPeriodAnchor (Stage A): the
            // write is served only by the exact port the intent-time token was
            // minted for, under a registered commit lease. A caller without a
            // token mints an intent-time token for the CURRENT port (the existing
            // beginOperation contract); an explicit legacy or superseded-port
            // token fails closed and is never upgraded. The result carries the
            // token's CAPTURED context, not a post-commit fresh read.
            val write=token?:beginOperation()
            requireTokenFresh(write)
            val port=boundPort(write)?:throw OwnerTransitionException()
            requireExactTokenPort(write,port)
            beforeBoundPortDispatchForTests?.invoke()
            val d=port.updateSettings(newSettings,previousAverageCycleLength,commitLease(write,port))
            return SettingsUpdate(write.context,d.profile)
        }
        val guard=admitLegacyDispatch()
        try{
        val write = token ?: captureWriteContext()
        requireTokenFresh(write)
        val captured = write.context
        afterContextCaptured?.invoke(captured)
        val db = captured.database
        val key = ownerKey(captured.owner)
        // ONE transaction for the profile read and both journaled writes (Room's withTransaction
        // joins an enclosing transaction: RoomDatabase.android.kt withTransactionContext reuses the
        // inherited TransactionElement). If either write throws, both roll back, so settings can
        // never be saved without the matching profile, and the profile cannot be overwritten from a
        // stale read. The token is re-checked at the start of the transaction; that check is still
        // not atomic with the commit.
        val (profile, updatedProfile) = db.withTransaction {
            requireTokenFresh(write)
            val read = db.onboardingDao().getProfile()
            // Native-first profile-sync decision (M10): null reply (native
            // unavailable, malformed, or drifted echo) falls back to the
            // verbatim Kotlin expression. Write/transaction mechanics and
            // the always-synced day fields are unchanged.
            val profileSync=com.cyclecare.app.rust.CycleCareCoreBridge.syncProfileOnSettings(read?.cycleLengthOption,newSettings.averageCycleLength,previousAverageCycleLength)
            val changed = when {
                profileSync == null -> read?.copy(
                    cycleLengthOption = if (newSettings.averageCycleLength != previousAverageCycleLength) {
                        "${newSettings.averageCycleLength} days"
                    } else read.cycleLengthOption,
                    cycleLengthDays = newSettings.averageCycleLength,
                    periodDurationDays = newSettings.periodDuration
                )
                // Echo-bound: writeProfile implies a profile was submitted,
                // so read is non-null here (cycleLengthOption is non-null).
                profileSync.writeProfile && read != null -> read.copy(
                    cycleLengthOption = profileSync.cycleLengthOption!!,
                    cycleLengthDays = newSettings.averageCycleLength,
                    periodDurationDays = newSettings.periodDuration
                )
                else -> null
            }
            journalMutation(db, "settings", HealthSyncPayloadCodec.settingsPayload(newSettings), ownerKey = key, consentGeneration = write.consentGeneration) {
                db.cycleDao().saveSettings(newSettings)
            }
            if (changed != null) {
                journalMutation(db, "profile", HealthSyncPayloadCodec.profilePayload(changed), ownerKey = key, consentGeneration = write.consentGeneration) {
                    db.onboardingDao().insertOrUpdateProfile(changed)
                }
            }
            read to changed
        }
        return SettingsUpdate(captured, updatedProfile ?: profile)
        }finally{guard.close()}
    }

    data class PeriodAnchorUpdate(val context:StoredContext,val settings:CycleSettings,val profile:OnboardingProfile?)
    suspend fun setPeriodAnchor(date:LocalDate,token:OperationToken=beginOperation()):PeriodAnchorUpdate {
        requireTokenFresh(token)
        boundPort(token)?.let{port->
            // The intent-time token must name THIS port: a port swapped in after
            // the token was minted must not serve the older intent under newer
            // authority.
            requireExactTokenPort(token,port)
            beforeBoundPortDispatchForTests?.invoke()
            val result=port.setPeriodAnchor(date.toString(),commitLease(token,port));return PeriodAnchorUpdate(token.context,result.settings!!,result.profile)}
        val guard=admitLegacyDispatch()
        try{
        val db=token.context.database;val owner=ownerKey(token.context.owner)
        return db.withTransaction {
            writeTxEntryProbeForTests?.invoke()
            requireTokenFresh(token)
            val profile=db.onboardingDao().getProfile()
            val prior=db.cycleDao().getSettings()?:CycleSettings(isOnboardingCompleted=profile?.isCompleted==true,selectedGoalsCsv=profile?.selectedGoalsCsv.orEmpty(),lastPeriodStartDate=profile?.lastPeriodDateString.orEmpty(),averageCycleLength=profile?.cycleLengthDays?:28,periodDuration=profile?.periodDurationDays?:5,remindFertile=profile?.remindFertileWindow?:false)
            val settings=prior.copy(lastPeriodStartDate=date.toString())
            val changed=profile?.copy(lastPeriodDateString=date.toString(),approximatePeriodChoice="EXACT")
            journalMutation(db,"settings",HealthSyncPayloadCodec.settingsPayload(settings),ownerKey=owner,consentGeneration=token.consentGeneration){db.cycleDao().saveSettings(settings)}
            if(changed!=null)journalMutation(db,"profile",HealthSyncPayloadCodec.profilePayload(changed),ownerKey=owner,consentGeneration=token.consentGeneration){db.onboardingDao().insertOrUpdateProfile(changed)}
            PeriodAnchorUpdate(token.context,settings,changed)
        }
        }finally{guard.close()}
    }

    /** Dormant (no production callers). Same binding parity as the live writes. */
    /** SELECTED-BRANCH binding only. The legacy body below never sees [token]:
     * with no explicit token the default is null and a token is minted at
     * INVOCATION time only when a port is selected (invocation-time, NOT
     * user-intent-time; production callers pass entry-minted tokens).
     * Legacy content AND semantics are byte-unchanged. */
    suspend fun saveSettings(settings: CycleSettings, token: OperationToken? = null) {
        rustPort?.let{port->
        val operation=token?:beginOperation()
        requireTokenFresh(operation);requireExactTokenPort(operation,port);beforeBoundPortDispatchForTests?.invoke();port.saveRows(listOf(com.cyclecare.app.data.local.rust.RustDomainRows.Row.Settings(settings)),commitLease(operation,port));return}
        val guard=admitLegacyDispatch()
        try{
        val db = activeDatabase.value
        val selectedDao = db.cycleDao()
        journalMutation(db, "settings", HealthSyncPayloadCodec.settingsPayload(settings)) {
            selectedDao.saveSettings(settings)
        }
        }finally{guard.close()}
    }

    private suspend fun journalMutation(
        db: CycleCareDatabase,
        recordId: String,
        payload: String,
        kind: String = "UPSERT",
        ownerKey: String = ownerKey(lifecycleIdentity),
        consentGeneration: Long = this.consentGeneration,
        write: suspend () -> Unit
    ) {
        val owner = ownerKey
        val capturedConsentGeneration = consentGeneration
        db.withTransaction {
            val sync = db.healthSyncDao()
            val prior = sync.revision(owner, recordId)
            val revision = (prior?.revision ?: 0L) + 1L
            write()
            sync.putRevision(HealthSyncRevision(owner, recordId, revision, HealthSyncPayloadCodec.hash(payload), false, System.currentTimeMillis(), prior?.serverRevision ?: 0L))
            sync.enqueue(HealthSyncOutboxEntry(owner, java.util.UUID.randomUUID().toString(), recordId, kind, prior?.serverRevision ?: 0L, revision, payload, HealthSyncPayloadCodec.hash(payload), capturedConsentGeneration))
        }
    }

    suspend fun getLogForDate(dateString: String): CycleDayLog? {
        rustPort?.let{return it.domain(dateString).log}
        val selectedDatabase = activeDatabase.value
        return selectedDatabase.cycleDao().getLogByDate(dateString)
    }

    suspend fun pendingSyncConflicts(): List<com.cyclecare.app.data.local.HealthSyncConflictEntity> =
        if(rustPort!=null)rustPort!!.pendingConflicts()else activeDatabase.value.healthSyncDao().conflicts(lifecycleIdentity?.takeIf { it.isNotBlank() } ?: "legacy_guest")

    /** Keep the captured local snapshot, rebased on the reviewed server revision. */
    /** SELECTED-BRANCH binding only. The legacy body below never sees [token]:
     * with no explicit token the default is null and a token is minted at
     * INVOCATION time only when a port is selected (invocation-time, NOT
     * user-intent-time; production callers pass entry-minted tokens).
     * Legacy content AND semantics are byte-unchanged. */
    suspend fun retainLocalConflict(recordId: String, token: OperationToken? = null) {
        rustPort?.let{port->
        val operation=token?:beginOperation()
        requireTokenFresh(operation);requireExactTokenPort(operation,port);beforeBoundPortDispatchForTests?.invoke();port.resolveConflict(recordId,true,commitLease(operation,port));return}
        val guard=admitLegacyDispatch()
        try{
        val db = activeDatabase.value
        val owner = lifecycleIdentity?.takeIf { it.isNotBlank() } ?: "legacy_guest"
        val capturedConsentGeneration = consentGeneration
        db.withTransaction {
            val sync = db.healthSyncDao()
            val conflict = sync.conflicts(owner).firstOrNull { it.recordId == recordId } ?: return@withTransaction
            if (conflict.remoteTombstone) throw IllegalStateException("keep_local_unavailable_server_tombstone")
            val current = sync.revision(owner, recordId)
            if (current == null || current.revision != conflict.localRevision || current.payloadHash != HealthSyncPayloadCodec.hash(conflict.localPayloadJson))
                return@withTransaction
            val localSeq = current.revision + 1L
            val payload = conflict.localPayloadJson
            sync.removeReviewedPending(owner, recordId, current.revision)
            sync.putRevision(current.copy(revision = localSeq, serverRevision = conflict.remoteRevision, updatedAt = System.currentTimeMillis()))
            sync.enqueue(HealthSyncOutboxEntry(owner, java.util.UUID.randomUUID().toString(), recordId, conflict.localKind, conflict.remoteRevision, localSeq, payload, HealthSyncPayloadCodec.hash(payload), capturedConsentGeneration))
            sync.resolveConflict(owner, recordId, System.currentTimeMillis(), "RETAIN_LOCAL")
        }
        }finally{guard.close()}
    }

    /** Apply the reviewed typed remote snapshot only if the local snapshot is still current. */
    /** SELECTED-BRANCH binding only. The legacy body below never sees [token]:
     * with no explicit token the default is null and a token is minted at
     * INVOCATION time only when a port is selected (invocation-time, NOT
     * user-intent-time; production callers pass entry-minted tokens).
     * Legacy content AND semantics are byte-unchanged. */
    suspend fun acceptRemoteConflict(recordId: String, token: OperationToken? = null) {
        rustPort?.let{port->
        val operation=token?:beginOperation()
        requireTokenFresh(operation);requireExactTokenPort(operation,port);beforeBoundPortDispatchForTests?.invoke();port.resolveConflict(recordId,false,commitLease(operation,port));return}
        val guard=admitLegacyDispatch()
        try{
        val db = activeDatabase.value
        val owner = lifecycleIdentity?.takeIf { it.isNotBlank() } ?: "legacy_guest"
        db.withTransaction {
            val sync = db.healthSyncDao()
            val conflict = sync.conflicts(owner).firstOrNull { it.recordId == recordId } ?: return@withTransaction
            val current = sync.revision(owner, recordId)
            if (current != null && (current.revision != conflict.localRevision || current.payloadHash != HealthSyncPayloadCodec.hash(conflict.localPayloadJson))) return@withTransaction
            if (current == null && conflict.localRevision != 0L) return@withTransaction
            val remoteRevision = conflict.remoteRevision
            if (remoteRevision <= 0L) throw IllegalStateException("remote_record_missing_accept_unavailable")
            val selectedDao = db.cycleDao()
            val selectedOnboardingDao = db.onboardingDao()
            if (conflict.remoteTombstone) {
                if (conflict.remotePayloadJson != null) throw IllegalArgumentException("tombstone_payload")
                when (recordId) { "settings" -> selectedDao.saveSettings(HealthSyncPayloadCodec.parseSettingsPayload(HealthSyncPayloadCodec.settingsPayload(CycleSettings()), recordId, selectedDao.getSettings())); "profile" -> selectedOnboardingDao.insertOrUpdateProfile(HealthSyncPayloadCodec.parseProfilePayload(HealthSyncPayloadCodec.profilePayload(OnboardingProfile()), recordId, selectedOnboardingDao.getProfile())); else -> selectedDao.deleteLog(recordId) }
                sync.putRevision(HealthSyncRevision(owner, recordId, (current?.revision ?: 0L) + 1L, null, true, System.currentTimeMillis(), remoteRevision))
            } else {
                val payload = conflict.remotePayloadJson ?: throw IllegalArgumentException("missing_remote_payload")
                when (recordId) {
                    "settings" -> selectedDao.saveSettings(HealthSyncPayloadCodec.parseSettingsPayload(payload, recordId, selectedDao.getSettings()))
                    "profile" -> selectedOnboardingDao.insertOrUpdateProfile(HealthSyncPayloadCodec.parseProfilePayload(payload, recordId, selectedOnboardingDao.getProfile()))
                    else -> selectedDao.insertOrUpdateLog(HealthSyncPayloadCodec.parseLogPayload(payload, recordId))
                }
                sync.putRevision(HealthSyncRevision(owner, recordId, (current?.revision ?: 0L) + 1L, HealthSyncPayloadCodec.hash(payload), false, System.currentTimeMillis(), remoteRevision))
            }
            sync.removeReviewedPending(owner, recordId, conflict.localRevision)
            sync.resolveConflict(owner, recordId, System.currentTimeMillis(), "ACCEPT_REMOTE")
        }
        }finally{guard.close()}
    }

    fun getLogForDateFlow(dateString: String): Flow<CycleDayLog?> {
        return rustPort?.observations?.map{it.rows.firstOrNull{row->row.dateString==dateString}}?:activeDatabase.value.cycleDao().getLogByDateFlow(dateString)
    }

    /** SELECTED-BRANCH binding only. The legacy body below never sees [token]:
     * with no explicit token the default is null and a token is minted at
     * INVOCATION time only when a port is selected (invocation-time, NOT
     * user-intent-time; production callers pass entry-minted tokens).
     * Legacy content AND semantics are byte-unchanged. */
    suspend fun saveLog(log: CycleDayLog, token: OperationToken? = null) {
        rustPort?.let{port->
        val operation=token?:beginOperation()
        requireTokenFresh(operation);requireExactTokenPort(operation,port);beforeBoundPortDispatchForTests?.invoke();port.save(log,commitLease(operation,port));return}
        val guard=admitLegacyDispatch()
        try{
        val selectedDatabase = activeDatabase.value
        val owner = lifecycleIdentity?.takeIf { it.isNotBlank() } ?: "legacy_guest"
        val capturedConsentGeneration = consentGeneration
        val selectedDao = selectedDatabase.cycleDao()
        selectedDatabase.withTransaction {
            val sync = selectedDatabase.healthSyncDao()
            val recordId = log.dateString
            val prior = sync.revision(owner, recordId)
            val revision = (prior?.revision ?: 0L) + 1L
            val payload = HealthSyncPayloadCodec.logPayload(log)
            val operationId = java.util.UUID.randomUUID().toString()
            selectedDao.insertOrUpdateLog(log)
            sync.putRevision(HealthSyncRevision(owner, recordId, revision, HealthSyncPayloadCodec.hash(payload), false, System.currentTimeMillis(), prior?.serverRevision ?: 0L))
            sync.enqueue(HealthSyncOutboxEntry(owner, operationId, recordId, "UPSERT", prior?.serverRevision ?: 0L, revision, payload, HealthSyncPayloadCodec.hash(payload), capturedConsentGeneration))
        }
        }finally{guard.close()}
    }

    /** Legacy flow-anchor policy reads the current row while holding its write
     * transaction, not a prior UI aggregate. Rust Track already owns this effect.
     * Deliberately leaves profile unchanged, matching legacy saveDayLog policy.
     */
    suspend fun saveDayLogBound(log:CycleDayLog,token:OperationToken=beginOperation()) {
        requireTokenFresh(token)
        boundPort(token)?.let{port->requireExactTokenPort(token,port);beforeBoundPortDispatchForTests?.invoke();port.save(log,commitLease(token,port));return}
        val guard=admitLegacyDispatch()
        try{
        val db=token.context.database;val owner=ownerKey(token.context.owner)
        db.withTransaction {
            requireTokenFresh(token)
            journalMutation(db,log.dateString,HealthSyncPayloadCodec.logPayload(log),ownerKey=owner,consentGeneration=token.consentGeneration){db.cycleDao().insertOrUpdateLog(log)}
            if(log.flowLevel in listOf("LIGHT","MODERATE","HEAVY","UNUSUALLY_HEAVY")){
                val prior=settingsFor(token.context,guard)
                // Native-first: core flow_anchor_decision port of this exact
                // policy. A null reply (native unavailable/malformed) keeps
                // the Kotlin computation below, byte for byte.
                val nativeDecision=com.cyclecare.app.rust.CycleCareCoreBridge.flowAnchorDecision(log.dateString,log.flowLevel,prior.lastPeriodStartDate)
                val newAnchor:String?=if(nativeDecision!=null){
                    if(nativeDecision.update)nativeDecision.newAnchor else null
                }else{
                    val start=runCatching{LocalDate.parse(prior.lastPeriodStartDate)}.getOrNull();val date=LocalDate.parse(log.dateString)
                    if(start==null||date.isAfter(start.plusDays(15)))log.dateString else null
                }
                if(newAnchor!=null){
                    val changed=prior.copy(lastPeriodStartDate=newAnchor)
                    journalMutation(db,"settings",HealthSyncPayloadCodec.settingsPayload(changed),ownerKey=owner,consentGeneration=token.consentGeneration){db.cycleDao().saveSettings(changed)}
                }
            }
        }
        }finally{guard.close()}
    }

    /** SELECTED-BRANCH binding only. The legacy body below never sees [token]:
     * with no explicit token the default is null and a token is minted at
     * INVOCATION time only when a port is selected (invocation-time, NOT
     * user-intent-time; production callers pass entry-minted tokens).
     * Legacy content AND semantics are byte-unchanged. */
    suspend fun deleteLog(dateString: String, token: OperationToken? = null) {
        rustPort?.let{port->
        val operation=token?:beginOperation()
        requireTokenFresh(operation);requireExactTokenPort(operation,port);beforeBoundPortDispatchForTests?.invoke();port.delete(dateString,commitLease(operation,port));return}
        val guard=admitLegacyDispatch()
        try{
        val selectedDatabase = activeDatabase.value
        val owner = lifecycleIdentity?.takeIf { it.isNotBlank() } ?: "legacy_guest"
        val capturedConsentGeneration = consentGeneration
        val selectedDao = selectedDatabase.cycleDao()
        selectedDatabase.withTransaction {
            val sync = selectedDatabase.healthSyncDao()
            val prior = sync.revision(owner, dateString)
            val revision = (prior?.revision ?: 0L) + 1L
            val operationId = java.util.UUID.randomUUID().toString()
            selectedDao.deleteLog(dateString)
            sync.putRevision(HealthSyncRevision(owner, dateString, revision, null, true, System.currentTimeMillis(), prior?.serverRevision ?: 0L))
            sync.enqueue(HealthSyncOutboxEntry(owner, operationId, dateString, "DELETE", prior?.serverRevision ?: 0L, revision, null, HealthSyncPayloadCodec.hash(null), capturedConsentGeneration))
        }
        }finally{guard.close()}
    }

    suspend fun deleteAllLogs() {
        rustPort?.let{it.wipe();storedPeriodDateRange=null;return}
        // Registered BEFORE any wipe pre-effect (OwnerDataGate mark, consent and
        // processor revocations): a sealed-gate rejection leaves zero effects.
        val guard=admitLegacyDispatch()
        try{
        val wipeOwner = lifecycleIdentity
        val selectedDatabase = activeDatabase.value
        val dao = selectedDatabase.cycleDao()
        val onboardingDao = selectedDatabase.onboardingDao()
        val capturedPreferences = lifecyclePreferences
        val capturedContext = lifecycleContext
        // Persist pause before deleting, so concurrent sync cannot resurrect the cleared namespace.
        capturedContext?.let { OwnerDataGate.markWiped(it, wipeOwner) }
        if (capturedPreferences != null && capturedPreferences.userId == wipeOwner) {
            capturedPreferences.isCloudSyncAllowed = false
            listOf("COHERE", "GEMINI", "OPENROUTER", "LAJIWA").forEach(capturedPreferences::revokeProcessorReceipt)
        }
        val owner = wipeOwner?.takeIf { it.isNotBlank() } ?: "legacy_guest"
        selectedDatabase.withTransaction {
            dao.deleteAllLogs()
            onboardingDao.deleteProfile()
            dao.deleteSettings()
            selectedDatabase.healthSyncDao().eraseOutbox(owner)
            selectedDatabase.healthSyncDao().eraseRevisions(owner)
            selectedDatabase.healthSyncDao().eraseCursor(owner)
            selectedDatabase.healthSyncDao().eraseConflicts(owner)
            dao.saveSettings(CycleSettings(isOnboardingCompleted = false, lastPeriodStartDate = "", averageCycleLength = 28, periodDuration = 5))
        }
        storedPeriodDateRange = null
        capturedContext?.let { OwnerDataGate.markWiped(it, wipeOwner) }
        }finally{guard.close()}
    }

    fun calculateDayPrediction(date: LocalDate, settings: CycleSettings): DayPrediction? {
        // Native core port first (identical semantics); null answer -> Kotlin path.
        com.cyclecare.app.rust.CycleCareCoreBridge.calculateDayPrediction(date, settings)?.let { return it.prediction }
        val lastStart = settings.lastPeriodStartDate
            .takeIf(String::isNotBlank)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return null

        val cycleLength = settings.averageCycleLength.coerceIn(21, 45)
        val periodDuration = settings.periodDuration.coerceIn(2, 10)
        val lutealLength = settings.lutealPhaseDays.coerceIn(10, 16)

        val ovulationCycleDay = (cycleLength - lutealLength).coerceAtLeast(periodDuration + 2)
        val fertileStartCycleDay = (ovulationCycleDay - 4).coerceAtLeast(periodDuration + 1)
        val fertileEndCycleDay = (ovulationCycleDay + 1).coerceAtMost(cycleLength)

        val daysDifference = ChronoUnit.DAYS.between(lastStart, date).toInt()
        val cycleDayZeroIndexed = Math.floorMod(daysDifference, cycleLength)
        val cycleDay = cycleDayZeroIndexed + 1

        val isPeriod = cycleDay <= periodDuration
        val isOvulation = cycleDay == ovulationCycleDay
        val isFertile = cycleDay in fertileStartCycleDay..fertileEndCycleDay

        val phase = when {
            isPeriod -> CyclePhase.MENSTRUAL
            cycleDay < fertileStartCycleDay -> CyclePhase.FOLLICULAR
            cycleDay <= fertileEndCycleDay -> CyclePhase.OVULATION
            else -> CyclePhase.LUTEAL
        }

        return DayPrediction(
            date = date,
            dayOfCycle = cycleDay,
            phase = phase,
            isPeriod = isPeriod,
            isFertile = isFertile,
            isOvulation = isOvulation,
            fertilityConfidence = "Calendar Estimate",
            pregnancyChance = "Not calculated from calendar dates"
        )
    }

    companion object {
        fun validatePeriodDates(
            period_start_date: LocalDate?,
            period_end_date: LocalDate?
        ): Boolean {
            // Native core port first (identical semantics); null -> Kotlin path.
            com.cyclecare.app.rust.CycleCareCoreBridge.validatePeriodDates(period_start_date, period_end_date)?.let { return it }
            if (period_start_date == null || period_end_date == null) return false
            return !period_end_date.isBefore(period_start_date)
        }

        fun validatePeriodDates(range: PeriodDateRange?): Boolean {
            if (range == null) return false
            return validatePeriodDates(range.period_start_date, range.period_end_date)
        }

        /**
         * Delegates to the tested PeriodDateConsistency rules: only chronology
         * and future dates are hard errors; unusual durations stay valid and
         * carry a confirmation warning.
         */
        fun validatePeriodDatePair(
            period_start_date: LocalDate?,
            period_end_date: LocalDate?,
            isOngoing: Boolean = false,
            today: LocalDate = LocalDate.now()
        ): PeriodDateValidationResult {
            val check = com.cyclecare.app.domain.health.PeriodDateConsistency.validatePair(
                period_start_date, period_end_date, today, isOngoing
            )
            return PeriodDateValidationResult(
                isValid = check.isValid,
                errorMessage = check.errorMessage,
                durationDays = check.durationDays,
                range = if (check.isValid && period_start_date != null && period_end_date != null)
                    PeriodDateRange(period_start_date, period_end_date) else null,
                warningMessage = check.warningMessage
            )
        }

        fun validatePeriodDateStrings(
            period_start_date: String?,
            period_end_date: String?
        ): Boolean {
            if (period_start_date.isNullOrBlank() || period_end_date.isNullOrBlank()) return false
            return try {
                val start = LocalDate.parse(period_start_date)
                val end = LocalDate.parse(period_end_date)
                validatePeriodDates(start, end)
            } catch (e: Exception) {
                false
            }
        }
    }
}

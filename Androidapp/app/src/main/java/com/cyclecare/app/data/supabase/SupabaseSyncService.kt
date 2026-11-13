package com.cyclecare.app.data.supabase

import androidx.room.withTransaction
import com.cyclecare.app.data.local.*
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.repository.CycleRepository.SyncApplyStaleException
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Cloud transfer stays source-gated. Neither background work nor a server flag may grant consent. */
class SupabaseSyncService(
    private val legacyDatabase: CycleCareDatabase,
    private val authPreferences: AuthPreferences,
    private val client: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
    private val databaseProvider: OwnerScopedDatabaseProvider = OwnerScopedDatabaseProvider(authPreferences.context, authPreferences)
) {
    /** Explicit inactive integration seam. All other legacy sync mutations stay
     * fail closed while port selected until ACK/retry/consent routes connected.
     */
    private var rustPort:com.cyclecare.app.data.local.rust.RustRepositoryPort?=null
    internal fun attachRustPort(port:com.cyclecare.app.data.local.rust.RustRepositoryPort){port.verifyPreferences(authPreferences);rustPort=port}

    /** Optional internal binding, set ONCE: repository-native selection is
     * authoritative for the four apply routes. Production constructors and the
     * ViewModel never bind it (Stage-C wiring is separate); the rustPort var
     * above is RETAINED only for the journal/staged-history routes (separate
     * blocker). A selected service port WITHOUT this binding fails closed. */
    private var syncApplyRepository: CycleRepository? = null
    internal fun bindSyncApplyRepository(repository: CycleRepository) {
        check(syncApplyRepository == null) { "sync_apply_repository_bound" }
        syncApplyRepository = repository
    }

    /** Resolve ONE attempt's exact origin BEFORE pending reads/transport over
     * the already-captured request identity and DB instance. Fails closed when
     * a selected service port has no authoritative binding, or when the bound
     * repository has no port while the stale var disagrees. */
    internal fun captureSyncApplyBinding(db: CycleCareDatabase, owner: String, session: Long, local: Long, server: Long): CycleRepository.SyncApplyOrigin? {
        val repo = syncApplyRepository
        if (repo == null) {
            check(rustPort == null) { "sync_apply_requires_repo_binding" }
            return null
        }
        val origin = repo.captureSyncApplyOrigin(authPreferences, db, owner, session, local, server)
        check(!(origin == null && rustPort != null)) { "sync_apply_selection_inconsistent" }
        return origin
    }
    private fun conflictSafeProtocolReleased() = false

    /** False while the conflict-safe wire protocol is unreleased: the guarded
     * cloud-backup path (syncAll) uploads nothing, whatever the UI promises. */
    val isOnlineBackupActive: Boolean
        get() = conflictSafeProtocolReleased()
    private fun wireRecordId(owner: String, logicalId: String) = UUID.nameUUIDFromBytes("cyclecare-v3:$owner:$logicalId".toByteArray(Charsets.UTF_8)).toString()
    private fun current(owner: String, session: Long, local: Long, server: Long) = authPreferences.userId == owner && authPreferences.sessionGeneration == session && authPreferences.healthConsentGeneration == local && authPreferences.serverHealthConsentGeneration == server && authPreferences.isCloudSyncAllowed && !OwnerDataGate.isWiped(authPreferences.context, owner)
    private fun validLogicalId(id: String) = id in setOf("profile", "settings") || runCatching { LocalDate.parse(id) }.isSuccess
    private fun envelope(id: String, value: JSONObject) = JSONObject().put("kind", "UPSERT").put("logicalRecordId", id).put("value", value)

    /** Explicit consent action only. Withdrawal remains available even while release is closed. */
    suspend fun requestV3Consent(enabled: Boolean, transport: HealthSyncV3Transport, accessToken: String, includeExistingData: Boolean = false): V3ConsentReceipt = withContext(Dispatchers.IO) {
        val owner = authPreferences.userId ?: error("owner_required"); val session = authPreferences.sessionGeneration
        if (enabled) {
            check(conflictSafeProtocolReleased()) { "v3_release_policy_closed" }
            check(authPreferences.isCloudSyncAllowed && !authPreferences.hasPendingHealthSyncRevoke) { "explicit_current_consent_required" }
        }
        val local = if (enabled) authPreferences.healthConsentGeneration else authPreferences.pauseHealthSyncFor(owner, session) ?: error("owner_changed")
        val receipt = transport.setConsent(enabled, accessToken) // Failed revocation leaves the durable pending flag set.
        check(receipt.enabled == enabled && authPreferences.saveServerHealthConsentReceipt(owner, session, local, receipt.generation, enabled)) { "stale_or_invalid_consent_receipt" }
        // Historical health data is never automatically enrolled by background sync.
        if (enabled && includeExistingData) journalExplicitSnapshot(owner, session, local, receipt.generation)
        receipt
    }
    private suspend fun journalExplicitSnapshot(owner: String, session: Long, local: Long, server: Long) {
        rustPort?.let{port->
            check(port.token.preference.operation.owner==owner&&port.token.preference.operation.session==session&&port.token.preference.operation.consentGeneration==local){"stale_history_identity"}
            applyExplicitRustHistory(server,System.currentTimeMillis());return
        }
        val db = databaseProvider.current(); val dao = db.healthSyncDao()
        db.withTransaction {
            check(current(owner, session, local, server)) { "consent_changed" }
            suspend fun journal(id: String, payload: String) {
                val prior = dao.revision(owner, id); val sequence = Math.addExact(prior?.revision ?: 0L, 1L)
                dao.removeReviewedPending(owner, id, prior?.revision ?: 0L)
                dao.putRevision(HealthSyncRevision(owner, id, sequence, HealthSyncPayloadCodec.hash(payload), false, System.currentTimeMillis(), prior?.serverRevision ?: 0L))
                dao.enqueue(HealthSyncOutboxEntry(owner, UUID.randomUUID().toString(), id, "UPSERT", prior?.serverRevision ?: 0L, sequence, payload, HealthSyncPayloadCodec.hash(payload), local))
            }
            for (log in db.cycleDao().getAllLogsList()) journal(log.dateString, HealthSyncPayloadCodec.logPayload(log))
            db.cycleDao().getSettings()?.let { journal("settings", HealthSyncPayloadCodec.settingsPayload(it)) }
            db.onboardingDao().getProfile()?.let { journal("profile", HealthSyncPayloadCodec.profilePayload(it)) }
        }
    }

    suspend fun executeV3Outbox(transport: HealthSyncV3Transport, accessToken: String): Int = withContext(Dispatchers.IO) {
        if (!conflictSafeProtocolReleased()) return@withContext 0
        val owner = authPreferences.userId ?: return@withContext 0; val session = authPreferences.sessionGeneration
        val local = authPreferences.healthConsentGeneration; val server = authPreferences.serverHealthConsentGeneration ?: return@withContext 0
        if (!current(owner, session, local, server)) return@withContext 0
        val db = databaseProvider.current()
        executeV3OutboxAttempt(transport, accessToken, owner, session, local, server, db, captureSyncApplyBinding(db, owner, session, local, server))
    }

    /** ONE outbox attempt over a captured scope. The public entry is
     * gate-false-first and captures identity+DB once; tests drive THIS body
     * with a scripted local transport. No release override, no parallel
     * implementation. Stale identity escapes as SyncApplyStaleException
     * (never a retry, never a legacy fallback); CancellationException always
     * rethrows; every other failure keeps the existing bounded behavior. */
    internal suspend fun executeV3OutboxAttempt(transport: HealthSyncV3Transport, accessToken: String, owner: String, session: Long, local: Long, server: Long, db: CycleCareDatabase, origin: CycleRepository.SyncApplyOrigin?): Int {
        val repository: CycleRepository? = if (origin == null) null else requireNotNull(syncApplyRepository) { "sync_apply_binding_lost" }
        val dao = db.healthSyncDao(); var applied = 0
        val blockedRecords = mutableSetOf<String>()
        val operations = dao.pending(owner).sortedBy { it.revision }.take(100).map { it.operationId }
        for (operationId in operations) {
            if (!current(owner, session, local, server)) break
            // Re-read after each ACK: a prior operation may have rebased this operation.
            val e = dao.pending(owner).firstOrNull { it.operationId == operationId } ?: continue
            if (e.recordId in blockedRecords || e.consentGeneration != local) continue
            if (e.attempts >= 8 || e.nextAttemptAt > System.currentTimeMillis()) { blockedRecords += e.recordId; continue }
            try {
                check(validLogicalId(e.recordId)) { "invalid_logical_identity" }
                val revision = Math.addExact(e.baseRevision, 1L)
                val payload = if (e.kind == "DELETE") null else envelope(e.recordId, JSONObject(e.payloadJson ?: error("missing_payload")))
                val r = transport.apply(V3WireOperation(e.operationId, wireRecordId(owner, e.recordId), e.baseRevision, revision, e.kind, payload, server, e.recordId), accessToken)
                currentCoroutineContext().ensureActive()
                if (!current(owner, session, local, server)) break
                when (r.status) {
                    "applied", "duplicate" -> {
                        check(r.recordId == wireRecordId(owner, e.recordId) && r.operationId == e.operationId && r.revision == revision && r.logicalRecordId == e.recordId && r.payloadHash?.matches(Regex("^[a-f0-9]{64}$")) == true) { "invalid_ack" }
                        val committed = if (origin != null && repository != null) when (val outcome = repository.dispatchAcknowledgeSync(origin, db, e, revision)) {
                            is CycleRepository.SyncApplyOutcome.Applied -> outcome.value
                            CycleRepository.SyncApplyOutcome.NoChange -> false
                            CycleRepository.SyncApplyOutcome.Stale -> throw SyncApplyStaleException()
                        } else db.withTransaction {
                            if (!current(owner, session, local, server)) return@withTransaction false
                            dao.revision(owner, e.recordId)?.let { dao.putRevision(it.copy(serverRevision = maxOf(it.serverRevision, revision))) }
                            dao.acknowledgeMatched(owner, e.operationId, e.recordId, e.revision)
                            dao.rebasePending(owner, e.recordId, e.baseRevision, revision)
                            true
                        }
                        if (committed) applied++
                    }
                    "conflict" -> {
                        blockedRecords += e.recordId
                        if (origin != null && repository != null) {
                            val remoteValue = r.remotePayload?.optJSONObject("value")
                            check(r.recordId == wireRecordId(owner, e.recordId) && r.operationId == e.operationId && r.logicalRecordId == e.recordId && r.revision != null && r.revision >= 0 && (r.remoteTombstone || r.revision == 0L || remoteValue != null)) { "invalid_conflict_receipt" }
                            when (repository.dispatchConflictSync(origin, db, e, r.revision ?: error("missing_remote_revision"), remoteValue?.toString(), r.remoteTombstone, System.currentTimeMillis())) {
                                is CycleRepository.SyncApplyOutcome.Applied, CycleRepository.SyncApplyOutcome.NoChange -> Unit
                                CycleRepository.SyncApplyOutcome.Stale -> throw SyncApplyStaleException()
                            }
                        } else db.withTransaction {
                            if (!current(owner, session, local, server)) return@withTransaction
                            val latest = dao.pending(owner).filter { it.recordId == e.recordId }.maxByOrNull { it.revision } ?: e
                            val remoteValue = r.remotePayload?.optJSONObject("value")
                            check(r.remoteTombstone || r.revision == 0L || remoteValue != null) { "invalid_conflict_payload" }
                            dao.putConflict(HealthSyncConflictEntity(owner, e.recordId, latest.operationId, latest.revision, latest.payloadJson, latest.kind, r.revision ?: error("missing_remote_revision"), remoteValue?.toString(), r.remoteTombstone, System.currentTimeMillis()))
                        }
                    }
                    else -> error("v3_operation_rejected")
                }
            } catch (stale: SyncApplyStaleException) {
                throw stale
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A noncooperative transport may have cancelled this Job and
                // then thrown: cancellation wins over retry scheduling.
                currentCoroutineContext().ensureActive()
                blockedRecords += e.recordId
                if (!current(owner, session, local, server)) break
                val nextAttempt=System.currentTimeMillis() + (1_000L shl e.attempts.coerceIn(0, 6))
                if (origin != null && repository != null) when (repository.dispatchRetrySync(origin, db, e, nextAttempt, "Backup needs another try")) {
                    is CycleRepository.SyncApplyOutcome.Applied, CycleRepository.SyncApplyOutcome.NoChange -> Unit
                    CycleRepository.SyncApplyOutcome.Stale -> throw SyncApplyStaleException()
                } else dao.retry(owner,e.operationId,nextAttempt,"Backup needs another try")
            }
        }
        return applied
    }

    /** Snapshot/map/publish the backup outcome from the SAME captured DB the
     * attempts used - never a re-resolved provider instance. ONE snapshot
     * (conflicts + unfiltered pending: EVERY waiting entry, due or not), then
     * guarded atomic publication under the prefs lock. A scope that went stale
     * after the snapshot publishes NOTHING and yields the existing non-retry
     * stopped outcome. Snapshot status, not permanence. */
    internal suspend fun publishBackupOutcome(syncDatabase: CycleCareDatabase, owner: String, session: Long, local: Long, server: Long, uploaded: Int, pulled: Int): SyncResult {
        val (conflicts, undelivered) = syncDatabase.withTransaction {
            val outcomeDao = syncDatabase.healthSyncDao()
            outcomeDao.conflicts(owner).size to outcomeDao.pending(owner).size
        }
        val complete = conflicts == 0 && undelivered == 0
        val message = if (conflicts > 0) "$conflicts saved record(s) need your choice in Settings; nothing was overwritten."
            else if (undelivered > 0) "Backup isn't complete. Some records are still waiting to be sent."
            else "Backup complete ($uploaded sent, $pulled received)."
        val timestamp = System.currentTimeMillis()
        if (!authPreferences.publishBackupStatusIfCurrent(owner, session, local, server, timestamp, message, complete))
            return SyncResult(false, "Backup stopped because the account or your choices changed.")
        return SyncResult(complete, message, syncedLogsCount = uploaded + pulled, timestamp = timestamp, shouldRetry = conflicts == 0 && undelivered > 0)
    }

    suspend fun syncAll(): SyncResult = withContext(Dispatchers.IO) {
        if (!authPreferences.isCloudSyncAllowed && !authPreferences.hasPendingHealthSyncRevoke)
            return@withContext SyncResult(true, "Online backup is off, so nothing was sent.")
        val owner = authPreferences.userId ?: return@withContext SyncResult(false, "Please sign in to back up your records.")
        if (OwnerDataGate.isWiped(authPreferences.context, owner) && !authPreferences.hasPendingHealthSyncRevoke) return@withContext SyncResult(true, "This device's records were erased; online backup stays paused.")
        // A pending withdrawal sends only a consent decision, never a health payload.
        if (!authPreferences.hasPendingHealthSyncRevoke && (!authPreferences.isCloudSyncAllowed || !conflictSafeProtocolReleased()))
            return@withContext SyncResult(false, "Online backup is paused for now. Your records stay safe on this device.")
        val token = when (val state = SupabaseSessionManager.resolveAccessToken(authPreferences)) {
            is AccessTokenResolution.Valid -> state.token
            is AccessTokenResolution.Missing, is AccessTokenResolution.ReauthenticationRequired -> return@withContext SyncResult(false, "Please sign in again to finish.", requiresAuthentication = true)
            AccessTokenResolution.TemporarilyUnavailable -> return@withContext SyncResult(false, "Your secure session isn't available right now. Your records stay private on this device.", shouldRetry = true)
        }
        val transport = OkHttpHealthSyncV3Transport(SupabaseConfig.restUrl, SupabaseConfig.anonKey, client)
        try {
            if (authPreferences.hasPendingHealthSyncRevoke) {
                requestV3Consent(false, transport, token)
                return@withContext SyncResult(true, "Your choice was saved. Online backup is off.")
            }
            val local = authPreferences.healthConsentGeneration; val session = authPreferences.sessionGeneration
            val server = authPreferences.serverHealthConsentGeneration ?: return@withContext SyncResult(false, "Please allow online backup in Settings first.")
            val syncDatabase = databaseProvider.current()
            val pulled = pullAttempt(transport, token, owner, session, local, server, syncDatabase, captureSyncApplyBinding(syncDatabase, owner, session, local, server))
            // Pull and outbox are DISTINCT attempts; the second runs only while
            // the ORIGINAL overall identity still holds - a changed identity is
            // never silently re-read and accepted.
            val overall = authPreferences.syncIdentitySnapshot()
            if (!current(owner, session, local, server) || overall.owner != owner || overall.session != session ||
                overall.localConsentGeneration != local || overall.serverConsentGeneration != server)
                return@withContext SyncResult(false, "Backup stopped because the account or your choices changed.")
            val uploaded = executeV3OutboxAttempt(transport, token, owner, session, local, server, syncDatabase, captureSyncApplyBinding(syncDatabase, owner, session, local, server))
            if (!current(owner, session, local, server)) return@withContext SyncResult(false, "Backup stopped because the account or your choices changed.")
publishBackupOutcome(syncDatabase, owner, session, local, server, uploaded, pulled)
        } catch (stale: SyncApplyStaleException) {
            SyncResult(false, "Backup stopped because the account or your choices changed.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { SyncResult(false, "Backup couldn't finish. Your changes stay saved on this device; please try again.", shouldRetry = true) }
    }
    suspend fun pullRemoteUpdates(userId: String): Int = withContext(Dispatchers.IO) {
        if (!conflictSafeProtocolReleased() || !authPreferences.isCloudSyncAllowed) return@withContext 0
        val token = SupabaseSessionManager.validAccessToken(authPreferences) ?: return@withContext 0
        val server = authPreferences.serverHealthConsentGeneration ?: return@withContext 0
        pull(userId, authPreferences.healthConsentGeneration, server, authPreferences.sessionGeneration, OkHttpHealthSyncV3Transport(SupabaseConfig.restUrl, SupabaseConfig.anonKey, client), token)
    }
    private suspend fun pull(owner: String, local: Long, server: Long, session: Long, transport: HealthSyncV3Transport, token: String): Int {
        val db = databaseProvider.current()
        return pullAttempt(transport, token, owner, session, local, server, db, captureSyncApplyBinding(db, owner, session, local, server))
    }

    /** ONE pull attempt over a captured scope: cursor and count advance ONLY
     * on Applied (Applied(0) is valid); Stale stops with no cursor, no count.
     * Tests drive THIS body with a scripted local transport. */
    internal suspend fun pullAttempt(transport: HealthSyncV3Transport, accessToken: String, owner: String, session: Long, local: Long, server: Long, db: CycleCareDatabase, origin: CycleRepository.SyncApplyOrigin?): Int {
        val repository: CycleRepository? = if (origin == null) null else requireNotNull(syncApplyRepository) { "sync_apply_binding_lost" }
        val dao = db.healthSyncDao(); var cursor = dao.cursor(owner)?.changeSeq ?: 0L; var count = 0
        while (current(owner, session, local, server)) {
            val page = transport.readChanges(cursor, 100, server, accessToken)
            currentCoroutineContext().ensureActive()
            if (!current(owner, session, local, server) || page.isEmpty()) break
            if (origin != null && repository != null) {
                val typed = page.map { change ->
                    val id = change.logicalRecordId ?: error("missing_logical_identity")
                    check(validLogicalId(id) && change.recordId == wireRecordId(owner, id)) { "record_identity_mismatch" }
                    val value = change.payload?.optJSONObject("value")
                    check(change.tombstone || (change.payload?.optString("logicalRecordId") == id && change.payload.optString("kind") == "UPSERT" && value != null)) { "invalid_remote_payload" }
                    com.cyclecare.app.data.local.rust.RustRemoteRows.Change(id, change.revision, change.changeSeq, value?.toString(), change.tombstone)
                }
                when (val outcome = repository.dispatchApplyRemotePage(origin, db, typed, System.currentTimeMillis())) {
                    is CycleRepository.SyncApplyOutcome.Applied -> { count += outcome.value; cursor = page.last().changeSeq }
                    CycleRepository.SyncApplyOutcome.NoChange -> error("unreachable:page_apply_returns_count")
                    CycleRepository.SyncApplyOutcome.Stale -> throw SyncApplyStaleException()
                }
            } else db.withTransaction {
                check(current(owner, session, local, server)) { "consent_changed" }
                for (change in page) {
                    check(current(owner, session, local, server)) { "consent_changed" }
                    val id = change.logicalRecordId ?: error("missing_logical_identity")
                    check(validLogicalId(id) && change.recordId == wireRecordId(owner, id)) { "record_identity_mismatch" }
                    val value = change.payload?.optJSONObject("value")
                    check(change.tombstone || (change.payload?.optString("logicalRecordId") == id && change.payload.optString("kind") == "UPSERT" && value != null)) { "invalid_remote_payload" }
                    val prior = dao.revision(owner, id)
                    if (change.revision > (prior?.serverRevision ?: 0L)) {
                        val pending = dao.pending(owner).filter { it.recordId == id }.maxByOrNull { it.revision }
                        if (pending != null) dao.putConflict(HealthSyncConflictEntity(owner, id, pending.operationId, prior?.revision ?: pending.revision, pending.payloadJson, pending.kind, change.revision, value?.toString(), change.tombstone, System.currentTimeMillis()))
                        else {
                            if (change.tombstone) when (id) { "settings" -> db.cycleDao().saveSettings(HealthSyncPayloadCodec.parseSettingsPayload(HealthSyncPayloadCodec.settingsPayload(CycleSettings()), id, db.cycleDao().getSettings())); "profile" -> db.onboardingDao().insertOrUpdateProfile(HealthSyncPayloadCodec.parseProfilePayload(HealthSyncPayloadCodec.profilePayload(OnboardingProfile()), id, db.onboardingDao().getProfile())); else -> db.cycleDao().deleteLog(id) }
                            else applyRemote(db, id, value!!.toString())
                            dao.putRevision(HealthSyncRevision(owner, id, Math.addExact(prior?.revision ?: 0L, 1L), value?.toString()?.let(HealthSyncPayloadCodec::hash), change.tombstone, System.currentTimeMillis(), change.revision)); count++
                        }
                    }
                    cursor = change.changeSeq; dao.putCursor(HealthSyncCursor(owner, cursor))
                }
            }
            if (page.size < 100) break
        }
        return count
    }
    /** Synthetic/local preparation route until full staged sender cutover. One
     * invocation processes bounded pages, no auto-restart on concurrent writes.
     * Caller resumes same operation on Progress rather than hidden retry loops.
     */
    internal fun beginStagedRustHistory(server:Long,operation:String){
        val port=rustPort?:error("rust_port_required");check(port.host.beginHistoryStage(port.token,server,operation) is com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Value)
    }
    internal fun advanceStagedRustHistory(server:Long,operation:String,maxPages:Int=4):com.cyclecare.app.data.local.rust.RustEnrollmentStage.Progress {
        require(maxPages in 1..8);val port=rustPort?:error("rust_port_required")
        var progress=com.cyclecare.app.data.local.rust.RustEnrollmentStage.Progress(0,false,false)
        repeat(maxPages){
            if(!progress.ready&&!progress.stale)progress=when(val result=port.host.historyStagePage(port.token,server,operation)){is com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Value->result.value;else->error("rust_stage_rejected")}
        };return progress
    }
    internal suspend fun sendStagedRustHistory(server:Long,operation:String,transport:HealthSyncV3Transport,accessToken:String,maxRecords:Int=32):Int {
        require(maxRecords in 1..32);val port=rustPort?:error("rust_port_required")
        val rows=when(val result=port.host.historyTakeSendWindow(port.token,server,operation,maxRecords)){is com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Value->result.value;else->error("rust_stage_send_rejected")}
        var sent=0
        for(row in rows.take(maxRecords)){
            val owner=port.token.preference.operation.owner?:error("owner_required")
            val valid=port.host.claimHistoryDispatch(port.token,server,operation,row,System.currentTimeMillis())
            check(valid is com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Value){"staged_send_changed"}
            if(valid.value==null){
                val decision=port.host.historySendDecision(port.token,server,operation,row,System.currentTimeMillis())
                check(decision is com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Value){"staged_send_identity_changed"}
                when(decision.value){
                    com.cyclecare.app.data.local.rust.RustEnrollmentStage.SendDecision.COOLDOWN,com.cyclecare.app.data.local.rust.RustEnrollmentStage.SendDecision.ACKNOWLEDGED->continue
                    com.cyclecare.app.data.local.rust.RustEnrollmentStage.SendDecision.ACK_REQUIRES_RECONCILIATION->{
                        val reconciled=port.host.reconcileHistoryReceipt(port.token,server,operation,row)
                        check(reconciled is com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Value&&reconciled.value){"staged_receipt_reconciliation_required"}
                        // This row already had a verified remote effect. Adoption
                        // is not another send and does not consume retry budget.
                        continue
                    }
                    else->error("staged_send_requires_recovery:${decision.value}")
                }
            }
            val revision=Math.addExact(row.serverRevision,1L)
            val receipt=transport.apply(V3WireOperation(row.child,wireRecordId(owner,row.record),row.serverRevision,revision,"UPSERT",envelope(row.record,JSONObject(row.payload)),server,row.record),accessToken)
            check(receipt.status in setOf("applied","duplicate")&&receipt.recordId==wireRecordId(owner,row.record)&&receipt.operationId==row.child&&receipt.logicalRecordId==row.record&&receipt.revision==revision&&receipt.payloadHash?.matches(Regex("^[a-f0-9]{64}$"))==true){"invalid_staged_ack"}
            val archived=port.host.archiveHistoryReceipt(valid.value!!,revision,receipt.payloadHash!!)
            check(archived is com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Value&&archived.value){"staged_receipt_archive_rejected"}
            val result=port.host.historyAcknowledge(port.token,server,operation,row,revision,receipt.payloadHash!!,System.currentTimeMillis())
            check(result is com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Value&&result.value){"staged_ack_changed"};sent++
        };return sent
    }
    internal fun applyExplicitRustHistory(server:Long,now:Long):Int {
        val port=rustPort?:error("rust_port_required")
        return when(val result=port.host.journalExisting(port.token,server,UUID.randomUUID().toString(),now)){is com.cyclecare.app.data.local.rust.RustBoundRoomHost.Result.Value->result.value;else->error("rust_history_rejected")}
    }
    private suspend fun applyRemote(db: CycleCareDatabase, id: String, payload: String) {
        when (id) {
            "settings" -> db.cycleDao().saveSettings(HealthSyncPayloadCodec.parseSettingsPayload(payload, id, db.cycleDao().getSettings()))
            "profile" -> db.onboardingDao().insertOrUpdateProfile(HealthSyncPayloadCodec.parseProfilePayload(payload, id, db.onboardingDao().getProfile()))
            else -> db.cycleDao().insertOrUpdateLog(HealthSyncPayloadCodec.parseLogPayload(payload, id))
        }
    }
}

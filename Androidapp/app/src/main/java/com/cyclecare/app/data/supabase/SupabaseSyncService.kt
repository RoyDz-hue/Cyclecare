package com.cyclecare.app.data.supabase

import androidx.room.withTransaction
import com.cyclecare.app.data.local.*
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.Dispatchers
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
    private fun conflictSafeProtocolReleased() = false
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
        val db = databaseProvider.current(); val dao = db.healthSyncDao(); var applied = 0
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
                if (!current(owner, session, local, server)) break
                when (r.status) {
                    "applied", "duplicate" -> {
                        check(r.recordId == wireRecordId(owner, e.recordId) && r.operationId == e.operationId && r.revision == revision && r.logicalRecordId == e.recordId && r.payloadHash?.matches(Regex("^[a-f0-9]{64}$")) == true) { "invalid_ack" }
                        val committed = db.withTransaction {
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
                        db.withTransaction {
                            if (!current(owner, session, local, server)) return@withTransaction
                            val latest = dao.pending(owner).filter { it.recordId == e.recordId }.maxByOrNull { it.revision } ?: e
                            val remoteValue = r.remotePayload?.optJSONObject("value")
                            check(r.remoteTombstone || r.revision == 0L || remoteValue != null) { "invalid_conflict_payload" }
                            dao.putConflict(HealthSyncConflictEntity(owner, e.recordId, latest.operationId, latest.revision, latest.payloadJson, latest.kind, r.revision ?: error("missing_remote_revision"), remoteValue?.toString(), r.remoteTombstone, System.currentTimeMillis()))
                        }
                    }
                    else -> error("v3_operation_rejected")
                }
            } catch (_: Exception) {
                blockedRecords += e.recordId
                if (!current(owner, session, local, server)) break
                dao.retry(owner, e.operationId, System.currentTimeMillis() + (1_000L shl e.attempts.coerceIn(0, 6)), "Sync retry or manual conflict review required")
            }
        }
        applied
    }

    suspend fun syncAll(): SyncResult = withContext(Dispatchers.IO) {
        if (!authPreferences.isCloudSyncAllowed && !authPreferences.hasPendingHealthSyncRevoke)
            return@withContext SyncResult(true, "Cloud sync is disabled; no health request was made.")
        val owner = authPreferences.userId ?: return@withContext SyncResult(false, "An authenticated account is required.")
        if (OwnerDataGate.isWiped(authPreferences.context, owner) && !authPreferences.hasPendingHealthSyncRevoke) return@withContext SyncResult(true, "Local data was wiped; cloud sync remains paused.")
        // A pending withdrawal sends only a consent decision, never a health payload.
        if (!authPreferences.hasPendingHealthSyncRevoke && (!authPreferences.isCloudSyncAllowed || !conflictSafeProtocolReleased()))
            return@withContext SyncResult(false, "Cloud health sync is paused pending V3 release acceptance.")
        val token = when (val state = SupabaseSessionManager.resolveAccessToken(authPreferences)) {
            is AccessTokenResolution.Valid -> state.token
            is AccessTokenResolution.Missing, is AccessTokenResolution.ReauthenticationRequired -> return@withContext SyncResult(false, "Sign in to complete consent revocation or sync.", requiresAuthentication = true)
            AccessTokenResolution.TemporarilyUnavailable -> return@withContext SyncResult(false, "Secure session unavailable; local data remains private.", shouldRetry = true)
        }
        val transport = OkHttpHealthSyncV3Transport(SupabaseConfig.restUrl, SupabaseConfig.anonKey, client)
        try {
            if (authPreferences.hasPendingHealthSyncRevoke) {
                requestV3Consent(false, transport, token)
                return@withContext SyncResult(true, "Cloud sync consent withdrawal confirmed.")
            }
            val local = authPreferences.healthConsentGeneration; val session = authPreferences.sessionGeneration
            val server = authPreferences.serverHealthConsentGeneration ?: return@withContext SyncResult(false, "Explicit V3 consent is required.")
            val pulled = pull(owner, local, server, session, transport, token); val uploaded = executeV3Outbox(transport, token)
            if (!current(owner, session, local, server)) return@withContext SyncResult(false, "Sync stopped because the account or consent changed.")
            val timestamp = System.currentTimeMillis(); val conflicts = databaseProvider.current().healthSyncDao().conflicts(owner).size
            authPreferences.lastSyncTimestamp = timestamp; authPreferences.lastSyncSuccess = conflicts == 0
            val message = if (conflicts > 0) "$conflicts sync conflict(s) need your review; no automatic overwrite was performed." else "V3 sync completed ($uploaded uploaded, $pulled received)."
            authPreferences.lastSyncMessage = message
            SyncResult(conflicts == 0, message, syncedLogsCount = uploaded + pulled, timestamp = timestamp)
        } catch (_: Exception) { SyncResult(false, "Sync or consent withdrawal could not finish. Local changes remain saved; retry is required.", shouldRetry = true) }
    }
    suspend fun pullRemoteUpdates(userId: String): Int = withContext(Dispatchers.IO) {
        if (!conflictSafeProtocolReleased() || !authPreferences.isCloudSyncAllowed) return@withContext 0
        val token = SupabaseSessionManager.validAccessToken(authPreferences) ?: return@withContext 0
        val server = authPreferences.serverHealthConsentGeneration ?: return@withContext 0
        pull(userId, authPreferences.healthConsentGeneration, server, authPreferences.sessionGeneration, OkHttpHealthSyncV3Transport(SupabaseConfig.restUrl, SupabaseConfig.anonKey, client), token)
    }
    private suspend fun pull(owner: String, local: Long, server: Long, session: Long, transport: HealthSyncV3Transport, token: String): Int {
        val db = databaseProvider.current(); val dao = db.healthSyncDao(); var cursor = dao.cursor(owner)?.changeSeq ?: 0L; var count = 0
        while (current(owner, session, local, server)) {
            val page = transport.readChanges(cursor, 100, server, token)
            if (!current(owner, session, local, server) || page.isEmpty()) break
            db.withTransaction {
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
    private suspend fun applyRemote(db: CycleCareDatabase, id: String, payload: String) {
        when (id) {
            "settings" -> db.cycleDao().saveSettings(HealthSyncPayloadCodec.parseSettingsPayload(payload, id, db.cycleDao().getSettings()))
            "profile" -> db.onboardingDao().insertOrUpdateProfile(HealthSyncPayloadCodec.parseProfilePayload(payload, id, db.onboardingDao().getProfile()))
            else -> db.cycleDao().insertOrUpdateLog(HealthSyncPayloadCodec.parseLogPayload(payload, id))
        }
    }
}

package com.cyclecare.app.security

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.cyclecare.app.data.local.OwnerDataGate
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
class AuthPreferences internal constructor(context: Context, unitTestPreferences: SharedPreferences?, unitTestSecure: Boolean) {
    constructor(context: Context) : this(context, null, false)
    val context: Context = context.applicationContext
    // Production construction always uses Android Keystore. The internal seam is
    // solely for JVM state-machine tests, not evidence of hardware encryption.
    private val preferenceStore = unitTestPreferences?.let { PreferenceStore(it, unitTestSecure) }
        ?: createEncryptedOrFallbackPrefs(this.context)
    private val prefs: SharedPreferences = preferenceStore.preferences
    val isSecureStorageAvailable: Boolean = preferenceStore.secure
    private data class PreferenceStore(val preferences: SharedPreferences, val secure: Boolean)

    /** Only explicit candidate construction installs this coordinator. Normal
     * production preferences remain unchanged until full connected cutover.
     */
    @Volatile internal var publicationAdmission:IdentityPublicationAdmission?=null
        private set
    internal fun attachPublicationAdmission(admission:IdentityPublicationAdmission){
        check(publicationAdmission==null||publicationAdmission===admission)
        OwnerDataGate.attachPublicationAdmission(context,admission)
        publicationAdmission=admission
    }
    internal fun mutatePublicationIdentity(action:()->Unit){
        val admission=publicationAdmission
        if(admission==null)action()else admission.mutate(action)
    }
    /** External result may commit only into its original candidate context.
     * Validation and all bundled setters share the serialized admission.
     */
    internal fun mutatePublicationIdentityIfCurrent(expected:RustOperationSnapshot,stillCurrent:()->Boolean={true},action:()->Unit):Boolean{
        if(publicationAdmission==null){action();return true}
        return mutatePublicationResult(false){runIfRustOperationUnchanged(expected){if(stillCurrent()){action();true}else false}?:false}
    }
    private fun <T> mutatePublicationResult(rejected:T,action:()->T):T{
        val admission=publicationAdmission
        return if(admission==null)action()else admission.mutateResult(rejected,action)
    }
    companion object {
        private const val PREFS_SECURE_NAME = "cyclecare_auth_secure_prefs"
        private const val PREFS_LEGACY_NAME = "cyclecare_auth_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ACCOUNT_MODE = "account_mode" // ANONYMOUS or EMAIL
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_SESSION_EXPIRES_AT = "session_expires_at"
        private const val KEY_PENDING_EMAIL = "pending_email"
        private const val KEY_GOOGLE_PKCE_VERIFIER = "google_pkce_verifier"
        private const val KEY_PENDING_AUTH_ACTION = "pending_auth_action"
        private const val KEY_PENDING_GUEST_EMAIL_LINK = "pending_guest_email_link"
        private const val KEY_NEEDS_PASSWORD_SETUP = "needs_password_setup"
        private const val KEY_PIN = "quick_unlock_pin"
        private const val KEY_PIN_ENABLED = "pin_enabled"
        private const val KEY_FINGERPRINT_ENABLED = "fingerprint_enabled"
        private const val KEY_QUICK_UNLOCK_ASKED = "quick_unlock_asked"
        private const val KEY_LAST_SYNC_TIMESTAMP = "last_sync_timestamp"
        private const val KEY_LAST_SYNC_MESSAGE = "last_sync_message"
        private const val KEY_LAST_SYNC_STATUS = "last_sync_status"
        private const val KEY_CLOUD_SYNC_ALLOWED = "cloud_sync_allowed"
        private const val KEY_HEALTH_CONSENT_GENERATION = "health_consent_generation"
        private const val KEY_INITIAL_AUTH_COMPLETED = "initial_auth_completed"
        private const val KEY_ACCOUNT_STATUS = "account_status"
        private const val KEY_SUSPENSION_REASON = "suspension_reason"
        private const val KEY_SUSPENDED_UNTIL = "suspended_until"
        private const val KEY_PENDING_OFFLINE_CLAIM_DATE = "pending_offline_claim_date"
        private const val KEY_PENDING_OFFLINE_CLAIM_AMOUNT = "pending_offline_claim_amount"
        private const val KEY_IS_PENDING_RECONCILIATION = "is_pending_reconciliation"
        private const val KEY_SOUND_ENABLED = "sound_feedback_enabled"
        private const val AI_CONSENT_REVISION = "ai-provider-consent-v1"
        private val AI_PROVIDERS = setOf("COHERE", "GEMINI", "OPENROUTER", "LAJIWA")
        const val AI_PROCESSOR_CONSENT_REVISION: String = "ai-provider-consent-v1"

        // Quick-unlock PIN: PBKDF2 parameters. 150k iterations is deliberately
        // slow for an offline attacker yet under ~200ms on a modern device.
        private const val PBKDF2_PREFIX = "pbkdf2:"
        private const val PBKDF2_ITERATIONS = 150_000
        private const val PBKDF2_MIN_ACCEPTED_ITERATIONS = 50_000
        private const val PBKDF2_MAX_ACCEPTED_ITERATIONS = 2_000_000
        private const val PBKDF2_SALT_BYTES = 16
        private const val PBKDF2_KEY_BITS = 256

        private fun createEncryptedOrFallbackPrefs(ctx: Context): PreferenceStore {
            return try {
                val masterKey = MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val securePrefs = EncryptedSharedPreferences.create(
                    ctx,
                    PREFS_SECURE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                migrateLegacyPrefs(ctx, securePrefs)
                PreferenceStore(securePrefs, secure = true)
            } catch (_: Exception) {
                val legacy = ctx.getSharedPreferences(PREFS_LEGACY_NAME, Context.MODE_PRIVATE)
                // Never keep session credentials or the quick-unlock PIN in the plaintext fallback.
                legacy.edit {
                    remove(KEY_USER_ID)
                    remove(KEY_USER_EMAIL)
                    remove(KEY_ACCESS_TOKEN)
                    remove(KEY_REFRESH_TOKEN)
                    remove(KEY_SESSION_EXPIRES_AT)
                    remove(KEY_PENDING_EMAIL)
                    remove(KEY_GOOGLE_PKCE_VERIFIER)
                    remove(KEY_PENDING_AUTH_ACTION)
                    remove(KEY_PENDING_GUEST_EMAIL_LINK)
                    remove(KEY_NEEDS_PASSWORD_SETUP)
                    remove(KEY_PIN)
                    remove(KEY_PIN_ENABLED)
                    remove(KEY_FINGERPRINT_ENABLED)
                    remove(KEY_QUICK_UNLOCK_ASKED)
                }
                PreferenceStore(legacy, secure = false)
            }
        }

        private fun migrateLegacyPrefs(ctx: Context, securePrefs: SharedPreferences) {
            try {
                val legacy = ctx.getSharedPreferences(PREFS_LEGACY_NAME, Context.MODE_PRIVATE)
                val allLegacy = legacy.all
                if (allLegacy.isNotEmpty()) {
                    securePrefs.edit {
                        for ((key, value) in allLegacy) {
                            if (!securePrefs.contains(key)) {
                                when (value) {
                                    is String -> putString(key, value)
                                    is Boolean -> putBoolean(key, value)
                                    is Int -> putInt(key, value)
                                    is Long -> putLong(key, value)
                                    is Float -> putFloat(key, value)
                                }
                            }
                        }
                    }
                    legacy.edit { clear() }
                }
            } catch (_: Throwable) {}
        }
    }

    /** Local mode is not an authenticated identity and must never create a cloud session. */
    var isLocalOnlyMode: Boolean
        get() = prefs.getBoolean("local_only_mode_v1", false)
        set(value) { mutatePublicationIdentity { synchronized(prefs) {
            if (isLocalOnlyMode != value) prefs.edit().putBoolean("local_only_mode_v1", value)
                .putLong("session_generation_v3", Math.addExact(sessionGeneration, 1L)).commit()
        } } }

    var hasCompletedInitialAuth: Boolean
        get() = isLocalOnlyMode || (isSecureStorageAvailable && prefs.getBoolean(KEY_INITIAL_AUTH_COMPLETED, false))
        set(value) { if (isSecureStorageAvailable || isLocalOnlyMode) prefs.edit { putBoolean(KEY_INITIAL_AUTH_COMPLETED, value) } }

    var isCloudSyncAllowed: Boolean
        get() = prefs.getBoolean("${KEY_CLOUD_SYNC_ALLOWED}_v2_${effectiveUserId}", false)
        set(value) { mutatePublicationIdentity {
            setCloudSyncChoiceWithoutCallouts(value)
            if (!value) {
                runCatching { androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("cyclecare_sync") }
            }
        } }

    /** Caller owns candidate admission; no WorkManager call under domain locks. */
    internal fun setCloudSyncChoiceWithoutCallouts(value:Boolean,finishDisabledChoice:Boolean=false){
        check(publicationAdmission==null||publicationAdmission!!.isTransitionAction())
        val wasAllowed=isCloudSyncAllowed
        var disabledExact:RustOperationSnapshot?=null
        if(finishDisabledChoice&&wasAllowed&&!value){
            val gate=publicationAdmission?:error("candidate_finalizer_requires_admission")
            // Reserve before any durable choice write. Failure cannot consume
            // the choice and then discover no finalizer capacity remains.
            gate.afterCurrentAction{disabledExact?.let(::cancelOwnDisabledSyncChoice)}
        }
            // Same lock as the userId setter and clearSession, so the owner used for the key
            // and the generation advance cannot straddle an identity change.
            synchronized(prefs) {
                val owner = effectiveUserId
                val key = "${KEY_CLOUD_SYNC_ALLOWED}_v2_$owner"
                val previous = prefs.getBoolean(key, false)
                if (previous != value) {
                    // One editor commit makes the consent decision and generation advance
                    // visible together, preventing stale sync workers from ABA reuse.
                    val generationKey = "${KEY_HEALTH_CONSENT_GENERATION}_$owner"
                    val generation = prefs.getLong(generationKey, 0L)
                    val edit = prefs.edit().putBoolean(key, value).putLong(generationKey, Math.addExact(generation, 1L))
                    if (!value) edit.putBoolean("health_sync_pending_revoke_$owner", true)
                    check(edit.commit()){"cloud_choice_commit_failed"}
                }
            }
        if(finishDisabledChoice&&wasAllowed&&!value)disabledExact=rustOperationSnapshot()
    }

    /** Explicit retry of THIS host's uncertain consent write. Rewrites exact
     * expected values without another generation advance; commit success is
     * required before the host can publish any replacement capability. */
    internal fun persistExactCloudChoice(expected:RustOperationSnapshot,enabled:Boolean):Boolean {
        check(publicationAdmission?.isTransitionAction()==true)
        return synchronized(prefs){
            if(rustOperationSnapshot()!=expected||isCloudSyncAllowed!=enabled)return@synchronized false
            val owner=expected.operation.effectiveUserId
            val editor=prefs.edit().putBoolean("${KEY_CLOUD_SYNC_ALLOWED}_v2_$owner",enabled)
                .putLong("${KEY_HEALTH_CONSENT_GENERATION}_$owner",expected.operation.consentGeneration)
            if(!enabled)editor.putBoolean("health_sync_pending_revoke_$owner",true)
            editor.commit()
        }
    }

    /** Finalizer of an exact own choice. Serialized before later admission;
     * never cancels a replacement identity or a later enabled choice. */
    internal fun cancelOwnDisabledSyncChoice(expected:RustOperationSnapshot){
        if(rustOperationSnapshot()!=expected||isCloudSyncAllowed)return
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork("cyclecare_daily_supabase_sync")
    }

    /** Candidate explicit erasure helper: no WorkManager call while identity
     * and lifecycle/Room monitors are held. Existing public setter unchanged.
     */
    fun pauseHealthSyncForErasureWithoutCallouts(owner:String?,session:Long):Boolean= mutatePublicationResult(false) { synchronized(prefs){
        check(userId==owner&&sessionGeneration==session){"owner_changed"}
        val effective=effectiveUserId;val allowed="${KEY_CLOUD_SYNC_ALLOWED}_v2_$effective";val generation="${KEY_HEALTH_CONSENT_GENERATION}_$effective"
        val was=prefs.getBoolean(allowed,false);val before=prefs.getLong(generation,0L)
        check(prefs.edit().putBoolean(allowed,false).putLong(generation,if(was)Math.addExact(before,1L)else before).putBoolean("health_sync_pending_revoke_$effective",true).commit())
        true
    } }
    fun cancelHealthSyncWorkAfterErasure(){runCatching{androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("cyclecare_sync")}}

    /** Durable, account-bound cloud health-consent generation for sync CAS. */
    val healthConsentGeneration: Long
        get() = prefs.getLong("${KEY_HEALTH_CONSENT_GENERATION}_$effectiveUserId", 0L)

    val hasPendingHealthSyncRevoke: Boolean
        get() = isSecureStorageAvailable && prefs.getBoolean("health_sync_pending_revoke_$effectiveUserId", false)
    fun markPendingHealthSyncRevoke() = mutatePublicationIdentity {
        // Same prefs monitor the sync-identity snapshots and dispatch guards hold
        // through admitted SQL (admission-before-prefs order, established): a revoke
        // can no longer interleave between a guard read and the guarded SQL and
        // surface as a mistyped storage-looking failure. Commit-result semantics
        // unchanged (unchecked, no durability claim).
        if (isSecureStorageAvailable) synchronized(prefs) { prefs.edit().putBoolean("health_sync_pending_revoke_$effectiveUserId", true).commit() }
    }
    fun pauseHealthSyncFor(owner: String, session: Long): Long? = mutatePublicationResult(null) {
      val result=synchronized(prefs) {
        if (!isSecureStorageAvailable || userId != owner || sessionGeneration != session) return@synchronized null
        val allowedKey = "${KEY_CLOUD_SYNC_ALLOWED}_v2_$owner"
        val generationKey = "${KEY_HEALTH_CONSENT_GENERATION}_$owner"
        val previousGeneration = prefs.getLong(generationKey, 0L)
        val generation = if (prefs.getBoolean(allowedKey, false)) Math.addExact(previousGeneration, 1L) else previousGeneration
        val committed = prefs.edit().putBoolean(allowedKey, false)
            .putLong(generationKey, generation).putBoolean("health_sync_pending_revoke_$owner", true)
            .remove("health_sync_receipt_server_$owner").remove("health_sync_receipt_local_$owner")
            .remove("health_sync_receipt_session_$owner").commit()
        if (!committed) return@synchronized null
        generation
      }
      if(result!=null)cancelHealthSyncWorkAfterErasure()
      result
    }
    val serverHealthConsentGeneration: Long?
        get() {
            val owner = effectiveUserId
            if (!isSecureStorageAvailable || !isCloudSyncAllowed || hasPendingHealthSyncRevoke || prefs.getLong("health_sync_receipt_local_$owner", -1) != healthConsentGeneration || prefs.getLong("health_sync_receipt_session_$owner", -1) != sessionGeneration) return null
            return prefs.getLong("health_sync_receipt_server_$owner", 0).takeIf { it > 0 }
        }
    fun saveServerHealthConsentReceipt(owner: String, session: Long, localGeneration: Long, serverGeneration: Long, enabled: Boolean): Boolean = mutatePublicationResult(false) {  synchronized(prefs) {
        if (!isSecureStorageAvailable || userId != owner || sessionGeneration != session || healthConsentGeneration != localGeneration || serverGeneration <= 0 || (enabled && (!isCloudSyncAllowed || hasPendingHealthSyncRevoke || OwnerDataGate.isWiped(context, owner)))) return@synchronized false
        val edit = prefs.edit().putBoolean("health_sync_pending_revoke_$owner", false)
        if (enabled) edit.putLong("health_sync_receipt_server_$owner", serverGeneration).putLong("health_sync_receipt_local_$owner", localGeneration).putLong("health_sync_receipt_session_$owner", session)
        else edit.remove("health_sync_receipt_server_$owner").remove("health_sync_receipt_local_$owner").remove("health_sync_receipt_session_$owner")
        edit.commit()
    } }

    private fun providerKey(provider: String, suffix: String): String =
        "ai_processor_${provider}_${suffix}_$effectiveUserId"

    /** Consent belongs to one identity and is never inherited during account switching. */
    /**
     * Legacy aggregate switch. New UI must use providerConsent()/setProviderConsent()
     * so a processor is never silently inferred. It is retained for existing callers
     * and deliberately grants/revokes every known processor only when the caller
     * explicitly changes this aggregate setting.
     */
    var isAiSharingAllowed: Boolean
        // Aggregate availability is not a grant for every processor. Dispatch still
        // requires the selected processor's current authoritative receipt.
        get() = AI_PROVIDERS.any { providerConsent(it).granted }
        set(value) {
            if (value) AI_PROVIDERS.forEach { setProviderConsent(it, true) }
            else revokeAllProviderConsents()
        }

    data class ProviderConsentSnapshot(
        val provider: String,
        val granted: Boolean,
        val revision: String,
        val accountId: String
    )

    fun providerConsent(provider: String): ProviderConsentSnapshot {
        val normalized = provider.trim().uppercase()
        if (normalized !in AI_PROVIDERS) {
            return ProviderConsentSnapshot(normalized, false, "", effectiveUserId)
        }
        val owner = effectiveUserId
        val revision = prefs.getString(providerKey(normalized, "revision"), null).orEmpty()
        val account = prefs.getString(providerKey(normalized, "account"), null).orEmpty()
        val granted = isSecureStorageAvailable && prefs.getBoolean(providerKey(normalized, "granted"), false)
        return ProviderConsentSnapshot(normalized, granted && account == owner && revision == AI_CONSENT_REVISION, revision, account)
    }

    fun providerConsents(): Map<String, ProviderConsentSnapshot> =
        AI_PROVIDERS.associateWith { providerConsent(it) }

    fun setProviderConsent(provider: String, granted: Boolean) {
        val normalized = provider.trim().uppercase()
        if (normalized !in AI_PROVIDERS || !isSecureStorageAvailable) return
        val owner = effectiveUserId
        prefs.edit {
            putBoolean(providerKey(normalized, "granted"), granted)
            putString(providerKey(normalized, "revision"), AI_CONSENT_REVISION)
            putString(providerKey(normalized, "account"), owner)
        }
    }

    fun revokeAllProviderConsents() {
        // Aggregate withdrawal is also used by account/health deletion paths.
        // Clear receipts and enqueue server CAS revocations, not just the UI
        // preference, so a previously granted processor cannot remain usable.
        AI_PROVIDERS.forEach {
            setProviderConsent(it, false)
            revokeProcessorReceipt(it)
        }
    }

    data class ProcessorReceipt(val providerId: String, val revision: Int, val manifestFingerprint: String, val accountId: String)

    private fun receiptKey(provider: String, suffix: String) = "ai_receipt_${provider.trim().uppercase()}_${suffix}_$effectiveUserId"
    private fun pendingRevokeKey() = "ai_pending_revokes_$effectiveUserId"

    data class ConsentMutationSnapshot(val owner: String, val session: Long, val decision: Long, val healthGeneration: Long)
    val sessionGeneration: Long get() = prefs.getLong("session_generation_v3", 0L)

    /** Owner, session, effective account id and that account's consent generation, read under one lock. */
    data class OperationSnapshot(val owner: String?, val session: Long, val effectiveUserId: String, val consentGeneration: Long)

    /**
     * Reads the identity and the consent generation under the same lock the userId
     * setter, clearSession, the cloud-sync consent setter and pauseHealthSyncFor use,
     * so the four values always belong together (no read-order reasoning needed).
     */
    fun operationSnapshot(): OperationSnapshot = synchronized(prefs) {
        OperationSnapshot(userId, sessionGeneration, effectiveUserId, healthConsentGeneration)
    }

    /** Non-suspending IO-only candidate guard: preferences -> host router -> DB.
     * No coroutine suspension or main-thread DB operation is allowed in block.
     * Existing callers are unchanged; candidate adapter must not activate until
     * all lifecycle/wipe/writers share this publication protocol.
     */
    data class RustOperationSnapshot(val operation: OperationSnapshot, val localOnly: Boolean, val accountMode: String)
    fun rustOperationSnapshot(): RustOperationSnapshot = synchronized(prefs) {
        RustOperationSnapshot(operationSnapshot(), isLocalOnlyMode, accountMode)
    }
    fun <T> runIfRustOperationUnchanged(expected: RustOperationSnapshot, block: () -> T): T? = synchronized(prefs) {
        if (rustOperationSnapshot() == expected) block() else null
    }

    /** Coherent sync identity: owner, session, local AND server consent
     * generation under the same prefs lock (no read-order reasoning needed).
     * Pending-revoke writes remain outside this lock (stated gap). */
    data class SyncIdentitySnapshot(val owner: String?, val session: Long, val localConsentGeneration: Long, val serverConsentGeneration: Long?)
    internal fun syncIdentitySnapshot(): SyncIdentitySnapshot = synchronized(prefs) {
        SyncIdentitySnapshot(userId, sessionGeneration, healthConsentGeneration, serverHealthConsentGeneration)
    }

    /** Sync-dispatch guard: like runIfRustOperationUnchanged, plus the server
     * consent generation inside the SAME lock, so a receipt/withdrawal cannot
     * land between the operation snapshot and the server-generation read. */
    internal fun <T> runIfRustSyncUnchanged(expected: RustOperationSnapshot, serverGeneration: Long, block: () -> T): T? = synchronized(prefs) {
        if (rustOperationSnapshot() == expected && serverHealthConsentGeneration == serverGeneration) block() else null
    }

    /**
     * Runs [block] only if the account and session are still exactly [owner] and
     * [session], holding that same lock, so an identity change (userId, clearSession) or a
     * cloud-sync consent / account mode change through the synchronized setters cannot
     * land in the middle of [block]. markPendingHealthSyncRevoke takes this lock;
     * provider consents do not and are NOT covered. Returns null (block not run) when
     * stale. Atomic with preference changes only; not atomic with any database write.
     */
    fun <T> runIfIdentityUnchanged(owner: String?, session: Long, block: () -> T): T? = synchronized(prefs) {
        if (userId == owner && sessionGeneration == session) block() else null
    }

    /** Execute an owner-bound mutation only while the captured login session is current. */
    internal inline fun <T> withCurrentSession(owner: String, session: Long, block: () -> T): T? =
        synchronized(prefs) {
            if (!isSecureStorageAvailable || userId != owner || sessionGeneration != session) null else block()
        }
    private fun decisionKey(provider: String) = receiptKey(provider, "decision_generation")
    fun beginProcessorConsentMutation(provider: String, enabled: Boolean): ConsentMutationSnapshot? = mutatePublicationResult(null) {  synchronized(prefs) {
        val id = provider.trim().uppercase()
        if (!isSecureStorageAvailable || id !in AI_PROVIDERS || (enabled && id in pendingProcessorRevokes())) return@synchronized null
        val next = Math.addExact(prefs.getLong(decisionKey(id), 0L), 1L)
        if (!prefs.edit().putLong(decisionKey(id), next).commit()) return@synchronized null
        if (!enabled) revokeProcessorReceipt(id)
        ConsentMutationSnapshot(effectiveUserId, sessionGeneration, next, healthConsentGeneration)
    } }
    fun completeProcessorConsentMutation(snapshot: ConsentMutationSnapshot, provider: String, receipt: com.cyclecare.app.domain.ai.AiProcessorReceipt?): Boolean = mutatePublicationResult(false) {  synchronized(prefs) {
        val id = provider.trim().uppercase()
        if (!isSecureStorageAvailable || id !in AI_PROVIDERS || effectiveUserId != snapshot.owner || sessionGeneration != snapshot.session || healthConsentGeneration != snapshot.healthGeneration || prefs.getLong(decisionKey(id), 0L) != snapshot.decision || OwnerDataGate.isWiped(context, snapshot.owner)) return@synchronized false
        if (receipt != null && (receipt.providerId != id || receipt.revision < 1 || !Regex("^[a-f0-9]{64}$").matches(receipt.manifestFingerprint))) return@synchronized false
        if (receipt != null && id in pendingProcessorRevokes()) return@synchronized false
        val pending = pendingProcessorRevokes().toMutableSet().also { it.remove(id) }
        val editor = prefs.edit().putString(pendingRevokeKey(), JSONArray(pending.toList()).toString())
        if (receipt != null) editor.putInt(receiptKey(id, "revision"), receipt.revision).putString(receiptKey(id, "fingerprint"), receipt.manifestFingerprint).putString(receiptKey(id, "account"), snapshot.owner)
        editor.commit()
    } }

    fun saveProcessorReceipt(receipt: com.cyclecare.app.domain.ai.AiProcessorReceipt) {
        if (!isSecureStorageAvailable || receipt.providerId.isBlank() || receipt.revision < 0 || receipt.manifestFingerprint.isBlank()) return
        prefs.edit {
            putInt(receiptKey(receipt.providerId, "revision"), receipt.revision)
            putString(receiptKey(receipt.providerId, "fingerprint"), receipt.manifestFingerprint)
            putString(receiptKey(receipt.providerId, "account"), effectiveUserId)
        }
        clearPendingProcessorRevoke(receipt.providerId)
    }

    fun processorReceipt(provider: String): com.cyclecare.app.domain.ai.AiProcessorReceipt? {
        if (!isSecureStorageAvailable) return null
        val id = provider.trim().uppercase(); val owner = effectiveUserId
        if (prefs.getString(receiptKey(id, "account"), null) != owner) return null
        val fp = prefs.getString(receiptKey(id, "fingerprint"), null).orEmpty()
        val revision = prefs.getInt(receiptKey(id, "revision"), -1)
        return if (fp.isNotBlank() && revision >= 0) com.cyclecare.app.domain.ai.AiProcessorReceipt(id, revision, fp) else null
    }

    fun revokeProcessorReceipt(provider: String) {
        if (!isSecureStorageAvailable) return
        val id = provider.trim().uppercase()
        prefs.edit {
            remove(receiptKey(id, "revision"))
            remove(receiptKey(id, "fingerprint"))
            putString(receiptKey(id, "account"), effectiveUserId)
        }
        val current = pendingProcessorRevokes().toMutableSet(); current += id
        prefs.edit().putString(pendingRevokeKey(), JSONArray(current.toList()).toString()).commit()
    }

    fun clearPendingProcessorRevoke(provider: String) {
        if (!isSecureStorageAvailable) return
        val id = provider.trim().uppercase(); val current = pendingProcessorRevokes().toMutableSet(); current.remove(id)
        prefs.edit { putString(pendingRevokeKey(), JSONArray(current.toList()).toString()) }
    }

    fun pendingProcessorRevokes(): Set<String> {
        if (!isSecureStorageAvailable) return emptySet()
        return try { val a = JSONArray(prefs.getString(pendingRevokeKey(), "[]")); buildSet { for (i in 0 until a.length()) add(a.getString(i).uppercase()) } } catch (_: Exception) { emptySet() }
    }

    /** JSON map of dynamic onboarding question id -> selected options. */
    var onboardingDynamicInsightsJson: String
        get() = prefs.getString("onboarding_dynamic_insights_v1", "{}") ?: "{}"
        set(value) = prefs.edit { putString("onboarding_dynamic_insights_v1", value) }

    var isSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_SOUND_ENABLED, value) }

    val deviceId: String
        get() {
            var id = prefs.getString(KEY_DEVICE_ID, null)
            if (id.isNullOrBlank()) {
                id = UUID.randomUUID().toString()
                prefs.edit { putString(KEY_DEVICE_ID, id) }
            }
            return id
        }

    var accountMode: String
        get() = prefs.getString(KEY_ACCOUNT_MODE, "ANONYMOUS") ?: "ANONYMOUS"
        set(value) = mutatePublicationIdentity { synchronized(prefs) {
            if (accountMode != value) prefs.edit().putString(KEY_ACCOUNT_MODE, value)
                .putLong("session_generation_v3", Math.addExact(sessionGeneration, 1L)).commit()
            Unit
        } }

    var userId: String?
        get() = readSecureString(KEY_USER_ID)
        set(value) { mutatePublicationIdentity { synchronized(prefs) {
            // Identity and its generation must become durable TOGETHER. The
            // former generation-only commit followed by apply(user_id) could
            // acknowledge sign-out before the identity removal reached disk.
            val stored = value?.takeIf { isSecureStorageAvailable && it.isNotBlank() }
            val edit = prefs.edit()
            if (readSecureString(KEY_USER_ID) != stored)
                edit.putLong("session_generation_v3", Math.addExact(sessionGeneration, 1L))
            if (stored == null) edit.remove(KEY_USER_ID) else edit.putString(KEY_USER_ID, stored)
            check(edit.commit()) { "identity_commit_failed" }
        } } }

    var userEmail: String?
        get() = readSecureString(KEY_USER_EMAIL)
        set(value) = writeSecureString(KEY_USER_EMAIL, value)

    var accessToken: String?
        get() = readSecureString(KEY_ACCESS_TOKEN)
        set(value) = writeSecureString(KEY_ACCESS_TOKEN, value)

    var refreshToken: String?
        get() = readSecureString(KEY_REFRESH_TOKEN)
        set(value) = writeSecureString(KEY_REFRESH_TOKEN, value)

    var sessionExpiresAtSeconds: Long
        get() = if (isSecureStorageAvailable) prefs.getLong(KEY_SESSION_EXPIRES_AT, 0L) else 0L
        set(value) = mutatePublicationIdentity { if (isSecureStorageAvailable) prefs.edit { putLong(KEY_SESSION_EXPIRES_AT, value) } }

    var pendingEmail: String?
        get() = readSecureString(KEY_PENDING_EMAIL)
        set(value) = writeSecureString(KEY_PENDING_EMAIL, value)

    var googlePkceVerifier: String?
        get() = readSecureString(KEY_GOOGLE_PKCE_VERIFIER)
        set(value) = writeSecureString(KEY_GOOGLE_PKCE_VERIFIER, value)

    var pendingAuthAction: String?
        get() = readSecureString(KEY_PENDING_AUTH_ACTION)
        set(value) = writeSecureString(KEY_PENDING_AUTH_ACTION, value)

    var isPendingGuestEmailLink: Boolean
        get() = isSecureStorageAvailable && prefs.getBoolean(KEY_PENDING_GUEST_EMAIL_LINK, false)
        set(value) { if (isSecureStorageAvailable) prefs.edit { putBoolean(KEY_PENDING_GUEST_EMAIL_LINK, value) } }

    var needsPasswordSetup: Boolean
        get() = isSecureStorageAvailable && prefs.getBoolean(KEY_NEEDS_PASSWORD_SETUP, false)
        set(value) { if (isSecureStorageAvailable) prefs.edit { putBoolean(KEY_NEEDS_PASSWORD_SETUP, value) } }

    var pin: String?
        get() = readSecureString(KEY_PIN)
        set(value) {
            if (!isSecureStorageAvailable) return
            if (value.isNullOrBlank()) {
                prefs.edit { remove(KEY_PIN) }
            } else {
                prefs.edit { putString(KEY_PIN, nativeEncodePinPbkdf2(value) ?: encodePinPbkdf2(value)) }
            }
        }

    /** Migration handoff only: whatever the store currently holds, end in ONE
     * synchronous, disk-acknowledged commit of the verified encoding plus the
     * enabled flag, then verify by readback. Ordinary setters use asynchronous
     * apply(), and a failed commit(false) can leave memory ahead of disk; a
     * migration that is about to DROP the plaintext SQL copy must never depend
     * on unacknowledged persistence of either key.
     *
     * ENABLEMENT POLICY (grounded in the original migration + PIN writers):
     * - EMPTY store: the original 4->5 migration set isPinEnabled=true when it
     *   copied a plaintext appLockPin into an empty store, so this branch
     *   commits enabled=true.
     * - EXISTING store: the original migration did NOTHING to a non-empty
     *   store, and the app's PIN writers keep PIN and enablement separate
     *   (CycleCareViewModel.setPinProtectionEnabled flips isPinEnabled without
     *   touching the stored PIN; cycle_settings carries its own
     *   isAppLockEnabled). A stored PIN alone therefore does NOT establish
     *   lock-on, so this branch preserves the store's current enablement
     *   EXACTLY: the flag is committed back at its current value (never
     *   flipped), which also closes the memory-ahead-of-disk window for the
     *   flag itself.
     * An existing store holding a DIFFERENT PIN fails closed: deliberately
     * STRICTER than the original migration (which silently skipped any
     * non-empty store). That is an intentional safety behavior, not parity:
     * the plaintext SQL copy is about to be dropped, so an unverifiable
     * existing store must abort the upgrade rather than be trusted.
     * Returns false
     * when secure storage is unavailable, the store conflicts, the commit
     * fails, or the readback does not verify - the caller then fails the
     * migration closed. */
    internal fun ensureMigratedPinDurable(rawPin: String): Boolean {
        if (!isSecureStorageAvailable) return false
        val existing = pin
        val chosen: String
        val enabled: Boolean
        if (existing.isNullOrBlank()) {
            chosen = nativeEncodePinPbkdf2(rawPin) ?: encodePinPbkdf2(rawPin)
            enabled = true
        } else {
            if (!verifyPin(rawPin)) return false
            // Re-read after verification: a legacy-format store was just
            // re-encoded in memory by verifyPin, and the durable commit must
            // carry the CURRENT encoding, never the legacy value.
            chosen = pin ?: return false
            // Preserve, never flip: the existing store's enablement is its own
            // policy state (see the kdoc); commit it back unchanged so the
            // flag is as durable as the encoding.
            enabled = isPinEnabled
        }
        val committed = prefs.edit().putString(KEY_PIN, chosen).putBoolean(KEY_PIN_ENABLED, enabled).commit()
        return committed && verifyPin(rawPin) && isPinEnabled == enabled
    }

    fun verifyPin(candidate: String): Boolean {
        val stored = readSecureString(KEY_PIN) ?: return false
        // Native core port first (identical chain + iteration bounds + legacy
        // order); on native verdict use it, on null fall through to Kotlin.
        when (com.cyclecare.app.rust.CycleCareCoreBridge.verifyPin(stored, candidate, deviceId)) {
            "current" -> return true
            "legacy_migrate" -> { pin = candidate; return true }
            "rejected" -> return false
        }
        // Current format: salted, slow PBKDF2 - resists offline brute force.
        if (stored.startsWith(PBKDF2_PREFIX)) {
            return verifyPinPbkdf2(stored, candidate)
        }
        // Legacy formats below. A successful legacy verify re-encodes the PIN
        // with PBKDF2 immediately, so every device migrates on first unlock.
        if (stored == candidate) {
            pin = candidate
            return true
        }
        if (stored == hashPinLegacyDevice(candidate)) {
            pin = candidate
            return true
        }
        if (stored == hashPinLegacySalt(candidate)) {
            pin = candidate
            return true
        }
        return false
    }

    /** Native encode with a fresh SecureRandom salt; null when native is down. */
    private fun nativeEncodePinPbkdf2(raw: String): String? {
        val salt = ByteArray(PBKDF2_SALT_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        return com.cyclecare.app.rust.CycleCareCoreBridge.encodePin(raw, salt.toHex())
    }

    private fun encodePinPbkdf2(raw: String): String {
        return try {
            val salt = ByteArray(PBKDF2_SALT_BYTES).also { java.security.SecureRandom().nextBytes(it) }
            val hash = pbkdf2(raw, salt, PBKDF2_ITERATIONS)
            PBKDF2_PREFIX + PBKDF2_ITERATIONS + ":" + salt.toHex() + ":" + hash.toHex()
        } catch (_: Exception) {
            // PBKDF2WithHmacSHA256 is guaranteed on the JVM and Android; reaching
            // this means the runtime is broken, so fail closed rather than
            // silently downgrading to a fast hash.
            throw IllegalStateException("PBKDF2 unavailable; refusing to store a weak PIN hash")
        }
    }

    private fun verifyPinPbkdf2(stored: String, candidate: String): Boolean {
        return try {
            val parts = stored.removePrefix(PBKDF2_PREFIX).split(":")
            if (parts.size != 3) return false
            val iterations = parts[0].toInt()
            if (iterations < PBKDF2_MIN_ACCEPTED_ITERATIONS || iterations > PBKDF2_MAX_ACCEPTED_ITERATIONS) return false
            val salt = parts[1].hexToBytes()
            val expected = parts[2].hexToBytes()
            val actual = pbkdf2(candidate, salt, iterations)
            MessageDigest.isEqual(expected, actual)
        } catch (_: Exception) {
            false
        }
    }

    private fun pbkdf2(raw: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(raw.toCharArray(), salt, iterations, PBKDF2_KEY_BITS)
        return try {
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun hashPinLegacyDevice(raw: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest("cyclecare_${deviceId}_$raw".toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    private fun hashPinLegacySalt(raw: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest("cyclecare_salt_$raw".toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    var isPinEnabled: Boolean
        get() = isSecureStorageAvailable && prefs.getBoolean(KEY_PIN_ENABLED, false)
        set(value) { if (isSecureStorageAvailable) prefs.edit { putBoolean(KEY_PIN_ENABLED, value) } }

    var isFingerprintEnabled: Boolean
        get() = isSecureStorageAvailable && prefs.getBoolean(KEY_FINGERPRINT_ENABLED, false)
        set(value) { if (isSecureStorageAvailable) prefs.edit { putBoolean(KEY_FINGERPRINT_ENABLED, value) } }

    var isQuickUnlockAsked: Boolean
        get() = isSecureStorageAvailable && prefs.getBoolean(KEY_QUICK_UNLOCK_ASKED, false)
        set(value) { if (isSecureStorageAvailable) prefs.edit { putBoolean(KEY_QUICK_UNLOCK_ASKED, value) } }

    /** Backup-status publication: ONE editor for all three status fields,
     * written only while the original request scope (owner/session/local/
     * server) is still current under the prefs lock. Returns false - nothing
     * written - when stale. apply() parity with the individual setters: the
     * return value reports publication, not durable persistence. Pending-
     * revoke writes remain outside this lock (stated gap). */
    fun publishBackupStatusIfCurrent(owner: String, session: Long, local: Long, server: Long, timestamp: Long, message: String, success: Boolean): Boolean =
        runIfIdentityUnchanged(owner, session) {
            if (healthConsentGeneration != local || serverHealthConsentGeneration != server || !isCloudSyncAllowed || OwnerDataGate.isWiped(context, owner)) false
            else {
                prefs.edit().putLong(KEY_LAST_SYNC_TIMESTAMP, timestamp).putString(KEY_LAST_SYNC_MESSAGE, message).putBoolean(KEY_LAST_SYNC_STATUS, success).apply()
                true
            }
        } ?: false

    var lastSyncTimestamp: Long
        get() = prefs.getLong(KEY_LAST_SYNC_TIMESTAMP, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_SYNC_TIMESTAMP, value) }

    var lastSyncMessage: String
        get() = prefs.getString(KEY_LAST_SYNC_MESSAGE, "Never synced") ?: "Never synced"
        set(value) = prefs.edit { putString(KEY_LAST_SYNC_MESSAGE, value) }

    var lastSyncSuccess: Boolean
        get() = prefs.getBoolean(KEY_LAST_SYNC_STATUS, false)
        set(value) = prefs.edit { putBoolean(KEY_LAST_SYNC_STATUS, value) }

    val isAuthenticated: Boolean
        get() = isSecureStorageAvailable && !accessToken.isNullOrBlank()

    val effectiveUserId: String
        get() {
            val uid = userId
            return if (!uid.isNullOrBlank()) {
                uid
            } else {
                try {
                    UUID.nameUUIDFromBytes(deviceId.toByteArray()).toString()
                } catch (e: Exception) {
                    deviceId
                }
            }
        }

    var accountStatus: String
        get() = prefs.getString(KEY_ACCOUNT_STATUS, "ACTIVE") ?: "ACTIVE"
        set(value) = prefs.edit { putString(KEY_ACCOUNT_STATUS, value) }

    var suspensionReason: String?
        get() = prefs.getString(KEY_SUSPENSION_REASON, null)
        set(value) = prefs.edit { putString(KEY_SUSPENSION_REASON, value) }

    var suspendedUntil: String?
        get() = prefs.getString(KEY_SUSPENDED_UNTIL, null)
        set(value) = prefs.edit { putString(KEY_SUSPENDED_UNTIL, value) }

    var pendingOfflineClaimDate: String?
        get() = prefs.getString(KEY_PENDING_OFFLINE_CLAIM_DATE, null)
        set(value) = prefs.edit { putString(KEY_PENDING_OFFLINE_CLAIM_DATE, value) }

    var pendingOfflineClaimAmount: Long
        get() = prefs.getLong(KEY_PENDING_OFFLINE_CLAIM_AMOUNT, 0L)
        set(value) = prefs.edit { putLong(KEY_PENDING_OFFLINE_CLAIM_AMOUNT, value) }

    var isPendingReconciliation: Boolean
        get() = prefs.getBoolean(KEY_IS_PENDING_RECONCILIATION, false)
        set(value) = prefs.edit { putBoolean(KEY_IS_PENDING_RECONCILIATION, value) }

    fun recordOfflineClaim(claimDate: String, amount: Long) {
        prefs.edit {
            putString(KEY_PENDING_OFFLINE_CLAIM_DATE, claimDate)
            putLong(KEY_PENDING_OFFLINE_CLAIM_AMOUNT, amount)
            putBoolean(KEY_IS_PENDING_RECONCILIATION, true)
        }
    }

    fun clearPendingOfflineClaim() {
        prefs.edit {
            remove(KEY_PENDING_OFFLINE_CLAIM_DATE)
            remove(KEY_PENDING_OFFLINE_CLAIM_AMOUNT)
            putBoolean(KEY_IS_PENDING_RECONCILIATION, false)
        }
    }

    fun setAccountRestriction(status: String, reason: String?, until: String?) {
        prefs.edit {
            putString(KEY_ACCOUNT_STATUS, status)
            putString(KEY_SUSPENSION_REASON, reason)
            putString(KEY_SUSPENDED_UNTIL, until)
        }
    }

    fun clearSession() = mutatePublicationIdentity { synchronized(prefs) {
        prefs.edit {
            putLong("session_generation_v3", Math.addExact(sessionGeneration, 1L))
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_SESSION_EXPIRES_AT)
            remove(KEY_GOOGLE_PKCE_VERIFIER)
            remove(KEY_USER_ID)
            remove(KEY_USER_EMAIL)
            remove(KEY_PENDING_EMAIL)
            remove(KEY_PENDING_AUTH_ACTION)
            putBoolean(KEY_PENDING_GUEST_EMAIL_LINK, false)
            putBoolean(KEY_NEEDS_PASSWORD_SETUP, false)
            putString(KEY_ACCOUNT_MODE, "ANONYMOUS")
        }
    } }

    private fun readSecureString(key: String): String? =
        if (isSecureStorageAvailable) prefs.getString(key, null) else null

    private fun writeSecureString(key: String, value: String?) = mutatePublicationIdentity {
        prefs.edit {
            if (isSecureStorageAvailable && !value.isNullOrBlank()) putString(key, value)
            else remove(key)
        }
    }
}


package com.example.security

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.data.local.OwnerDataGate
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
        private val AI_PROVIDERS = setOf("COHERE", "GEMINI", "OPENROUTER")
        const val AI_PROCESSOR_CONSENT_REVISION: String = "ai-provider-consent-v1"

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
        set(value) { prefs.edit().putBoolean("local_only_mode_v1", value).commit() }

    var hasCompletedInitialAuth: Boolean
        get() = isLocalOnlyMode || (isSecureStorageAvailable && prefs.getBoolean(KEY_INITIAL_AUTH_COMPLETED, false))
        set(value) { if (isSecureStorageAvailable || isLocalOnlyMode) prefs.edit { putBoolean(KEY_INITIAL_AUTH_COMPLETED, value) } }

    var isCloudSyncAllowed: Boolean
        get() = prefs.getBoolean("${KEY_CLOUD_SYNC_ALLOWED}_v2_${effectiveUserId}", false)
        set(value) {
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
                edit.commit()
            }
            if (!value) {
                runCatching { androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("cyclecare_sync") }
            }
        }

    /** Durable, account-bound cloud health-consent generation for sync CAS. */
    var healthConsentGeneration: Long
        get() = prefs.getLong("${KEY_HEALTH_CONSENT_GENERATION}_$effectiveUserId", 0L)
        set(value) { prefs.edit().putLong("${KEY_HEALTH_CONSENT_GENERATION}_$effectiveUserId", value).commit() }

    val hasPendingHealthSyncRevoke: Boolean
        get() = isSecureStorageAvailable && prefs.getBoolean("health_sync_pending_revoke_$effectiveUserId", false)
    fun markPendingHealthSyncRevoke() {
        if (isSecureStorageAvailable) prefs.edit().putBoolean("health_sync_pending_revoke_$effectiveUserId", true).commit()
    }
    fun pauseHealthSyncFor(owner: String, session: Long): Long? = synchronized(prefs) {
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
        runCatching { androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("cyclecare_sync") }
        generation
    }
    val serverHealthConsentGeneration: Long?
        get() {
            val owner = effectiveUserId
            if (!isSecureStorageAvailable || !isCloudSyncAllowed || hasPendingHealthSyncRevoke || prefs.getLong("health_sync_receipt_local_$owner", -1) != healthConsentGeneration || prefs.getLong("health_sync_receipt_session_$owner", -1) != sessionGeneration) return null
            return prefs.getLong("health_sync_receipt_server_$owner", 0).takeIf { it > 0 }
        }
    fun saveServerHealthConsentReceipt(owner: String, session: Long, localGeneration: Long, serverGeneration: Long, enabled: Boolean): Boolean = synchronized(prefs) {
        if (!isSecureStorageAvailable || userId != owner || sessionGeneration != session || healthConsentGeneration != localGeneration || serverGeneration <= 0 || (enabled && (!isCloudSyncAllowed || hasPendingHealthSyncRevoke || OwnerDataGate.isWiped(context, owner)))) return@synchronized false
        val edit = prefs.edit().putBoolean("health_sync_pending_revoke_$owner", false)
        if (enabled) edit.putLong("health_sync_receipt_server_$owner", serverGeneration).putLong("health_sync_receipt_local_$owner", localGeneration).putLong("health_sync_receipt_session_$owner", session)
        else edit.remove("health_sync_receipt_server_$owner").remove("health_sync_receipt_local_$owner").remove("health_sync_receipt_session_$owner")
        edit.commit()
    }

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

    /** Execute an owner-bound mutation only while the captured login session is current. */
    internal inline fun <T> withCurrentSession(owner: String, session: Long, block: () -> T): T? =
        synchronized(prefs) {
            if (!isSecureStorageAvailable || userId != owner || sessionGeneration != session) null else block()
        }
    private fun decisionKey(provider: String) = receiptKey(provider, "decision_generation")
    fun beginProcessorConsentMutation(provider: String, enabled: Boolean): ConsentMutationSnapshot? = synchronized(prefs) {
        val id = provider.trim().uppercase()
        if (!isSecureStorageAvailable || id !in AI_PROVIDERS || (enabled && id in pendingProcessorRevokes())) return@synchronized null
        val next = Math.addExact(prefs.getLong(decisionKey(id), 0L), 1L)
        if (!prefs.edit().putLong(decisionKey(id), next).commit()) return@synchronized null
        if (!enabled) revokeProcessorReceipt(id)
        ConsentMutationSnapshot(effectiveUserId, sessionGeneration, next, healthConsentGeneration)
    }
    fun completeProcessorConsentMutation(snapshot: ConsentMutationSnapshot, provider: String, receipt: com.example.domain.ai.AiProcessorReceipt?): Boolean = synchronized(prefs) {
        val id = provider.trim().uppercase()
        if (!isSecureStorageAvailable || id !in AI_PROVIDERS || effectiveUserId != snapshot.owner || sessionGeneration != snapshot.session || healthConsentGeneration != snapshot.healthGeneration || prefs.getLong(decisionKey(id), 0L) != snapshot.decision || OwnerDataGate.isWiped(context, snapshot.owner)) return@synchronized false
        if (receipt != null && (receipt.providerId != id || receipt.revision < 1 || !Regex("^[a-f0-9]{64}$").matches(receipt.manifestFingerprint))) return@synchronized false
        if (receipt != null && id in pendingProcessorRevokes()) return@synchronized false
        val pending = pendingProcessorRevokes().toMutableSet().also { it.remove(id) }
        val editor = prefs.edit().putString(pendingRevokeKey(), JSONArray(pending.toList()).toString())
        if (receipt != null) editor.putInt(receiptKey(id, "revision"), receipt.revision).putString(receiptKey(id, "fingerprint"), receipt.manifestFingerprint).putString(receiptKey(id, "account"), snapshot.owner)
        editor.commit()
    }

    fun saveProcessorReceipt(receipt: com.example.domain.ai.AiProcessorReceipt) {
        if (!isSecureStorageAvailable || receipt.providerId.isBlank() || receipt.revision < 0 || receipt.manifestFingerprint.isBlank()) return
        prefs.edit {
            putInt(receiptKey(receipt.providerId, "revision"), receipt.revision)
            putString(receiptKey(receipt.providerId, "fingerprint"), receipt.manifestFingerprint)
            putString(receiptKey(receipt.providerId, "account"), effectiveUserId)
        }
        clearPendingProcessorRevoke(receipt.providerId)
    }

    fun processorReceipt(provider: String): com.example.domain.ai.AiProcessorReceipt? {
        if (!isSecureStorageAvailable) return null
        val id = provider.trim().uppercase(); val owner = effectiveUserId
        if (prefs.getString(receiptKey(id, "account"), null) != owner) return null
        val fp = prefs.getString(receiptKey(id, "fingerprint"), null).orEmpty()
        val revision = prefs.getInt(receiptKey(id, "revision"), -1)
        return if (fp.isNotBlank() && revision >= 0) com.example.domain.ai.AiProcessorReceipt(id, revision, fp) else null
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
        set(value) = prefs.edit { putString(KEY_ACCOUNT_MODE, value) }

    var userId: String?
        get() = readSecureString(KEY_USER_ID)
        set(value) { synchronized(prefs) {
            if (readSecureString(KEY_USER_ID) != value) prefs.edit().putLong("session_generation_v3", Math.addExact(sessionGeneration, 1L)).commit()
            writeSecureString(KEY_USER_ID, value)
        } }

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
        set(value) { if (isSecureStorageAvailable) prefs.edit { putLong(KEY_SESSION_EXPIRES_AT, value) } }

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
                val hashed = hashPin(value)
                prefs.edit { putString(KEY_PIN, hashed) }
            }
        }

    fun verifyPin(candidate: String): Boolean {
        val stored = readSecureString(KEY_PIN) ?: return false
        if (stored == candidate) {
            pin = candidate
            return true
        }
        if (stored == hashPin(candidate)) {
            return true
        }
        if (stored == hashPinLegacy(candidate)) {
            pin = candidate // upgrade to new hash
            return true
        }
        return false
    }

    private fun hashPin(raw: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest("cyclecare_${deviceId}_$raw".toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            hashPinLegacy(raw)
        }
    }

    private fun hashPinLegacy(raw: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest("cyclecare_salt_$raw".toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            raw
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

    fun clearSession() = synchronized(prefs) {
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
    }

    private fun readSecureString(key: String): String? =
        if (isSecureStorageAvailable) prefs.getString(key, null) else null

    private fun writeSecureString(key: String, value: String?) {
        prefs.edit {
            if (isSecureStorageAvailable && !value.isNullOrBlank()) putString(key, value)
            else remove(key)
        }
    }
}

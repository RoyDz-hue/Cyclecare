package com.example.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.util.UUID

class AuthPreferences(context: Context) {
    val context: Context = context.applicationContext
    private val preferenceStore = createEncryptedOrFallbackPrefs(this.context)
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
        private const val KEY_INITIAL_AUTH_COMPLETED = "initial_auth_completed"
        private const val KEY_ACCOUNT_STATUS = "account_status"
        private const val KEY_SUSPENSION_REASON = "suspension_reason"
        private const val KEY_SUSPENDED_UNTIL = "suspended_until"
        private const val KEY_PENDING_OFFLINE_CLAIM_DATE = "pending_offline_claim_date"
        private const val KEY_PENDING_OFFLINE_CLAIM_AMOUNT = "pending_offline_claim_amount"
        private const val KEY_IS_PENDING_RECONCILIATION = "is_pending_reconciliation"
        private const val KEY_SOUND_ENABLED = "sound_feedback_enabled"

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
                legacy.edit()
                    .remove(KEY_USER_ID)
                    .remove(KEY_USER_EMAIL)
                    .remove(KEY_ACCESS_TOKEN)
                    .remove(KEY_REFRESH_TOKEN)
                    .remove(KEY_SESSION_EXPIRES_AT)
                    .remove(KEY_PENDING_EMAIL)
                    .remove(KEY_GOOGLE_PKCE_VERIFIER)
                    .remove(KEY_PENDING_AUTH_ACTION)
                    .remove(KEY_PENDING_GUEST_EMAIL_LINK)
                    .remove(KEY_NEEDS_PASSWORD_SETUP)
                    .remove(KEY_PIN)
                    .remove(KEY_PIN_ENABLED)
                    .remove(KEY_FINGERPRINT_ENABLED)
                    .remove(KEY_QUICK_UNLOCK_ASKED)
                    .apply()
                PreferenceStore(legacy, secure = false)
            }
        }

        private fun migrateLegacyPrefs(ctx: Context, securePrefs: SharedPreferences) {
            try {
                val legacy = ctx.getSharedPreferences(PREFS_LEGACY_NAME, Context.MODE_PRIVATE)
                val allLegacy = legacy.all
                if (allLegacy.isNotEmpty()) {
                    val editor = securePrefs.edit()
                    for ((key, value) in allLegacy) {
                        if (!securePrefs.contains(key)) {
                            when (value) {
                                is String -> editor.putString(key, value)
                                is Boolean -> editor.putBoolean(key, value)
                                is Int -> editor.putInt(key, value)
                                is Long -> editor.putLong(key, value)
                                is Float -> editor.putFloat(key, value)
                            }
                        }
                    }
                    editor.apply()
                    legacy.edit().clear().apply()
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
        set(value) { if (isSecureStorageAvailable || isLocalOnlyMode) prefs.edit().putBoolean(KEY_INITIAL_AUTH_COMPLETED, value).apply() }

    var isCloudSyncAllowed: Boolean
        get() = prefs.getBoolean("${KEY_CLOUD_SYNC_ALLOWED}_v2_${effectiveUserId}", false)
        set(value) {
            prefs.edit().putBoolean("${KEY_CLOUD_SYNC_ALLOWED}_v2_${effectiveUserId}", value).commit()
            if (!value) {
                runCatching { androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("cyclecare_sync") }
            }
        }

    /** Consent belongs to one identity and is never inherited during account switching. */
    var isAiSharingAllowed: Boolean
        get() = prefs.getBoolean("ai_sharing_v1_${effectiveUserId}", false)
        set(value) { prefs.edit().putBoolean("ai_sharing_v1_${effectiveUserId}", value).commit() }

    var isSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_ENABLED, value).apply()

    val deviceId: String
        get() {
            var id = prefs.getString(KEY_DEVICE_ID, null)
            if (id.isNullOrBlank()) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }

    var accountMode: String
        get() = prefs.getString(KEY_ACCOUNT_MODE, "ANONYMOUS") ?: "ANONYMOUS"
        set(value) = prefs.edit().putString(KEY_ACCOUNT_MODE, value).apply()

    var userId: String?
        get() = readSecureString(KEY_USER_ID)
        set(value) = writeSecureString(KEY_USER_ID, value)

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
        set(value) { if (isSecureStorageAvailable) prefs.edit().putLong(KEY_SESSION_EXPIRES_AT, value).apply() }

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
        set(value) { if (isSecureStorageAvailable) prefs.edit().putBoolean(KEY_PENDING_GUEST_EMAIL_LINK, value).apply() }

    var needsPasswordSetup: Boolean
        get() = isSecureStorageAvailable && prefs.getBoolean(KEY_NEEDS_PASSWORD_SETUP, false)
        set(value) { if (isSecureStorageAvailable) prefs.edit().putBoolean(KEY_NEEDS_PASSWORD_SETUP, value).apply() }

    var pin: String?
        get() = readSecureString(KEY_PIN)
        set(value) {
            if (!isSecureStorageAvailable) return
            if (value.isNullOrBlank()) {
                prefs.edit().remove(KEY_PIN).apply()
            } else {
                val hashed = hashPin(value)
                prefs.edit().putString(KEY_PIN, hashed).apply()
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
        set(value) { if (isSecureStorageAvailable) prefs.edit().putBoolean(KEY_PIN_ENABLED, value).apply() }

    var isFingerprintEnabled: Boolean
        get() = isSecureStorageAvailable && prefs.getBoolean(KEY_FINGERPRINT_ENABLED, false)
        set(value) { if (isSecureStorageAvailable) prefs.edit().putBoolean(KEY_FINGERPRINT_ENABLED, value).apply() }

    var isQuickUnlockAsked: Boolean
        get() = isSecureStorageAvailable && prefs.getBoolean(KEY_QUICK_UNLOCK_ASKED, false)
        set(value) { if (isSecureStorageAvailable) prefs.edit().putBoolean(KEY_QUICK_UNLOCK_ASKED, value).apply() }

    var lastSyncTimestamp: Long
        get() = prefs.getLong(KEY_LAST_SYNC_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_TIMESTAMP, value).apply()

    var lastSyncMessage: String
        get() = prefs.getString(KEY_LAST_SYNC_MESSAGE, "Never synced") ?: "Never synced"
        set(value) = prefs.edit().putString(KEY_LAST_SYNC_MESSAGE, value).apply()

    var lastSyncSuccess: Boolean
        get() = prefs.getBoolean(KEY_LAST_SYNC_STATUS, false)
        set(value) = prefs.edit().putBoolean(KEY_LAST_SYNC_STATUS, value).apply()

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
        set(value) = prefs.edit().putString(KEY_ACCOUNT_STATUS, value).apply()

    var suspensionReason: String?
        get() = prefs.getString(KEY_SUSPENSION_REASON, null)
        set(value) = prefs.edit().putString(KEY_SUSPENSION_REASON, value).apply()

    var suspendedUntil: String?
        get() = prefs.getString(KEY_SUSPENDED_UNTIL, null)
        set(value) = prefs.edit().putString(KEY_SUSPENDED_UNTIL, value).apply()

    var pendingOfflineClaimDate: String?
        get() = prefs.getString(KEY_PENDING_OFFLINE_CLAIM_DATE, null)
        set(value) = prefs.edit().putString(KEY_PENDING_OFFLINE_CLAIM_DATE, value).apply()

    var pendingOfflineClaimAmount: Long
        get() = prefs.getLong(KEY_PENDING_OFFLINE_CLAIM_AMOUNT, 0L)
        set(value) = prefs.edit().putLong(KEY_PENDING_OFFLINE_CLAIM_AMOUNT, value).apply()

    var isPendingReconciliation: Boolean
        get() = prefs.getBoolean(KEY_IS_PENDING_RECONCILIATION, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_PENDING_RECONCILIATION, value).apply()

    fun recordOfflineClaim(claimDate: String, amount: Long) {
        prefs.edit()
            .putString(KEY_PENDING_OFFLINE_CLAIM_DATE, claimDate)
            .putLong(KEY_PENDING_OFFLINE_CLAIM_AMOUNT, amount)
            .putBoolean(KEY_IS_PENDING_RECONCILIATION, true)
            .apply()
    }

    fun clearPendingOfflineClaim() {
        prefs.edit()
            .remove(KEY_PENDING_OFFLINE_CLAIM_DATE)
            .remove(KEY_PENDING_OFFLINE_CLAIM_AMOUNT)
            .putBoolean(KEY_IS_PENDING_RECONCILIATION, false)
            .apply()
    }

    fun setAccountRestriction(status: String, reason: String?, until: String?) {
        prefs.edit()
            .putString(KEY_ACCOUNT_STATUS, status)
            .putString(KEY_SUSPENSION_REASON, reason)
            .putString(KEY_SUSPENDED_UNTIL, until)
            .apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_SESSION_EXPIRES_AT)
            .remove(KEY_GOOGLE_PKCE_VERIFIER)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_PENDING_EMAIL)
            .remove(KEY_PENDING_AUTH_ACTION)
            .putBoolean(KEY_PENDING_GUEST_EMAIL_LINK, false)
            .putBoolean(KEY_NEEDS_PASSWORD_SETUP, false)
            .putString(KEY_ACCOUNT_MODE, "ANONYMOUS")
            .apply()
    }

    private fun readSecureString(key: String): String? =
        if (isSecureStorageAvailable) prefs.getString(key, null) else null

    private fun writeSecureString(key: String, value: String?) {
        val editor = prefs.edit()
        if (isSecureStorageAvailable && !value.isNullOrBlank()) editor.putString(key, value)
        else editor.remove(key)
        editor.apply()
    }
}

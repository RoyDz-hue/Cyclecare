package com.cyclecare.app.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores only an AES-GCM wrapped SQLCipher passphrase; the wrapping key remains in Android Keystore. */
/** Reserved prefix marking instrumentation-owned key stores. Package-level so the
 * instrumentation seam and its fixtures reference it unqualified; same value as
 * [DatabaseKeyManager.TEST_OWNED_PREFIX], which now aliases it. */
internal const val TEST_OWNED_KEYSTORE_PREFIX = "instrumented_test_"

internal class DatabaseKeyManager(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
    keyAlias: String = KEY_ALIAS,
) {
    init {
        // Test-owned injection must be visibly test-owned in BOTH names; the
        // production pair stays the only other combination, so an injected
        // manager can never read or write production key material.
        require(
            (preferencesName == PREFERENCES_NAME && keyAlias == KEY_ALIAS) ||
                (preferencesName.startsWith(TEST_OWNED_PREFIX) && keyAlias.startsWith(TEST_OWNED_PREFIX))
        ) { "Injected key stores must use the reserved test-owned prefix." }
    }
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val wrappingKeyAlias = keyAlias

    /** Exact store pair this manager reads/writes; the instrumentation seam binds caches to it. */
    internal val storeIdentity: Pair<String, String> = preferencesName to keyAlias

    @Synchronized
    fun getOrCreatePassphrase(): String {
        val wrappingKey = getOrCreateWrappingKey()
        val wrapped = preferences.getString(WRAPPED_PASSPHRASE, null)
        if (wrapped != null) return decrypt(wrappingKey, wrapped)

        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val passphrase = Base64.encodeToString(secret, Base64.NO_WRAP)
        secret.fill(0)
        val encrypted = encrypt(wrappingKey, passphrase)
        check(preferences.edit().putString(WRAPPED_PASSPHRASE, encrypted).commit()) {
            "Could not securely persist the local database key."
        }
        return passphrase
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(wrappingKeyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                wrappingKeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(key: SecretKey, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(key: SecretKey, encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > GCM_IV_LENGTH) { "Stored local database key is invalid." }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "cyclecare_room_db_wrapping_key_v1"
        const val PREFERENCES_NAME = "cyclecare_local_db_key"
        const val WRAPPED_PASSPHRASE = "wrapped_sqlcipher_passphrase"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
        /** Reserved prefix marking instrumentation-owned key material. */
        const val TEST_OWNED_PREFIX = TEST_OWNED_KEYSTORE_PREFIX
    }
}

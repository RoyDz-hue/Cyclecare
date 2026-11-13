package com.cyclecare.app

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.security.AuthPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthPreferencesLintSemanticsTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private lateinit var backing: SharedPreferences
    private lateinit var recording: RecordingPreferences
    private lateinit var preferences: AuthPreferences

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        backing = context.getSharedPreferences("lint-semantics-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        backing.edit().putString("user_id", owner).apply()
        recording = RecordingPreferences(backing)
        preferences = AuthPreferences(context, recording, true)
    }

    @Test fun singleAsyncSettingStillAppliesWithoutCommit() {
        preferences.isSoundEnabled = true
        assertTrue(preferences.isSoundEnabled)
        assertEquals(1, recording.applyCalls)
        assertEquals(0, recording.commitCalls)
    }

    @Test fun multiKeyAsyncConsentAppliesExactlyOnceAfterAllFields() {
        preferences.setProviderConsent("COHERE", true)
        val consent = preferences.providerConsent("COHERE")
        assertTrue(consent.granted)
        assertEquals(owner, consent.accountId)
        assertEquals(AuthPreferences.AI_PROCESSOR_CONSENT_REVISION, consent.revision)
        assertEquals(1, recording.applyCalls)
        assertEquals(0, recording.commitCalls)
    }

    @Test fun failedMultiKeyStagingCannotPublishPartialConsent() {
        recording.failKey = "ai_processor_COHERE_revision_$owner"
        assertThrows(IllegalStateException::class.java) {
            preferences.setProviderConsent("COHERE", true)
        }
        assertFalse(backing.contains("ai_processor_COHERE_granted_$owner"))
        assertFalse(preferences.providerConsent("COHERE").granted)
        assertEquals(0, recording.applyCalls)
        assertEquals(0, recording.commitCalls)
    }

    @Test fun localModeStillUsesSynchronousCommit() {
        preferences.isLocalOnlyMode = true
        assertTrue(preferences.isLocalOnlyMode)
        assertEquals(1, recording.commitCalls)
        assertEquals(0, recording.applyCalls)
    }

    @Test fun failedCheckedDecisionCommitCannotStartConsentMutation() {
        recording.failCommit = true
        assertNull(preferences.beginProcessorConsentMutation("COHERE", true))
        assertFalse(preferences.providerConsent("COHERE").granted)
        assertFalse(backing.contains("ai_receipt_COHERE_decision_generation_$owner"))
        assertEquals(1, recording.commitCalls)
        assertEquals(0, recording.applyCalls)
    }

    @Test fun ownerMoveAndClearEachCommitIdentityAndGenerationTogetherWithoutApply() {
        val before = preferences.sessionGeneration
        preferences.userId = "synthetic-other-owner"
        assertEquals("synthetic-other-owner", backing.getString("user_id", null))
        assertEquals(before + 1L, backing.getLong("session_generation_v3", -1L))
        assertEquals(1, recording.commitCalls)
        assertEquals(0, recording.applyCalls)
        preferences.userId = null
        assertFalse(backing.contains("user_id"))
        assertEquals(before + 2L, backing.getLong("session_generation_v3", -1L))
        assertEquals(2, recording.commitCalls)
        assertEquals(0, recording.applyCalls)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reloaded = AuthPreferences(context, backing, true)
        assertNull(reloaded.userId)
        assertEquals(before + 2L, reloaded.sessionGeneration)
    }

    @Test fun failedOwnerMoveCommitMustNotAcknowledgeSuccess() {
        val before = preferences.operationSnapshot()
        recording.failCommit = true
        val error = assertThrows(IllegalStateException::class.java) { preferences.userId = null }
        assertEquals("identity_commit_failed", error.message)
        assertEquals(before, preferences.operationSnapshot())
        assertEquals(1, recording.commitCalls)
        assertEquals(0, recording.applyCalls)
    }

    @Test fun migrationCopiesOncePreservesSecureValuesThenClearsLegacy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacy = context.getSharedPreferences("cyclecare_auth_prefs", Context.MODE_PRIVATE)
        legacy.edit().clear().putString("migration_existing", "legacy")
            .putString("migration_new", "copied").apply()
        backing.edit().putString("migration_existing", "secure").apply()
        migrate(context)
        assertEquals("secure", backing.getString("migration_existing", null))
        assertEquals("copied", backing.getString("migration_new", null))
        assertTrue(legacy.all.isEmpty())
        assertEquals(1, recording.applyCalls)
        assertEquals(0, recording.commitCalls)
    }

    @Test fun failedMigrationStagingRetainsLegacyAndCannotApplyPartialCopy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacy = context.getSharedPreferences("cyclecare_auth_prefs", Context.MODE_PRIVATE)
        legacy.edit().clear().putString("migration_safe", "one")
            .putString("migration_fail", "two").apply()
        val original = legacy.all.toMap()
        recording.failKey = "migration_fail"
        migrate(context)
        assertEquals(original, legacy.all)
        assertFalse(backing.contains("migration_safe"))
        assertFalse(backing.contains("migration_fail"))
        assertEquals(0, recording.applyCalls)
        assertEquals(0, recording.commitCalls)
    }

    private fun migrate(context: Context) {
        // Exercise the existing private migration without pretending a JVM fixture
        // establishes Android Keystore or hardware-encryption acceptance.
        val method = AuthPreferences.Companion.javaClass.getDeclaredMethod(
            "migrateLegacyPrefs", Context::class.java, SharedPreferences::class.java
        )
        method.isAccessible = true
        method.invoke(AuthPreferences.Companion, context, recording)
    }

    private class RecordingPreferences(private val backing: SharedPreferences) : SharedPreferences by backing {
        var applyCalls = 0
        var commitCalls = 0
        var failKey: String? = null
        var failCommit = false

        override fun edit(): SharedPreferences.Editor {
            val delegate = backing.edit()
            return object : SharedPreferences.Editor by delegate {
                private fun checkKey(key: String) {
                    check(key != failKey) { "Injected staging failure" }
                }
                override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                    checkKey(key); delegate.putBoolean(key, value); return this
                }
                override fun putString(key: String, value: String?): SharedPreferences.Editor {
                    checkKey(key); delegate.putString(key, value); return this
                }
                override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                    checkKey(key); delegate.putLong(key, value); return this
                }
                override fun apply() { applyCalls++; delegate.apply() }
                override fun commit(): Boolean {
                    commitCalls++
                    return if (failCommit) false else delegate.commit()
                }
            }
        }
    }
}

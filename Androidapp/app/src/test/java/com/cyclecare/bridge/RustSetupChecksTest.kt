package com.cyclecare.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric sdk 34 runs the REAL AOSP org.json classes - required here because AOSP and the
 * Maven org.json jar differ on duplicate keys (AOSP last-wins) and optString of explicit null
 * (AOSP returns the literal "null"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RustSetupChecksTest {

    // --- fallback semantics (native absent in host tests): exact production expressions ---

    @Test fun setupOkTrue() = assertTrue(RustSetupChecks.ensureSetupOk("""{"success":true}"""))
    @Test fun setupOkStringTrue() = assertTrue(RustSetupChecks.ensureSetupOk("""{"success":"TRUE"}"""))
    @Test fun setupOkFalse() = assertFalse(RustSetupChecks.ensureSetupOk("""{"success":false}"""))
    @Test fun setupOkMissing() = assertFalse(RustSetupChecks.ensureSetupOk("""{}"""))
    @Test fun setupOkMalformed() = assertFalse(RustSetupChecks.ensureSetupOk("not json"))
    @Test fun setupOkNumberIsFalse() = assertFalse(RustSetupChecks.ensureSetupOk("""{"success":1}"""))
    @Test fun setupOkDuplicateKeysLastWins() {
        // AOSP JSONTokener: no duplicate-key rejection, later value wins
        assertTrue(RustSetupChecks.ensureSetupOk("""{"success":false,"success":true}"""))
        assertFalse(RustSetupChecks.ensureSetupOk("""{"success":true,"success":false}"""))
    }

    @Test fun remoteProfileTrue() =
        assertEquals(true, RustSetupChecks.hasRemoteProfile(true, """{"user_id":"o1","exists":true}""", "o1"))
    @Test fun remoteProfileFalse() =
        assertEquals(false, RustSetupChecks.hasRemoteProfile(true, """{"user_id":"o1","exists":false}""", "o1"))
    @Test fun remoteProfileWrongOwner() =
        assertNull(RustSetupChecks.hasRemoteProfile(true, """{"user_id":"o2","exists":true}""", "o1"))
    @Test fun remoteProfileMissingExists() =
        assertNull(RustSetupChecks.hasRemoteProfile(true, """{"user_id":"o1"}""", "o1"))
    @Test fun remoteProfileNotSuccessful() =
        assertNull(RustSetupChecks.hasRemoteProfile(false, """{"user_id":"o1","exists":true}""", "o1"))
    @Test fun remoteProfileMalformed() =
        assertNull(RustSetupChecks.hasRemoteProfile(true, "garbage", "o1"))
    @Test fun remoteProfileNullUserIdIsLiteralNullString() {
        // AOSP optString on explicit null -> literal "null", so it can only match owner "null"
        assertEquals(true, RustSetupChecks.hasRemoteProfile(true, """{"user_id":null,"exists":true}""", "null"))
        assertNull(RustSetupChecks.hasRemoteProfile(true, """{"user_id":null,"exists":true}""", ""))
    }
    @Test fun remoteProfileNonBooleanExistsIsNull() =
        assertNull(RustSetupChecks.hasRemoteProfile(true, """{"user_id":"o1","exists":"yes"}""", "o1"))

    @Test fun flagsRemainDisabled() {
        assertTrue(!RustSetupChecksConfig.ENABLED)
        assertTrue(!RustEffectiveUserIdConfig.ENABLED)
    }
}

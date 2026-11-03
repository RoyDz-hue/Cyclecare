package com.cyclecare.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RustEffectiveUserIdTest {

    // --- fallback semantics (native absent in host tests): verbatim AuthPreferences getter ---

    @Test fun userIdReturnedVerbatim() =
        assertEquals("user-abc", RustEffectiveUserId.effectiveUserId("user-abc", "dev-1"))
    @Test fun blankUserIdFallsToDeviceUuid() =
        assertEquals(UUID.nameUUIDFromBytes("dev-1".toByteArray()).toString(),
            RustEffectiveUserId.effectiveUserId(null, "dev-1"))
    @Test fun whitespaceOnlyUserIdIsBlank() =
        assertEquals(UUID.nameUUIDFromBytes("dev-1".toByteArray()).toString(),
            RustEffectiveUserId.effectiveUserId("  ", "dev-1"))
    @Test fun noTrimmingOfRealUserId() =
        assertEquals("  padded  ", RustEffectiveUserId.effectiveUserId("  padded  ", "dev-1"))

    // --- unpaired surrogate detection (JNI transport boundary) ---

    @Test fun detectsLoneHighSurrogate() = assertTrue(RustEffectiveUserId.hasUnpairedSurrogate("a\uD800b"))
    @Test fun detectsTrailingHighSurrogate() = assertTrue(RustEffectiveUserId.hasUnpairedSurrogate("a\uD800"))
    @Test fun detectsLoneLowSurrogate() = assertTrue(RustEffectiveUserId.hasUnpairedSurrogate("a\uDC00"))
    @Test fun acceptsPairedSurrogates() = assertFalse(RustEffectiveUserId.hasUnpairedSurrogate("a\uD83D\uDE31b"))
    @Test fun acceptsPlainAndNull() {
        assertFalse(RustEffectiveUserId.hasUnpairedSurrogate("plain-ascii"))
        assertFalse(RustEffectiveUserId.hasUnpairedSurrogate(null))
    }

    @Test fun unpairedSurrogateUsesKotlinPath() {
        // With native absent both paths agree; the point is no JNI transport is attempted.
        val uid = "dev\uD800"
        assertEquals(UUID.nameUUIDFromBytes(uid.toByteArray()).toString(),
            RustEffectiveUserId.effectiveUserId(null, uid))
    }

    @Test fun flagRemainsDisabled() = assertTrue(!RustEffectiveUserIdConfig.ENABLED)
}

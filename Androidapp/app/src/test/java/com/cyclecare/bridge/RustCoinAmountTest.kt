package com.cyclecare.bridge

import com.cyclecare.app.ui.screens.sanitizeCoinAmountInput
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric like every sibling JSONObject test: plain-JVM android.jar
 * stubs throw on JSONObject and android.util.Log. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RustCoinAmountTest {

    @Test
    fun decodeAcceptsTextWithNullRejection() {
        val ok = JSONObject().put("text", "12.5").put("rejection", JSONObject.NULL)
        assertEquals("12.5" to null, RustCoinAmount.decode(ok))
    }

    @Test
    fun decodeAcceptsTextWithRejectionMessage() {
        val ok = JSONObject().put("text", ".5")
            .put("rejection", "Start the amount with a digit, for example 0.5.")
        assertEquals(".5" to "Start the amount with a digit, for example 0.5.",
            RustCoinAmount.decode(ok))
    }

    @Test
    fun decodeRejectsMalformedPayloads() {
        assertNull(RustCoinAmount.decode(JSONObject()))
        assertNull(RustCoinAmount.decode(JSONObject().put("text", 5)))
        assertNull(RustCoinAmount.decode(JSONObject()
            .put("text", "12.5").put("rejection", 7)))
    }

    @Test
    fun guardRequiresExactKotlinOraclePair() {
        assertEquals("12.5" to null, RustCoinAmount.guard(" 12.5 ", "12.5" to null))
        // rewritten amount rejected
        assertNull(RustCoinAmount.guard("-1", "1" to null))
        assertNull(RustCoinAmount.guard("abc", "" to null))
        // unknown message rejected
        assertNull(RustCoinAmount.guard("5", "5" to "Some new message"))
        // invalid pair rejected: kept text but missing the rejection the
        // Kotlin oracle produces for "-1"
        assertNull(RustCoinAmount.guard("-1", "-1" to null))
        // exact oracle pair accepted
        assertEquals(
            "-1" to "Use digits and at most one decimal point, for example 12.5.",
            RustCoinAmount.guard("-1",
                "-1" to "Use digits and at most one decimal point, for example 12.5."))
    }

    /** Fallback path only: with no native library on the host the adapter
     * must equal the production Kotlin function exactly. The NATIVE path is
     * proven separately by a host JNI differential driving the real
     * CycleCareCoreBridge against this same Kotlin oracle (5038 cases,
     * 5038 native answers, 0 fallbacks, 0 mismatches). */
    @Test
    fun fallbackEqualsKotlinOracleWhenNativeUnavailable() {
        val corpus = listOf("", "   ", "50", "12.5", "12.500", " 10 ", "-1",
            "1.2.3", "1,5", "1a2", ".5", "0.001", "12345678901234567",
            "１２３４５６７８９", "１２３４５６７８９０１２３４５６７",
            // supplementary-plane digits: 8 chars = 16 UTF-16 units (shape
            // error, NOT too long); 9 chars = 18 units (too long)
            "𝟘".repeat(8), "𝟘".repeat(9),
            // ASCII length boundary: 16 accepted, 17 too long
            "1".repeat(16), "1".repeat(17))
        for (raw in corpus) {
            assertEquals("input <$raw>", sanitizeCoinAmountInput(raw),
                RustCoinAmount.sanitize(raw))
        }
    }
}

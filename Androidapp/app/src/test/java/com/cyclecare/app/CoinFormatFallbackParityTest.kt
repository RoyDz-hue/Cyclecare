package com.cyclecare.app

import com.cyclecare.app.util.CoinFormatter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * Fallback-parity pins for the coin_format_v1 twin (NEW IMPLEMENTATION,
 * 2026-11-13): the twin is the verbatim pre-wiring
 * DecimalFormat("#,##0.###", Locale.US) body, reached by reflection on the
 * CoinFormatter object. Every expectation is JVM-probe-verified and pinned
 * in the Rust port corpus (fixtures/oracle/corpus_coin_format.tsv +
 * results_coin_format.tsv, cases cf01-cf20). DecimalFormat is a plain
 * java.text class, so no Android runtime is needed here. Written, NOT
 * executed in this workspace.
 */
class CoinFormatFallbackParityTest {

    private fun twin(value: String): String {
        val m = CoinFormatter::class.java.getDeclaredMethod(
            "kotlinCoinFormat", BigDecimal::class.java,
        )
        m.isAccessible = true
        return m.invoke(CoinFormatter, BigDecimal(value)) as String
    }

    @Test
    fun jvmCorpusExpectations() {
        assertEquals("3.5", twin("3.50"))
        assertEquals("0", twin("0"))
        assertEquals("1,000,000", twin("1000000"))
        assertEquals("1,234,567.891", twin("1234567.891"))
        assertEquals("0", twin("0.0005"))
        assertEquals("0.002", twin("0.0015"))
        assertEquals("0.002", twin("0.0025"))
        assertEquals("2.5", twin("2.5"))
        assertEquals("-1,234.5", twin("-1234.5"))
        assertEquals("0.5", twin("0.5"))
        assertEquals("123,456,789,012,345,678.123", twin("123456789012345678.1234"))
        assertEquals("-0.001", twin("-0.001"))
        assertEquals("1,000", twin("999.9999"))
        assertEquals("10", twin("10.0005"))
        assertEquals("999,999.5", twin("999999.5"))
        assertEquals("-0.5", twin("-0.5"))
        assertEquals("0", twin("0.0000"))
        assertEquals("5", twin("5"))
        // HALF_EVEN rounding to zero keeps the original sign (cf19).
        assertEquals("-0", twin("-0.0005"))
        assertEquals("-1,000", twin("-999.9999"))
    }
}

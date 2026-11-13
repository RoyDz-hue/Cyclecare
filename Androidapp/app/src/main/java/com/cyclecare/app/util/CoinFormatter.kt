package com.cyclecare.app.util

import com.cyclecare.app.rust.CycleCareCoreBridge
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Decimal Precision Rules: coins support up to 3 decimal places. Whole numbers
 * display without decimals and trailing zeros are never shown (3.50 -> "3.5").
 * Balances are exact BigDecimal values; this is display-only formatting.
 */
object CoinFormatter {
    private val amountFormat = DecimalFormat("#,##0.###", DecimalFormatSymbols(Locale.US))

    /**
     * NEW IMPLEMENTATION (2026-11-13): native-first via coin_format_v1.
     * Pure display interpretation of the exact BigDecimal (crosses as its
     * exact toPlainString() form at the bridge); no I/O, no clock. A null
     * reply (native absent, {"error"}, or a strict-decode decline) runs the
     * verbatim pre-wiring body, kept as the private twin [kotlinCoinFormat].
     * The monitor semantics are unchanged: this method was @Synchronized
     * before wiring and stays @Synchronized, so the twin's DecimalFormat
     * use remains under the same lock as before.
     */
    @Synchronized
    fun format(value: BigDecimal): String =
        CycleCareCoreBridge.coinFormat(value) ?: kotlinCoinFormat(value)

    /**
     * Verbatim pre-wiring body of [format]. Private: only reachable from
     * [format], which holds this object's monitor, exactly as before.
     */
    private fun kotlinCoinFormat(value: BigDecimal): String = amountFormat.format(value)

    fun format(value: Long): String = format(BigDecimal.valueOf(value))
}

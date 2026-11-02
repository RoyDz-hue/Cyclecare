package com.cyclecare.app.util

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

    @Synchronized
    fun format(value: BigDecimal): String = amountFormat.format(value)

    fun format(value: Long): String = format(BigDecimal.valueOf(value))
}


package com.cyclecare.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class CoinFormatterTest {
    @Test fun wholeNumbersShowWithoutDecimals() = assertEquals("4", CoinFormatter.format(BigDecimal("4")))
    @Test fun trailingZerosAreNeverShown() = assertEquals("3.5", CoinFormatter.format(BigDecimal("3.50")))
    @Test fun thousandthsAreExact() = assertEquals("3.456", CoinFormatter.format(BigDecimal("3.456")))
    @Test fun thousandsGroupAndDecimals() = assertEquals("1,234.5", CoinFormatter.format(BigDecimal("1234.5")))
    @Test fun longOverloadFormatsWhole() = assertEquals("250", CoinFormatter.format(250L))
}

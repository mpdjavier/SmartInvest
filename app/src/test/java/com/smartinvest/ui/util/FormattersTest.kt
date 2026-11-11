package com.smartinvest.ui.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersTest {

    @After
    fun resetPrivacy() {
        MoneyVisibility.update(false)
    }

    @Test
    fun `privacy mode masks every monetary amount and keeps percentages visible`() {
        MoneyVisibility.update(true)

        assertEquals("$ ${MoneyVisibility.MASK}", Fmt.ars(123_456L))
        assertEquals("US$ ${MoneyVisibility.MASK}", Fmt.usd(123_456L))
        assertEquals("$ ${MoneyVisibility.MASK}", Fmt.compact(1_234_567_00L))
        assertEquals("+ $ ${MoneyVisibility.MASK}", Fmt.signedArs(50_000L))
        assertEquals("− US$ ${MoneyVisibility.MASK}", Fmt.signedUsd(-50_000L))
        assertEquals("12,5%", Fmt.percent(12.5))
        assertTrue(Fmt.shortDate(java.time.LocalDate.of(2026, 11, 9)).contains("nov"))
    }

    @Test
    fun `turning privacy off restores formatted amounts`() {
        MoneyVisibility.update(true)
        MoneyVisibility.update(false)

        assertEquals("$ 1.234,56", Fmt.ars(123_456L))
        assertEquals("US$ 1.234,56", Fmt.usd(123_456L))
        assertEquals("+ $ 500,00", Fmt.signedArs(50_000L))
    }
}

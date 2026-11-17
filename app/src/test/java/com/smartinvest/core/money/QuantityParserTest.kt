package com.smartinvest.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuantityParserTest {

    @Test
    fun `parses argentinian quantity formats`() {
        assertEquals(13_551_401.075, QuantityParser.parse("13.551.401,075")!!, 0.000001)
        assertEquals(13_551_401.075, QuantityParser.parse("13551401,075")!!, 0.000001)
        assertEquals(13_551_401.075, QuantityParser.parse("13551401.075")!!, 0.000001)
        assertEquals(3_154_364.0, QuantityParser.parse("3.154.364")!!, 0.000001)
        assertEquals(481.0, QuantityParser.parse("481")!!, 0.000001)
        assertEquals(6_778.219, QuantityParser.parse("6778,219")!!, 0.000001)
    }

    @Test
    fun `rejects invalid quantities`() {
        assertNull(QuantityParser.parse(""))
        assertNull(QuantityParser.parse("-10"))
        assertNull(QuantityParser.parse("abc"))
        assertNull(QuantityParser.parse("1,234,567"))
    }

    @Test
    fun `formats large quantities without scientific notation`() {
        assertEquals("13551401,075", QuantityParser.format(13_551_401.075))
        assertEquals("3154364", QuantityParser.format(3_154_364.0))
        assertEquals("6778,219", QuantityParser.format(6_778.219))
        assertEquals("481", QuantityParser.format(481.0))
        assertEquals("", QuantityParser.format(0.0))
    }

    @Test
    fun `round trips a holding quantity`() {
        val values = listOf(13_551_401.075, 13_603.562, 6_778.219, 20_278.996, 3_154_364.0, 2_964_509.0)
        for (value in values) {
            assertEquals(value, QuantityParser.parse(QuantityParser.format(value))!!, 0.000001)
        }
    }
}

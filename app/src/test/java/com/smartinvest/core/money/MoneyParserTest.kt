package com.smartinvest.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyParserTest {

    @Test
    fun `parses argentinian formats`() {
        assertEquals(123456L, MoneyParser.parseToCents("1.234,56"))
        assertEquals(123456L, MoneyParser.parseToCents("1234,56"))
        assertEquals(123456L, MoneyParser.parseToCents("1234.56"))
        assertEquals(123400L, MoneyParser.parseToCents("1.234"))
        assertEquals(123456L, MoneyParser.parseToCents("$ 1.234,56"))
        assertEquals(100L, MoneyParser.parseToCents("1"))
    }

    @Test
    fun `rejects invalid input`() {
        assertNull(MoneyParser.parseToCents(""))
        assertNull(MoneyParser.parseToCents("-10"))
        assertNull(MoneyParser.parseToCents("abc"))
    }

    @Test
    fun `allocate keeps the total`() {
        val parts = MoneyMath.allocate(1000, 3)
        assertEquals(1000L, parts.sum())
        assertEquals(listOf(334L, 333L, 333L), parts)
    }

    @Test
    fun `weighted allocation keeps the total`() {
        val parts = MoneyMath.allocateByWeights(10_000, listOf(50, 30, 20))
        assertEquals(10_000L, parts.sum())
        assertEquals(listOf(5000L, 3000L, 2000L), parts)
    }
}

package com.smartinvest.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FundWatchlistCodecTest {

    @Test
    fun `round trips names and removes duplicates`() {
        val encoded = FundWatchlistCodec.encode(
            listOf(
                "Adcap Balanceado III - Clase A",
                " Balanz Acciones - Clase A ",
                "Adcap Balanceado III - Clase A",
                "",
            ),
        )

        assertEquals(
            listOf(
                "Adcap Balanceado III - Clase A",
                "Balanz Acciones - Clase A",
            ),
            FundWatchlistCodec.decode(encoded),
        )
    }

    @Test
    fun `blank or invalid json decodes to an empty list`() {
        assertTrue(FundWatchlistCodec.decode("").isEmpty())
        assertTrue(FundWatchlistCodec.decode("   ").isEmpty())
        assertTrue(FundWatchlistCodec.decode("not json").isEmpty())
    }

    @Test
    fun `empty selection is preserved as an empty list`() {
        val encoded = FundWatchlistCodec.encode(emptyList())
        assertEquals("[]", encoded)
        assertTrue(FundWatchlistCodec.decode(encoded).isEmpty())
    }
}

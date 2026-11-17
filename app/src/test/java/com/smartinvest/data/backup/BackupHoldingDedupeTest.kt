package com.smartinvest.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupHoldingDedupeTest {

    private fun holding(
        id: Long,
        symbol: String,
        name: String = symbol,
        notes: String = "",
        currency: String = "ARS",
        assetType: String = "BOND",
        valueCents: Long = 100_000L,
    ) = BackupHoldingDto(
        id = id,
        symbol = symbol,
        name = name,
        assetType = assetType,
        currency = currency,
        currentValueCents = valueCents,
        notes = notes,
    )

    @Test
    fun `same asset in the same account collapses into the newest row`() {
        val duplicated = listOf(
            holding(1, "SPY", name = "SPY (IEB)", notes = "IEB", valueCents = 10_000L),
            holding(7, "SPY", name = "SPY (IEB)", notes = "IEB", valueCents = 20_000L),
        )

        val result = BackupHoldingDedupe.apply(duplicated)

        assertEquals(1, result.holdings.size)
        assertEquals(1, result.removed)
        assertEquals(20_000L, result.holdings.single().currentValueCents)
    }

    @Test
    fun `same ticker in different accounts stays separate`() {
        val holdings = listOf(
            holding(1, "S30N6", name = "S30N6 (GALICIA)", notes = "GALICIA"),
            holding(2, "S30N6", name = "S30N6 (IEB)", notes = "IEB"),
        )

        val result = BackupHoldingDedupe.apply(holdings)

        assertEquals(2, result.holdings.size)
        assertEquals(0, result.removed)
    }

    @Test
    fun `same symbol in different currencies stays separate`() {
        val holdings = listOf(
            holding(1, "MP", currency = "ARS"),
            holding(2, "MP", currency = "USD"),
        )

        val result = BackupHoldingDedupe.apply(holdings)

        assertEquals(2, result.holdings.size)
    }

    @Test
    fun `distinct holdings are untouched`() {
        val holdings = listOf(
            holding(1, "AO27"),
            holding(2, "T15E7"),
            holding(3, "ADCAP-BAL3", assetType = "FCI"),
        )

        val result = BackupHoldingDedupe.apply(holdings)

        assertEquals(3, result.holdings.size)
        assertEquals(0, result.removed)
    }

    @Test
    fun `account tag is read from the notes or the name`() {
        assertEquals("IEB", BackupHoldingDedupe.accountTag(holding(1, "SPY", notes = "Compras IEB+")))
        assertEquals("GALICIA", BackupHoldingDedupe.accountTag(holding(1, "S30N6", name = "S30N6 (Galicia)")))
        assertEquals("", BackupHoldingDedupe.accountTag(holding(1, "AO27")))
    }
}
